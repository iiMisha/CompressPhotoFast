package com.compressphotofast.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import com.compressphotofast.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.atomic.AtomicBoolean
import com.compressphotofast.data.TempFilesCleaner
import com.compressphotofast.domain.GalleryScanCoordinator
import com.compressphotofast.data.SettingsManager
import com.compressphotofast.platform.NotificationUtil
import com.compressphotofast.data.MediaStoreObserver
import com.compressphotofast.util.LogUtil
import com.compressphotofast.data.UriProcessingTracker
import com.compressphotofast.domain.CompressionEvents
import com.compressphotofast.domain.CompressionWorkScheduler
import com.compressphotofast.domain.CompressionEnqueueResult
import javax.inject.Inject

/**
 * Сервис для фонового мониторинга новых изображений
 */
@AndroidEntryPoint
class BackgroundMonitoringService : Service() {

    companion object {
        /**
         * Атомарный флаг активности Foreground Service.
         * Используется ImageDetectionJobService для быстрого пропуска обработки,
         * когда ContentObserver уже обеспечивает real-time обнаружение.
         *
         * Volatile гарантирует видимость изменений между потоками.
         * Устанавливается в onCreate/onDestroy — корректно работает даже при
         * force-kill (значение сбрасывается при перезапуске процесса).
         */
        @Volatile
        @JvmStatic
        var isRunning: Boolean = false
            private set

        /** Становится true только после успешного FGS startForeground и observer setup. */
        @Volatile
        @JvmStatic
        var isReady: Boolean = false
            private set

        /**
         * Флаг явной остановки пользователем (переключатель / кнопка в уведомлении).
         *
         * Предотвращает восстановление мониторинга в рамках текущего процесса после
         * осознанного выключения автосжатия. Сбрасывается при новом [startMonitoring].
         */
        @Volatile
        @JvmStatic
        var isUserStopped: Boolean = false
    }

    // Service-scoped корутины для привязки к lifecycle сервиса
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var isServiceDestroyed = AtomicBoolean(false)

    @Inject
    lateinit var uriProcessingTracker: UriProcessingTracker

    @Inject
    lateinit var compressionWorkScheduler: CompressionWorkScheduler

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var galleryScanCoordinator: GalleryScanCoordinator

    @Inject
    lateinit var compressionEvents: CompressionEvents

    // MediaStoreObserver для централизованной работы с ContentObserver
    private var mediaStoreObserver: MediaStoreObserver? = null

    // Интервал сканирования галереи — резервный механизм на случай пропуска событий
    // ContentObserver и JobService. Используем константу из Constants.
    private val scanInterval = Constants.BACKGROUND_SCAN_INTERVAL_MINUTES * 60 * 1000L

    // Job для периодического сканирования галереи
    private var scanJob: Job? = null

    // Job для периодической очистки временных файлов
    private var cleanupJob: Job? = null

    // Подписка на события воркера
    private var eventsJob: Job? = null

    // Mutex для предотвращения конкурентного сканирования

    /**
     * Безопасный запуск корутины в scope сервиса
     * Проверяет состояние сервиса перед запуском и обрабатывает исключения
     */
    private fun launchServiceScope(block: suspend CoroutineScope.() -> Unit) {
        if (isServiceDestroyed.get()) {
            LogUtil.warning(null, "Service", "Попытка запуска корутины после уничтожения сервиса")
            return
        }

        serviceScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e // Пробрасываем для корректной отмены
            } catch (e: Exception) {
                LogUtil.error(null, "ServiceCoroutine", "Ошибка в корутине сервиса", e)
            }
        }
    }

    /**
     * Реакция на успешное одиночное сжатие: снимаем URI из обрабатываемых,
     * показываем результат и игнорируем собственное изменение файла.
     */
    private suspend fun handleCompressionResult(event: CompressionEvents.Event.Result) {
        uriProcessingTracker.removeProcessingUriSafe(event.uri)
        NotificationUtil.showCompressionResultNotification(
            applicationContext, event.fileName, event.originalSize, event.compressedSize,
            event.sizeReduction, skipped = false
        )
        uriProcessingTracker.setIgnorePeriod(event.uri)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = false
        isReady = false
        isUserStopped = false

        try {
            // Сначала гарантируем foreground promotion. Любая ошибка старта не
            // оставляет ложный isRunning=true и позволяет Job продолжить recovery.
            NotificationUtil.createDefaultNotificationChannel(applicationContext)
            startForegroundWithNotification()

            if (!settingsManager.isAutoCompressionEnabled()) {
                stopSelf()
                return
            }

            setupContentObserver()
            eventsJob = serviceScope.launch {
                compressionEvents.events
                    .filterIsInstance<CompressionEvents.Event.Result>()
                    .filter { !it.skipped }
                    .collect { event ->
                        try {
                            handleCompressionResult(event)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LogUtil.error(event.uri, "ServiceEvents", "Ошибка обработки результата сжатия", e)
                        }
                    }
            }

            isRunning = true
            isReady = true
            // Content-trigger Job остаётся armed всегда: в отличие от ContentObserver
            // он будит замороженный/приостановленный процесс (battery saver, OEM).
            ensureDetectionJobArmed()
            startPeriodicScanning()
            // Stale pending, recovery и temp-очистку при холодном старте процесса
            // уже выполняет CompressPhotoApp.performPostCrashCleanup
            startPeriodicCleanup()
        } catch (e: Exception) {
            isReady = false
            isRunning = false
            LogUtil.error(null, "BackgroundMonitoringService", "Не удалось подготовить monitoring FGS", e)
            try {
                mediaStoreObserver?.unregister()
                eventsJob?.cancel()
            } catch (_: Exception) {
                // Ресурсы могли не успеть зарегистрироваться.
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Проверяем, не является ли это запросом на остановку сервиса
        if (intent?.action == Constants.ACTION_STOP_SERVICE) {
            // Явная остановка пользователем: отключаем автосжатие, отменяем резервный Job
            // и помечаем флаг, чтобы не восстанавливать мониторинг в этом процессе.
            isUserStopped = true
            settingsManager.setAutoCompression(false)
            ImageDetectionJobService.cancelJob(applicationContext)

            stopSelf()
            return START_NOT_STICKY
        }

        // Если система пересоздала службу (START_STICKY, intent == null), а пользователь
        // уже явно её остановил в текущем процессе — завершаемся без восстановления.
        if (isUserStopped) {
            LogUtil.processDebug("BackgroundMonitoringService: восстановление отменено — пользователь остановил мониторинг")
            stopSelf()
            return START_NOT_STICKY
        }

        // Служба уже готова: discovery остаётся за ContentObserver, но armed
        // content-trigger Job гарантируем — он пробуждает замороженный процесс,
        // если observer-события не доставляются (battery saver).
        ensureDetectionJobArmed()

        return START_STICKY
    }

    /**
     * Гарантирует armed-состояние content-trigger Job'а. Job — единственный
     * механизм, будящий замороженный процесс, поэтому он не отменяется при
     * живом ContentObserver; дублирование постановки URI исключает dedup
     * (unique work KEEP, UriProcessingTracker, маркер сжатия).
     */
    private fun ensureDetectionJobArmed() {
        if (isUserStopped) return
        try {
            ImageDetectionJobService.scheduleJob(applicationContext)
        } catch (e: Exception) {
            LogUtil.error(null, "BackgroundMonitoringService", "Не удалось гарантировать armed Job", e)
        }
    }

    /**
     * Вызывается, когда пользователь смахивает приложение из списка недавних.
     *
     * Постоянная foreground-служба при этом обычно остаётся работать, но на некоторых
     * OEM-сборках процесс может быть завершён. Подстраховываемся: гарантируем, что
     * резервный JobScheduler-триггер запланирован, чтобы новые фото не потерялись.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Job перепланируется даже при живом FGS: если OEM убьёт процесс после
        // свайпа, Job сработает в новом процессе (isReady=false) и восстановит
        // мониторинг. Дублирование обработки при живом FGS исключает guard
        // isReady в ImageDetectionJobService.onStartJob.
        if (!isUserStopped && settingsManager.isAutoCompressionEnabled()) {
            LogUtil.processDebug("BackgroundMonitoringService: task removed — перепланируем резервный Job")
            ImageDetectionJobService.scheduleJob(applicationContext)
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Запуск сервиса в режиме переднего плана с уведомлением.
     *
     * На Android 14+ используется тип `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, который
     * не имеет лимита времени работы (в отличие от `dataSync`, ограниченного ~6 часами
     * в сутки). Это позволяет постоянной службе мониторинга работать круглосуточно при
     * включенном автосжатии. На Android 10–13 тип не критичен — временные лимиты
     * появились только в Android 14.
     */
    private fun startForegroundWithNotification() {
        val notification = NotificationUtil.createBackgroundServiceNotification(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIFICATION_ID_BACKGROUND_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                Constants.NOTIFICATION_ID_BACKGROUND_SERVICE,
                notification
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        val shouldKeepRecoveryJob = !isUserStopped &&
            settingsManager.isAutoCompressionEnabled()
        isRunning = false
        isReady = false
        isServiceDestroyed.set(true)

        if (shouldKeepRecoveryJob) {
            try {
                ImageDetectionJobService.scheduleJob(applicationContext)
            } catch (e: Exception) {
                LogUtil.error(null, "BackgroundMonitoringService", "Не удалось оставить recovery Job", e)
            }
        }

        super.onDestroy()

        // Неблокирующее завершение корутин сервиса
        serviceScope.launch {
            try {
                withTimeout(5000) {
                    serviceScope.coroutineContext[Job]?.children?.forEach { it.cancelAndJoin() }
                }
            } catch (e: TimeoutCancellationException) {
                LogUtil.warning(null, "BackgroundMonitoringService", "Таймаут ожидания завершения корутин (5000мс)")
            } finally {
                // Окончательная отмена job
                serviceJob.cancel()
            }
        }

        // Немедленно освобождаем ресурсы (без блокировки)
        mediaStoreObserver?.unregister()
        scanJob?.cancel()
        cleanupJob?.cancel()
        eventsJob?.cancel()
    }
    
    /**
     * Настройка наблюдателя за контент-провайдером MediaStore
     */
    private fun setupContentObserver() {
        // Создаем MediaStoreObserver
        val observer = MediaStoreObserver(applicationContext, uriProcessingTracker) { uri ->
            // Этот код будет выполнен при обнаружении изменений после задержки
            launchServiceScope {
                processNewImage(uri)
            }
        }
        mediaStoreObserver = observer

        // Регистрируем MediaStoreObserver
        observer.register()

        // Начальный скан от watermark; HISTORY-догон при холодном старте ставит
        // CompressPhotoApp через GalleryReconciliationWorker
        launchServiceScope {
            scanForNewImages()
        }
    }
    
    /**
     * Периодическое сканирование галереи от последнего watermark
     */
    private suspend fun scanForNewImages() {
        // Периодически проверяем недоступные URI и восстанавливаем их
        try {
            uriProcessingTracker.retryUnavailableUris()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.warning(Uri.EMPTY, "BackgroundMonitoring", "Ошибка при восстановлении недоступных URI: ${e.message}")
        }
        galleryScanCoordinator.scan(GalleryScanCoordinator.Window.SINCE_WATERMARK)
    }
    
    /**
     * Обработка нового изображения
     */
    private suspend fun processNewImage(uri: Uri): Boolean {
        try {
            if (!settingsManager.isAutoCompressionEnabled()) {
                return false
            }

            return compressionWorkScheduler.enqueue(uri).let {
                it == CompressionEnqueueResult.DURABLY_ACCEPTED ||
                    it == CompressionEnqueueResult.NOT_REQUIRED
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.error(uri, "Обработка нового изображения", "Ошибка при обработке нового изображения", e)
            return false
        }
    }
    
    /**
     * Очистка старых временных файлов
     */
    private fun cleanupTempFiles() {
        TempFilesCleaner.cleanupTempFiles(applicationContext)
    }

    /**
     * Запуск периодического сканирования галереи.
     * Использует корутины вместо Handler для лучшей производительности
     */
    private fun startPeriodicScanning() {
        scanJob = serviceScope.launch {
            while (isActive) {
                // Первый проход уже выполнен начальным сканом в setupContentObserver
                delay(
                    if (isReady && isRunning && !isServiceDestroyed.get()) {
                        Constants.BACKGROUND_SCAN_INTERVAL_FALLBACK_MINUTES * 60 * 1000L
                    } else {
                        scanInterval
                    }
                )
                val observerAlive = isReady && isRunning && !isServiceDestroyed.get()
                // Страховка от пропущенных observer-событий: при живом observer
                // сканируем редко, иначе — штатным интервалом.
                if (!observerAlive) {
                    LogUtil.processDebug("Периодический скан: ContentObserver неактивен — полный интервал")
                }
                try {
                    scanForNewImages()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogUtil.error(null, "PERIODIC_SCAN", "Ошибка периодического скана", e)
                }
            }
        }
    }

    /**
     * Запуск периодической очистки временных файлов
     * Использует корутины вместо Handler для лучшей производительности
     */
    private fun startPeriodicCleanup() {
        cleanupJob = serviceScope.launch {
            while (isActive) {
                // Планируем следующую очистку через 24 часа
                delay(24 * 60 * 60 * 1000L) // 24 часа
                cleanupTempFiles()
            }
        }
    }
}

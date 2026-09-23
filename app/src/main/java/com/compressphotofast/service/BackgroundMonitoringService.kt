package com.compressphotofast.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import com.compressphotofast.util.Constants
import com.compressphotofast.util.StatsTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.atomic.AtomicBoolean
import com.compressphotofast.util.TempFilesCleaner
import com.compressphotofast.util.SettingsManager
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.GalleryScanUtil
import com.compressphotofast.util.MediaStoreObserver
import com.compressphotofast.util.MediaStoreUtil
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.PerformanceMonitor
import com.compressphotofast.util.UriProcessingTracker
import com.compressphotofast.util.UriUtil
import com.compressphotofast.util.CompressionWorkScheduler
import com.compressphotofast.util.CompressionEnqueueResult
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

    // MediaStoreObserver для централизованной работы с ContentObserver
    private var mediaStoreObserver: MediaStoreObserver? = null

    // Интервал сканирования галереи — резервный механизм на случай пропуска событий
    // ContentObserver и JobService. Используем константу из Constants.
    private val scanInterval = Constants.BACKGROUND_SCAN_INTERVAL_MINUTES * 60 * 1000L

    // Job для периодического сканирования галереи
    private var scanJob: Job? = null

    // Job для периодической очистки временных файлов
    private var cleanupJob: Job? = null

    // Mutex для предотвращения конкурентного сканирования
    private val scanMutex = kotlinx.coroutines.sync.Mutex()

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

    // BroadcastReceiver для обработки запросов на обработку изображений
    private val imageProcessingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Constants.ACTION_PROCESS_IMAGE) {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Constants.EXTRA_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Constants.EXTRA_URI)
                }

                uri?.let {
                    // Запускаем корутину для проверки статуса изображения
                    launchServiceScope {
                        // Проверяем, не было ли изображение уже обработано
                        if (!StatsTracker.shouldProcessImage(context, uri)) {
                            return@launchServiceScope Unit
                        }

                        // Добавляем URI в список обрабатываемых и запускаем обработку
                        // processNewImage уже содержит все необходимые проверки
                        processNewImage(uri)
                    }
                }
            }
        }
    }
    
    // BroadcastReceiver для получения уведомлений о завершении сжатия
    private val compressionCompletedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_COMPRESSION_COMPLETED) {
                val uriString = intent.getStringExtra(Constants.EXTRA_URI)
                if (uriString != null) {
                    val reductionPercent = intent.getFloatExtra(Constants.EXTRA_REDUCTION_PERCENT, 0f)
                    val fileName = intent.getStringExtra(Constants.EXTRA_FILE_NAME) ?: "неизвестный"
                    val originalSize = intent.getLongExtra(Constants.EXTRA_ORIGINAL_SIZE, 0)
                    val compressedSize = intent.getLongExtra(Constants.EXTRA_COMPRESSED_SIZE, 0)

                    // Создаем Uri из строки
                    val uri = Uri.parse(uriString)

                    // Удаляем URI из списка обрабатываемых (с синхронизацией)
                    launchServiceScope {
                        uriProcessingTracker.removeProcessingUriSafe(uri)
                        return@launchServiceScope Unit
                    }
                    
                    // Показываем уведомление о результате сжатия
                    NotificationUtil.showCompressionResultNotification(applicationContext, fileName, originalSize, compressedSize, reductionPercent, skipped = false)
                    
                    // Устанавливаем таймер игнорирования изменений
                    uriProcessingTracker.setIgnorePeriod(uri)
                }
            }
        }
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

            if (!SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()) {
                stopSelf()
                return
            }

            setupContentObserver()
            registerProcessImageReceiver()
            registerReceiver(
                compressionCompletedReceiver,
                IntentFilter(Constants.ACTION_COMPRESSION_COMPLETED),
                Context.RECEIVER_NOT_EXPORTED
            )

            isRunning = true
            isReady = true
            // Observer жив — content-trigger Job не нужен как активный путь.
            // Он перепланируется в onDestroy/onTaskRemoved как recovery-механизм.
            cancelRecoveryJobsWhileReady()
            startPeriodicScanning()
            startPeriodicCleanup()
            serviceScope.launch { MediaStoreUtil.cleanupStalePendingEntries(applicationContext) }
        } catch (e: Exception) {
            isReady = false
            isRunning = false
            LogUtil.error(null, "BackgroundMonitoringService", "Не удалось подготовить monitoring FGS", e)
            try {
                mediaStoreObserver?.unregister()
                unregisterReceiver(imageProcessingReceiver)
                unregisterReceiver(compressionCompletedReceiver)
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
            SettingsManager.getInstance(applicationContext).setAutoCompression(false)
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

        // Если служба уже готова, content-trigger Job'ы не нужны: обнаружением
        // занимается живой ContentObserver. Повторный intent мог принести arm
        // из MonitoringController.startMonitoring — снимаем его снова.
        if (isReady) {
            cancelRecoveryJobsWhileReady()
        } else {
            scanForNewImages()
        }

        return START_STICKY
    }

    /**
     * Пока живой ContentObserver является активным путём обнаружения, armed
     * content-trigger Job'ы только дублируют обработку каждого нового фото
     * (двойные wake-up'ы и enqueue). Job остаётся recovery-механизмом: он
     * перепланируется в [onDestroy] и [onTaskRemoved].
     */
    private fun cancelRecoveryJobsWhileReady() {
        try {
            ImageDetectionJobService.cancelJob(applicationContext)
        } catch (e: Exception) {
            LogUtil.error(null, "BackgroundMonitoringService", "Не удалось отменить recovery Job", e)
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
        if (!isUserStopped && SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()) {
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
            SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()
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

        // Отменяем регистрацию BroadcastReceiver
        try {
            unregisterReceiver(imageProcessingReceiver)
            unregisterReceiver(compressionCompletedReceiver)
        } catch (e: Exception) {
            // Игнорируем ошибку отмены регистрации
        }
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

        // Запускаем начальное сканирование
        launchServiceScope {
            scanGalleryForUnprocessedImages()
        }
    }
    
    /**
     * Периодическое сканирование галереи для поиска новых изображений
     */
    private fun scanForNewImages() {
        serviceScope.launch {
            if (!scanMutex.tryLock()) {
                LogUtil.processDebug("Сканирование уже выполняется, пропуск")
                return@launch
            }
            try {
                // Периодически проверяем недоступные URI и восстанавливаем их
                try {
                    uriProcessingTracker.retryUnavailableUris()
                } catch (e: Exception) {
                    LogUtil.warning(Uri.EMPTY, "BackgroundMonitoring", "Ошибка при восстановлении недоступных URI: ${e.message}")
                }

                // Вычисляем динамическое окно сканирования на основе lastScanTimestamp
                val currentTimeMs = System.currentTimeMillis()
                val lastScanMs = SettingsManager.getInstance(applicationContext).getLastScanTimestamp()
                val timeWindowSeconds = ((currentTimeMs - lastScanMs) / 1000L + Constants.RECENT_SCAN_WINDOW_SECONDS)
                    .coerceIn(Constants.RECENT_SCAN_WINDOW_SECONDS, Constants.HISTORY_SCAN_WINDOW_SECONDS)
                    .toInt()

                // Используем централизованную логику сканирования с динамическим окном
                val scanResult = GalleryScanUtil.scanRecentImages(
                    applicationContext,
                    timeWindowSeconds = timeWindowSeconds
                )

                // Обрабатываем найденные изображения
                var allQueued = scanResult.completedSuccessfully
                scanResult.foundUris.forEach { uri ->
                    if (SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()) {
                        if (!processNewImage(uri)) allQueued = false
                    } else {
                        allQueued = false
                    }
                }

                // Продвигаем watermark только после того, как все найденные URI
                // переданы в долговечную WorkManager-очередь.
                if (allQueued) {
                    SettingsManager.getInstance(applicationContext).setLastScanTimestamp(currentTimeMs)
                }

                // Выводим автоматический отчет о производительности
                PerformanceMonitor.autoReportIfNeeded(this@BackgroundMonitoringService)
            } finally {
                scanMutex.unlock()
            }
        }
    }
    
    /**
     * Обработка нового изображения
     */
    private suspend fun processNewImage(uri: Uri): Boolean {
        try {
            val settingsManager = SettingsManager.getInstance(applicationContext)
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
     * Сканирует галерею для поиска необработанных изображений
     */
    private suspend fun scanGalleryForUnprocessedImages() = withContext(Dispatchers.IO) {
        if (!scanMutex.tryLock()) {
            LogUtil.processDebug("Сканирование уже выполняется, пропуск")
            return@withContext
        }
        try {
            // Используем централизованную логику сканирования за историю (по умолчанию 48 часов)
            val scanResult = GalleryScanUtil.scanHistoryImages(applicationContext)
            
            // Обрабатываем найденные изображения
            var allQueued = scanResult.completedSuccessfully
            scanResult.foundUris.forEach { uri ->
                if (!processNewImage(uri)) allQueued = false
            }
            
            // Watermark обновляется только после постановки всех найденных URI в
            // WorkManager; kill между scan и enqueue не создаёт окно потери.
            if (allQueued) {
                SettingsManager.getInstance(applicationContext).setLastScanTimestamp(System.currentTimeMillis())
            }
            
            // Выводим автоматический отчет о производительности
            PerformanceMonitor.autoReportIfNeeded(applicationContext)
        } finally {
            scanMutex.unlock()
        }
    }

    /**
     * Регистрация BroadcastReceiver для обработки запросов на сжатие изображений
     */
    private fun registerProcessImageReceiver() {
        // Регистрируем BroadcastReceiver для обработки запросов на обработку изображений
        registerReceiver(
            imageProcessingReceiver,
            IntentFilter(Constants.ACTION_PROCESS_IMAGE),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Очистка старых временных файлов
     */
    private fun cleanupTempFiles() {
        TempFilesCleaner.cleanupTempFiles(applicationContext)
    }

    /**
     * Запуск периодического сканирования галереи
     * Использует корутины вместо Handler для лучшей производительности
     */
    private fun startPeriodicScanning() {
        scanJob = serviceScope.launch {
            while (isActive) {
                if (isReady && isRunning && !isServiceDestroyed.get()) {
                    // ContentObserver жив и является активным путём обнаружения —
                    // периодический скан дал бы пустые проходы и лишний I/O.
                    LogUtil.processDebug("Периодический скан пропущен: ContentObserver активен")
                } else {
                    scanForNewImages()
                }
                delay(scanInterval)
            }
        }
    }

    /**
     * Запуск периодической очистки временных файлов
     * Использует корутины вместо Handler для лучшей производительности
     */
    private fun startPeriodicCleanup() {
        cleanupJob = serviceScope.launch {
            cleanupTempFiles() // Немедленная очистка при старте
            while (isActive) {
                // Планируем следующую очистку через 24 часа
                delay(24 * 60 * 60 * 1000L) // 24 часа
                cleanupTempFiles()
            }
        }
    }
}

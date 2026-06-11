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
import com.compressphotofast.util.ImageProcessingUtil
import com.compressphotofast.util.SettingsManager
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.GalleryScanUtil
import com.compressphotofast.util.MediaStoreObserver
import com.compressphotofast.util.MediaStoreUtil
import com.compressphotofast.util.OptimizedCacheUtil
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.PerformanceMonitor
import com.compressphotofast.util.UriProcessingTracker
import com.compressphotofast.util.UriUtil
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
    }

    // Service-scoped корутины для привязки к lifecycle сервиса
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var isServiceDestroyed = AtomicBoolean(false)

    @Inject
    lateinit var uriProcessingTracker: UriProcessingTracker

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
            } finally {
                if (isActive) {
                    // Очищаем ресурсы если корутина ещё активна
                }
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
        isRunning = true
         
        
        // Создаем канал уведомлений
        NotificationUtil.createDefaultNotificationChannel(applicationContext)
        
        // Создаем уведомление и запускаем сервис как Foreground Service
        startForegroundWithNotification()
        
        // Настраиваем ContentObserver для отслеживания изменений в MediaStore
        setupContentObserver()
        
        // Регистрируем BroadcastReceiver для обработки запросов на сжатие
        registerProcessImageReceiver()
        
        // Регистрируем BroadcastReceiver для получения уведомлений о завершении сжатия
        registerReceiver(
            compressionCompletedReceiver, 
            IntentFilter(Constants.ACTION_COMPRESSION_COMPLETED),
            Context.RECEIVER_NOT_EXPORTED
        )
        
        // Проверяем состояние автоматического сжатия при создании сервиса
        val isEnabled = SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()
        
        if (!isEnabled) {
            stopSelf()
            return
        }
        
        // Запускаем периодическое сканирование для обеспечения обработки всех изображений
        startPeriodicScanning()

        // Запускаем периодическую очистку временных файлов
        startPeriodicCleanup()

        // Очищаем stale IS_PENDING записи от предыдущих сессий
        serviceScope.launch { MediaStoreUtil.cleanupStalePendingEntries(applicationContext) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Проверяем, не является ли это запросом на остановку сервиса
        if (intent?.action == Constants.ACTION_STOP_SERVICE) {
            // Отключаем автоматическое сжатие в настройках
            SettingsManager.getInstance(applicationContext).setAutoCompression(false)
            
            // Останавливаем сервис
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Выполняем первоначальное сканирование при запуске сервиса
        scanForNewImages()
        
        return START_STICKY
    }

    /**
     * Запуск сервиса в режиме переднего плана с уведомлением
     */
    private fun startForegroundWithNotification() {
        val notification = NotificationUtil.createBackgroundServiceNotification(applicationContext)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIFICATION_ID_BACKGROUND_SERVICE,
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Constants.NOTIFICATION_ID_BACKGROUND_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
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
        super.onDestroy()
        isRunning = false
        isServiceDestroyed.set(true)

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
        val observer = MediaStoreObserver(applicationContext, OptimizedCacheUtil, uriProcessingTracker) { uri ->
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
                val timeWindowSeconds = ((currentTimeMs - lastScanMs) / 1000L)
                    .coerceIn(Constants.RECENT_SCAN_WINDOW_SECONDS, Constants.HISTORY_SCAN_WINDOW_SECONDS)
                    .toInt()

                // Используем централизованную логику сканирования с динамическим окном
                val scanResult = GalleryScanUtil.scanRecentImages(
                    applicationContext,
                    timeWindowSeconds = timeWindowSeconds
                )

                // Сохраняем время начала сканирования после успеха
                SettingsManager.getInstance(applicationContext).setLastScanTimestamp(currentTimeMs)

                // Обрабатываем найденные изображения
                scanResult.foundUris.forEach { uri ->
                    // Проверяем состояние автоматического сжатия еще раз перед началом обработки
                    if (SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()) {
                        processNewImage(uri)
                    }
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
    private suspend fun processNewImage(uri: Uri) {
        try {
            if (!UriUtil.isUriExistsSuspend(applicationContext, uri)) {
                return
            }

            val settingsManager = SettingsManager.getInstance(applicationContext)
            if (!settingsManager.isAutoCompressionEnabled()) {
                return
            }

            // isImageBeingProcessed включает проверку shouldIgnore + processingUris + recentlyProcessed
            if (uriProcessingTracker.isImageBeingProcessed(uri)) {
                return
            }

            val result = ImageProcessingUtil.handleImage(applicationContext, uri)

            if (!result.first) {
                uriProcessingTracker.removeProcessingUriSafe(uri)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            uriProcessingTracker.removeProcessingUri(uri)
            throw e
        } catch (e: Exception) {
            LogUtil.error(uri, "Обработка нового изображения", "Ошибка при обработке нового изображения", e)
            uriProcessingTracker.removeProcessingUriSafe(uri)
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
            scanResult.foundUris.forEach { uri ->
                processNewImage(uri)
            }
            
            // Обновляем временную метку после первоначального сканирования истории,
            // чтобы последующие периодические сканирования не дублировали уже найденные файлы
            SettingsManager.getInstance(applicationContext).setLastScanTimestamp(System.currentTimeMillis())
            
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
                scanForNewImages()
                // Планируем следующее сканирование через 5 минут для оптимизации энергопотребления
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
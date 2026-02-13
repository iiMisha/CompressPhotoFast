package com.compressphotofast.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import com.compressphotofast.util.TempFilesCleaner
import com.compressphotofast.util.ImageProcessingUtil
import com.compressphotofast.util.SettingsManager
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.GalleryScanUtil
import com.compressphotofast.util.MediaStoreObserver
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

    // Service-scoped корутины для привязки к lifecycle сервиса
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    @Inject
    lateinit var uriProcessingTracker: UriProcessingTracker

    // MediaStoreObserver для централизованной работы с ContentObserver
    private var mediaStoreObserver: MediaStoreObserver? = null

    // Интервал сканирования галереи для новых изображений (5 минут)
    // Оптимизировано с 1 минуты для снижения энергопотребления на 40-60%
    private val scanInterval = 300000L

    // Job для периодического сканирования галереи
    private var scanJob: Job? = null

    // Job для периодической очистки временных файлов
    private var cleanupJob: Job? = null
    
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
                    serviceScope.launch {
                        // Проверяем, не было ли изображение уже обработано
                        if (!StatsTracker.shouldProcessImage(context, uri)) {
                            return@launch
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
                    serviceScope.launch {
                        uriProcessingTracker.removeProcessingUriSafe(uri)
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

        // Отменяем все корутины, связанные с сервисом
        serviceJob.cancel()

        // Отменяем регистрацию MediaStoreObserver
        mediaStoreObserver?.unregister()

        // Останавливаем периодическое сканирование
        scanJob?.cancel()
        // Останавливаем периодическую очистку
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
            serviceScope.launch {
                processNewImage(uri)
            }
        }
        mediaStoreObserver = observer

        // Регистрируем MediaStoreObserver
        observer.register()

        // Запускаем начальное сканирование
        serviceScope.launch {
            scanGalleryForUnprocessedImages()
        }
    }
    
    /**
     * Периодическое сканирование галереи для поиска новых изображений
     */
    private fun scanForNewImages() {
        serviceScope.launch {
            // Периодически проверяем недоступные URI и восстанавливаем их
            try {
                uriProcessingTracker.retryUnavailableUris()
            } catch (e: Exception) {
                LogUtil.warning(Uri.EMPTY, "BackgroundMonitoring", "Ошибка при восстановлении недоступных URI: ${e.message}")
            }

            // Используем централизованную логику сканирования
            val scanResult = GalleryScanUtil.scanRecentImages(applicationContext)

            // Обрабатываем найденные изображения
            scanResult.foundUris.forEach { uri ->
                // Проверяем состояние автоматического сжатия еще раз перед началом обработки
                if (SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()) {
                    processNewImage(uri)
                }
            }

            // Выводим автоматический отчет о производительности
            PerformanceMonitor.autoReportIfNeeded(this@BackgroundMonitoringService)
        }
    }
    
    /**
     * Обработка нового изображения
     */
    private suspend fun processNewImage(uri: Uri) {
        if (uriProcessingTracker.isProcessing(uri)) {
            return
        }

        try {
            if (!UriUtil.isUriExistsSuspend(applicationContext, uri)) {
                return
            }

            val settingsManager = SettingsManager.getInstance(applicationContext)
            if (!settingsManager.isAutoCompressionEnabled()) {
                return
            }

            if (uriProcessingTracker.shouldIgnore(uri)) {
                return
            }

            val result = ImageProcessingUtil.handleImage(applicationContext, uri)

            if (!result.first) {
                uriProcessingTracker.removeProcessingUriSafe(uri)
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Корутина была отменена - игнорируем
        } catch (e: Exception) {
            LogUtil.error(uri, "Обработка нового изображения", "Ошибка при обработке нового изображения", e)
            uriProcessingTracker.removeProcessingUriSafe(uri)
        }
    }
    
    /**
     * Сканирование галереи для поиска необработанных изображений
     */
    private suspend fun scanGalleryForUnprocessedImages() = withContext(Dispatchers.IO) {
        // Используем централизованную логику сканирования за 24 часа
        val scanResult = GalleryScanUtil.scanDayOldImages(applicationContext)
        
        // Обрабатываем найденные изображения
        scanResult.foundUris.forEach { uri ->
            processNewImage(uri)
        }
        
        // Выводим автоматический отчет о производительности
        PerformanceMonitor.autoReportIfNeeded(applicationContext)
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
            while (isActive) {
                cleanupTempFiles()
                // Планируем следующую очистку через 24 часа
                delay(24 * 60 * 60 * 1000L) // 24 часа
            }
        }
    }
} 
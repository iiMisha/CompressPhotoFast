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
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.compressphotofast.util.TempFilesCleaner
import com.compressphotofast.util.ImageProcessingUtil
import com.compressphotofast.util.SettingsManager
import com.compressphotofast.util.UriProcessingTracker
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.GalleryScanUtil
import com.compressphotofast.util.MediaStoreObserver
import com.compressphotofast.util.LogUtil

/**
 * Сервис для фонового мониторинга новых изображений
 */
@OptIn(DelicateCoroutinesApi::class)
@AndroidEntryPoint
class BackgroundMonitoringService : Service() {

    private val executorService = Executors.newSingleThreadExecutor()
    
    // MediaStoreObserver для централизованной работы с ContentObserver
    private lateinit var mediaStoreObserver: MediaStoreObserver
    
    // Handler для периодического сканирования
    private val handler = Handler(Looper.getMainLooper())
    private val scanRunnable = object : Runnable {
        override fun run() {
            LogUtil.processDebug("Запуск периодического сканирования галереи")
            scanForNewImages()
            // Планируем следующее сканирование
            handler.postDelayed(this, 60000) // Каждую минуту
        }
    }

    // Handler для периодической очистки временных файлов
    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            LogUtil.processDebug("Запуск периодической очистки временных файлов")
            cleanupTempFiles()
            // Планируем следующую очистку через 24 часа
            cleanupHandler.postDelayed(this, 24 * 60 * 60 * 1000) // 24 часа
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
                    LogUtil.processDebug("Получен запрос на обработку изображения через broadcast: $uri")
                    
                    // Запускаем корутину для проверки статуса изображения
                    GlobalScope.launch(Dispatchers.IO) {
                        // Проверяем, не было ли изображение уже обработано
                        if (!StatsTracker.shouldProcessImage(context, uri)) {
                            LogUtil.processDebug("Изображение не требует обработки: $uri, пропускаем")
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
                    
                    // Удаляем URI из списка обрабатываемых
                    UriProcessingTracker.removeProcessingUri(uri)
                    LogUtil.processDebug("URI удален из списка обрабатываемых: $uriString (осталось ${UriProcessingTracker.getProcessingCount()} URIs)")
                    LogUtil.processDebug("Обработка изображения завершена: $fileName, сокращение размера: ${String.format("%.1f", reductionPercent)}%")
                    
                    // Показываем Toast-уведомление о результате сжатия
                    NotificationUtil.showCompressionResultToast(applicationContext, fileName, originalSize, compressedSize, reductionPercent)
                    
                    // Устанавливаем таймер игнорирования изменений
                    UriProcessingTracker.setIgnorePeriod(uri)
                    
                    LogUtil.processDebug("Обработка URI $uriString завершена и будет игнорироваться")
                }
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        LogUtil.processDebug("BackgroundMonitoringService: onCreate")
        
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
        LogUtil.processDebug("BackgroundMonitoringService: состояние автоматического сжатия: ${if (isEnabled) "включено" else "выключено"}")
        
        if (!isEnabled) {
            LogUtil.processDebug("BackgroundMonitoringService: автоматическое сжатие отключено, останавливаем сервис")
            stopSelf()
            return
        }
        
        // Запускаем периодическое сканирование для обеспечения обработки всех изображений
        handler.post(scanRunnable)
        LogUtil.processDebug("BackgroundMonitoringService: периодическое сканирование запущено")

        // Запускаем периодическую очистку временных файлов
        cleanupHandler.post(cleanupRunnable)
        LogUtil.processDebug("BackgroundMonitoringService: периодическая очистка временных файлов запущена")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.processDebug("BackgroundMonitoringService: onStartCommand(startId=$startId)")
        
        // Проверяем, не является ли это запросом на остановку сервиса
        if (intent?.action == Constants.ACTION_STOP_SERVICE) {
            LogUtil.processDebug("BackgroundMonitoringService: получен запрос на остановку сервиса")
            
            // Отключаем автоматическое сжатие в настройках
            SettingsManager.getInstance(applicationContext).setAutoCompression(false)
            
            // Останавливаем сервис
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Выполняем первоначальное сканирование при запуске сервиса
        LogUtil.processDebug("BackgroundMonitoringService: запуск первоначального сканирования")
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
        // Отменяем регистрацию MediaStoreObserver
        if (::mediaStoreObserver.isInitialized) {
            mediaStoreObserver.unregister()
        }
        
        executorService.shutdown()
        // Останавливаем периодическое сканирование
        handler.removeCallbacks(scanRunnable)
        // Останавливаем периодическую очистку
        cleanupHandler.removeCallbacks(cleanupRunnable)
        
        // Отменяем регистрацию BroadcastReceiver
        try {
            unregisterReceiver(imageProcessingReceiver)
            unregisterReceiver(compressionCompletedReceiver)
        } catch (e: Exception) {
            LogUtil.errorWithException("Ошибка при отмене регистрации BroadcastReceiver", e)
        }
        
        LogUtil.processDebug("Фоновый сервис остановлен")
    }
    
    /**
     * Настройка наблюдателя за контент-провайдером MediaStore
     */
    private fun setupContentObserver() {
        // Создаем MediaStoreObserver
        mediaStoreObserver = MediaStoreObserver(applicationContext, Handler(Looper.getMainLooper())) { uri ->
            // Этот код будет выполнен при обнаружении изменений после задержки
            executorService.execute {
                kotlinx.coroutines.runBlocking {
                    processNewImage(uri)
                }
            }
        }
        
        // Регистрируем MediaStoreObserver
        mediaStoreObserver.register()
        
        // Запускаем начальное сканирование
        executorService.execute {
            kotlinx.coroutines.runBlocking {
                scanGalleryForUnprocessedImages()
            }
        }
    }
    
    /**
     * Периодическое сканирование галереи для поиска новых изображений
     */
    private fun scanForNewImages() {
        executorService.execute {
            kotlinx.coroutines.runBlocking {
                // Используем централизованную логику сканирования
                val scanResult = GalleryScanUtil.scanRecentImages(applicationContext)
                
                // Обрабатываем найденные изображения
                scanResult.foundUris.forEach { uri ->
                    // Проверяем состояние автоматического сжатия еще раз перед началом обработки
                    if (SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()) {
                        LogUtil.processDebug("BackgroundMonitoringService: обработка изображения: $uri")
                        processNewImage(uri)
                    }
                }
                
                LogUtil.processDebug("BackgroundMonitoringService: сканирование завершено. Обработано: ${scanResult.processedCount}, Пропущено: ${scanResult.skippedCount}")
            }
        }
    }
    
    /**
     * Обработка нового изображения
     */
    private suspend fun processNewImage(uri: Uri) {
        LogUtil.processDebug("BackgroundMonitoringService: начало обработки нового изображения: $uri")
        LogUtil.uriInfo(uri, "URI scheme: ${uri.scheme}, authority: ${uri.authority}, path: ${uri.path}")
        
        try {
            // Проверяем, включено ли автоматическое сжатие
            val settingsManager = SettingsManager.getInstance(applicationContext)
            if (!settingsManager.isAutoCompressionEnabled()) {
                LogUtil.processDebug("BackgroundMonitoringService: автоматическое сжатие отключено, пропускаем обработку")
                return
            }

            // Проверяем, обрабатывается ли уже это изображение
            if (UriProcessingTracker.isImageBeingProcessed(uri)) {
                LogUtil.processDebug("BackgroundMonitoringService: изображение уже находится в обработке: $uri")
                return
            }
            
            // Проверяем, не следует ли игнорировать это изменение
            if (UriProcessingTracker.shouldIgnore(uri)) {
                LogUtil.processDebug("BackgroundMonitoringService: игнорируем изменение для недавно обработанного URI: $uri")
                return
            }
            
            // Добавляем URI в список обрабатываемых
            UriProcessingTracker.addProcessingUri(uri)
            
            // Используем централизованный метод обработки изображения
            val result = ImageProcessingUtil.handleImage(applicationContext, uri)
            LogUtil.processDebug("BackgroundMonitoringService: результат обработки изображения: ${result.third}")
            
            // Если обработка не удалась, удаляем URI из списка обрабатываемых
            if (!result.first) {
                UriProcessingTracker.removeProcessingUri(uri)
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Обработка нового изображения", "Ошибка при обработке нового изображения", e)
            // В случае исключения, очищаем URI из списка обрабатываемых
            UriProcessingTracker.removeProcessingUri(uri)
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
        
        LogUtil.processDebug("BackgroundMonitoringService: начальное сканирование завершено. Обработано: ${scanResult.processedCount}, Пропущено: ${scanResult.skippedCount}")
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
        LogUtil.processDebug("BackgroundMonitoringService: зарегистрирован BroadcastReceiver для ACTION_PROCESS_IMAGE")
    }

    /**
     * Очистка старых временных файлов
     */
    private fun cleanupTempFiles() {
        TempFilesCleaner.cleanupTempFiles(applicationContext)
    }
} 
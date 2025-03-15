package com.compressphotofast.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.compressphotofast.R
import com.compressphotofast.ui.MainActivity
import com.compressphotofast.util.Constants
import com.compressphotofast.util.FileUtil
import com.compressphotofast.util.StatsTracker
import com.compressphotofast.worker.ImageCompressionWorker
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import android.content.pm.ServiceInfo
import com.compressphotofast.util.TempFilesCleaner
import com.compressphotofast.util.ImageProcessingUtil
import com.compressphotofast.util.SettingsManager
import com.compressphotofast.util.UriProcessingTracker
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.GalleryScanUtil
import com.compressphotofast.util.FileInfoUtil

/**
 * Сервис для фонового мониторинга новых изображений
 */
@AndroidEntryPoint
class BackgroundMonitoringService : Service() {

    @Inject
    lateinit var workManager: WorkManager

    private lateinit var contentObserver: ContentObserver
    private val executorService = Executors.newSingleThreadExecutor()
    
    // Система для предотвращения дублирования событий от ContentObserver
    private val recentlyObservedUris = Collections.synchronizedMap(HashMap<String, Long>())
    private val contentObserverDebounceTime = 2000L // 2000мс (2 секунды) для дедупликации событий
    
    // Очередь отложенных задач для ContentObserver
    private val pendingTasks = ConcurrentHashMap<String, Runnable>()
    
    companion object {
        // Пустой companion object, функциональность перенесена в UriProcessingTracker
    }
    
    // Таймаут для обработки изображения (мс)
    private val processingTimeout = 30000L // 30 секунд
    
    // Игнорировать изменения в MediaStore на указанное время после удачного сжатия (мс)
    private val ignoreMediaStoreChangesAfterCompression = 5000L // 5 секунд
    private val ignoreChangesUntil = ConcurrentHashMap<String, Long>()

    // Handler для периодического сканирования
    private val handler = Handler(Looper.getMainLooper())
    private val scanRunnable = object : Runnable {
        override fun run() {
            Timber.d("Запуск периодического сканирования галереи")
            scanForNewImages()
            // Планируем следующее сканирование
            handler.postDelayed(this, 60000) // Каждую минуту
        }
    }

    // Handler для периодической очистки временных файлов
    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            Timber.d("Запуск периодической очистки временных файлов")
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
                    Timber.d("Получен запрос на обработку изображения через broadcast: $uri")
                    
                    // Проверяем, не обрабатывается ли URI уже через MainActivity
                    if (StatsTracker.isUriBeingProcessedByMainActivity(uri)) {
                        Timber.d("URI $uri уже обрабатывается через MainActivity, пропускаем")
                        return
                    }
                    
                    // Запускаем корутину для проверки статуса изображения
                    GlobalScope.launch(Dispatchers.IO) {
                        // Проверяем, не было ли изображение уже обработано
                        if (!StatsTracker.shouldProcessImage(context, uri)) {
                            Timber.d("Изображение не требует обработки: $uri, пропускаем")
                            return@launch
                        }
                        
                        // Проверяем, не находится ли URI уже в списке обрабатываемых
                        if (UriProcessingTracker.isImageBeingProcessed(uri.toString())) {
                            Timber.d("URI уже в списке обрабатываемых: $uri, пропускаем")
                            return@launch
                        }
                        
                        // Добавляем URI в список обрабатываемых, чтобы избежать повторной обработки
                        UriProcessingTracker.addProcessingUri(uri.toString())
                        
                        // Запускаем обработку изображения
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
                    
                    // Удаляем URI из списка обрабатываемых
                    val wasRemoved = UriProcessingTracker.removeProcessingUri(uriString)
                    Timber.d("URI был ${if (wasRemoved) "успешно удалён" else "не найден"} в списке обрабатываемых: $uriString (осталось ${UriProcessingTracker.getProcessingCount()} URIs)")
                    Timber.d("Обработка изображения завершена: $fileName, сокращение размера: ${String.format("%.1f", reductionPercent)}%")
                    
                    // Показываем Toast-уведомление о результате сжатия
                    NotificationUtil.showCompressionResultToast(applicationContext, fileName, originalSize, compressedSize, reductionPercent)
                    
                    // Устанавливаем таймер игнорирования изменений
                    UriProcessingTracker.setIgnorePeriod(uriString)
                    
                    Timber.d("Обработка URI $uriString завершена и будет игнорироваться")
                }
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Timber.d("BackgroundMonitoringService: onCreate")
        
        // Создаем канал уведомлений
        NotificationUtil.createNotificationChannel(applicationContext)
        
        // Создаем уведомление и запускаем сервис как Foreground Service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Constants.NOTIFICATION_ID_BACKGROUND_SERVICE, 
                NotificationUtil.createBackgroundServiceNotification(applicationContext), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Constants.NOTIFICATION_ID_BACKGROUND_SERVICE, 
                NotificationUtil.createBackgroundServiceNotification(applicationContext))
        }
        
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
        Timber.d("BackgroundMonitoringService: состояние автоматического сжатия: ${if (isEnabled) "включено" else "выключено"}")
        
        if (!isEnabled) {
            Timber.d("BackgroundMonitoringService: автоматическое сжатие отключено, останавливаем сервис")
            stopSelf()
            return
        }
        
        // Запускаем периодическое сканирование для обеспечения обработки всех изображений
        handler.post(scanRunnable)
        Timber.d("BackgroundMonitoringService: периодическое сканирование запущено")

        // Запускаем периодическую очистку временных файлов
        cleanupHandler.post(cleanupRunnable)
        Timber.d("BackgroundMonitoringService: периодическая очистка временных файлов запущена")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("BackgroundMonitoringService: onStartCommand(startId=$startId)")
        
        // Проверяем, не является ли это запросом на остановку сервиса
        if (intent?.action == Constants.ACTION_STOP_SERVICE) {
            Timber.d("BackgroundMonitoringService: получен запрос на остановку сервиса")
            
            // Отключаем автоматическое сжатие в настройках
            SettingsManager.getInstance(applicationContext).setAutoCompression(false)
            
            // Останавливаем сервис
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Создание уведомления для foreground сервиса
        startForeground(Constants.NOTIFICATION_ID_BACKGROUND_SERVICE, 
            NotificationUtil.createBackgroundServiceNotification(applicationContext))
        Timber.d("BackgroundMonitoringService: запущен как foreground сервис")
        
        // Выполняем первоначальное сканирование при запуске сервиса
        Timber.d("BackgroundMonitoringService: запуск первоначального сканирования")
        scanForNewImages()
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(contentObserver)
        executorService.shutdown()
        // Останавливаем периодическое сканирование
        handler.removeCallbacks(scanRunnable)
        // Останавливаем периодическую очистку
        cleanupHandler.removeCallbacks(cleanupRunnable)
        
        // Очищаем все отложенные задачи
        pendingTasks.forEach { (uri, runnable) ->
            handler.removeCallbacks(runnable)
            Timber.d("Отменена отложенная задача для $uri при остановке сервиса")
        }
        pendingTasks.clear()
        
        // Отменяем регистрацию BroadcastReceiver
        try {
            unregisterReceiver(imageProcessingReceiver)
            unregisterReceiver(compressionCompletedReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при отмене регистрации BroadcastReceiver")
        }
        
        Timber.d("Фоновый сервис остановлен")
    }
    
    /**
     * Настройка наблюдателя за контент-провайдером MediaStore
     */
    private fun setupContentObserver() {
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                
                uri?.let {
                    // Проверяем, что это новое изображение с базовой фильтрацией
                    if (it.toString().contains("media") && it.toString().contains("image")) {
                        // Предотвращаем дублирование событий для одного URI за короткий период времени
                        val uriString = it.toString()
                        val currentTime = System.currentTimeMillis()
                        val lastObservedTime = recentlyObservedUris[uriString]
                        
                        if (lastObservedTime != null && (currentTime - lastObservedTime < contentObserverDebounceTime)) {
                            // Если URI был недавно обработан, пропускаем его
                            return
                        }
                        
                        // Обновляем время последнего наблюдения
                        recentlyObservedUris[uriString] = currentTime
                        
                        // Удаляем старые записи (старше 10 секунд)
                        val urisToRemove = recentlyObservedUris.entries
                            .filter { (currentTime - it.value) > 10000 }
                            .map { it.key }
                        
                        urisToRemove.forEach { key -> recentlyObservedUris.remove(key) }
                        
                        // Логируем событие
                        Timber.d("ContentObserver: обнаружено изменение в MediaStore: $uri, обработка через ${Constants.CONTENT_OBSERVER_DELAY_SECONDS} сек")
                        
                        // Отменяем предыдущую отложенную задачу для этого URI, если она существует
                        pendingTasks[uriString]?.let { runnable ->
                            handler.removeCallbacks(runnable)
                            Timber.d("ContentObserver: предыдущая задача для $uriString отменена")
                        }
                        
                        // Создаем новую задачу с задержкой
                        val delayTask = Runnable {
                            executorService.execute {
                                kotlinx.coroutines.runBlocking {
                                    Timber.d("ContentObserver: начинаем обработку URI $uriString после задержки")
                                    processNewImage(it)
                                    pendingTasks.remove(uriString)
                                }
                            }
                        }
                        
                        // Сохраняем задачу и планируем ее выполнение с задержкой
                        pendingTasks[uriString] = delayTask
                        handler.postDelayed(delayTask, Constants.CONTENT_OBSERVER_DELAY_SECONDS * 1000)
                    }
                }
            }
        }
        
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
        
        Timber.d("ContentObserver зарегистрирован для MediaStore.Images.Media.EXTERNAL_CONTENT_URI")
        
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
                        Timber.d("BackgroundMonitoringService: обработка изображения: $uri")
                        processNewImage(uri)
                    }
                }
                
                Timber.d("BackgroundMonitoringService: сканирование завершено. Обработано: ${scanResult.processedCount}, Пропущено: ${scanResult.skippedCount}")
            }
        }
    }
    
    /**
     * Обработка нового изображения
     */
    private suspend fun processNewImage(uri: Uri) {
        Timber.d("BackgroundMonitoringService: начало обработки нового изображения: $uri")
        Timber.d("BackgroundMonitoringService: URI scheme: ${uri.scheme}, authority: ${uri.authority}, path: ${uri.path}")
        
        try {
            // Проверяем, включено ли автоматическое сжатие
            val settingsManager = SettingsManager.getInstance(applicationContext)
            if (!settingsManager.isAutoCompressionEnabled()) {
                Timber.d("BackgroundMonitoringService: автоматическое сжатие отключено, пропускаем обработку")
                return
            }

            val uriString = uri.toString()
            
            // Проверяем, обрабатывается ли уже это изображение
            if (UriProcessingTracker.isImageBeingProcessed(uriString)) {
                Timber.d("BackgroundMonitoringService: изображение уже находится в обработке: $uri")
                return
            }
            
            // Проверяем, не следует ли игнорировать это изменение
            if (UriProcessingTracker.shouldIgnoreUri(uriString)) {
                Timber.d("BackgroundMonitoringService: игнорируем изменение для недавно обработанного URI: $uri")
                return
            }

            // Логируем подробную информацию о файле
            logDetailedFileInfo(uri)
            
            // Используем централизованную логику проверки и обработки изображения
            if (ImageProcessingUtil.shouldProcessImage(applicationContext, uri)) {
                // Регистрируем URI как обрабатываемый перед началом обработки
                UriProcessingTracker.addProcessingUri(uriString)
                
                // Запускаем обработку изображения
                ImageProcessingUtil.processImage(applicationContext, uri)
                Timber.d("BackgroundMonitoringService: запрос на обработку изображения отправлен: $uri")
            } else {
                Timber.d("BackgroundMonitoringService: изображение не требует обработки: $uri")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "BackgroundMonitoringService: ошибка при обработке изображения: $uri")
            val uriString = uri.toString()
            UriProcessingTracker.removeProcessingUri(uriString)
        }
    }
    
    /**
     * Логирует подробную информацию о файле для отладки
     */
    private fun logDetailedFileInfo(uri: Uri) {
        // Используем централизованную логику для получения информации о файле
        FileInfoUtil.getFileInfo(applicationContext, uri)
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
        
        Timber.d("BackgroundMonitoringService: начальное сканирование завершено. Обработано: ${scanResult.processedCount}, Пропущено: ${scanResult.skippedCount}")
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
        Timber.d("BackgroundMonitoringService: зарегистрирован BroadcastReceiver для ACTION_PROCESS_IMAGE")
    }

    /**
     * Очистка старых временных файлов
     */
    private fun cleanupTempFiles() {
        TempFilesCleaner.cleanupTempFiles(applicationContext)
    }
} 
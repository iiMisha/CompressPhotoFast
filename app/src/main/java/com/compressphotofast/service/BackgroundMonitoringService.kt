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

/**
 * Сервис для фонового мониторинга новых изображений
 */
@AndroidEntryPoint
class BackgroundMonitoringService : Service() {

    @Inject
    lateinit var workManager: WorkManager

    private lateinit var contentObserver: ContentObserver
    private val executorService = Executors.newSingleThreadExecutor()
    
    // Набор URI, которые в данный момент находятся в обработке
    private val processingUris = Collections.synchronizedSet(HashSet<String>())
    
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
                        if (StatsTracker.isImageProcessed(context, uri)) {
                            Timber.d("Изображение уже обработано: $uri, пропускаем")
                            return@launch
                        }
                        
                        // Проверяем, не находится ли URI уже в списке обработанных
                        if (processingUris.contains(uri.toString())) {
                            Timber.d("URI уже в списке обработанных: $uri, пропускаем")
                            return@launch
                        }
                        
                        // Добавляем URI в список обработанных, чтобы избежать повторной обработки
                        processingUris.add(uri.toString())
                        
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
                    
                    // Удаляем URI из списка обрабатываемых
                    val wasRemoved = processingUris.remove(uriString)
                    Timber.d("URI был ${if (wasRemoved) "успешно удалён" else "не найден"} в списке обрабатываемых: $uriString (осталось ${processingUris.size} URIs)")
                    Timber.d("Обработка изображения завершена: $fileName, сокращение размера: ${String.format("%.1f", reductionPercent)}%")
                    
                    // Устанавливаем таймер игнорирования изменений
                    ignoreChangesUntil[uriString] = System.currentTimeMillis() + ignoreMediaStoreChangesAfterCompression
                    
                    // Запускаем таймер для очистки игнорируемого URI
                    Handler(Looper.getMainLooper()).postDelayed({
                        ignoreChangesUntil.remove(uriString)
                    }, ignoreMediaStoreChangesAfterCompression * 2)
                    
                    Timber.d("Обработка URI $uriString завершена, будет игнорироваться в течение ${ignoreMediaStoreChangesAfterCompression}мс")
                }
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Timber.d("BackgroundMonitoringService: onCreate")
        
        // Создаем уведомление и запускаем сервис как Foreground Service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Constants.NOTIFICATION_ID_BACKGROUND_SERVICE, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Constants.NOTIFICATION_ID_BACKGROUND_SERVICE, createNotification())
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
        val isEnabled = isAutoCompressionEnabled()
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
            val prefs = getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(Constants.PREF_AUTO_COMPRESSION, false).apply()
            
            // Останавливаем сервис
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Создание уведомления для foreground сервиса
        val notification = createNotification()
        startForeground(Constants.NOTIFICATION_ID_BACKGROUND_SERVICE, notification)
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
     * Создание уведомления для foreground сервиса
     */
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Создаем действие для остановки сервиса
        val stopIntent = Intent(this, BackgroundMonitoringService::class.java).apply {
            action = Constants.ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_background_service))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_launcher_foreground,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(getString(R.string.notification_background_service_description)))
            .build()
    }
    
    /**
     * Настройка наблюдателя за контент-провайдером MediaStore
     */
    private fun setupContentObserver() {
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                
                uri?.let {
                    Timber.d("ContentObserver: обнаружено изменение в MediaStore: $uri")
                    
                    // Проверяем, что это новое изображение с базовой фильтрацией
                    if (it.toString().contains("media") && it.toString().contains("image")) {
                        executorService.execute {
                            kotlinx.coroutines.runBlocking {
                                processNewImage(it)
                            }
                        }
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
                Timber.d("BackgroundMonitoringService: начало сканирования галереи")
                
                // Проверяем состояние автоматического сжатия
                if (!isAutoCompressionEnabled()) {
                    Timber.d("BackgroundMonitoringService: автоматическое сжатие отключено, пропускаем сканирование")
                    return@runBlocking
                }
                
                // Запрашиваем последние изображения из MediaStore
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE
                )
                
                // Ищем фотографии, созданные за последние 5 минут
                val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
                val currentTimeInSeconds = System.currentTimeMillis() / 1000
                val fiveMinutesAgo = currentTimeInSeconds - (5 * 60) // 5 минут назад
                val selectionArgs = arrayOf(fiveMinutesAgo.toString())
                
                // Сортируем по времени создания (сначала новые)
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                
                try {
                    contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                        
                        val totalImages = cursor.count
                        Timber.d("BackgroundMonitoringService: найдено $totalImages изображений за последние 5 минут")
                        
                        var processedCount = 0
                        var skippedCount = 0
                        
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idColumn)
                            val name = if (nameColumn != -1) cursor.getString(nameColumn) else "unknown"
                            val size = if (sizeColumn != -1) cursor.getLong(sizeColumn) else 0L
                            
                            // Пропускаем слишком маленькие файлы
                            if (size < Constants.MIN_FILE_SIZE) {
                                Timber.d("BackgroundMonitoringService: пропуск маленького файла: $name ($size байт)")
                                skippedCount++
                                continue
                            }
                            
                            // Пропускаем слишком большие файлы
                            if (size > Constants.MAX_FILE_SIZE) {
                                Timber.d("BackgroundMonitoringService: пропуск большого файла: $name (${size / (1024 * 1024)} MB)")
                                skippedCount++
                                continue
                            }
                            
                            val contentUri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                            )
                            
                            // Проверяем, не обрабатывается ли URI уже через MainActivity
                            if (StatsTracker.isUriBeingProcessedByMainActivity(contentUri)) {
                                Timber.d("BackgroundMonitoringService: URI $contentUri уже обрабатывается через MainActivity, пропускаем")
                                skippedCount++
                                continue
                            }
                            
                            // Проверяем, не находится ли URI уже в процессе обработки
                            if (processingUris.contains(contentUri.toString())) {
                                Timber.d("BackgroundMonitoringService: URI $contentUri уже в процессе обработки, пропускаем")
                                skippedCount++
                                continue
                            }
                            
                            // Проверяем, не было ли изображение уже обработано (по EXIF)
                            if (StatsTracker.isImageProcessed(applicationContext, contentUri)) {
                                Timber.d("BackgroundMonitoringService: изображение уже обработано (по EXIF): $contentUri")
                                skippedCount++
                                continue
                            }
                            
                            // Обрабатываем изображение
                            Timber.d("BackgroundMonitoringService: обработка изображения: $name ($contentUri)")
                            processNewImage(contentUri)
                            processedCount++
                        }
                        
                        Timber.d("BackgroundMonitoringService: сканирование завершено. Обработано: $processedCount, Пропущено: $skippedCount")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "BackgroundMonitoringService: ошибка при сканировании галереи")
                }
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
            val uriString = uri.toString()
            
            // Проверяем, обрабатывается ли уже это изображение
            if (processingUris.contains(uriString)) {
                Timber.d("BackgroundMonitoringService: изображение уже находится в обработке: $uri")
                return
            }
            
            // Проверяем, не следует ли игнорировать это изменение
            val ignoreUntil = ignoreChangesUntil[uriString]
            if (ignoreUntil != null && System.currentTimeMillis() < ignoreUntil) {
                Timber.d("BackgroundMonitoringService: игнорируем изменение для недавно обработанного URI: $uri")
                return
            }
            
            // Проверяем, включено ли автоматическое сжатие
            if (!isAutoCompressionEnabled()) {
                Timber.d("BackgroundMonitoringService: автоматическое сжатие отключено")
                return
            }
            
            // Проверяем, не обрабатывается ли URI уже через MainActivity
            if (StatsTracker.isUriBeingProcessedByMainActivity(uri)) {
                Timber.d("BackgroundMonitoringService: URI $uri уже обрабатывается через MainActivity, пропускаем")
                return
            }

            // Ждем пока файл станет доступным
            if (!FileUtil.waitForFileAvailability(applicationContext, uri)) {
                Timber.d("BackgroundMonitoringService: файл недоступен после ожидания: $uri")
                return
            }

            // Проверяем размер файла
            val fileSize = FileUtil.getFileSize(applicationContext, uri)
            if (fileSize < Constants.MIN_FILE_SIZE || fileSize > Constants.MAX_FILE_SIZE) {
                Timber.d("BackgroundMonitoringService: пропускаем файл из-за размера ($fileSize байт): $uri")
                return
            }

            // Логируем подробную информацию о файле
            logDetailedFileInfo(uri)

            // Проверяем EXIF-данные 
            if (StatsTracker.isImageProcessed(applicationContext, uri)) {
                Timber.d("BackgroundMonitoringService: изображение уже обработано (по EXIF): $uri")
                return
            }

            // Помечаем URI как обрабатываемый
            processingUris.add(uriString)
            Timber.d("URI добавлен в список обрабатываемых: $uriString (всего ${processingUris.size} URIs)")
            
            // Получаем имя файла и качество сжатия
            val fileName = FileUtil.getFileNameFromUri(applicationContext, uri)
            val compressionQuality = Constants.DEFAULT_COMPRESSION_QUALITY
            
            // Создаем уникальный тег для работы
            val workTag = "compress_${System.currentTimeMillis()}_${fileName}"
            
            // Создаем и запускаем работу по сжатию
            val compressionWork = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
                .setInputData(
                    workDataOf(
                        Constants.WORK_INPUT_IMAGE_URI to uri.toString(),
                        "compression_quality" to compressionQuality
                    )
                )
                .addTag(workTag)
                .build()
            
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(
                    workTag,
                    ExistingWorkPolicy.REPLACE,
                    compressionWork
                )
            
            Timber.d("BackgroundMonitoringService: запущена работа по сжатию для $uri с тегом $workTag")
            
        } catch (e: Exception) {
            Timber.e(e, "BackgroundMonitoringService: ошибка при обработке изображения: $uri")
            processingUris.remove(uri.toString())
        }
    }
    
    /**
     * Логирует подробную информацию о файле для отладки
     */
    private fun logDetailedFileInfo(uri: Uri) {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE
            )
            
            contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val dateIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    
                    val id = if (idIndex != -1) cursor.getLong(idIndex) else -1
                    val name = if (nameIndex != -1) cursor.getString(nameIndex) else "unknown"
                    val size = if (sizeIndex != -1) cursor.getLong(sizeIndex) else -1
                    val date = if (dateIndex != -1) cursor.getLong(dateIndex) else -1
                    val mime = if (mimeIndex != -1) cursor.getString(mimeIndex) else "unknown"
                    
                    Timber.d("BackgroundMonitoringService: Информация о файле: ID=$id, Имя=$name, Размер=$size, Дата=$date, MIME=$mime, URI=$uri")
                    
                    // Получаем путь к файлу
                    val path = FileUtil.getFilePathFromUri(applicationContext, uri)
                    Timber.d("BackgroundMonitoringService: Путь к файлу: $path")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении информации о файле: $uri")
        }
    }
    
    /**
     * Проверка, является ли файл временным (pending)
     */
    private suspend fun isFilePending(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING)
            contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val isPendingIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                    return@withContext cursor.getInt(isPendingIndex) == 1
                }
            }
            false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса файла")
            true // В случае ошибки считаем файл временным
        }
    }

    /**
     * Получение размера файла
     */
    private suspend fun getFileSize(uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.SIZE)
            contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    return@withContext cursor.getLong(sizeIndex)
                }
            }
            -1
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении размера файла")
            -1
        }
    }
    
    /**
     * Получение информации о файле для отладки
     */
    private fun getFileInfo(uri: Uri): String {
        val cursor = contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATA
            ),
            null,
            null,
            null
        )
        
        var info = "Нет данных"
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIndex = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val sizeIndex = it.getColumnIndex(MediaStore.Images.Media.SIZE)
                val dataIndex = it.getColumnIndex(MediaStore.Images.Media.DATA)
                
                val name = if (nameIndex != -1) it.getString(nameIndex) else "неизвестно"
                val date = if (dateIndex != -1) it.getLong(dateIndex) else 0
                val size = if (sizeIndex != -1) it.getLong(sizeIndex) else 0
                val data = if (dataIndex != -1) it.getString(dataIndex) else "неизвестно"
                
                info = "Имя: $name, Дата: $date, Размер: $size, Путь: $data"
            }
        }
        
        return info
    }
    
    /**
     * Получение текущего уровня сжатия из SharedPreferences
     */
    private fun getCompressionQuality(): Int {
        val sharedPreferences = applicationContext.getSharedPreferences(
            Constants.PREF_FILE_NAME,
            Context.MODE_PRIVATE
        )
        return sharedPreferences.getInt(
            Constants.PREF_COMPRESSION_QUALITY,
            Constants.DEFAULT_COMPRESSION_QUALITY
        )
    }

    /**
     * Проверка состояния автоматического сжатия
     */
    private fun isAutoCompressionEnabled(): Boolean {
        val sharedPreferences = applicationContext.getSharedPreferences(
            Constants.PREF_FILE_NAME,
            Context.MODE_PRIVATE
        )
        return sharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, false)
    }

    /**
     * Сканирование галереи для поиска необработанных изображений
     */
    private suspend fun scanGalleryForUnprocessedImages() = withContext(Dispatchers.IO) {
        if (!isAutoCompressionEnabled()) {
            Timber.d("BackgroundMonitoringService: автоматическое сжатие отключено, пропускаем сканирование")
            return@withContext
        }

        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED
            )

            // Ищем только недавно добавленные изображения (за последние 24 часа)
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val oneDayAgo = (System.currentTimeMillis() / 1000) - (24 * 60 * 60)
            val selectionArgs = arrayOf(oneDayAgo.toString())
            
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                var processedCount = 0
                var skippedCount = 0

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
                    
                    if (size < Constants.MIN_FILE_SIZE || size > Constants.MAX_FILE_SIZE) {
                        skippedCount++
                        continue
                    }

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    if (!StatsTracker.isImageProcessed(applicationContext, contentUri)) {
                        processNewImage(contentUri)
                        processedCount++
                    } else {
                        skippedCount++
                    }
                }

                Timber.d("BackgroundMonitoringService: сканирование завершено. Обработано: $processedCount, Пропущено: $skippedCount")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при сканировании галереи")
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
        Timber.d("BackgroundMonitoringService: зарегистрирован BroadcastReceiver для ACTION_PROCESS_IMAGE")
    }

    /**
     * Очистка старых временных файлов
     */
    private fun cleanupTempFiles() {
        try {
            val cacheDir = applicationContext.cacheDir
            val currentTime = System.currentTimeMillis()
            val files = cacheDir.listFiles { file ->
                file.name.startsWith("temp_image_") && 
                (currentTime - file.lastModified() > Constants.TEMP_FILE_MAX_AGE)
            }
            
            files?.forEach { file ->
                if (!file.delete()) {
                    Timber.w("Не удалось удалить старый временный файл: ${file.absolutePath}")
                } else {
                    Timber.d("Удален старый временный файл: ${file.absolutePath}")
                }
            }
            
            Timber.d("Очистка временных файлов завершена, обработано файлов: ${files?.size ?: 0}")
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при очистке временных файлов")
        }
    }
} 
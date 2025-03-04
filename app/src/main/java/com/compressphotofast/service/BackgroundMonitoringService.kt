package com.compressphotofast.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
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
import com.compressphotofast.util.ImageTrackingUtil
import com.compressphotofast.worker.ImageCompressionWorker
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Сервис для фонового мониторинга новых изображений
 */
@AndroidEntryPoint
class BackgroundMonitoringService : Service() {

    @Inject
    lateinit var workManager: WorkManager

    private lateinit var contentObserver: ContentObserver
    private val executorService = Executors.newSingleThreadExecutor()
    
    // Список последних обработанных URI для предотвращения повторной обработки
    private val processedUris = mutableSetOf<String>()
    private val maxProcessedUrisSize = 100 // Ограничиваем размер истории

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
    
    override fun onCreate() {
        super.onCreate()
        Timber.d("BackgroundMonitoringService: onCreate()")
        
        // Проверяем состояние автоматического сжатия при создании сервиса
        val isEnabled = isAutoCompressionEnabled()
        Timber.d("BackgroundMonitoringService: состояние автоматического сжатия: ${if (isEnabled) "включено" else "выключено"}")
        
        if (!isEnabled) {
            Timber.d("BackgroundMonitoringService: автоматическое сжатие отключено, останавливаем сервис")
            stopSelf()
            return
        }
        
        setupContentObserver()
        Timber.d("BackgroundMonitoringService: ContentObserver настроен")
        
        // Запускаем периодическое сканирование для обеспечения обработки всех изображений
        handler.post(scanRunnable)
        Timber.d("BackgroundMonitoringService: периодическое сканирование запущено")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("BackgroundMonitoringService: onStartCommand(startId=$startId)")
        
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
        
        return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_background_service))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
                    MediaStore.Images.Media.IS_PENDING
                )
                
                // Ищем фотографии, созданные за последние 5 минут
                val selection = "${MediaStore.Images.Media.DATE_ADDED} > ? AND ${MediaStore.Images.Media.IS_PENDING} = 0"
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
                        val isPendingColumn = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                        
                        val totalImages = cursor.count
                        Timber.d("BackgroundMonitoringService: найдено $totalImages изображений за последние 5 минут")
                        
                        var processedCount = 0
                        var skippedCount = 0
                        
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idColumn)
                            val name = if (nameColumn != -1) cursor.getString(nameColumn) else "unknown"
                            val isPending = if (isPendingColumn != -1) cursor.getInt(isPendingColumn) == 1 else false
                            
                            if (isPending) {
                                Timber.d("BackgroundMonitoringService: пропуск временного файла: $name")
                                skippedCount++
                                continue
                            }
                            
                            val contentUri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                            )
                            
                            if (!processedUris.contains(contentUri.toString())) {
                                Timber.d("BackgroundMonitoringService: обработка изображения: $name ($contentUri)")
                                processNewImage(contentUri)
                                processedCount++
                            } else {
                                Timber.d("BackgroundMonitoringService: пропуск ранее обработанного изображения: $name")
                                skippedCount++
                            }
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
        
        try {
            // Проверяем, включено ли автоматическое сжатие
            if (!isAutoCompressionEnabled()) {
                Timber.d("BackgroundMonitoringService: автоматическое сжатие отключено")
                return
            }
            
            // Проверяем, не является ли файл временным
            if (isFilePending(uri)) {
                Timber.d("BackgroundMonitoringService: файл все еще в процессе создания: $uri")
                return
            }
            
            // Проверяем размер файла
            val fileSize = getFileSize(uri)
            if (fileSize <= Constants.MIN_FILE_SIZE) {
                Timber.d("BackgroundMonitoringService: файл слишком мал для сжатия: $fileSize байт")
                return
            }
            
            if (fileSize > Constants.MAX_FILE_SIZE) {
                Timber.d("BackgroundMonitoringService: файл слишком велик для обработки: ${fileSize / (1024 * 1024)} MB")
                return
            }
            
            // Проверяем, не было ли изображение уже обработано
            if (ImageTrackingUtil.isImageProcessed(applicationContext, uri)) {
                Timber.d("BackgroundMonitoringService: изображение уже обработано: $uri")
                return
            }
            
            // Получаем текущее качество сжатия
            val quality = getCompressionQuality()
            
            // Создаем уникальный тег для работы
            val workTag = "compression_${uri.lastPathSegment}_${System.currentTimeMillis()}"
            
            // Запускаем worker для сжатия изображения
            val compressionWorkRequest = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
                .setInputData(workDataOf(
                    Constants.WORK_INPUT_IMAGE_URI to uri.toString(),
                    "compression_quality" to quality
                ))
                .addTag(Constants.WORK_TAG_COMPRESSION)
                .addTag(workTag)
                .build()
            
            // Запускаем работу с уникальным именем для предотвращения дублирования
            workManager.beginUniqueWork(
                workTag,
                ExistingWorkPolicy.REPLACE,
                compressionWorkRequest
            ).enqueue()
            
            Timber.d("BackgroundMonitoringService: задача сжатия добавлена в очередь: ${compressionWorkRequest.id}")
            
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при обработке изображения: $uri")
            // Очищаем статус обработки в случае ошибки
            ImageTrackingUtil.clearProcessingStatus(uri)
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

                    if (!ImageTrackingUtil.isImageProcessed(applicationContext, contentUri)) {
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
} 
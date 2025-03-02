package com.compressphotofast.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.compressphotofast.R
import com.compressphotofast.ui.MainActivity
import com.compressphotofast.util.Constants
import com.compressphotofast.util.FileUtil
import com.compressphotofast.worker.ImageCompressionWorker
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject

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
        
        setupContentObserver()
        
        // Запускаем периодическое сканирование для обеспечения обработки всех изображений
        handler.post(scanRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Создание уведомления для foreground сервиса
        val notification = createNotification()
        startForeground(Constants.NOTIFICATION_ID_BACKGROUND_SERVICE, notification)
        
        Timber.d("Фоновый сервис запущен")
        
        // Выполняем первоначальное сканирование при запуске сервиса
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
                        // Проверяем, не обрабатывали ли мы уже этот URI
                        if (processedUris.contains(it.toString())) {
                            Timber.d("URI уже был обработан ранее: $uri")
                            return
                        }
                        
                        executorService.execute {
                            processNewImage(it)
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
    }
    
    /**
     * Периодическое сканирование галереи для поиска новых изображений
     */
    private fun scanForNewImages() {
        executorService.execute {
            Timber.d("Начало сканирования галереи для поиска новых изображений")
            
            // Запрашиваем последние изображения из MediaStore
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME
            )
            
            // Ищем фотографии, созданные за последние 5 минут
            val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
            val currentTimeInSeconds = System.currentTimeMillis() / 1000
            val fiveMinutesAgo = currentTimeInSeconds - (5 * 60) // 5 минут назад
            val selectionArgs = arrayOf(fiveMinutesAgo.toString())
            
            // Сортируем по времени создания (сначала новые)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                
                Timber.d("Найдено ${cursor.count} изображений за последние 5 минут")
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = if (nameColumn != -1) cursor.getString(nameColumn) else "unknown"
                    
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    
                    Timber.d("Сканирование: найдено изображение $name с URI: $contentUri")
                    
                    // Проверяем, не обрабатывали ли мы уже этот URI
                    if (!processedUris.contains(contentUri.toString())) {
                        processNewImage(contentUri)
                    } else {
                        Timber.d("Изображение уже было обработано ранее: $contentUri")
                    }
                }
            }
        }
    }
    
    /**
     * Обработка нового изображения
     */
    private fun processNewImage(uri: Uri) {
        Timber.d("Обнаружено новое изображение: $uri")
        
        // Добавляем URI в список обработанных
        synchronized(processedUris) {
            processedUris.add(uri.toString())
            // Если список слишком большой, удаляем старые записи
            if (processedUris.size > maxProcessedUrisSize) {
                processedUris.iterator().apply {
                    next()
                    remove()
                }
            }
        }
        
        // Запросим информацию о файле для лучшего логирования
        val fileInfo = getFileInfo(uri)
        Timber.d("Информация о файле: $fileInfo")
        
        // Проверяем, не было ли изображение уже сжато нашим приложением
        if (isImageProcessed(uri)) {
            Timber.d("Изображение уже обработано или создано нашим приложением: $uri")
            return
        }
        
        Timber.d("Изображение будет отправлено на сжатие: $uri")
        
        // Запуск worker для сжатия изображения
        val compressionWorkRequest = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
            .setInputData(workDataOf(Constants.WORK_INPUT_IMAGE_URI to uri.toString()))
            .addTag(Constants.WORK_TAG_COMPRESSION)
            .build()
        
        workManager.enqueue(compressionWorkRequest)
        Timber.d("Запрос на сжатие нового изображения добавлен в очередь: ${compressionWorkRequest.id}")
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
     * Проверка, было ли изображение уже обработано нашим приложением
     * или создано самим приложением
     */
    private fun isImageProcessed(uri: Uri): Boolean {
        // Проверяем по суффиксу в имени файла
        if (FileUtil.isAlreadyCompressed(uri)) {
            Timber.d("Файл содержит суффикс сжатия: $uri")
            return true
        }
        
        // Дополнительная проверка по директории (только точное совпадение)
        val path = uri.toString()
        if (path.contains("/${Constants.APP_DIRECTORY}/")) {
            Timber.d("Файл находится в директории приложения: $uri")
            return true
        }
        
        // Проверяем размер файла - если он слишком маленький, возможно, это уже сжатое изображение
        val cursor = contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_ADDED),
            null,
            null,
            null
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                // Проверка по времени создания - только очень свежие файлы (2 секунды)
                val dateAddedIndex = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                if (dateAddedIndex != -1) {
                    val dateAdded = it.getLong(dateAddedIndex)
                    val currentTime = System.currentTimeMillis() / 1000
                    
                    // Если файл был создан менее 2 секунд назад
                    if (currentTime - dateAdded < 2) {
                        Timber.d("Изображение создано менее 2 секунд назад, скорее всего это результат нашего сжатия: $uri")
                        return true
                    }
                }
            }
        }
        
        // Файл прошел все проверки и должен быть обработан
        return false
    }
} 
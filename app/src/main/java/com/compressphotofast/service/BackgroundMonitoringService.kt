package com.compressphotofast.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
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
    
    override fun onCreate() {
        super.onCreate()
        
        setupContentObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Создание уведомления для foreground сервиса
        val notification = createNotification()
        startForeground(Constants.NOTIFICATION_ID_BACKGROUND_SERVICE, notification)
        
        Timber.d("Фоновый сервис запущен")
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(contentObserver)
        executorService.shutdown()
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
                    // Проверяем, что это новое изображение
                    if (it.toString().contains("media") && it.toString().contains("image")) {
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
    }
    
    /**
     * Обработка нового изображения
     */
    private fun processNewImage(uri: Uri) {
        Timber.d("Обнаружено новое изображение: $uri")
        
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
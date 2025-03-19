package com.compressphotofast.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.compressphotofast.R
import com.compressphotofast.service.BackgroundMonitoringService
import com.compressphotofast.service.ImageDetectionJobService
import com.compressphotofast.util.Constants
import com.compressphotofast.util.FileUtil
import com.compressphotofast.util.ImageProcessingChecker
import com.compressphotofast.util.ImageProcessingUtil
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.SettingsManager
import com.compressphotofast.util.StatsTracker
import com.compressphotofast.worker.ImageCompressionWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import timber.log.Timber
import javax.inject.Inject
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Модель представления для главного экрана
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sharedPreferences: SharedPreferences,
    private val workManager: WorkManager,
    private val settingsManager: SettingsManager
) : ViewModel() {

    // LiveData для URI выбранного изображения
    private val _selectedImageUri = MutableLiveData<Uri?>()
    val selectedImageUri: LiveData<Uri?> = _selectedImageUri

    // LiveData для статуса загрузки
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading
    
    // LiveData для результата сжатия
    private val _compressionResult = MutableLiveData<CompressionResult?>()
    val compressionResult: LiveData<CompressionResult?> = _compressionResult
    
    // LiveData для уровня сжатия
    private val _compressionQuality = MutableLiveData<Int>()
    val compressionQuality: LiveData<Int> = _compressionQuality
    
    // LiveData для отслеживания прогресса обработки нескольких изображений
    private val _multipleImagesProgress = MutableLiveData<MultipleImagesProgress>()
    val multipleImagesProgress: LiveData<MultipleImagesProgress> = _multipleImagesProgress
    
    // Job для отслеживания текущей обработки
    private var processingJob: kotlinx.coroutines.Job? = null
    
    init {
        // Загрузить сохраненный уровень сжатия
        _compressionQuality.value = getCompressionQuality()
    }

    /**
     * Установка URI выбранного изображения
     */
    fun setSelectedImageUri(uri: Uri) {
        _selectedImageUri.value = uri
    }

    /**
     * Сжатие выбранного изображения
     */
    fun compressSelectedImage() {
        val uri = selectedImageUri.value ?: return
        
        _isLoading.value = true
        
        // Запуск worker для сжатия изображения
        val compressionWorkRequest = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
            .setInputData(workDataOf(
                Constants.WORK_INPUT_IMAGE_URI to uri.toString(),
                "compression_quality" to getCompressionQuality()
            ))
            .build()
        
        workManager.enqueue(compressionWorkRequest)
        
        // Наблюдение за статусом работы
        workManager.getWorkInfoByIdLiveData(compressionWorkRequest.id)
            .observeForever { workInfo ->
                if (workInfo != null && workInfo.state.isFinished) {
                    _isLoading.postValue(false)
                    
                    val success = workInfo.outputData.getBoolean("success", false)
                    val errorMessage = workInfo.outputData.getString("error_message")
                    
                    // Снимаем регистрацию URI после обработки
                    uri.let { 
                        Timber.d("compressSelectedImage: снимаем регистрацию URI после обработки: $it")
                    }
                    
                    // Показываем корректный результат
                    _compressionResult.postValue(
                        CompressionResult(
                            success = success,
                            errorMessage = if (!success) errorMessage else null,
                            totalImages = 1,
                            successfulImages = if (success) 1 else 0,
                            failedImages = if (success) 0 else 1,
                            allSuccessful = success
                        )
                    )
                    
                    // Логируем реальный результат сжатия для отладки
                    Timber.d("Реальный результат сжатия: ${if (success) "успешно" else "с ошибкой: $errorMessage"}")
                    Timber.d("Показываем пользователю: успешное сжатие")
                }
            }
    }

    /**
     * Сжатие списка изображений
     */
    fun compressMultipleImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        
        processingJob = viewModelScope.launch(Dispatchers.Main) {
            _isLoading.value = true
            // Сбрасываем предыдущий результат
            _compressionResult.value = null
            
            // Инициализируем прогресс
            _multipleImagesProgress.value = MultipleImagesProgress(
                total = uris.size,
                processed = 0,
                successful = 0,
                failed = 0,
                skipped = 0
            )
            
            // Создаем отдельный массив для отслеживания рабочих заданий
            val workRequests = mutableListOf<WorkRequest>()
            val alreadyOptimizedUris = mutableListOf<Uri>()
            
            // Предварительно проверяем, какие изображения уже оптимизированы
            for (uri in uris) {
                val isAlreadyOptimized = withContext(Dispatchers.IO) {
                    ImageProcessingChecker.isAlreadyProcessed(context, uri)
                }
                
                if (isAlreadyOptimized) {
                    // Отмечаем, что изображение уже оптимизировано
                    alreadyOptimizedUris.add(uri)
                    
                    // Отправляем уведомление о том, что изображение уже оптимизировано
                    val alreadyOptimizedIntent = Intent(Constants.ACTION_IMAGE_ALREADY_OPTIMIZED)
                    context.sendBroadcast(alreadyOptimizedIntent)
                    
                    // Логируем информацию
                    Timber.d("Изображение уже оптимизировано: $uri")
                } else {
                    // Создаем рабочее задание для неоптимизированного изображения
                    val workRequest = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
                    .setInputData(workDataOf(
                        Constants.WORK_INPUT_IMAGE_URI to uri.toString(),
                        "compression_quality" to getCompressionQuality()
                    ))
                    .build()
                    workRequests.add(workRequest)
                }
            }
            
            // Обновляем состояние для уже оптимизированных изображений
            if (alreadyOptimizedUris.isNotEmpty()) {
                val currentProgress = _multipleImagesProgress.value ?: MultipleImagesProgress()
                val newProgress = currentProgress.copy(
                    processed = currentProgress.processed + alreadyOptimizedUris.size,
                    skipped = currentProgress.skipped + alreadyOptimizedUris.size
                )
                _multipleImagesProgress.value = newProgress
            }
            
            // Если все изображения уже оптимизированы, завершаем здесь
            if (workRequests.isEmpty()) {
                val finalProgress = _multipleImagesProgress.value?.copy() ?: MultipleImagesProgress()
                _multipleImagesProgress.value = finalProgress.copy(processed = finalProgress.total)
                _isLoading.value = false
                
                // Показываем результат
                _compressionResult.value = CompressionResult(
                    success = true,
                    errorMessage = null,
                    totalImages = uris.size,
                    successfulImages = 0,
                    failedImages = 0,
                    skippedImages = uris.size,
                    allSuccessful = true
                )
                
                Timber.d("Завершена обработка всех изображений (${uris.size}/${uris.size})")
                Timber.d("Реальный результат: success=true, allSuccessful=true, totalImages=${uris.size}, successfulImages=0, skippedImages=${uris.size}, failedImages=0")
                return@launch
            }
            
            // Показываем уведомление о начале обработки нескольких изображений
            if (workRequests.size > 1) {
                showBatchProcessingStartedNotification(workRequests.size)
            }
            
            // Создаем карту для отслеживания связей между заданиями и URI
            val requestToUriMap = workRequests.zip(uris.filter { uri -> !alreadyOptimizedUris.contains(uri) }).toMap()
            
            // Отправляем запросы в WorkManager
            for (request in workRequests) {
                workManager.enqueue(request)
                
                // Получаем URI, связанный с этим запросом
                val uri = requestToUriMap[request]
                
                // Устанавливаем наблюдателя за каждым заданием
                workManager.getWorkInfoByIdLiveData(request.id)
                    .observeForever(object : androidx.lifecycle.Observer<androidx.work.WorkInfo> {
                        override fun onChanged(value: androidx.work.WorkInfo) {
                            if (value.state.isFinished) {
                                // Удаляем наблюдателя, так как задание завершено
                                workManager.getWorkInfoByIdLiveData(request.id).removeObserver(this)
                                
                                val success = value.outputData.getBoolean("success", false)
                                val skipped = value.outputData.getBoolean("skipped", false)
                                
                                // Снимаем регистрацию URI после обработки
                                uri?.let { processedUri -> 
                                    Timber.d("compressMultipleImages: снимаем регистрацию URI после обработки: $processedUri")
                                }
                                
                                // Обновляем прогресс в зависимости от результата
                                viewModelScope.launch(Dispatchers.Main) {
                                    val currentProgress = _multipleImagesProgress.value ?: MultipleImagesProgress()
                                    val newProgress = currentProgress.copy(
                                        processed = currentProgress.processed + 1,
                                        successful = if (success) currentProgress.successful + 1 else currentProgress.successful,
                                        failed = if (!success && !skipped) currentProgress.failed + 1 else currentProgress.failed,
                                        skipped = if (skipped) currentProgress.skipped + 1 else currentProgress.skipped
                                    )
                                    _multipleImagesProgress.value = newProgress
                                    
                                    // Обновляем уведомление о прогрессе
                                    if (uris.size > 1) {
                                        updateBatchProcessingNotification(newProgress)
                                    }
                                    
                                    // Показываем результат только когда все изображения обработаны
                                    if (newProgress.processed >= newProgress.total) {
                                        _isLoading.value = false
                                        
                                        // Показываем уведомление о завершении обработки
                                        if (uris.size > 1) {
                                            showBatchProcessingCompletedNotification(newProgress)
                                        }
                                        
                                        // Показываем результат только если это не было частью автоматической обработки
                                        if (newProgress.total <= 10) {
                                            _compressionResult.value = CompressionResult(
                                                success = newProgress.failed == 0,
                                                errorMessage = if (newProgress.failed > 0) "Не удалось обработать ${newProgress.failed} изображений" else null,
                                                totalImages = newProgress.total,
                                                successfulImages = newProgress.successful,
                                                failedImages = newProgress.failed,
                                                skippedImages = newProgress.skipped,
                                                allSuccessful = newProgress.failed == 0
                                            )
                                        }
                                        
                                        Timber.d("Обработка завершена: всего=${newProgress.total}, успешно=${newProgress.successful}, пропущено=${newProgress.skipped}, ошибок=${newProgress.failed}")
                                    }
                                }
                            }
                        }
                    })
            }
            
            // Логируем общее количество запущенных задач
            Timber.d("Запущена обработка ${uris.size} изображений")
        }
    }

    /**
     * Проверка, включено ли автоматическое сжатие
     */
    fun isAutoCompressionEnabled(): Boolean {
        return settingsManager.isAutoCompressionEnabled()
    }

    /**
     * Установка статуса автоматического сжатия
     */
    fun setAutoCompression(enabled: Boolean) {
        settingsManager.setAutoCompression(enabled)
        
        if (enabled) {
            // Запускаем проверку пропущенных изображений при включении
            viewModelScope.launch {
                processUncompressedImages()
            }
        } else {
            // Останавливаем фоновый сервис при выключении
            val intent = Intent(context, BackgroundMonitoringService::class.java)
            intent.action = Constants.ACTION_STOP_SERVICE
            ContextCompat.startForegroundService(context, intent)
        }
        
        Timber.d("Автоматическое сжатие: ${if (enabled) "включено" else "выключено"}")
    }
    
    /**
     * Получение текущего уровня сжатия
     */
    fun getCompressionQuality(): Int {
        return settingsManager.getCompressionQuality()
    }
    
    /**
     * Установка уровня сжатия
     */
    fun setCompressionQuality(quality: Int) {
        settingsManager.setCompressionQuality(quality)
        _compressionQuality.value = quality
    }
    
    /**
     * Получение уровня сжатия по предустановке (низкий, средний, высокий)
     */
    fun setCompressionPreset(preset: CompressionPreset) {
        settingsManager.setCompressionPreset(preset)
        _compressionQuality.value = settingsManager.getCompressionQuality()
    }

    /**
     * Запуск фонового сервиса
     */
    suspend fun startBackgroundService() {
        if (isAutoCompressionEnabled()) {
            ImageDetectionJobService.scheduleJob(context)
            Timber.d("JobService запланирован")
        }
    }

    /**
     * Обработка пропущенных изображений
     */
    suspend fun processUncompressedImages() = withContext(Dispatchers.IO) {
        try {
            // Получаем все изображения, созданные за последние 24 часа
            val currentTime = System.currentTimeMillis()
            val oneDayAgo = currentTime - (24 * 60 * 60 * 1000) // 24 часа в миллисекундах
            
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            
            // Ищем изображения, добавленные за последние 24 часа
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val selectionArgs = arrayOf((oneDayAgo / 1000).toString()) // DATE_ADDED хранится в секундах
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            
            val uncompressedImages = mutableListOf<Uri>()
            
            // Сначала собираем все сжатые версии файлов
            val compressedFileNames = mutableSetOf<String>()
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    if (displayName.contains("_compressed") || 
                        displayName.contains("_сжатое") || 
                        displayName.contains("_small")) {
                        // Сохраняем оригинальное имя файла (без маркера сжатия)
                        val originalName = displayName.replace("_compressed", "")
                            .replace("_сжатое", "")
                            .replace("_small", "")
                        compressedFileNames.add(originalName)
                    }
                }
            }
            
            // Теперь ищем неотсжатые изображения
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH))
                    } else {
                        ""
                    }
                    
                    // Пропускаем файлы, которые:
                    // 1. Уже имеют маркер сжатия
                    // 2. Находятся в нашей директории CompressPhotoFast
                    // 3. Уже имеют сжатую версию
                    if (!displayName.contains("_compressed") && 
                        !displayName.contains("_сжатое") && 
                        !displayName.contains("_small") &&
                        !relativePath.contains("CompressPhotoFast") &&
                        !compressedFileNames.contains(displayName)) {
                        
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        uncompressedImages.add(contentUri)
                    }
                }
            }
            
            // Обрабатываем найденные изображения в главном потоке
            withContext(Dispatchers.Main) {
                if (uncompressedImages.isNotEmpty()) {
                    Timber.d("Найдено ${uncompressedImages.size} необработанных изображений")
                    compressMultipleImages(uncompressedImages)
                } else {
                    Timber.d("Необработанных изображений не найдено")
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при обработке пропущенных изображений")
        }
    }

    /**
     * Проверка, включен ли режим замены оригинальных файлов
     */
    fun isSaveModeReplace(): Boolean {
        return settingsManager.isSaveModeReplace()
    }
    
    /**
     * Установка режима сохранения
     * @param replace true - заменять оригинальные файлы, false - сохранять в отдельной папке
     */
    fun setSaveMode(replace: Boolean) {
        settingsManager.setSaveMode(replace)
    }

    /**
     * Показывает уведомление о начале обработки нескольких изображений
     */
    private fun showBatchProcessingStartedNotification(count: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Создание канала уведомлений для Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                context.getString(R.string.notification_channel_id),
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = context.getString(R.string.notification_channel_description)
            notificationManager.createNotificationChannel(channel)
        }
        
        // Создаем Intent для открытия приложения при нажатии на уведомление
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Создаем уведомление о начале обработки
        val notification = NotificationCompat.Builder(context, context.getString(R.string.notification_channel_id))
            .setContentTitle(context.getString(R.string.notification_batch_processing_title))
            .setContentText(context.getString(R.string.notification_batch_processing_start, count))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setProgress(count, 0, false)
            .build()
        
        notificationManager.notify(Constants.NOTIFICATION_ID_BATCH_PROCESSING, notification)
    }
    
    /**
     * Обновляет уведомление о прогрессе обработки нескольких изображений
     */
    private fun updateBatchProcessingNotification(progress: MultipleImagesProgress) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Создаем Intent для остановки обработки
        val stopIntent = Intent(context, MainActivity::class.java).apply {
            action = Constants.ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getActivity(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Создаем уведомление о прогрессе обработки
        val notification = NotificationCompat.Builder(context, context.getString(R.string.notification_channel_id))
            .setContentTitle(context.getString(R.string.notification_batch_processing_title))
            .setContentText(context.getString(
                R.string.notification_batch_processing_progress,
                progress.processed,
                progress.total
            ))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_launcher_foreground,
                context.getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .setProgress(progress.total, progress.processed, false)
            .build()
        
        notificationManager.notify(Constants.NOTIFICATION_ID_BATCH_PROCESSING, notification)
    }
    
    /**
     * Показывает уведомление о завершении обработки нескольких изображений
     */
    private fun showBatchProcessingCompletedNotification(progress: MultipleImagesProgress) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Создаем Intent для открытия приложения
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Создаем текст уведомления с учетом пропущенных файлов
        val contentText = if (progress.skipped > 0) {
            context.getString(
                R.string.notification_batch_processing_completed_with_skipped,
                progress.successful,
                progress.skipped,
                progress.failed,
                progress.total
            )
        } else {
            context.getString(
                R.string.notification_batch_processing_completed,
                progress.successful,
                progress.failed,
                progress.total
            )
        }
        
        // Создаем уведомление о завершении обработки
        val notification = NotificationCompat.Builder(context, context.getString(R.string.notification_channel_id))
            .setContentTitle(context.getString(R.string.notification_batch_processing_completed_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(Constants.NOTIFICATION_ID_BATCH_PROCESSING, notification)
    }

    /**
     * Останавливает текущую обработку изображений
     */
    fun stopBatchProcessing() {
        processingJob?.cancel()
        processingJob = null
        
        // Удаляем уведомление о прогрессе
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(Constants.NOTIFICATION_ID_BATCH_PROCESSING)
        
        // Показываем уведомление об остановке
        showToast(context.getString(R.string.batch_processing_stopped))
    }

    /**
     * Показывает Toast с дедупликацией
     */
    fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        // Делегируем вызов централизованному методу
        NotificationUtil.showToast(context, message, duration)
    }
}

/**
 * Результат операции сжатия
 */
data class CompressionResult(
    val success: Boolean,
    val errorMessage: String? = null,
    val totalImages: Int,
    val successfulImages: Int,
    val failedImages: Int,
    val skippedImages: Int = 0,
    val allSuccessful: Boolean
)

/**
 * Предустановки уровня сжатия
 */
enum class CompressionPreset {
    LOW, MEDIUM, HIGH
}

/**
 * Прогресс обработки нескольких изображений
 */
data class MultipleImagesProgress(
    val total: Int = 0,
    val processed: Int = 0,
    val successful: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0
) {
    val isComplete: Boolean get() = processed >= total
    val percentComplete: Int get() = if (total > 0) (processed * 100) / total else 0
} 
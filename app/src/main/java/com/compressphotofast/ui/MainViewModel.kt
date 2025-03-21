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
import com.compressphotofast.util.SequentialImageProcessor

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
    
    // Объект для последовательной обработки изображений
    private val sequentialImageProcessor = SequentialImageProcessor(context)
    
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
        
        // Начинаем отслеживать прогресс обработки изображений
        viewModelScope.launch {
            // Отслеживаем состояние загрузки
            sequentialImageProcessor.isLoading.collect { isLoading ->
                _isLoading.value = isLoading
            }
        }
        
        viewModelScope.launch {
            // Отслеживаем прогресс обработки
            sequentialImageProcessor.progress.collect { progress ->
                _multipleImagesProgress.value = progress
            }
        }
        
        viewModelScope.launch {
            // Отслеживаем результат обработки
            sequentialImageProcessor.result.collect { result ->
                _compressionResult.value = result
            }
        }
        
        // Запускаем обработку изображений последовательно
        sequentialImageProcessor.processImages(
            uris = uris,
            compressionQuality = getCompressionQuality(),
            showResultNotification = true
        )
        
        // Логируем начало обработки
        Timber.d("Запущена последовательная обработка ${uris.size} изображений")
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
     * Останавливает текущую обработку изображений
     */
    fun stopBatchProcessing() {
        sequentialImageProcessor.cancelProcessing()
    }

    /**
     * Показывает Toast с дедупликацией
     */
    fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        NotificationUtil.showToast(context, message, duration)
    }

    /**
     * Проверяет, можно ли обработать изображение
     */
    suspend fun canProcessImage(context: Context, uri: Uri, quality: Int? = null): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                // Проверяем, существует ли файл
                val exists = context.contentResolver.query(uri, null, null, null, null)?.use { cursor -> 
                    cursor.count > 0 
                } ?: false
                
                if (!exists) {
                    Timber.e("Файл не существует или недоступен: $uri")
                    return@withContext false
                }
                
                // Проверяем, не является ли файл временным
                val isPending = isPendingFile(context, uri)
                if (isPending) {
                    Timber.e("Файл в процессе создания (IS_PENDING=1): $uri")
                    return@withContext false
                }
                
                // Проверяем поддерживаемые типы файлов
                val mimeType = FileUtil.getMimeType(context, uri)
                if (mimeType?.startsWith("image/") != true || (!mimeType.contains("jpeg") && !mimeType.contains("png"))) {
                    Timber.e("Неподдерживаемый тип файла: $mimeType")
                    return@withContext false
                }
                
                // Проверяем, не был ли уже обработан файл
                val isAlreadyProcessed = withContext(Dispatchers.IO) {
                    ImageProcessingChecker.isAlreadyProcessed(context, uri)
                }
                
                if (isAlreadyProcessed) {
                    Timber.d("Файл уже был обработан ранее: $uri")
                    return@withContext false
                }
                
                // Если дошли до этой точки, то можно обрабатывать изображение
                return@withContext true
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке возможности обработки изображения: $uri")
            false
        }
    }
    
    /**
     * Проверяет, является ли файл временным (IS_PENDING=1)
     */
    private suspend fun isPendingFile(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING)
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val isPendingColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                    return@withContext cursor.getInt(isPendingColumn) == 1
                }
            }
            return@withContext false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса IS_PENDING файла")
            return@withContext false
        }
    }

    // Очистка ресурсов при уничтожении ViewModel
    override fun onCleared() {
        super.onCleared()
        sequentialImageProcessor.destroy()
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
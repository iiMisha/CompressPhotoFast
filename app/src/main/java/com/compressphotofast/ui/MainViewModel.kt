package com.compressphotofast.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import com.compressphotofast.service.BackgroundMonitoringService
import com.compressphotofast.service.ImageDetectionJobService
import com.compressphotofast.util.Constants
import com.compressphotofast.util.ImageProcessingChecker
import com.compressphotofast.util.SettingsManager
import com.compressphotofast.worker.ImageCompressionWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import com.compressphotofast.util.SequentialImageProcessor
import com.compressphotofast.util.NotificationUtil
import javax.inject.Inject
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.UriUtil
import com.compressphotofast.util.FileOperationsUtil
import com.compressphotofast.util.UriProcessingTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.IntentSender
import androidx.activity.result.IntentSenderRequest
import com.compressphotofast.util.Event
import com.compressphotofast.util.EventObserver


/**
 * Модель представления для главного экрана
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sharedPreferences: SharedPreferences,
    private val workManager: WorkManager,
    private val settingsManager: SettingsManager,
    private val uriProcessingTracker: UriProcessingTracker
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
    
    // StateFlow для управления видимостью предупреждения
    private val _isWarningExpanded = MutableStateFlow(false)
    val isWarningExpanded = _isWarningExpanded.asStateFlow()

    // Map для хранения WorkInfo observers
    private val workObservers = mutableMapOf<UUID, Observer<WorkInfo?>>()

    // StateFlows для счетчиков пропущенных и уже оптимизированных изображений
    private val _skippedCount = MutableStateFlow(0)
    private val _alreadyOptimizedCount = MutableStateFlow(0)

    private val _permissionRequest = MutableLiveData<Event<IntentSenderRequest>>()
    val permissionRequest: LiveData<Event<IntentSenderRequest>> = _permissionRequest


    // Объект для последовательной обработки изображений
    private val sequentialImageProcessor = SequentialImageProcessor(context, uriProcessingTracker)
    
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
                Constants.WORK_COMPRESSION_QUALITY to getCompressionQuality()
            ))
            .build()

        workManager.enqueue(compressionWorkRequest)

        // Создаем и сохраняем observer
        val observer = Observer<WorkInfo?> { workInfo ->
            if (workInfo != null && workInfo.state.isFinished) {
                _isLoading.postValue(false)

                val success = workInfo.outputData.getBoolean("success", false)
                val errorMessage = workInfo.outputData.getString("error_message")

                // Показываем результат
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

                // Логируем результат сжатия для отладки
                LogUtil.processDebug("Результат сжатия: ${if (success) "успешно" else "с ошибкой: $errorMessage"}")
            }
        }

        // Сохраняем observer в Map
        workObservers[compressionWorkRequest.id] = observer
        workManager.getWorkInfoByIdLiveData(compressionWorkRequest.id)
            .observeForever(observer)
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
        viewModelScope.launch {
            sequentialImageProcessor.processImages(
                uris = uris,
                compressionQuality = getCompressionQuality(),
                listener = null  // Null так как не используем данные колбэки
            )
        }
        
        // Логируем начало обработки
        LogUtil.processInfo("Запущена последовательная обработка ${uris.size} изображений")
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
        
        LogUtil.processDebug("Автоматическое сжатие: ${if (enabled) "включено" else "выключено"}")
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
            LogUtil.processDebug("JobService запланирован")
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
            
            // Ищем неотсжатые изображения
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    
                    // Проверяем, требует ли изображение обработки с использованием центрального класса проверки
                    val shouldProcess = ImageProcessingChecker.shouldProcessImage(context, contentUri, false)
                    
                    if (shouldProcess) {
                        uncompressedImages.add(contentUri)
                    }
                }
            }
            
            // Обрабатываем найденные изображения в главном потоке
            withContext(Dispatchers.Main) {
                if (uncompressedImages.isNotEmpty()) {
                    LogUtil.processDebug("Найдено ${uncompressedImages.size} необработанных изображений")
                    compressMultipleImages(uncompressedImages)
                } else {
                    LogUtil.processDebug("Необработанных изображений не найдено")
                }
            }
            
        } catch (e: Exception) {
            LogUtil.errorWithException("Ошибка при обработке пропущенных изображений", e)
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
     * Проверяет, нужно ли игнорировать изображения из мессенджеров.
     */
    fun shouldIgnoreMessengerPhotos(): Boolean {
        return settingsManager.shouldIgnoreMessengerPhotos()
    }

    /**
     * Устанавливает настройку игнорирования изображений из мессенджеров.
     */
    fun setIgnoreMessengerPhotos(ignore: Boolean) {
        settingsManager.setIgnoreMessengerPhotos(ignore)
    }

    /**
     * Останавливает текущую обработку изображений
     */
    fun stopBatchProcessing() {
        viewModelScope.launch {
            sequentialImageProcessor.cancelProcessing()
        }
    }

    /**
     * Переключает состояние видимости предупреждения
     */
    fun toggleWarningExpanded() {
        _isWarningExpanded.value = !_isWarningExpanded.value
    }

    /**
     * Увеличивает счетчик пропущенных изображений
     */
    fun incrementSkippedCount() {
        _skippedCount.value++
    }

    /**
     * Увеличивает счетчик уже оптимизированных изображений
     */
    fun incrementAlreadyOptimizedCount() {
        _alreadyOptimizedCount.value++
    }

    /**
     * Сбрасывает счетчики пакетной обработки
     */
    fun resetBatchCounters() {
        _skippedCount.value = 0
        _alreadyOptimizedCount.value = 0
        LogUtil.processDebug("Счетчики пакетной обработки сброшены")
    }

    /**
     * Показывает итоговое сообщение о пакетной обработке
     */
    fun showBatchSummary() {
        val skipped = _skippedCount.value
        val optimized = _alreadyOptimizedCount.value

        if (skipped > 0 || optimized > 0) {
            val messageParts = mutableListOf<String>()
            if (skipped > 0) {
                messageParts.add("Пропущено: $skipped")
            }
            if (optimized > 0) {
                messageParts.add("Уже оптимизировано: $optimized")
            }
            val message = messageParts.joinToString(separator = ". ")
            showToast(message)
        }

        // Сбрасываем счетчики после показа сообщения
        resetBatchCounters()
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
    suspend fun canProcessImage(context: Context, uri: Uri): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                // Используем общую логику проверки из ImageProcessingChecker
                val shouldProcess = ImageProcessingChecker.shouldProcessImage(context, uri, false)
                
                // Возвращаем результат проверки
                return@withContext shouldProcess
            }
        } catch (e: Exception) {
            LogUtil.errorWithException("Ошибка при проверке возможности обработки изображения: $uri", e)
            false
        }
    }

    fun requestPermission(intentSender: IntentSender) {
        val request = IntentSenderRequest.Builder(intentSender).build()
        _permissionRequest.value = Event(request)
    }

    // Очистка ресурсов при уничтожении ViewModel
    override fun onCleared() {
        super.onCleared()

        // Удаляем всех WorkInfo observers
        workObservers.forEach { (workId, observer) ->
            try {
                workManager.getWorkInfoByIdLiveData(workId).removeObserver(observer)
            } catch (e: Exception) {
                LogUtil.errorWithException("MAIN_VIEWMODEL_ON_CLEARED: Failed to remove observer for $workId", e)
            }
        }
        workObservers.clear()

        // OPTIMIZED: запускаем cleanup в отдельной корутине без блокировки
        viewModelScope.launch(Dispatchers.IO) {
            sequentialImageProcessor.destroy()
        }
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
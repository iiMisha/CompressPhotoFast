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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import com.compressphotofast.service.BackgroundMonitoringService
import com.compressphotofast.util.Constants
import com.compressphotofast.util.ImageProcessingChecker
import com.compressphotofast.util.ImageProcessingUtil
import com.compressphotofast.util.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.compressphotofast.util.CompressionBatchTracker
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
    private val uriProcessingTracker: UriProcessingTracker,
    private val compressionBatchTracker: CompressionBatchTracker
) : ViewModel() {

    // LiveData для URI выбранного изображения
    private val _selectedImageUri = MutableLiveData<Uri?>()
    val selectedImageUri: LiveData<Uri?> = _selectedImageUri

    // LiveData для уровня сжатия
    private val _compressionQuality = MutableLiveData<Int>()
    val compressionQuality: LiveData<Int> = _compressionQuality

    // StateFlow для управления видимостью предупреждения
    private val _isWarningExpanded = MutableStateFlow(false)
    val isWarningExpanded = _isWarningExpanded.asStateFlow()

    // Map для хранения WorkInfo observers
    private val workObservers = mutableMapOf<UUID, Observer<WorkInfo?>>()

    private val _permissionRequest = MutableLiveData<Event<IntentSenderRequest>>()
    val permissionRequest: LiveData<Event<IntentSenderRequest>> = _permissionRequest


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
     * Сжатие списка изображений
     *
     * Консолидировано с ручным путём (handleIntent): создаётся batchId, каждое
     * изображение ставится в очередь через ImageProcessingUtil.handleImage
     * (WorkManager). Результаты группируются CompressionBatchTracker в единый
     * Toast/уведомление по достижении expectedCount.
     */
    fun compressMultipleImages(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val batchId = compressionBatchTracker.createIntentBatch(uris.size)
        LogUtil.processInfo("Запущена пакетная обработка ${uris.size} изображений (batch=$batchId)")

        viewModelScope.launch(Dispatchers.IO) {
            var enqueued = 0
            for (uri in uris) {
                try {
                    val result = ImageProcessingUtil.handleImage(
                        context, uri, forceProcess = true, batchId = batchId
                    )
                    if (result.first && result.second) enqueued++
                } catch (e: Exception) {
                    LogUtil.error(uri, "Автосжатие", "Ошибка запуска обработки", e)
                }
            }
            // Если ни одно изображение не запущено (все пропущены/ошибки) — финализируем батч
            if (enqueued == 0) {
                compressionBatchTracker.finalizeBatch(batchId)
            }
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
     * Обработка пропущенных изображений
     */
    suspend fun processUncompressedImages() = withContext(Dispatchers.IO) {
        try {
            // Получаем все изображения, созданные за историю (по умолчанию 48 часов)
            val currentTime = System.currentTimeMillis()
            val historyAgo = currentTime - Constants.HISTORY_SCAN_WINDOW_MILLIS
            
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            
            // Ищем изображения, добавленные за историю
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val selectionArgs = arrayOf((historyAgo / 1000).toString()) // DATE_ADDED хранится в секундах
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
        // Отменяем очередь воркеров сжатия (имя работы совпадает с handleImage)
        workManager.cancelUniqueWork("sequential_image_compression")
    }

    /**
     * Переключает состояние видимости предупреждения
     */
    fun toggleWarningExpanded() {
        _isWarningExpanded.value = !_isWarningExpanded.value
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
    }
}

/**
 * Предустановки уровня сжатия
 */
enum class CompressionPreset {
    LOW, MEDIUM, HIGH
}
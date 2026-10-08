package com.compressphotofast.ui

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.compressphotofast.service.MonitoringController
import com.compressphotofast.util.Constants
import com.compressphotofast.domain.ImageProcessingChecker
import com.compressphotofast.domain.CompressionEnqueueResult
import com.compressphotofast.domain.CompressionOrigin
import com.compressphotofast.domain.CompressionWorkScheduler
import com.compressphotofast.BuildConfig
import com.compressphotofast.data.FileOperationsUtil
import com.compressphotofast.data.MediaItemSnapshot
import com.compressphotofast.data.CompressionPreset
import com.compressphotofast.data.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.compressphotofast.domain.CompressionBatchTracker
import com.compressphotofast.domain.CompressionEvents
import javax.inject.Inject
import com.compressphotofast.util.LogUtil
import com.compressphotofast.data.UriProcessingTracker
import com.compressphotofast.data.UriUtil
import com.compressphotofast.data.MediaStoreUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


/**
 * Модель представления для главного экрана
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager,
    private val uriProcessingTracker: UriProcessingTracker,
    private val compressionBatchTracker: CompressionBatchTracker,
    private val compressionWorkScheduler: CompressionWorkScheduler,
    private val imageProcessingChecker: ImageProcessingChecker,
    compressionEvents: CompressionEvents
) : ViewModel() {

    /** События воркера: результат одиночного сжатия и запросы подтверждения удаления. */
    val compressionEvents: SharedFlow<CompressionEvents.Event> = compressionEvents.events

    /** Прогресс ручных батчей (null — активных нет). */
    val batchProgress: StateFlow<CompressionBatchTracker.BatchProgress?> = compressionBatchTracker.progress

    // LiveData для уровня сжатия
    private val _compressionQuality = MutableLiveData<Int>()
    val compressionQuality: LiveData<Int> = _compressionQuality

    // LiveData для максимального разрешения
    private val _maxResolution = MutableLiveData<Int>()
    val maxResolution: LiveData<Int> = _maxResolution

    // StateFlow для управления видимостью предупреждения
    private val _isWarningExpanded = MutableStateFlow(false)
    val isWarningExpanded = _isWarningExpanded.asStateFlow()


    init {
        // Загрузить сохраненный уровень сжатия
        _compressionQuality.value = getCompressionQuality()
        _maxResolution.value = getMaxResolution()
    }

    /**
     * Сжатие списка изображений
     *
     * Каждое изображение ставится в очередь через CompressionWorkScheduler
     * (WorkManager) в общем батче. Результаты группируются CompressionBatchTracker
     * в единый Toast/уведомление по достижении expectedCount.
     */
    fun compressMultipleImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { enqueueManualBatch(uris) }
    }

    /**
     * Ручное сжатие изображений из Share-интента или Photo Picker.
     * Отбрасывает несуществующие и не-image URI, показывает первое изображение в UI.
     * @return число URI, принятых в durable очередь (0 — все пропущены или невалидны)
     */
    suspend fun compressSharedImages(uris: List<Uri>): Int {
        val validUris = uris.filter { isValidSharedImage(it) }
        if (validUris.isEmpty()) {
            LogUtil.processWarning("compressSharedImages: Нет валидных URI для обработки")
            return 0
        }
        return withContext(Dispatchers.IO) {
            // Запрос ради debug-лога: в release R8 вырезает только вызов LogUtil, не query
            if (BuildConfig.DEBUG) validUris.forEach { logFileDetails(it) }
            enqueueManualBatch(validUris)
        }
    }

    suspend fun listImageFolders(): List<Pair<String, Int>> = MediaStoreUtil.queryImageFolders(context)

    /**
     * Ручное сжатие всех изображений папки (с подпапками) без лимита числа файлов.
     * @param relativePath RELATIVE_PATH с завершающим слешем
     * @return число принятых в очередь URI
     */
    suspend fun compressFolder(relativePath: String): Int = withContext(Dispatchers.IO) {
        val uris = MediaStoreUtil.queryImagesInFolder(context, relativePath)
        LogUtil.processInfo("Папка '$relativePath': найдено ${uris.size} изображений")
        enqueueManualBatch(uris)
    }

    private suspend fun isValidSharedImage(uri: Uri): Boolean {
        // Одна проверка: повтор попадал бы в кэш isUriExistsSuspend (TTL 10 с) и лишь
        // добавлял паузу на каждый URI; незавершённую запись отсекает воркер (pending)
        if (!UriUtil.isUriExistsSuspend(context, uri)) {
            LogUtil.error(uri, "Intent обработка", "Файл не существует")
            uriProcessingTracker.markUriUnavailable(uri)
            return false
        }
        val mimeType = try {
            withContext(Dispatchers.IO) { UriUtil.getMimeType(context, uri) }
        } catch (e: Exception) {
            LogUtil.error(uri, "Intent обработка", "Ошибка получения MIME типа: ${e.message}")
            null
        }
        if (mimeType?.startsWith("image/") != true) {
            LogUtil.processWarning("Intent обработка: Файл не является изображением ($uri): $mimeType")
            return false
        }
        return true
    }

    /**
     * Ставит URI в очередь одним ручным батчем; пустой батч сразу финализируется.
     * @return число URI, принятых в durable очередь
     */
    private suspend fun enqueueManualBatch(uris: List<Uri>): Int {
        val batchId = compressionBatchTracker.createIntentBatch(uris.size)
        LogUtil.processInfo("Запущена пакетная обработка ${uris.size} изображений (batch=$batchId)")
        var enqueued = 0
        for (uri in uris) {
            try {
                val result = compressionWorkScheduler.enqueue(
                    uri, forceProcess = true, batchId = batchId, origin = CompressionOrigin.MANUAL
                )
                if (result == CompressionEnqueueResult.DURABLY_ACCEPTED) {
                    enqueued++
                } else {
                    LogUtil.processDebug("URI $uri пропущен: $result")
                }
            } catch (e: Exception) {
                LogUtil.error(uri, "Ручное сжатие", "Ошибка запуска обработки", e)
            }
        }
        // Не принятые в очередь URI результата не пришлют; при 0 батч финализируется сразу
        compressionBatchTracker.setExpectedCount(batchId, enqueued)
        return enqueued
    }

    private fun logFileDetails(uri: Uri) {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getColumnIndex(MediaStore.Images.Media._ID).let { if (it != -1) cursor.getLong(it) else -1 }
                    val name = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME).let { if (it != -1) cursor.getString(it) else "unknown" }
                    val size = cursor.getColumnIndex(MediaStore.Images.Media.SIZE).let { if (it != -1) cursor.getLong(it) else -1 }
                    val date = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED).let { if (it != -1) cursor.getLong(it) else -1 }
                    val mime = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE).let { if (it != -1) cursor.getString(it) else "unknown" }
                    LogUtil.processDebug("Файл: ID=$id, Имя=$name, Размер=$size, Дата=$date, MIME=$mime, URI=$uri")
                }
            }
        } catch (e: Exception) {
            LogUtil.errorWithMessageAndException("FILE_INFO", "Ошибка при получении информации о файле", e)
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
            // Останавливаем фоновый сервис и резервный Job при выключении.
            // Единая точка остановки: отключает настройку, отменяет Job, останавливает службу.
            MonitoringController.stopMonitoring(context, disableAutoCompression = false)
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
     * Получение максимального разрешения (0 — исходное)
     */
    fun getMaxResolution(): Int {
        return settingsManager.getMaxResolution()
    }

    /**
     * Установка максимального разрешения (0 — исходное)
     */
    fun setMaxResolution(maxDimension: Int) {
        settingsManager.setMaxResolution(maxDimension)
        _maxResolution.value = maxDimension
    }

    /**
     * Обработка пропущенных изображений
     */
    suspend fun processUncompressedImages() = withContext(Dispatchers.IO) {
        try {
            // Получаем все изображения, созданные за историю (по умолчанию 48 часов)
            val currentTime = System.currentTimeMillis()
            val historyAgo = currentTime - Constants.HISTORY_SCAN_WINDOW_MILLIS
            
            val projection = MediaItemSnapshot.PROJECTION
            
            // Ищем изображения, добавленные за историю
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val selectionArgs = arrayOf((historyAgo / 1000).toString()) // DATE_ADDED хранится в секундах
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            
            val uncompressedImages = mutableListOf<Uri>()
            // Сжатые копии ищутся одним запросом (только separate-режим), как в скане галереи
            val appDirectoryNames = if (!settingsManager.isSaveModeReplace()) {
                FileOperationsUtil.queryAppDirectoryFileNames(context)
            } else null

            // Ищем неотсжатые изображения
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val columns = MediaItemSnapshot.Columns(cursor)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    
                    // Метаданные уже в курсоре: проверка не делает запросов к MediaStore
                    val snapshot = MediaItemSnapshot.fromCursor(cursor, contentUri, columns)
                    val shouldProcess = imageProcessingChecker.shouldProcessImage(
                        contentUri, false, snapshot, appDirectoryNames
                    )
                    
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
     * Останавливает текущую обработку изображений
     */
    fun stopBatchProcessing() {
        // Durable per-URI works и legacy chain не очищаются при старте UI:
        // это предотвращает потерю внешних share URI и гонку с manual enqueue.
        LogUtil.processDebug("Остановка UI не отменяет durable очередь обработки")
    }

    /**
     * Переключает состояние видимости предупреждения
     */
    fun toggleWarningExpanded() {
        _isWarningExpanded.value = !_isWarningExpanded.value
    }

    // Очистка ресурсов при уничтожении ViewModel
    override fun onCleared() {
        super.onCleared()
    }
}

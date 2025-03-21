package com.compressphotofast.util

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.widget.Toast
import com.compressphotofast.util.Constants
import com.compressphotofast.ui.CompressionResult
import com.compressphotofast.ui.MultipleImagesProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// Заменяем прямой импорт Timber на LogUtil
// import timber.log.Timber
import com.compressphotofast.util.LogUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Класс для последовательной обработки изображений без использования WorkManager
 * Это решает проблемы с race condition при параллельной обработке
 */
class SequentialImageProcessor(private val context: Context) {
    
    // StateFlow для отслеживания прогресса обработки нескольких изображений
    private val _progress = MutableStateFlow(MultipleImagesProgress())
    val progress: StateFlow<MultipleImagesProgress> = _progress.asStateFlow()
    
    // StateFlow для хранения результата обработки
    private val _result = MutableStateFlow<CompressionResult?>(null)
    val result: StateFlow<CompressionResult?> = _result.asStateFlow()
    
    // StateFlow для отслеживания состояния загрузки
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // Корутинный скоуп для запуска и отмены обработки
    private val processingScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Флаг для отмены обработки
    private var isCancelled = false
    
    /**
     * Сжатие списка изображений последовательно
     * @param uris Список URI изображений для обработки
     * @param compressionQuality Качество сжатия (от 0 до 100)
     * @param showResultNotification Показывать ли уведомление с результатом обработки
     */
    fun processImages(
        uris: List<Uri>, 
        compressionQuality: Int = Constants.COMPRESSION_QUALITY_MEDIUM,
        showResultNotification: Boolean = true
    ) {
        if (uris.isEmpty()) return
        
        // Сбрасываем флаг отмены
        isCancelled = false
        
        processingScope.launch {
            _isLoading.value = true
            // Сбрасываем предыдущий результат
            _result.value = null
            
            // Инициализируем прогресс
            _progress.value = MultipleImagesProgress(
                total = uris.size,
                processed = 0,
                successful = 0,
                failed = 0,
                skipped = 0
            )
            
            // Уведомляем о начале обработки, если изображений больше одного
            if (uris.size > 1 && showResultNotification) {
                NotificationUtil.showBatchProcessingStartedNotification(context, uris.size)
            }
            
            // Перебираем изображения и обрабатываем их последовательно
            withContext(Dispatchers.IO) {
                for ((index, uri) in uris.withIndex()) {
                    if (isCancelled) {
                        LogUtil.processInfo("Обработка отменена пользователем")
                        break
                    }
                    
                    try {
                        LogUtil.processInfo("Начало обработки изображения ${index+1}/${uris.size}")
                        LogUtil.uriInfo(uri, "Начало обработки")
                        
                        // 1. Проверяем, нужно ли обрабатывать изображение
                        val isAlreadyProcessed = ImageProcessingChecker.isAlreadyProcessed(context, uri)
                        if (isAlreadyProcessed) {
                            LogUtil.skipImage(uri, "Уже обработано ранее")
                            updateProgress(skipped = true)
                            continue
                        }
                        
                        // 2. Получаем имя файла
                        val fileName = FileUtil.getFileNameFromUri(context, uri)
                        if (fileName.isNullOrEmpty()) {
                            LogUtil.error(uri, "Получение имени файла", "Не удалось получить имя файла")
                            updateProgress(success = false)
                            continue
                        }
                        LogUtil.uriInfo(uri, "Имя файла: $fileName")
                        
                        // 3. Получаем путь для сохранения
                        val directory = if (FileUtil.isSaveModeReplace(context)) {
                            // Если включен режим замены, сохраняем в той же директории
                            FileUtil.getDirectoryFromUri(context, uri) ?: Constants.APP_DIRECTORY
                        } else {
                            // Иначе сохраняем в директории приложения
                            Constants.APP_DIRECTORY
                        }
                        LogUtil.uriInfo(uri, "Директория для сохранения: $directory")
                        
                        // 4. Проверяем тип URI и определяем стратегию обработки
                        val isMediaDocumentsUri = uri.authority == "com.android.providers.media.documents"
                        
                        // 5. Получаем поток с сжатым изображением
                        val compressedStream = compressImage(uri, compressionQuality)
                        
                        if (compressedStream == null) {
                            LogUtil.error(uri, "Сжатие", "Не удалось получить поток с сжатым изображением")
                            updateProgress(success = false)
                            continue
                        }
                        
                        // 6. Переименовываем оригинальный файл (если не MediaDocuments URI)
                        var backupUri: Uri? = null
                        if (!isMediaDocumentsUri) {
                            // Переименовываем оригинальный файл
                            backupUri = FileUtil.renameOriginalFile(context, uri)
                            if (backupUri == null) {
                                LogUtil.error(uri, "Переименование", "Не удалось переименовать оригинальный файл")
                                compressedStream.close()
                                updateProgress(success = false)
                                continue
                            }
                            LogUtil.fileOperation(uri, "Переименование", "Оригинал → ${backupUri}")
                        } else {
                            LogUtil.uriInfo(uri, "URI относится к MediaDocumentsProvider, пропускаем переименование")
                            // Используем исходный URI в качестве backupUri
                            backupUri = uri
                        }
                        
                        // 7. Сохраняем сжатое изображение
                        val savedUri = FileUtil.saveCompressedImageFromStream(
                            context,
                            compressedStream,
                            fileName,
                            directory,
                            backupUri,
                            compressionQuality
                        )
                        
                        // Закрываем поток после использования
                        compressedStream.close()
                        
                        if (savedUri == null) {
                            LogUtil.error(uri, "Сохранение", "Не удалось сохранить сжатый файл")
                            updateProgress(success = false)
                            continue
                        }
                        
                        LogUtil.fileOperation(uri, "Сохранение", "Сжатый файл сохранен: $savedUri")
                        
                        // 8. Удаляем переименованный оригинальный файл (если не MediaDocuments URI)
                        if (!isMediaDocumentsUri && FileUtil.isSaveModeReplace(context)) {
                            try {
                                backupUri?.let { renamedUri ->
                                    val deleteResult = FileUtil.deleteFile(context, renamedUri)
                                    
                                    if (deleteResult is Boolean && deleteResult) {
                                        LogUtil.fileOperation(renamedUri, "Удаление", "Переименованный оригинал успешно удален")
                                    } else if (deleteResult is IntentSender) {
                                        LogUtil.fileOperation(renamedUri, "Удаление", "Требуется разрешение пользователя")
                                        // Здесь можно добавить обработку запроса на удаление
                                    } else {
                                        LogUtil.error(renamedUri, "Удаление", "Не удалось удалить переименованный оригинал")
                                    }
                                }
                            } catch (e: Exception) {
                                LogUtil.error(backupUri, "Удаление", e)
                            }
                        }
                        
                        // 9. Обновляем прогресс
                        updateProgress(success = true)
                        
                    } catch (e: Exception) {
                        LogUtil.error(uri, "Обработка", e)
                        updateProgress(success = false)
                    }
                    
                    // Небольшая задержка между обработкой файлов, чтобы система могла обновить MediaStore
                    delay(100)
                }
            }
            
            // Завершаем обработку
            _isLoading.value = false
            
            // Показываем уведомление о завершении обработки, если изображений больше одного
            if (uris.size > 1 && showResultNotification) {
                NotificationUtil.showBatchProcessingCompletedNotification(context, _progress.value)
            }
            
            // Показываем результат
            _result.value = CompressionResult(
                success = _progress.value.failed == 0,
                errorMessage = if (_progress.value.failed > 0) 
                    "Не удалось обработать ${_progress.value.failed} изображений" else null,
                totalImages = _progress.value.total,
                successfulImages = _progress.value.successful,
                failedImages = _progress.value.failed,
                skippedImages = _progress.value.skipped,
                allSuccessful = _progress.value.failed == 0
            )
            
            LogUtil.processInfo("Обработка завершена: всего=${_progress.value.total}, успешно=${_progress.value.successful}, пропущено=${_progress.value.skipped}, ошибок=${_progress.value.failed}")
        }
    }
    
    /**
     * Обновляет прогресс обработки
     */
    private suspend fun updateProgress(success: Boolean = false, skipped: Boolean = false) {
        withContext(Dispatchers.Main) {
            val currentProgress = _progress.value
            val newProgress = currentProgress.copy(
                processed = currentProgress.processed + 1,
                successful = if (success) currentProgress.successful + 1 else currentProgress.successful,
                failed = if (!success && !skipped) currentProgress.failed + 1 else currentProgress.failed,
                skipped = if (skipped) currentProgress.skipped + 1 else currentProgress.skipped
            )
            _progress.value = newProgress
            
            // Обновляем уведомление о прогрессе, если изображений больше одного
            if (currentProgress.total > 1) {
                NotificationUtil.updateBatchProcessingNotification(context, newProgress)
            }
        }
    }
    
    /**
     * Сжимает изображение и возвращает поток с данными
     */
    private suspend fun compressImage(uri: Uri, quality: Int): InputStream? = withContext(Dispatchers.IO) {
        try {
            // Используем CompressionTestUtil для сжатия изображения в памяти
            val byteArrayOutputStream = CompressionTestUtil.getCompressedImageStream(
                context,
                uri,
                quality
            )
            
            // Преобразуем ByteArrayOutputStream в InputStream
            return@withContext byteArrayOutputStream?.let { 
                ByteArrayInputStream(it.toByteArray())
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Сжатие", e)
            return@withContext null
        }
    }
    
    /**
     * Вспомогательный метод для добавления задержки между операциями
     */
    private suspend fun delay(millis: Long) = withContext(Dispatchers.IO) {
        try {
            kotlinx.coroutines.delay(millis)
        } catch (e: Exception) {
            // Игнорируем ошибки
        }
    }
    
    /**
     * Отменяет текущую обработку изображений
     */
    fun cancelProcessing() {
        isCancelled = true
        
        // Удаляем уведомление о прогрессе
        if (_progress.value.total > 1) {
            NotificationUtil.cancelBatchProcessingNotification(context)
        }
        
        // Показываем уведомление об остановке
        NotificationUtil.showToast(context, "Обработка изображений остановлена")
        
        _isLoading.value = false
    }
    
    /**
     * Освобождает ресурсы при уничтожении объекта
     */
    fun destroy() {
        cancelProcessing()
        processingScope.cancel()
    }
} 
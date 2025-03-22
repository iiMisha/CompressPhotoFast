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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Интерфейс для отслеживания прогресса сжатия
 */
interface CompressionProgressListener {
    fun onBatchStarted(totalImages: Int)
    fun onBatchProgress(progress: Int, processed: Int, total: Int)
    fun onBatchCompleted(results: Map<Uri, Uri?>)
    fun onBatchCancelled()
    fun onCompressionStarted(uri: Uri)
    fun onCompressionProgress(uri: Uri, progress: Int)
    fun onCompressionCompleted(uri: Uri, savedUri: Uri, originalSize: Long, compressedSize: Long)
    fun onCompressionSkipped(uri: Uri, reason: String)
    fun onCompressionFailed(uri: Uri, error: String)
    fun onBatchFailed(error: String)
}

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
    private val processingCancelled = AtomicBoolean(false)
    
    /**
     * Обрабатывает список изображений последовательно
     * 
     * @param uris Список URI изображений для обработки
     * @param compressionQuality Качество сжатия (0-100)
     * @param listener Слушатель событий прогресса
     * @return Карта, где ключ - исходный URI, значение - URI сжатого изображения или null при ошибке
     */
    suspend fun processImages(
        uris: List<Uri>,
        compressionQuality: Int,
        listener: CompressionProgressListener?
    ): Map<Uri, Uri?> = withContext(Dispatchers.IO) {
        try {
            if (processingCancelled.get()) {
                return@withContext emptyMap<Uri, Uri?>()
            }
            
            val results = mutableMapOf<Uri, Uri?>()
            val totalImages = uris.size
            
            listener?.onBatchStarted(totalImages)
            
            for ((index, uri) in uris.withIndex()) {
                if (processingCancelled.get()) {
                    break
                }
                
                try {
                    // Обновляем прогресс по общему процессу
                    val progress = (index.toFloat() / totalImages) * 100
                    listener?.onBatchProgress(progress.toInt(), index, totalImages)
                    
                    // Обрабатываем отдельное изображение с использованием нового метода
                    val savedUri = processImage(uri, compressionQuality, listener)
                    results[uri] = savedUri
                    
                } catch (e: Exception) {
                    LogUtil.error(uri, "Обработка", "Ошибка при обработке изображения", e)
                    listener?.onCompressionFailed(uri, e.message ?: "Неизвестная ошибка")
                    results[uri] = null
                }
            }
            
            if (!processingCancelled.get()) {
                listener?.onBatchCompleted(results)
            } else {
                listener?.onBatchCancelled()
            }
            
            return@withContext results
        } catch (e: Exception) {
            LogUtil.error(null, "Обработка", "Ошибка при пакетной обработке изображений", e)
            listener?.onBatchFailed(e.message ?: "Неизвестная ошибка")
            return@withContext emptyMap<Uri, Uri?>()
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
        processingCancelled.set(true)
        
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

    /**
     * Обрабатывает одно изображение
     * @param uri URI изображения для обработки
     * @param compressionQuality Качество сжатия (0-100)
     * @param listener Слушатель для обратной связи о прогрессе
     * @return URI сохраненного сжатого изображения
     */
    private suspend fun processImage(
        uri: Uri,
        compressionQuality: Int,
        listener: CompressionProgressListener?
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // Проверяем, что изображение еще не сжато
            if (ExifUtil.isCompressedImage(context, uri)) {
                LogUtil.skipImage(uri, "Изображение уже сжато")
                listener?.onCompressionSkipped(uri, "Изображение уже сжато")
                return@withContext uri
            }
            
            // Проверяем размер исходного файла
            val sourceSize = FileUtil.getFileSize(context, uri)
            
            // Если размер слишком маленький или слишком большой, пропускаем
            if (!FileUtil.isFileSizeValid(sourceSize)) {
                LogUtil.skipImage(uri, "Размер файла невалидный: $sourceSize")
                listener?.onCompressionSkipped(uri, "Размер файла невалидный")
                return@withContext uri
            }
            
            // Уведомляем о старте сжатия
            listener?.onCompressionStarted(uri)
            
            // 1. Загружаем EXIF данные в память перед любыми операциями с файлом
            val exifDataMemory = ExifUtil.readExifDataToMemory(context, uri)
            LogUtil.uriInfo(uri, "Загружены EXIF данные в память: ${exifDataMemory.size} тегов")
            
            // Получаем имя файла
            val fileName = FileUtil.getFileNameFromUri(context, uri)
            if (fileName.isNullOrEmpty()) {
                LogUtil.error(uri, "Имя файла", "Не удалось получить имя файла")
                throw IllegalStateException("Не удалось получить имя файла")
            }
            
            // Получаем информацию о режиме сохранения
            val isSaveModeReplace = FileUtil.isSaveModeReplace(context)
            
            // Определяем директорию для сохранения
            val directory = if (isSaveModeReplace) {
                FileUtil.getDirectoryFromUri(context, uri)
            } else {
                Constants.APP_DIRECTORY
            }
            
            // Проверяем, является ли URI из MediaDocumentProvider
            val isMediaDocumentsUri = uri.authority == "com.android.providers.media.documents"
            
            // Переименовываем оригинальный файл перед сохранением сжатой версии, если нужно
            var backupUri = uri
            
            if (!isMediaDocumentsUri) {
                backupUri = FileUtil.renameOriginalFileIfNeeded(context, uri) ?: uri
            }
            
            // Сжимаем изображение
            val compressedImageStream = CompressionTestUtil.getCompressedImageStream(
                context,
                uri,
                compressionQuality
            )
            
            if (compressedImageStream == null) {
                LogUtil.error(uri, "Сжатие", "Ошибка при сжатии изображения")
                throw IllegalStateException("Ошибка при сжатии изображения")
            }
            
            // Сохраняем сжатое изображение
            val savedUri = FileUtil.saveCompressedImageFromStream(
                context,
                ByteArrayInputStream(compressedImageStream.toByteArray()),
                fileName,
                directory ?: Constants.APP_DIRECTORY,
                backupUri,
                compressionQuality,
                exifDataMemory
            )
            
            // Закрываем поток сжатого изображения
            compressedImageStream.close()
            
            if (savedUri == null) {
                LogUtil.error(uri, "Сохранение", "Не удалось сохранить сжатое изображение")
                throw IllegalStateException("Не удалось сохранить сжатое изображение")
            }
            
            LogUtil.fileOperation(uri, "Сохранение", "Сжатый файл успешно сохранен: $savedUri")
            
            // Если режим замены включен и URI был переименован, удаляем переименованный оригинальный файл
            try {
                if (isSaveModeReplace && backupUri != uri) {
                    val deleteResult = FileUtil.deleteFile(context, backupUri)
                    
                    when (deleteResult) {
                        is Boolean -> {
                            if (deleteResult) {
                                LogUtil.fileOperation(uri, "Удаление", "Переименованный оригинальный файл успешно удален")
                            } else {
                                LogUtil.error(uri, "Удаление", "Не удалось удалить переименованный оригинальный файл")
                            }
                        }
                        is android.content.IntentSender -> {
                            LogUtil.fileOperation(uri, "Удаление", "Требуется разрешение пользователя на удаление переименованного оригинала")
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtil.error(uri, "Удаление", "Ошибка при удалении переименованного оригинального файла", e)
            }
            
            // Уведомляем о завершении сжатия
            val compressedSize = FileUtil.getFileSize(context, savedUri)
            listener?.onCompressionCompleted(uri, savedUri, sourceSize, compressedSize)
            
            return@withContext savedUri
        } catch (e: Exception) {
            LogUtil.error(uri, "Обработка", "Ошибка при обработке изображения", e)
            listener?.onCompressionFailed(uri, e.message ?: "Неизвестная ошибка")
            return@withContext null
        }
    }

    /**
     * Сбрасывает флаг отмены обработки
     */
    fun resetCancellation() {
        processingCancelled.set(false)
    }
} 
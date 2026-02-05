package com.compressphotofast.util

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.widget.Toast
import com.compressphotofast.util.Constants
import com.compressphotofast.ui.CompressionResult
import com.compressphotofast.ui.MultipleImagesProgress
import com.compressphotofast.util.NotificationUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import com.compressphotofast.util.LogUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import com.compressphotofast.util.ImageCompressionUtil
import com.compressphotofast.util.UriUtil
import com.compressphotofast.util.FileOperationsUtil
import kotlinx.coroutines.runBlocking

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
 * Интерфейс для прогресса обработки изображений
 */
interface ProgressListener {
    fun onProgress(progress: MultipleImagesProgress)
}

/**
 * Класс для последовательной обработки изображений без использования WorkManager
 * Это решает проблемы с race condition при параллельной обработке
 */
class SequentialImageProcessor(
    private val context: Context,
    private val uriProcessingTracker: UriProcessingTracker
) {
    
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
    // Используем Default dispatcher для CPU-интенсивных операций сжатия изображений
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Флаг для отмены обработки
    private val processingCancelled = AtomicBoolean(false)
    
    // Слушатель прогресса
    private var progressListener: ProgressListener? = null
    
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
                    val savedUri = processImage(uri, compressionQuality, index, totalImages, listener)
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
     * Обновляет прогресс обработки в StateFlow
     * OPTIMIZED: StateFlow thread-safe, не нужно withContext(Dispatchers.Main)
     */
    private fun updateProgressState(success: Boolean = false, skipped: Boolean = false) {
        val currentProgress = _progress.value
        val newProgress = currentProgress.copy(
            processed = currentProgress.processed + 1,
            successful = if (success) currentProgress.successful + 1 else currentProgress.successful,
            failed = if (!success && !skipped) currentProgress.failed + 1 else currentProgress.failed,
            skipped = if (skipped) currentProgress.skipped + 1 else currentProgress.skipped
        )
        _progress.value = newProgress
    }
    
    /**
     * Отменяет текущую обработку изображений с ожиданием завершения
     */
    suspend fun cancelProcessing() {
        // Проверяем, была ли запущена обработка изображений
        val wasProcessing = !processingCancelled.get() && _isLoading.value

        processingCancelled.set(true)
        processingScope.cancel()

        // Ждём завершения обработки (макс 5 сек)
        if (wasProcessing) {
            try {
                withTimeout(5000) {
                    while (_isLoading.value) {
                        delay(100)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                LogUtil.processWarning("Таймаут ожидания завершения обработки")
            }
        }

        // Показываем уведомление об остановке только если действительно была запущена обработка
        if (wasProcessing) {
            NotificationUtil.showToast(context, "Обработка изображений остановлена")
        }

        _isLoading.value = false

        // Очищаем StateFlow
        _progress.value = MultipleImagesProgress()
        _result.value = null
    }
    
    /**
     * Сбрасывает состояние процессора для новой обработки
     */
    fun resetProcessing() {
        processingCancelled.set(false)
        _isLoading.value = false
        _progress.value = MultipleImagesProgress()
        _result.value = null
    }

    /**
     * Освобождает ресурсы при уничтожении объекта
     * OPTIMIZED: suspend функция без runBlocking для избежания блокировки вызывающего потока
     */
    suspend fun destroy() {
        cancelProcessing()
        processingScope.cancel()
    }

    /**
     * Обрабатывает одно изображение
     * @param uri URI изображения для обработки
     * @param compressionQuality Качество сжатия (0-100)
     * @param position Позиция изображения в списке
     * @param total Общее количество изображений
     * @param listener Слушатель для обратной связи о прогрессе
     * @return URI сохраненного сжатого изображения
     */
    private suspend fun processImage(
        uri: Uri,
        compressionQuality: Int,
        position: Int,
        total: Int,
        listener: CompressionProgressListener?
    ): Uri? = withContext(Dispatchers.IO) {
        // Проверка 1: в начале
        if (!isActive || processingCancelled.get()) {
            LogUtil.processDebug("processImage отменён (проверка 1): $uri")
            return@withContext null
        }

        uriProcessingTracker.addProcessingUriSafe(uri, "SequentialImageProcessor")
        try {
            // Проверка 2: после добавления
            if (!isActive || processingCancelled.get()) {
                LogUtil.processDebug("processImage отменён (проверка 2): $uri")
                return@withContext null
            }

            // Единая проверка существования файла
            val exists = UriUtil.isUriExistsSuspend(context, uri)
            if (!exists) {
                LogUtil.error(uri, "Пакетная обработка", "Файл недоступен")
                uriProcessingTracker.markUriUnavailable(uri)
                return@withContext null
            }

            // Продолжаем обработку без задержек
            if (processingCancelled.get()) {
                return@withContext null
            }

            val fileName = try {
                UriUtil.getFileNameFromUri(context, uri) ?: "Неизвестный файл"
            } catch (e: Exception) {
                LogUtil.error(uri, "Имя файла", "Ошибка получения имени файла: ${e.message}")
                "Неизвестный файл"
            }

            updateProgress(position, total, fileName)

            // Проверка перед сжатием
            if (!isActive || processingCancelled.get()) {
                LogUtil.processDebug("processImage отменён (проверка перед сжатием): $uri")
                return@withContext null
            }

            val result = try {
                ImageCompressionUtil.processAndSaveImage(context, uri, compressionQuality)
            } catch (e: kotlinx.coroutines.CancellationException) {
                LogUtil.processDebug("Сжатие отменено через CancellationException: $uri")
                throw e
            } catch (e: Exception) {
                LogUtil.error(uri, "Обработка", "Ошибка при сжатии", e)
                Triple(false, null, e.message ?: "Неизвестная ошибка")
            }

            if (!result.first) {
                LogUtil.error(uri, "Обработка", result.third)
                listener?.onCompressionFailed(uri, result.third)
                return@withContext null
            }

            if (result.second == null || result.second == uri) {
                LogUtil.skipImage(uri, result.third)
                listener?.onCompressionSkipped(uri, result.third)
                return@withContext null
            }

            val savedUri = result.second!!
            val originalSize = try {
                UriUtil.getFileSize(context, uri)
            } catch (e: java.io.FileNotFoundException) {
                LogUtil.error(uri, "Пакетная обработка", "Файл не найден при получении оригинального размера: ${e.message}")
                0L
            } catch (e: Exception) {
                LogUtil.error(uri, "Пакетная обработка", "Ошибка получения оригинального размера: ${e.message}")
                0L
            }

            val compressedSize = try {
                UriUtil.getFileSize(context, savedUri)
            } catch (e: java.io.FileNotFoundException) {
                LogUtil.error(savedUri, "Пакетная обработка", "Файл не найден при получении сжатого размера: ${e.message}")
                0L
            } catch (e: Exception) {
                LogUtil.error(savedUri, "Пакетная обработка", "Ошибка получения сжатого размера: ${e.message}")
                0L
            }

            val intent = Intent(Constants.ACTION_COMPRESSION_COMPLETED)
            intent.setPackage(context.packageName)
            intent.putExtra(Constants.EXTRA_FILE_NAME, fileName)
            intent.putExtra(Constants.EXTRA_ORIGINAL_SIZE, originalSize)
            intent.putExtra(Constants.EXTRA_COMPRESSED_SIZE, compressedSize)
            context.sendBroadcast(intent)

            listener?.onCompressionCompleted(uri, savedUri, originalSize, compressedSize)

            LogUtil.fileOperation(
                uri,
                "Сохранение",
                "Изображение успешно сжато: $fileName, ${originalSize / 1024}KB → ${compressedSize / 1024}KB"
            )
            return@withContext savedUri
        } catch (e: kotlinx.coroutines.CancellationException) {
            LogUtil.processDebug("processImage отменён через CancellationException: $uri")
            throw e
        } catch (e: Exception) {
            LogUtil.error(uri, "Обработка", "Ошибка при обработке изображения", e)
            listener?.onCompressionFailed(uri, e.message ?: "Неизвестная ошибка")
            return@withContext null
        } finally {
            uriProcessingTracker.removeProcessingUriSafe(uri)
        }
    }
    
    /**
     * Отправляет broadcast о прогрессе обработки и обновляет UI через listener
     */
    private fun updateProgress(current: Int, total: Int, fileName: String) {
        val progress = MultipleImagesProgress(total, current, 0, 0, 0)
        progressListener?.onProgress(progress)
        
        // Отправляем информацию через broadcast для слушателей
        val intent = Intent(Constants.ACTION_COMPRESSION_PROGRESS)
        intent.setPackage(context.packageName)
        intent.putExtra(Constants.EXTRA_PROGRESS, current)
        intent.putExtra(Constants.EXTRA_TOTAL, total)
        intent.putExtra(Constants.EXTRA_FILE_NAME, fileName)
        context.sendBroadcast(intent)
    }
}
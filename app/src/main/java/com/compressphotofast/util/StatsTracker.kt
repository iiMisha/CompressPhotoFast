package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.HashSet

/**
 * Утилитарный класс для отслеживания статистики и статуса сжатия изображений
 */
object StatsTracker {
    // Константы статусов сжатия
    const val COMPRESSION_STATUS_NONE = 0
    const val COMPRESSION_STATUS_PROCESSING = 1
    const val COMPRESSION_STATUS_COMPLETED = 2
    const val COMPRESSION_STATUS_FAILED = 3
    const val COMPRESSION_STATUS_SKIPPED = 4

    // Множество для отслеживания URI в процессе обработки
    private val processingUris = mutableSetOf<String>()
    
    // Реестр URI, которые обрабатываются через MainActivity (через Intent)
    private val urisBeingProcessedByMainActivity = Collections.synchronizedSet(HashSet<String>())

    /**
     * Начинает отслеживание URI
     */
    fun startTracking(uri: Uri) {
        processingUris.add(uri.toString())
        Timber.d("Начато отслеживание URI: $uri")
    }

    /**
     * Обновляет статус сжатия для указанного URI
     */
    fun updateStatus(context: Context, uri: Uri, status: Int) {
        try {
            // Если статус завершающий (COMPLETED или FAILED), убираем URI из отслеживаемых
            if (status == COMPRESSION_STATUS_COMPLETED || status == COMPRESSION_STATUS_FAILED || status == COMPRESSION_STATUS_SKIPPED) {
                processingUris.remove(uri.toString())
                Timber.d("URI удален из отслеживаемых: $uri")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при обновлении статуса для $uri")
        }
    }

    /**
     * Проверяет, было ли изображение обработано ранее
     * @param context контекст
     * @param uri URI изображения
     * @return true если изображение уже обработано, false в противном случае
     */
    suspend fun isImageProcessed(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверяем EXIF маркер с помощью ExifUtil
            val isCompressed = ExifUtil.isImageCompressed(context, uri)
            
            // Если найден маркер CompressPhotoFast_Compressed, проверяем наличие даты сжатия
            if (isCompressed) {
                val compressionDate = ExifUtil.getCompressionDateFromExif(context, uri)
                if (compressionDate != null) {
                    Timber.d("Изображение обработано (найден EXIF маркер с датой): $uri")
                } else {
                    Timber.d("Изображение обработано (найден EXIF маркер без даты): $uri")
                }
                return@withContext true
            }
            
            // Если маркер не найден, проверяем путь к файлу
            val path = FileUtil.getFilePathFromUri(context, uri)
            if (path?.contains("/${Constants.APP_DIRECTORY}/") == true || 
                (path?.contains("content://media/external/images/media") == true && path.contains(Constants.APP_DIRECTORY))) {
                Timber.d("Файл находится в директории приложения: $path")
                return@withContext true
            }
            
            // В остальных случаях считаем, что файл не обработан
            Timber.d("Файл не был обработан ранее: $uri")
            return@withContext false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса обработки файла: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Проверяет, нужно ли обрабатывать изображение с учетом размера файла
     * Делегирует вызов в ImageProcessingChecker для предотвращения дублирования логики
     */
    suspend fun shouldProcessImage(context: Context, uri: Uri): Boolean {
        return ImageProcessingChecker.shouldProcessImage(context, uri)
    }
    
    /**
     * Отмечает изображение как обработанное (добавляет EXIF маркер)
     */
    suspend fun markProcessed(context: Context, uri: Uri, quality: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext ExifUtil.markCompressedImage(context, uri, quality)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при маркировке изображения как обработанного: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Регистрирует URI, который обрабатывается через MainActivity
     * Метод для обратной совместимости
     */
    fun registerUriBeingProcessedByMainActivity(uri: Uri) {
        val uriString = uri.toString()
        urisBeingProcessedByMainActivity.add(uriString)
        Timber.d("Зарегистрирован URI для обработки через MainActivity: $uriString")
    }
    
    /**
     * Проверяет, обрабатывается ли URI через MainActivity
     * Метод для обратной совместимости
     */
    fun isUriBeingProcessedByMainActivity(uri: Uri): Boolean {
        val uriString = uri.toString()
        val isProcessing = urisBeingProcessedByMainActivity.contains(uriString)
        if (isProcessing) {
            Timber.d("URI обрабатывается через MainActivity: $uriString")
        }
        return isProcessing
    }
    
    /**
     * Снимает регистрацию URI, который больше не обрабатывается через MainActivity
     * Метод для обратной совместимости
     */
    fun unregisterUriBeingProcessedByMainActivity(uri: Uri) {
        val uriString = uri.toString()
        urisBeingProcessedByMainActivity.remove(uriString)
        Timber.d("Снята регистрация URI для обработки через MainActivity: $uriString")
    }
} 
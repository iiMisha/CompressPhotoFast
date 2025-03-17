package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.compressphotofast.service.BackgroundMonitoringService
import com.compressphotofast.worker.ImageCompressionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Утилитарный класс для логики обработки изображений
 * Предотвращает дублирование логики в разных классах
 */
object ImageProcessingUtil {

    /**
     * Проверяет, нужно ли обрабатывать изображение
     * Централизованная логика для всего приложения
     */
    suspend fun shouldProcessImage(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        // Делегируем проверку классу ImageProcessingChecker
        return@withContext ImageProcessingChecker.shouldProcessImage(context, uri)
    }
    
    /**
     * Запускает обработку изображения с использованием WorkManager
     */
    suspend fun processImage(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверяем, включено ли автоматическое сжатие
            val settingsManager = SettingsManager.getInstance(context)
            if (!settingsManager.isAutoCompressionEnabled()) {
                Timber.d("Автоматическое сжатие отключено, пропускаем обработку: $uri")
                return@withContext false
            }
            
            // Получаем качество сжатия из настроек
            val quality = settingsManager.getCompressionQuality()
            
            // Создаем уникальный тег для работы
            val fileName = FileUtil.getFileNameFromUri(context, uri)
            val workTag = "compress_${System.currentTimeMillis()}_$fileName"
            
            // Получаем размер исходного файла для логирования
            val originalSize = FileUtil.getFileSize(context, uri)
            
            // Создаем и запускаем работу по сжатию
            val compressionWorkRequest = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
                .setInputData(
                    workDataOf(
                        Constants.WORK_INPUT_IMAGE_URI to uri.toString(),
                        "compression_quality" to quality,
                        "original_size" to originalSize
                    )
                )
                .addTag(workTag)
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    workTag,
                    ExistingWorkPolicy.REPLACE,
                    compressionWorkRequest
                )
            
            Timber.d("Запущена работа по сжатию для $uri с тегом $workTag")
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при запуске обработки изображения: $uri")
            return@withContext false
        }
    }

    /**
     * Проверяет, содержатся ли в изображении EXIF-метаданные
     * @param context контекст
     * @param uri URI изображения
     * @return true если изображение содержит EXIF-метаданные, false в противном случае
     */
    suspend fun hasExifMetadata(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        return@withContext ExifUtil.hasBasicExifTags(context, uri)
    }
} 
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
        try {
            // Проверяем, существует ли URI
            val exists = context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Images.Media._ID), null, null, null)?.use {
                it.count > 0
            } ?: false
            
            if (!exists) {
                Timber.d("URI не существует: $uri")
                return@withContext false
            }
            
            // Проверяем, не обрабатывается ли уже это изображение
            if (BackgroundMonitoringService.isImageBeingProcessed(uri.toString())) {
                Timber.d("URI уже обрабатывается: $uri")
                return@withContext false
            }
            
            // Проверяем, не обрабатывается ли URI уже через MainActivity
            if (StatsTracker.isUriBeingProcessedByMainActivity(uri)) {
                Timber.d("URI уже обрабатывается через MainActivity: $uri")
                return@withContext false
            }
            
            // Проверяем размер файла
            val fileSize = FileUtil.getFileSize(context, uri)
            if (fileSize < Constants.MIN_FILE_SIZE || fileSize > Constants.MAX_FILE_SIZE) {
                Timber.d("Файл имеет недопустимый размер ($fileSize байт): $uri")
                return@withContext false
            }
            
            // Для файлов больше 1.5 МБ всегда возвращаем true независимо от EXIF маркера
            if (fileSize > Constants.TEST_COMPRESSION_THRESHOLD_SIZE) {
                Timber.d("Изображение по URI $uri больше 1.5 МБ (${fileSize / (1024 * 1024)}МБ), требуется обработка")
                return@withContext true
            }
            
            // Для файлов меньше 1.5 МБ проверяем EXIF маркер
            val isCompressed = FileUtil.isCompressedByExif(context, uri)
            if (isCompressed) {
                Timber.d("Изображение по URI $uri уже сжато (обнаружен EXIF маркер)")
                return@withContext false
            }
            
            // Проверяем путь к файлу
            val path = FileUtil.getFilePathFromUri(context, uri)
            if (!path.isNullOrEmpty() && path.contains("/${Constants.APP_DIRECTORY}/")) {
                Timber.d("Файл находится в директории приложения: $path")
                return@withContext false
            }
            
            // Если все проверки прошли успешно - нужно обрабатывать
            Timber.d("Изображение требует обработки: $uri")
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке изображения: $uri")
            return@withContext false
        }
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
            
            // Отмечаем URI как обрабатываемый
            BackgroundMonitoringService.addProcessingUri(uri.toString())
            Timber.d("URI добавлен в список обрабатываемых: $uri")
            
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
            // Удаляем URI из списка обрабатываемых в случае ошибки
            BackgroundMonitoringService.removeProcessingUri(uri.toString())
            return@withContext false
        }
    }
} 
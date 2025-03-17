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
     * Централизованный метод для обработки любого изображения в приложении
     * Используется и для share, и для галереи, и для других источников
     * 
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param forceProcess Принудительная обработка, даже если автосжатие отключено
     * @return Triple<Boolean, Boolean, String>: 
     *         - first: успешно ли запущена обработка
     *         - second: было ли изображение добавлено в очередь
     *         - third: сообщение о результате
     */
    suspend fun handleImage(context: Context, uri: Uri, forceProcess: Boolean = false): Triple<Boolean, Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // Сначала проверяем, требуется ли обработка
            val shouldProcess = shouldProcessImage(context, uri, forceProcess)
            if (!shouldProcess) {
                return@withContext Triple(true, false, "Изображение уже оптимизировано")
            }
            
            // Проверяем, нужно ли принудительно обрабатывать
            val settingsManager = SettingsManager.getInstance(context)
            val isAutoEnabled = settingsManager.isAutoCompressionEnabled()
            
            // Если автосжатие отключено и нет флага принудительной обработки, 
            // возвращаем сообщение о том, что нужна ручная обработка
            if (!isAutoEnabled && !forceProcess) {
                return@withContext Triple(true, false, "Требуется ручное сжатие")
            }
            
            // Получаем качество сжатия из настроек
            val quality = settingsManager.getCompressionQuality()
            
            // Создаем уникальный тег для работы
            val fileName = FileUtil.getFileNameFromUri(context, uri)
            val workTag = "compress_${System.currentTimeMillis()}_$fileName"
            
            // Получаем размер исходного файла для логирования
            val originalSize = FileUtil.getFileSize(context, uri)
            
            // Добавляем URI в список обрабатываемых
            UriProcessingTracker.addProcessingUri(uri.toString())
            Timber.d("URI добавлен в список обрабатываемых: $uri (всего ${UriProcessingTracker.getProcessingCount()})")
            
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
            return@withContext Triple(true, true, "Сжатие запущено")
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при обработке изображения: $uri")
            return@withContext Triple(false, false, "Ошибка: ${e.message}")
        }
    }

    /**
     * Проверяет, нужно ли обрабатывать изображение
     * Централизованная логика для всего приложения
     */
    suspend fun shouldProcessImage(context: Context, uri: Uri, forceProcess: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        // Делегируем проверку классу ImageProcessingChecker
        return@withContext ImageProcessingChecker.shouldProcessImage(context, uri, forceProcess)
    }
    
    /**
     * Запускает обработку изображения с использованием WorkManager
     * @deprecated Используйте handleImage вместо этого метода
     */
    suspend fun processImage(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Используем новый централизованный метод
            val result = handleImage(context, uri)
            return@withContext result.second // Возвращаем, было ли изображение добавлено в очередь
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
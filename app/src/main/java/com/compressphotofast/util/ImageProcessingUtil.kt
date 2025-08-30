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
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.UriUtil

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
     * @param batchId ID батча для группировки результатов (опционально)
     * @return Triple<Boolean, Boolean, String>: 
     *         - first: успешно ли запущена обработка
     *         - second: было ли изображение добавлено в очередь
     *         - third: сообщение о результате
     */
    suspend fun handleImage(context: Context, uri: Uri, forceProcess: Boolean = false, batchId: String? = null): Triple<Boolean, Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // Добавляем URI в список обрабатываемых
            UriProcessingTracker.addProcessingUri(uri)
            
            try {
                // Сначала проверяем, требуется ли обработка - используем централизованную логику
                val shouldProcess = ImageProcessingChecker.shouldProcessImage(context, uri, forceProcess)
                
                if (!shouldProcess) {
                    // Удаляем URI из списка обрабатываемых
                    UriProcessingTracker.removeProcessingUri(uri)
                    return@withContext Triple(true, false, "Изображение уже оптимизировано")
                }
                
                // Проверяем, нужно ли принудительно обрабатывать
                val settingsManager = SettingsManager.getInstance(context)
                val isAutoEnabled = settingsManager.isAutoCompressionEnabled()
                
                // Если автосжатие отключено и нет флага принудительной обработки, 
                // возвращаем сообщение о том, что нужна ручная обработка
                if (!isAutoEnabled && !forceProcess) {
                    // Удаляем URI из списка обрабатываемых
                    UriProcessingTracker.removeProcessingUri(uri)
                    return@withContext Triple(true, false, "Требуется ручное сжатие")
                }
                
                // Получаем качество сжатия из настроек
                val quality = settingsManager.getCompressionQuality()
                
                // Создаем уникальный тег для работы
                val fileName = UriUtil.getFileNameFromUri(context, uri)
                val workTag = "compress_${System.currentTimeMillis()}_$fileName"
                
                // Получаем размер исходного файла для логирования
                val originalSize = UriUtil.getFileSize(context, uri)
                
                // Создаем данные для работы
                val inputData = mutableMapOf<String, Any?>(
                    Constants.WORK_INPUT_IMAGE_URI to uri.toString(),
                    Constants.WORK_COMPRESSION_QUALITY to quality,
                    "original_size" to originalSize
                )
                
                // Добавляем batch ID если он предоставлен, или создаем автобатч
                val finalBatchId = batchId ?: if (forceProcess) {
                    // Для Intent-сжатий batch ID уже передан, для автосжатий создаем его
                    null
                } else {
                    // Для автоматического сжатия создаем или используем существующий автобатч
                    CompressionBatchTracker.getOrCreateAutoBatch(context)
                }
                
                if (finalBatchId != null) {
                    inputData[Constants.WORK_BATCH_ID] = finalBatchId
                }
                
                // Создаем и запускаем работу по сжатию
                val compressionWorkRequest = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
                    .setInputData(workDataOf(*inputData.toList().toTypedArray()))
                    .addTag(workTag)
                    .build()
                
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        workTag,
                        ExistingWorkPolicy.REPLACE,
                        compressionWorkRequest
                    )
                
                LogUtil.imageCompression(uri, "Запущена работа по сжатию для $uri с тегом $workTag${if (finalBatchId != null) " в батче $finalBatchId" else ""}")
                return@withContext Triple(true, true, "Сжатие запущено")
            } catch (e: Exception) {
                // Удаляем URI из списка обрабатываемых в случае ошибки
                UriProcessingTracker.removeProcessingUri(uri)
                throw e
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "PROCESS_IMAGE", "Ошибка при обработке изображения", e)
            return@withContext Triple(false, false, "Ошибка: ${e.message}")
        }
    }

    /**
     * Обертка вокруг handleImage для обратной совместимости
     * Возвращает true, если обработка была успешно запущена
     */
    suspend fun processImage(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = handleImage(context, uri)
            return@withContext result.first && result.second
        } catch (e: Exception) {
            LogUtil.error(uri, "PROCESS_IMAGE", "Ошибка при обработке изображения", e)
            return@withContext false
        }
    }

    /**
     * Проверяет, нужно ли обрабатывать изображение
     * Делегирует к централизованной логике в ImageProcessingChecker
     */
    suspend fun shouldProcessImage(context: Context, uri: Uri, forceProcess: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        // Делегируем проверку классу ImageProcessingChecker
        return@withContext ImageProcessingChecker.shouldProcessImage(context, uri, forceProcess)
    }
} 
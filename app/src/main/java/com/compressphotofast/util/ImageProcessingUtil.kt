package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.compressphotofast.worker.ImageCompressionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
            val added = UriProcessingTracker.getInstance(context).addProcessingUriSafe(uri, "ImageProcessingUtil")
            if (!added) {
                LogUtil.processDebug("URI уже обрабатывается, пропуск handleImage: $uri")
                return@withContext Triple(true, false, "Изображение уже обрабатывается")
            }

            try {
                // Сначала проверяем, требуется ли обработка - используем централизованную логику
                val shouldProcess = ImageProcessingChecker.shouldProcessImage(context, uri, forceProcess)

                if (!shouldProcess) {
                    // Удаляем URI из списка обрабатываемых (с синхронизацией)
                    UriProcessingTracker.getInstance(context).removeProcessingUriSafe(uri)
                    return@withContext Triple(true, false, "Изображение уже оптимизировано")
                }

                // Проверяем, нужно ли принудительно обрабатывать
                val settingsManager = SettingsManager.getInstance(context)
                val isAutoEnabled = settingsManager.isAutoCompressionEnabled()

                // Если автосжатие отключено и нет флага принудительной обработки,
                // возвращаем сообщение о том, что нужна ручная обработка
                if (!isAutoEnabled && !forceProcess) {
                    // Удаляем URI из списка обрабатываемых (с синхронизацией)
                    UriProcessingTracker.getInstance(context).removeProcessingUriSafe(uri)
                    return@withContext Triple(true, false, "Требуется ручное сжатие")
                }

                // Получаем качество сжатия из настроек
                val quality = settingsManager.getCompressionQuality()

                // Получаем размер исходного файла для логирования
                val fileName = UriUtil.getFileNameFromUri(context, uri)
                val originalSize = UriUtil.getFileSize(context, uri)

                // Добавляем batch ID если он предоставлен, или создаем автобатч
                val finalBatchId = batchId ?: if (forceProcess) {
                    null
                } else {
                    CompressionBatchTracker.getOrCreateAutoBatchCompat(context)
                }

                val compressionWorkRequest = buildCompressionWorkRequest(
                    uri = uri,
                    quality = quality,
                    originalSize = originalSize,
                    forceProcess = forceProcess,
                    batchId = finalBatchId
                )

                WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        "sequential_image_compression",
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        compressionWorkRequest
                    )

                LogUtil.imageCompression(uri, "Запущена работа по сжатию для $uri в последовательной очереди${if (finalBatchId != null) " в батче $finalBatchId" else ""}")

                // НЕ СНИМАЕМ URI с отслеживания: Worker сам снимет его в finally
                return@withContext Triple(true, true, "Сжатие запущено")
            } catch (e: Exception) {
                // Удаляем URI из списка обрабатываемых в случае ошибки (с синхронизацией)
                UriProcessingTracker.getInstance(context).removeProcessingUriSafe(uri)
                throw e
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "PROCESS_IMAGE", "Ошибка при обработке изображения", e)
            return@withContext Triple(false, false, "Ошибка: ${e.message}")
        }
    }

    /**
     * Строит WorkRequest отдельно от discovery. Discovery не декодирует Bitmap,
     * а WorkManager сохраняет URI в durable sequential chain.
     */
    internal fun buildCompressionWorkRequest(
        uri: Uri,
        quality: Int,
        originalSize: Long,
        forceProcess: Boolean,
        batchId: String?
    ) = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
        .setInputData(
            workDataOf(
                *buildMap {
                    put(Constants.WORK_INPUT_IMAGE_URI, uri.toString())
                    put(Constants.WORK_COMPRESSION_QUALITY, quality)
                    put("original_size", originalSize)
                    put("is_handled_by_ipu", true)
                    if (batchId != null) put(Constants.WORK_BATCH_ID, batchId)
                }.toList().toTypedArray()
            )
        )
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .build()
        )
        .setInitialDelay(
            if (forceProcess) 0L else Constants.AUTO_COMPRESSION_INITIAL_DELAY_SECONDS,
            TimeUnit.SECONDS
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag("compress_${uri.hashCode()}")
        .build()

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

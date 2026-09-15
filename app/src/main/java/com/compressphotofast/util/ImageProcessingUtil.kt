package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Общая точка durable enqueue для share, галереи и content-trigger. */
object ImageProcessingUtil {
    suspend fun enqueueImage(
        context: Context,
        uri: Uri,
        forceProcess: Boolean = false,
        batchId: String? = null,
        origin: CompressionOrigin = if (forceProcess) CompressionOrigin.MANUAL else CompressionOrigin.AUTO
    ): CompressionEnqueueResult = withContext(Dispatchers.IO) {
        CompressionWorkScheduler(context.applicationContext, androidx.work.WorkManager.getInstance(context))
            .enqueue(uri, forceProcess, batchId, origin)
    }

    /**
     * Совместимый фасад для старых callers. Второй элемент означает, что запрос
     * был durable accepted; duplicate под KEEP также считается принятым.
     */
    suspend fun handleImage(
        context: Context,
        uri: Uri,
        forceProcess: Boolean = false,
        batchId: String? = null
    ): Triple<Boolean, Boolean, String> = withContext(Dispatchers.IO) {
        when (val result = enqueueImage(context, uri, forceProcess, batchId)) {
            CompressionEnqueueResult.DURABLY_ACCEPTED ->
                Triple(true, true, "Сжатие поставлено в durable очередь")
            CompressionEnqueueResult.NOT_REQUIRED ->
                Triple(true, false, "Изображение не требует обработки")
            CompressionEnqueueResult.RETRYABLE_FAILURE ->
                Triple(false, false, "Временная ошибка постановки в очередь")
        }
    }

    suspend fun processImage(context: Context, uri: Uri): Boolean =
        enqueueImage(context, uri) == CompressionEnqueueResult.DURABLY_ACCEPTED

    suspend fun shouldProcessImage(context: Context, uri: Uri, forceProcess: Boolean = false): Boolean =
        withContext(Dispatchers.IO) { ImageProcessingChecker.shouldProcessImage(context, uri, forceProcess) }
}

package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.compressphotofast.worker.ImageCompressionWorker
import com.compressphotofast.worker.ImageSettleWorker
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

enum class CompressionEnqueueResult {
    DURABLY_ACCEPTED,
    NOT_REQUIRED,
    RETRYABLE_FAILURE
}

enum class CompressionOrigin { AUTO, MANUAL }

/** Постановка независимых durable работ для одного URI. */
@Singleton
class CompressionWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager
) {
    suspend fun enqueue(
        uri: Uri,
        forceProcess: Boolean = false,
        batchId: String? = null,
        origin: CompressionOrigin = if (forceProcess) CompressionOrigin.MANUAL else CompressionOrigin.AUTO,
        discoveredAt: Long = System.currentTimeMillis()
    ): CompressionEnqueueResult {
        if (!forceProcess && !SettingsManager.getInstance(context).isAutoCompressionEnabled()) {
            return CompressionEnqueueResult.NOT_REQUIRED
        }

        return try {
            val quality = SettingsManager.getInstance(context).getCompressionQuality()
            val maxResolution = SettingsManager.getInstance(context).getMaxResolution()
            val originalSize = runCatching { UriUtil.getFileSize(context, uri) ?: 0L }.getOrDefault(0L)
            val data = buildInputData(uri, quality, originalSize, forceProcess, batchId, origin, discoveredAt, maxResolution)
            if (forceProcess) {
                workManager.cancelUniqueWork(settleName(uri)).await()
                enqueueFinal(uri, data, expedited = true)
            } else {
                val request = buildSettleWorkRequest(uri, data)
                workManager.enqueueUniqueWork(settleName(uri), ExistingWorkPolicy.KEEP, request).await()
            }
            LogUtil.processDebug(
                "Durable enqueue: source=${origin.name}, uri=$uri, digest=${digest(uri)}, " +
                    "discoveredAt=$discoveredAt, enqueuedAt=${System.currentTimeMillis()}"
            )
            CompressionEnqueueResult.DURABLY_ACCEPTED
        } catch (e: Exception) {
            LogUtil.error(uri, "DURABLE_ENQUEUE", "Не удалось поставить работу", e)
            CompressionEnqueueResult.RETRYABLE_FAILURE
        }
    }

    suspend fun enqueueFinal(uri: Uri, inputData: androidx.work.Data, expedited: Boolean = false) {
        workManager.enqueueUniqueWork(finalName(uri), ExistingWorkPolicy.KEEP, buildFinalWorkRequest(uri, inputData, expedited)).await()
    }

    internal fun buildSettleWorkRequest(uri: Uri, inputData: androidx.work.Data): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ImageSettleWorker>()
            .setInputData(inputData)
            .setInitialDelay(Constants.AUTO_COMPRESSION_INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag("image_settle_v2_${digest(uri)}")
            .build()

    internal fun buildFinalWorkRequest(
        uri: Uri,
        inputData: androidx.work.Data,
        expedited: Boolean = false
    ): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag("image_compression_v2_${digest(uri)}")
        if (expedited) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        return builder.build()
    }

    fun settleName(uri: Uri): String = "image_settle_v2_${digest(uri)}"

    fun finalName(uri: Uri): String = "image_compression_v2_${digest(uri)}"

    internal fun buildInputData(
        uri: Uri,
        quality: Int,
        originalSize: Long,
        forceProcess: Boolean,
        batchId: String?,
        origin: CompressionOrigin,
        discoveredAt: Long,
        maxResolution: Int = Constants.DEFAULT_MAX_RESOLUTION
    ) = workDataOf(
        Constants.WORK_INPUT_IMAGE_URI to uri.toString(),
        Constants.WORK_COMPRESSION_QUALITY to quality,
        Constants.WORK_MAX_RESOLUTION to maxResolution,
        "original_size" to originalSize,
        Constants.WORK_ORIGIN to origin.name,
        Constants.WORK_DISCOVERED_AT to discoveredAt,
        Constants.WORK_ENQUEUED_AT to System.currentTimeMillis(),
        Constants.WORK_UNIQUE_DIGEST to digest(uri),
        *(if (batchId != null) arrayOf(Constants.WORK_BATCH_ID to batchId) else emptyArray())
    )

    companion object {
        fun digest(uri: Uri): String = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

package com.compressphotofast.domain

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import com.compressphotofast.worker.ImageCompressionWorker
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.compressphotofast.data.SettingsManager
import com.compressphotofast.util.Constants
import com.compressphotofast.util.LogUtil

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
    private val workManager: WorkManager,
    private val settingsManager: SettingsManager
) {
    suspend fun enqueue(
        uri: Uri,
        forceProcess: Boolean = false,
        batchId: String? = null,
        origin: CompressionOrigin = if (forceProcess) CompressionOrigin.MANUAL else CompressionOrigin.AUTO,
        discoveredAt: Long = System.currentTimeMillis()
    ): CompressionEnqueueResult {
        if (!forceProcess && !settingsManager.isAutoCompressionEnabled()) {
            return CompressionEnqueueResult.NOT_REQUIRED
        }

        return try {
            val quality = settingsManager.getCompressionQuality()
            val maxResolution = settingsManager.getMaxResolution()
            val data = buildInputData(uri, quality, forceProcess, batchId, origin, discoveredAt, maxResolution)
            if (forceProcess) {
                // Settle-работы ставились до версии с одной работой на URI
                workManager.cancelUniqueWork(settleName(uri)).await()
                // Отложенная auto-работа того же URI заменяется, иначе ручной батч
                // ждал бы её задержку и не получил бы отчёт; выполняющуюся не трогаем
                val running = workManager.getWorkInfosForUniqueWorkFlow(finalName(uri)).first()
                    .any { it.state == WorkInfo.State.RUNNING }
                val policy = if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
                workManager.enqueueUniqueWork(
                    finalName(uri), policy, buildFinalWorkRequest(uri, data, expedited = true)
                ).await()
            } else {
                // Одна отложенная работа вместо settle → final: задержка даёт файлу
                // «устояться», KEEP схлопывает повторные обнаружения
                workManager.enqueueUniqueWork(
                    finalName(uri), ExistingWorkPolicy.KEEP, buildFinalWorkRequest(uri, data, delayed = true)
                ).await()
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

    /** Дренаж legacy settle-работ ([com.compressphotofast.worker.ImageSettleWorker]). */
    suspend fun enqueueFinal(uri: Uri, inputData: androidx.work.Data, expedited: Boolean = false) {
        workManager.enqueueUniqueWork(finalName(uri), ExistingWorkPolicy.KEEP, buildFinalWorkRequest(uri, inputData, expedited)).await()
    }

    internal fun buildFinalWorkRequest(
        uri: Uri,
        inputData: androidx.work.Data,
        expedited: Boolean = false,
        delayed: Boolean = false
    ): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag("image_compression_v2_${digest(uri)}")
        if (expedited) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        if (delayed) builder.setInitialDelay(Constants.AUTO_COMPRESSION_INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
        return builder.build()
    }

    fun settleName(uri: Uri): String = "image_settle_v2_${digest(uri)}"

    fun finalName(uri: Uri): String = "image_compression_v2_${digest(uri)}"

    internal fun buildInputData(
        uri: Uri,
        quality: Int,
        forceProcess: Boolean,
        batchId: String?,
        origin: CompressionOrigin,
        discoveredAt: Long,
        maxResolution: Int = Constants.DEFAULT_MAX_RESOLUTION
    ) = workDataOf(
        Constants.WORK_INPUT_IMAGE_URI to uri.toString(),
        Constants.WORK_COMPRESSION_QUALITY to quality,
        Constants.WORK_MAX_RESOLUTION to maxResolution,
        Constants.WORK_ORIGIN to origin.name,
        Constants.WORK_DISCOVERED_AT to discoveredAt,
        Constants.WORK_ENQUEUED_AT to System.currentTimeMillis(),
        Constants.WORK_UNIQUE_DIGEST to digest(uri),
        *(if (batchId != null) arrayOf(Constants.WORK_BATCH_ID to batchId) else emptyArray())
    )

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()

        fun digest(uri: Uri): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(uri.toString().toByteArray(Charsets.UTF_8))
            val out = CharArray(bytes.size * 2)
            bytes.forEachIndexed { i, b ->
                out[i * 2] = HEX[(b.toInt() shr 4) and 0xF]
                out[i * 2 + 1] = HEX[b.toInt() and 0xF]
            }
            return String(out)
        }
    }
}

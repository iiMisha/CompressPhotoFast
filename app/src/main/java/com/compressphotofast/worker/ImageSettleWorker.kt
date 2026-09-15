package com.compressphotofast.worker

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.compressphotofast.util.CompressionWorkScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** После задержки подтверждает final unique-work для URI. */
@HiltWorker
class ImageSettleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val scheduler: CompressionWorkScheduler
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val value = inputData.getString(com.compressphotofast.util.Constants.WORK_INPUT_IMAGE_URI)
            ?: return Result.failure()
        return try {
            scheduler.enqueueFinal(Uri.parse(value), inputData)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount + 1 < 5) Result.retry() else Result.failure()
        }
    }
}

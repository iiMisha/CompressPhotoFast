package com.compressphotofast.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.compressphotofast.util.CompressionEnqueueResult
import com.compressphotofast.util.CompressionOrigin
import com.compressphotofast.util.CompressionWorkScheduler
import com.compressphotofast.util.Constants
import com.compressphotofast.util.GalleryScanUtil
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.SettingsManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Независимое от FGS восстановление MediaStore watermark и пропущенных URI. */
@HiltWorker
class GalleryReconciliationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val scheduler: CompressionWorkScheduler
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()) return Result.success()
        val window = if (inputData.getBoolean(CATCH_UP, false)) {
            Constants.HISTORY_SCAN_WINDOW_SECONDS.toInt()
        } else {
            val last = SettingsManager.getInstance(applicationContext).getLastScanTimestamp()
            ((System.currentTimeMillis() - last) / 1000L + Constants.RECENT_SCAN_WINDOW_SECONDS)
                .coerceIn(Constants.RECENT_SCAN_WINDOW_SECONDS, Constants.HISTORY_SCAN_WINDOW_SECONDS).toInt()
        }
        val scan = GalleryScanUtil.scanRecentImages(applicationContext, window)
        if (!scan.completedSuccessfully) return Result.retry()
        var durable = true
        scan.foundUris.forEach { uri ->
            val result = scheduler.enqueue(uri, origin = CompressionOrigin.AUTO)
            if (result == CompressionEnqueueResult.RETRYABLE_FAILURE) durable = false
        }
        if (durable) SettingsManager.getInstance(applicationContext).setLastScanTimestamp(System.currentTimeMillis())
        LogUtil.processDebug("Reconciliation: found=${scan.foundUris.size}, durable=$durable")
        return if (durable) Result.success() else Result.retry()
    }

    companion object {
        private const val ONE_TIME_NAME = "gallery_reconciliation_catch_up"
        private const val PERIODIC_NAME = "gallery_reconciliation_periodic"
        private const val CATCH_UP = "catch_up"

        fun schedule(context: Context, catchUp: Boolean) {
            if (!SettingsManager.getInstance(context).isAutoCompressionEnabled()) return
            val wm = WorkManager.getInstance(context)
            val request = OneTimeWorkRequestBuilder<GalleryReconciliationWorker>()
                .setInputData(androidx.work.workDataOf(CATCH_UP to catchUp))
                .build()
            wm.enqueueUniqueWork(ONE_TIME_NAME, ExistingWorkPolicy.KEEP, request)
            val periodic = PeriodicWorkRequestBuilder<GalleryReconciliationWorker>(
                Constants.BACKGROUND_SCAN_INTERVAL_MINUTES.coerceAtLeast(15L), TimeUnit.MINUTES
            ).build()
            wm.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
        }
    }
}

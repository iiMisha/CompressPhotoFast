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
import com.compressphotofast.util.Constants
import com.compressphotofast.domain.GalleryScanCoordinator
import com.compressphotofast.util.LogUtil
import com.compressphotofast.data.SettingsManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Независимое от FGS восстановление MediaStore watermark и пропущенных URI. */
@HiltWorker
class GalleryReconciliationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val galleryScanCoordinator: GalleryScanCoordinator,
    private val settingsManager: SettingsManager
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!settingsManager.isAutoCompressionEnabled()) return Result.success()
        val window = if (inputData.getBoolean(CATCH_UP, false)) {
            GalleryScanCoordinator.Window.HISTORY
        } else {
            GalleryScanCoordinator.Window.SINCE_WATERMARK
        }
        val outcome = galleryScanCoordinator.scan(window)
        LogUtil.processDebug("Reconciliation: found=${outcome.foundCount}, durable=${outcome.durable}")
        return if (outcome.durable) Result.success() else Result.retry()
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

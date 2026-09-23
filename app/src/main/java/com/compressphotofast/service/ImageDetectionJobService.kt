package com.compressphotofast.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.compressphotofast.util.CompressionEnqueueResult
import com.compressphotofast.util.CompressionOrigin
import com.compressphotofast.util.CompressionWorkScheduler
import com.compressphotofast.util.Constants
import com.compressphotofast.util.GalleryScanUtil
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.SettingsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

enum class DetectionJobScheduleResult { SCHEDULED, ALREADY_ARMED, FAILED }

/** Content-trigger с двумя слотами, чтобы новый trigger был armed до завершения текущего. */
@AndroidEntryPoint
class ImageDetectionJobService : JobService() {
    @Inject lateinit var scheduler: CompressionWorkScheduler

    private data class RunState(
        val params: JobParameters?,
        val scope: CoroutineScope,
        val alternateArmed: Boolean,
        val terminal: AtomicBoolean = AtomicBoolean(false),
        @Volatile var stopped: Boolean = false
    )

    @Volatile private var activeRun: RunState? = null

    companion object {
        const val JOB_ID_PRIMARY = 1000
        const val JOB_ID_ALTERNATE = 1001
        private const val MAX_DELAY_MS = 15_000L

        fun scheduleJob(context: Context): DetectionJobScheduleResult {
            if (!SettingsManager.getInstance(context).isAutoCompressionEnabled()) {
                cancelJob(context)
                return DetectionJobScheduleResult.FAILED
            }
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.allPendingJobs.any { it.id == JOB_ID_PRIMARY || it.id == JOB_ID_ALTERNATE }) {
                return DetectionJobScheduleResult.ALREADY_ARMED
            }
            return arm(context, scheduler, JOB_ID_PRIMARY)
        }

        fun cancelJob(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(JOB_ID_PRIMARY)
            scheduler.cancel(JOB_ID_ALTERNATE)
            LogUtil.processDebug("ImageDetectionJobService: отменены оба content-trigger slot")
        }

        private fun arm(context: Context, scheduler: JobScheduler, id: Int): DetectionJobScheduleResult {
            val trigger = JobInfo.TriggerContentUri(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
            )
            val info = JobInfo.Builder(id, ComponentName(context, ImageDetectionJobService::class.java))
                .addTriggerContentUri(trigger)
                .setTriggerContentMaxDelay(MAX_DELAY_MS)
                .setTriggerContentUpdateDelay(0L)
                .build()
            return if (scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS) {
                DetectionJobScheduleResult.SCHEDULED
            } else {
                DetectionJobScheduleResult.FAILED
            }
        }

        private fun armAlternate(context: Context, currentId: Int): Boolean {
            val alternateId = if (currentId == JOB_ID_PRIMARY) JOB_ID_ALTERNATE else JOB_ID_PRIMARY
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.allPendingJobs.any { it.id == alternateId }) return true
            return arm(context, scheduler, alternateId) == DetectionJobScheduleResult.SCHEDULED
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()) return false
        // Живой ContentObserver — активный путь обнаружения: Job сработал как
        // recovery при ещё живом FGS (например, после onTaskRemoved) — не
        // дублируем обработку URI, отдаем Job системе без работы.
        if (BackgroundMonitoringService.isReady) {
            LogUtil.processDebug("Content-trigger Job пропущен: ContentObserver активен (isReady)")
            return false
        }
        runCatching { MonitoringController.startForegroundService(applicationContext) }
        val currentId = params?.jobId ?: JOB_ID_PRIMARY
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val run = RunState(params, scope, armAlternate(applicationContext, currentId))
        activeRun = run
        scope.launch {
            try {
                delay(2_000L)
                val uris = params?.triggeredContentUris?.toList() ?: emptyList()
                if (uris.isEmpty()) processOverflowScan() else processUris(uris)
                finishRun(run, reschedule = !run.alternateArmed)
            } catch (_: CancellationException) {
                if (!run.stopped) finishRun(run, reschedule = !run.alternateArmed)
            } catch (e: Exception) {
                LogUtil.error(null, "JOB_PROCESSING", "Ошибка content-trigger", e)
                finishRun(run, reschedule = !run.alternateArmed)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        val run = activeRun ?: return true
        run.stopped = true
        run.scope.cancel()
        finishRun(run, reschedule = !run.alternateArmed)
        return !run.alternateArmed
    }

    private suspend fun processUris(uris: List<Uri>) = withContext(Dispatchers.IO) {
        var durable = true
        uris.forEach { uri ->
            when (scheduler.enqueue(uri, origin = CompressionOrigin.AUTO)) {
                CompressionEnqueueResult.RETRYABLE_FAILURE -> durable = false
                else -> Unit
            }
        }
        if (durable) SettingsManager.getInstance(applicationContext)
            .setLastScanTimestamp(System.currentTimeMillis())
    }

    private suspend fun processOverflowScan() = withContext(Dispatchers.IO) {
        val scan = GalleryScanUtil.scanRecentImages(applicationContext)
        if (!scan.completedSuccessfully) return@withContext
        var durable = true
        scan.foundUris.forEach { uri ->
            if (scheduler.enqueue(uri, origin = CompressionOrigin.AUTO) == CompressionEnqueueResult.RETRYABLE_FAILURE) {
                durable = false
            }
        }
        if (durable) SettingsManager.getInstance(applicationContext)
            .setLastScanTimestamp(System.currentTimeMillis())
    }

    private fun finishRun(run: RunState, reschedule: Boolean) {
        if (!run.terminal.compareAndSet(false, true)) return
        try {
            jobFinished(run.params, reschedule)
        } catch (e: Exception) {
            LogUtil.error(null, "JOB_FINISH", "Не удалось завершить Job", e)
        }
        if (activeRun === run) activeRun = null
        if (reschedule && SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()) {
            runCatching { scheduleJob(applicationContext) }
        }
    }
}

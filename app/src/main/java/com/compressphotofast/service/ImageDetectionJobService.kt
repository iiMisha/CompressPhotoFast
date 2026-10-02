package com.compressphotofast.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore
import com.compressphotofast.util.LogUtil
import com.compressphotofast.domain.GalleryScanCoordinator
import com.compressphotofast.data.SettingsManager
import com.compressphotofast.data.UriProcessingTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

enum class DetectionJobScheduleResult { SCHEDULED, ALREADY_ARMED, FAILED }

/** Content-trigger с двумя слотами, чтобы новый trigger был armed до завершения текущего. */
@AndroidEntryPoint
class ImageDetectionJobService : JobService() {
    @Inject lateinit var settingsManager: SettingsManager
    @Inject lateinit var galleryScanCoordinator: GalleryScanCoordinator
    @Inject lateinit var uriProcessingTracker: UriProcessingTracker

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
        // Серия снимков/собственные записи приложения схлопываются в один запуск
        private const val UPDATE_DELAY_MS = 3_000L

        fun scheduleJob(context: Context): DetectionJobScheduleResult {
            if (!SettingsManager.getInstance(context).isAutoCompressionEnabled()) {
                cancelJob(context)
                return DetectionJobScheduleResult.FAILED
            }
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            // getPendingJob вместо allPendingJobs: тот возвращает и все задачи WorkManager
            if (scheduler.getPendingJob(JOB_ID_PRIMARY) != null || scheduler.getPendingJob(JOB_ID_ALTERNATE) != null) {
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
                .setTriggerContentUpdateDelay(UPDATE_DELAY_MS)
                // setPersisted(true) для content-trigger Job недопустим (Android API
                // запрещает сочетание addTriggerContentUri + persisted): восстановление
                // после перезагрузки обеспечивают BootCompletedReceiver и cold-start
                // recovery через MonitoringController.ensureDetectionJob.
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
            if (scheduler.getPendingJob(alternateId) != null) return true
            return arm(context, scheduler, alternateId) == DetectionJobScheduleResult.SCHEDULED
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!settingsManager.isAutoCompressionEnabled()) return false
        // Живой ContentObserver остаётся активным путём обнаружения: при isReady
        // не поднимаем FGS и не делаем тяжёлый overflow-scan. Но triggered URIs
        // обрабатываем всегда: Job — единственный механизм, будящий замороженный
        // процесс (battery saver), а dedup (unique work KEEP, UriProcessingTracker,
        // маркер сжатия) исключает двойную постановку одного URI.
        val observerAlive = BackgroundMonitoringService.isReady
        if (!observerAlive) {
            runCatching { MonitoringController.startForegroundService(applicationContext) }
        }
        val currentId = params?.jobId ?: JOB_ID_PRIMARY
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val run = RunState(params, scope, armAlternate(applicationContext, currentId))
        activeRun = run
        scope.launch {
            try {
                val delayMs = if (observerAlive) 0L else 2_000L
                delay(delayMs)
                val triggered = params?.triggeredContentUris?.toList() ?: emptyList()
                // Собственные записи приложения (insert, IS_PENDING, EXIF) тоже будят Job —
                // отсекаем их до постановки settle/final работ
                val uris = triggered.filterNot { uriProcessingTracker.shouldIgnore(it) }
                if (triggered.isNotEmpty()) {
                    // Только triggered URI: это не скан галереи, watermark не продвигаем.
                    galleryScanCoordinator.enqueueTriggered(uris)
                } else if (!observerAlive) {
                    galleryScanCoordinator.scan(GalleryScanCoordinator.Window.SINCE_WATERMARK)
                }
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

    private fun finishRun(run: RunState, reschedule: Boolean) {
        if (!run.terminal.compareAndSet(false, true)) return
        try {
            jobFinished(run.params, reschedule)
        } catch (e: Exception) {
            LogUtil.error(null, "JOB_FINISH", "Не удалось завершить Job", e)
        }
        if (activeRun === run) activeRun = null
        if (reschedule && settingsManager.isAutoCompressionEnabled()) {
            runCatching { scheduleJob(applicationContext) }
        }
    }
}

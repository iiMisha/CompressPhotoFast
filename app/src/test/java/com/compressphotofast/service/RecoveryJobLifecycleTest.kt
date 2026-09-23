package com.compressphotofast.service

import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.compressphotofast.BaseUnitTest
import com.compressphotofast.util.Constants
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit тесты жизненного цикла recovery content-trigger Job'а
 * [ImageDetectionJobService] в связке с [MonitoringController].
 *
 * Инвариант оптимизации энергопотребления: пока живой ContentObserver в
 * [BackgroundMonitoringService] является активным путём обнаружения (isReady),
 * armed Job'ы отменяются и не дублируют обработку новых фото. Job остаётся
 * recovery-механизмом: он планируется заново при смерти службы.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RecoveryJobLifecycleTest : BaseUnitTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun jobScheduler() = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    private fun pendingDetectionJobIds(): List<Int> =
        jobScheduler().allPendingJobs.map { it.id }
            .filter { it == ImageDetectionJobService.JOB_ID_PRIMARY || it == ImageDetectionJobService.JOB_ID_ALTERNATE }

    private fun setAutoCompression(value: Boolean) {
        context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_AUTO_COMPRESSION, value)
            .commit()
    }

    @After
    override fun tearDown() {
        setAutoCompression(false)
        jobScheduler().cancelAll()
        unmockkAll()
        super.tearDown()
    }

    @Test
    fun `scheduleJob arms content-trigger job when auto compression is on`() {
        setAutoCompression(true)

        assertEquals(DetectionJobScheduleResult.SCHEDULED, ImageDetectionJobService.scheduleJob(context))
        assertTrue(pendingDetectionJobIds().isNotEmpty())
    }

    @Test
    fun `scheduleJob cancels jobs when auto compression is off`() {
        setAutoCompression(true)
        ImageDetectionJobService.scheduleJob(context)
        assertTrue(pendingDetectionJobIds().isNotEmpty())

        setAutoCompression(false)
        ImageDetectionJobService.scheduleJob(context)

        assertTrue("Armed Job'ы должны быть отменены при выключенном автосжатии", pendingDetectionJobIds().isEmpty())
    }

    @Test
    fun `startMonitoring arms recovery job before foreground service start`() {
        setAutoCompression(true)

        MonitoringController.startMonitoring(context)

        assertTrue(pendingDetectionJobIds().isNotEmpty())
    }

    @Test
    fun `ready service cancels armed recovery jobs`() {
        setAutoCompression(true)
        MonitoringController.startMonitoring(context)
        assertTrue(pendingDetectionJobIds().isNotEmpty())

        // Эмулирует BackgroundMonitoringService.cancelRecoveryJobsWhileReady():
        // при готовом ContentObserver активные content-trigger Job'ы не нужны.
        ImageDetectionJobService.cancelJob(context)

        assertTrue(pendingDetectionJobIds().isEmpty())
    }

    @Test
    fun `job survives service death path when auto compression stays on`() {
        setAutoCompression(true)
        // Эмуляция onDestroy: служба планирует recovery Job заново.
        ImageDetectionJobService.scheduleJob(context)

        assertFalse(pendingDetectionJobIds().isEmpty())
    }
}

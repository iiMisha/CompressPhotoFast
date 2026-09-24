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
 * Unit тесты жизненного цикла content-trigger Job'а [ImageDetectionJobService]
 * в связке с [MonitoringController] и [BackgroundMonitoringService].
 *
 * Инвариант надёжности обнаружения: armed content-trigger Job присутствует
 * всегда при включённом автосжатии, в том числе при готовом FGS (isReady).
 * В отличие от ContentObserver Job будит замороженный/приостановленный процесс
 * (battery saver, OEM), поэтому он не отменяется при живом observer; дублирующая
 * постановка одного URI исключается dedup (unique work KEEP, трекер, маркер).
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
    fun `ready service keeps armed detection jobs`() {
        setAutoCompression(true)
        // Эмулирует BackgroundMonitoringService.ensureDetectionJobArmed():
        // при готовом ContentObserver Job остаётся armed — он будит замороженный
        // процесс, если observer-события не доставляются (battery saver).
        ImageDetectionJobService.scheduleJob(context)
        assertTrue(pendingDetectionJobIds().isNotEmpty())

        // Повторный вызов не снимает armed-состояние (ALREADY_ARMED — no-op).
        assertEquals(DetectionJobScheduleResult.ALREADY_ARMED, ImageDetectionJobService.scheduleJob(context))
        assertTrue("Armed Job должен сохраняться при готовом ContentObserver", pendingDetectionJobIds().isNotEmpty())
    }

    @Test
    fun `job survives service death path when auto compression stays on`() {
        setAutoCompression(true)
        // Эмуляция onDestroy: служба планирует recovery Job заново.
        ImageDetectionJobService.scheduleJob(context)

        assertFalse(pendingDetectionJobIds().isEmpty())
    }
}

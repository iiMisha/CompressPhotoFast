package com.compressphotofast.util

import android.net.Uri
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.mockk.mockk

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ImageProcessingUtilWorkRequestTest {
    private val uri = Uri.parse("content://media/external/images/media/42")

    @Test
    fun `automatic work is delayed by thirty seconds`() {
        val scheduler = CompressionWorkScheduler(
            org.robolectric.RuntimeEnvironment.getApplication(),
            mockk(relaxed = true)
        )
        val data = scheduler.buildInputData(uri, 70, 200_000L, false, null, CompressionOrigin.AUTO, 1L)
        val request = scheduler.buildSettleWorkRequest(uri, data)

        assertEquals(
            Constants.AUTO_COMPRESSION_INITIAL_DELAY_SECONDS,
            TimeUnit.MILLISECONDS.toSeconds(request.workSpec.initialDelay)
        )
        assertTrue(!request.workSpec.constraints.requiresBatteryNotLow())
    }

    @Test
    fun `manual work has no initial delay`() {
        val scheduler = CompressionWorkScheduler(
            org.robolectric.RuntimeEnvironment.getApplication(),
            mockk(relaxed = true)
        )
        val data = scheduler.buildInputData(uri, 70, 200_000L, true, "manual", CompressionOrigin.MANUAL, 1L)
        val request = scheduler.buildFinalWorkRequest(uri, data, expedited = true)

        assertEquals(0L, request.workSpec.initialDelay)
        assertEquals("manual", request.workSpec.input.getString(Constants.WORK_BATCH_ID))
        assertTrue(!request.workSpec.constraints.requiresBatteryNotLow())
    }

    @Test
    fun `digest uses complete URI instead of hashCode`() {
        val first = Uri.parse("content://media/external/images/media/42")
        val second = Uri.parse("content://other/external/images/media/42")

        assertNotEquals(
            CompressionWorkScheduler.digest(first),
            CompressionWorkScheduler.digest(second)
        )
    }
}

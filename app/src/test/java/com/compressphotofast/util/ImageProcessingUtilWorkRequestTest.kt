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
import com.compressphotofast.domain.CompressionOrigin
import com.compressphotofast.domain.CompressionWorkScheduler

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ImageProcessingUtilWorkRequestTest {
    private val uri = Uri.parse("content://media/external/images/media/42")

    @Test
    fun `automatic work is delayed by thirty seconds`() {
        val scheduler = CompressionWorkScheduler(
            org.robolectric.RuntimeEnvironment.getApplication(),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        val data = scheduler.buildInputData(uri, 70, false, null, CompressionOrigin.AUTO, 1L)
        val request = scheduler.buildFinalWorkRequest(uri, data, delayed = true)

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
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        val data = scheduler.buildInputData(uri, 70, true, "manual", CompressionOrigin.MANUAL, 1L)
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

    @Test
    fun `max resolution is threaded through input data`() {
        val scheduler = CompressionWorkScheduler(
            org.robolectric.RuntimeEnvironment.getApplication(),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        val data = scheduler.buildInputData(
            uri, 70, false, null, CompressionOrigin.AUTO, 1L,
            maxResolution = Constants.RESOLUTION_1920
        )

        assertEquals(
            Constants.RESOLUTION_1920,
            data.getInt(Constants.WORK_MAX_RESOLUTION, Constants.DEFAULT_MAX_RESOLUTION)
        )
    }

    @Test
    fun `max resolution defaults to original when not specified`() {
        val scheduler = CompressionWorkScheduler(
            org.robolectric.RuntimeEnvironment.getApplication(),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        val data = scheduler.buildInputData(uri, 70, false, null, CompressionOrigin.AUTO, 1L)

        assertEquals(
            Constants.DEFAULT_MAX_RESOLUTION,
            data.getInt(Constants.WORK_MAX_RESOLUTION, -1)
        )
    }
}

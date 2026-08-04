package com.compressphotofast.util

import android.net.Uri
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ImageProcessingUtilWorkRequestTest {
    private val uri = Uri.parse("content://media/external/images/media/42")

    @Test
    fun `automatic work is delayed by thirty seconds`() {
        val request = ImageProcessingUtil.buildCompressionWorkRequest(
            uri, 70, 200_000L, forceProcess = false, batchId = null
        )

        assertEquals(
            Constants.AUTO_COMPRESSION_INITIAL_DELAY_SECONDS,
            TimeUnit.MILLISECONDS.toSeconds(request.workSpec.initialDelay)
        )
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
    }

    @Test
    fun `manual work has no initial delay`() {
        val request = ImageProcessingUtil.buildCompressionWorkRequest(
            uri, 70, 200_000L, forceProcess = true, batchId = "manual"
        )

        assertEquals(0L, request.workSpec.initialDelay)
        assertEquals("manual", request.workSpec.input.getString(Constants.WORK_BATCH_ID))
    }
}

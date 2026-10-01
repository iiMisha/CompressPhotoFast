package com.compressphotofast.util

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.compressphotofast.data.SettingsManager
import com.compressphotofast.domain.CompressionEnqueueResult
import com.compressphotofast.domain.CompressionOrigin
import com.compressphotofast.domain.CompressionWorkScheduler
import com.compressphotofast.domain.GalleryScanCoordinator
import com.compressphotofast.domain.GalleryScanUtil

class GalleryScanCoordinatorTest {
    private val settings = mockk<SettingsManager>(relaxed = true)
    private val scheduler = mockk<CompressionWorkScheduler>()
    private val scanUtil = mockk<GalleryScanUtil>()
    private val coordinator = GalleryScanCoordinator(settings, scheduler, scanUtil)
    private val uri1 = mockk<Uri>()
    private val uri2 = mockk<Uri>()

    @Before
    fun setUp() {
        every { settings.isAutoCompressionEnabled() } returns true
        every { settings.getLastScanTimestamp() } returns 0L
        coEvery { scheduler.enqueue(any(), any(), any(), any(), any()) } returns CompressionEnqueueResult.DURABLY_ACCEPTED
    }

    private fun stubScan(completed: Boolean, uris: List<Uri> = listOf(uri1, uri2)) {
        coEvery { scanUtil.scanRecentImages(any(), any()) } returns
            GalleryScanUtil.ScanResult(foundUris = uris, completedSuccessfully = completed)
    }

    @Test
    fun `window since watermark is clamped between recent and history`() {
        val now = 10_000_000_000L
        assertEquals(
            Constants.HISTORY_SCAN_WINDOW_SECONDS.toInt(),
            GalleryScanCoordinator.windowSinceWatermark(0L, now)
        )
        assertEquals(
            Constants.RECENT_SCAN_WINDOW_SECONDS.toInt(),
            GalleryScanCoordinator.windowSinceWatermark(now, now)
        )
        assertEquals(
            (3600 + Constants.RECENT_SCAN_WINDOW_SECONDS).toInt(),
            GalleryScanCoordinator.windowSinceWatermark(now - 3_600_000L, now)
        )
    }

    @Test
    fun `durable scan advances watermark to scan start time`() = runTest {
        stubScan(completed = true)
        val before = System.currentTimeMillis()
        val saved = slot<Long>()
        every { settings.setLastScanTimestamp(capture(saved)) } returns Unit

        val outcome = coordinator.scan(GalleryScanCoordinator.Window.SINCE_WATERMARK)

        assertTrue(outcome.durable)
        assertEquals(2, outcome.foundCount)
        assertTrue(saved.captured in before..System.currentTimeMillis())
        coVerify(exactly = 2) { scheduler.enqueue(any(), any(), any(), CompressionOrigin.AUTO, any()) }
    }

    @Test
    fun `history window scans full history`() = runTest {
        stubScan(completed = true)
        coordinator.scan(GalleryScanCoordinator.Window.HISTORY)
        coVerify { scanUtil.scanRecentImages(Constants.HISTORY_SCAN_WINDOW_SECONDS.toInt(), any()) }
    }

    @Test
    fun `retryable enqueue failure keeps watermark`() = runTest {
        stubScan(completed = true)
        coEvery { scheduler.enqueue(uri2, any(), any(), any(), any()) } returns CompressionEnqueueResult.RETRYABLE_FAILURE

        val outcome = coordinator.scan(GalleryScanCoordinator.Window.SINCE_WATERMARK)

        assertFalse(outcome.durable)
        verify(exactly = 0) { settings.setLastScanTimestamp(any()) }
    }

    @Test
    fun `incomplete scan neither enqueues nor advances watermark`() = runTest {
        stubScan(completed = false)

        val outcome = coordinator.scan(GalleryScanCoordinator.Window.SINCE_WATERMARK)

        assertFalse(outcome.durable)
        coVerify(exactly = 0) { scheduler.enqueue(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { settings.setLastScanTimestamp(any()) }
    }

    @Test
    fun `disabled auto compression skips scan`() = runTest {
        every { settings.isAutoCompressionEnabled() } returns false

        val outcome = coordinator.scan(GalleryScanCoordinator.Window.HISTORY)

        assertFalse(outcome.durable)
        coVerify(exactly = 0) { scanUtil.scanRecentImages(any(), any()) }
    }

    @Test
    fun `enqueueAll does not touch watermark`() = runTest {
        assertTrue(coordinator.enqueueAll(listOf(uri1)))
        verify(exactly = 0) { settings.setLastScanTimestamp(any()) }
    }

    @Test
    fun `durable history scan records history timestamp`() = runTest {
        stubScan(completed = true)

        coordinator.scan(GalleryScanCoordinator.Window.HISTORY)

        verify(exactly = 1) { settings.setLastHistoryScanTimestamp(any()) }
    }

    @Test
    fun `watermark scan or failed history keeps history timestamp`() = runTest {
        stubScan(completed = true)
        coordinator.scan(GalleryScanCoordinator.Window.SINCE_WATERMARK)
        stubScan(completed = false)
        coordinator.scan(GalleryScanCoordinator.Window.HISTORY)

        verify(exactly = 0) { settings.setLastHistoryScanTimestamp(any()) }
    }

    @Test
    fun `history catch-up is throttled by interval`() {
        val now = 100_000_000_000L
        val interval = Constants.HISTORY_CATCH_UP_MIN_INTERVAL_MS
        assertTrue(GalleryScanCoordinator.isHistoryCatchUpDue(0L, now))
        assertTrue(GalleryScanCoordinator.isHistoryCatchUpDue(now - interval, now))
        assertFalse(GalleryScanCoordinator.isHistoryCatchUpDue(now - interval + 1, now))
        assertFalse(GalleryScanCoordinator.isHistoryCatchUpDue(now, now))
        // Часы переведены назад — не застреваем без HISTORY
        assertTrue(GalleryScanCoordinator.isHistoryCatchUpDue(now + 1, now))
    }
}

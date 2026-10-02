package com.compressphotofast.ui

import android.content.Context
import android.net.Uri
import com.compressphotofast.BaseUnitTest
import com.compressphotofast.data.FileOperationsUtil
import com.compressphotofast.data.SettingsManager
import com.compressphotofast.data.UriProcessingTracker
import com.compressphotofast.data.UriUtil
import com.compressphotofast.domain.CompressionBatchTracker
import com.compressphotofast.domain.CompressionEnqueueResult
import com.compressphotofast.domain.CompressionEvents
import com.compressphotofast.domain.CompressionWorkScheduler
import com.compressphotofast.domain.ImageProcessingChecker
import com.compressphotofast.util.LegacyFilesTestHelpers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ручной батч и поиск пропущенных фото не делают лишних запросов к MediaStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MainViewModelTest : BaseUnitTest() {

    private val context = mockk<Context>(relaxed = true)
    private val settings = mockk<SettingsManager>(relaxed = true)
    private val tracker = mockk<UriProcessingTracker>(relaxed = true)
    private val batchTracker = mockk<CompressionBatchTracker>(relaxed = true)
    private val scheduler = mockk<CompressionWorkScheduler>(relaxed = true)
    private val checker = mockk<ImageProcessingChecker>(relaxed = true)
    private val events = mockk<CompressionEvents>(relaxed = true)

    private fun viewModel() = MainViewModel(context, settings, tracker, batchTracker, scheduler, checker, events)

    @Before
    override fun setUp() {
        super.setUp()
        mockkObject(UriUtil, FileOperationsUtil)
        coEvery { scheduler.enqueue(any(), any(), any(), any(), any()) } returns CompressionEnqueueResult.DURABLY_ACCEPTED
    }

    @After
    override fun tearDown() {
        unmockkAll()
        super.tearDown()
    }

    @Test
    fun `ручной батч проверяет существование каждого URI один раз`() = runTest {
        val uris = (1..3).map { Uri.parse("content://media/external/images/media/$it") }
        coEvery { UriUtil.isUriExistsSuspend(any(), any()) } returns true
        every { UriUtil.getMimeType(any(), any()) } returns "image/jpeg"

        val accepted = viewModel().compressSharedImages(uris)

        assertEquals(3, accepted)
        uris.forEach { coVerify(exactly = 1) { UriUtil.isUriExistsSuspend(any(), it) } }
    }

    @Test
    fun `поиск пропущенных фото запрашивает имена сжатых копий один раз`() = runTest {
        val cursor = LegacyFilesTestHelpers.createMediaStoreCursor(id = 1L, displayName = "a.jpg")
        cursor.addRow(arrayOf(2L, 0L, 0L, 0, "b.jpg", 5L * 1024 * 1024, "image/jpeg", "DCIM/Camera/"))
        every { context.contentResolver } returns LegacyFilesTestHelpers.createMockContentResolverWithCursor(cursor)
        every { settings.isSaveModeReplace() } returns false
        val names = listOf("a_compressed.jpg")
        coEvery { FileOperationsUtil.queryAppDirectoryFileNames(any()) } returns names

        viewModel().processUncompressedImages()

        coVerify(exactly = 1) { FileOperationsUtil.queryAppDirectoryFileNames(any()) }
        coVerify(exactly = 2) { checker.shouldProcessImage(any(), false, any(), names) }
    }
}

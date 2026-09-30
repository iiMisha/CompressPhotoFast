package com.compressphotofast.domain

import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import com.compressphotofast.BaseUnitTest
import com.compressphotofast.data.ExifUtil
import com.compressphotofast.data.FileOperationsUtil
import com.compressphotofast.data.ImageIntegrityUtil
import com.compressphotofast.data.MediaStoreUtil
import com.compressphotofast.data.SettingsManager
import com.compressphotofast.data.StatsTracker
import com.compressphotofast.data.UriUtil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import com.compressphotofast.data.UriProcessingTracker

class CompressImageUseCaseTest : BaseUnitTest() {

    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val context = mockk<Context>(relaxed = true) { every { contentResolver } returns resolver }
    private val tracker = mockk<UriProcessingTracker>(relaxed = true)
    private val settings = mockk<SettingsManager>(relaxed = true)
    private val events = mockk<CompressionEvents>(relaxed = true)
    private val uri = mockk<Uri>(relaxed = true)
    private val savedUri = mockk<Uri>(relaxed = true)
    private val params = CompressImageUseCase.Params(quality = 70, maxResolution = 0)
    private val sourceSize = 1_000_000L
    private lateinit var artifact: File
    private lateinit var useCase: CompressImageUseCase

    @Before
    override fun setUp() {
        super.setUp()
        mockkObject(ExifUtil, UriUtil, ImageCompressionUtil, FileOperationsUtil, MediaStoreUtil, ImageIntegrityUtil, StatsTracker)
        artifact = File.createTempFile("artifact", ".jpg").apply { writeBytes(ByteArray(10)) }
        useCase = CompressImageUseCase(context, tracker, settings, events)

        coEvery { ExifUtil.readExifDataToMemory(any(), any()) } returns emptyMap()
        coEvery { ExifUtil.writeExifDataFromMemory(any(), any(), any(), any(), any()) } returns true
        coEvery { UriUtil.getFileSize(any(), uri) } returns sourceSize
        coEvery { UriUtil.getFileSize(any(), savedUri) } returns 400_000L
        every { UriUtil.getFileNameFromUri(any(), any()) } returns "photo.jpg"
        every { UriUtil.getDirectoryFromUri(any(), any()) } returns "Pictures/"
        coEvery { UriUtil.isUriExistsSuspend(any(), any()) } returns true
        every { FileOperationsUtil.isSaveModeReplace(any()) } returns false
        every { FileOperationsUtil.createCompressedFileName(any(), any()) } returns "photo_compressed.jpg"
        coEvery { ImageIntegrityUtil.verifyImageIntegrity(any(), any()) } returns true
        coEvery { MediaStoreUtil.isFileUnchanged(any(), any(), any()) } returns true
        every { MediaStoreUtil.isSameMediaItem(any(), any()) } returns false
        every { StatsTracker.recordSuccessfulCompression(any(), any(), any(), any()) } returns null
    }

    @After
    override fun tearDown() {
        unmockkAll()
        artifact.delete()
        super.tearDown()
    }

    private fun stubTest(compressedSize: Long) {
        val reduction = (sourceSize - compressedSize) * 100f / sourceSize
        coEvery { ImageCompressionUtil.testCompression(any(), any(), any(), any(), any(), any()) } returns
            ImageCompressionUtil.CompressionTestResult(
                ImageCompressionUtil.CompressionStats(sourceSize, compressedSize, reduction),
                artifact
            )
    }

    private fun stubSave(result: MediaStoreUtil.SaveResult) {
        coEvery {
            MediaStoreUtil.saveCompressedImageFromFile(any(), artifact, any(), any(), any(), any(), any(), any(), any())
        } returns result
    }

    @Test
    fun `недоступный EXIF помечает URI недоступным`() = runTest {
        coEvery { ExifUtil.readExifDataToMemory(any(), any()) } throws FileNotFoundException()

        assertEquals(CompressImageUseCase.Outcome.Failed(), useCase(uri, params))
        verify { tracker.markUriUnavailable(uri) }
    }

    @Test
    fun `маленький файл пропускается без тестового сжатия`() = runTest {
        coEvery { UriUtil.getFileSize(any(), uri) } returns 1_000L

        assertEquals(CompressImageUseCase.Outcome.SkippedInvalidSize, useCase(uri, params))
        coVerify(exactly = 0) { ImageCompressionUtil.testCompression(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `неэффективное сжатие пишет маркер и удаляет artifact`() = runTest {
        stubTest(compressedSize = 950_000L)

        val outcome = useCase(uri, params)

        assertTrue(outcome is CompressImageUseCase.Outcome.SkippedInefficient)
        coVerify { ExifUtil.writeExifDataFromMemory(any(), uri, any(), 99, sourceSize) }
        assertFalse(artifact.exists())
    }

    @Test
    fun `причина сбоя сохранения передаётся вызывающей стороне`() = runTest {
        stubTest(compressedSize = 400_000L)
        stubSave(MediaStoreUtil.SaveResult.Failed(MediaStoreUtil.SaveFailure.ROLLBACK_FAILED))

        val outcome = useCase(uri, params)

        assertEquals(CompressImageUseCase.Outcome.Failed(MediaStoreUtil.SaveFailure.ROLLBACK_FAILED), outcome)
        assertFalse(artifact.exists())
    }

    @Test
    fun `успешное сжатие в отдельный файл`() = runTest {
        stubTest(compressedSize = 400_000L)
        stubSave(MediaStoreUtil.SaveResult.Saved(savedUri))

        val outcome = useCase(uri, params)

        assertTrue(outcome is CompressImageUseCase.Outcome.Compressed)
        assertEquals(400_000L, (outcome as CompressImageUseCase.Outcome.Compressed).compressedSize)
        coVerify(exactly = 0) { FileOperationsUtil.deleteFile(any(), any(), any(), any()) }
    }

    @Test
    fun `повреждённая новая копия — сбой без удаления оригинала`() = runTest {
        every { FileOperationsUtil.isSaveModeReplace(any()) } returns true
        stubTest(compressedSize = 400_000L)
        // Верификацию и удаление повреждённой копии выполняет MediaStoreUtil
        stubSave(MediaStoreUtil.SaveResult.Failed(MediaStoreUtil.SaveFailure.CORRUPTED_OUTPUT))

        assertEquals(
            CompressImageUseCase.Outcome.Failed(MediaStoreUtil.SaveFailure.CORRUPTED_OUTPUT),
            useCase(uri, params)
        )
        coVerify(exactly = 0) { FileOperationsUtil.deleteFile(any(), uri, any(), any()) }
    }

    @Test
    fun `replace-режим откладывает удаление до подтверждения пользователя`() = runTest {
        every { FileOperationsUtil.isSaveModeReplace(any()) } returns true
        stubTest(compressedSize = 400_000L)
        stubSave(MediaStoreUtil.SaveResult.Saved(savedUri))
        coEvery { FileOperationsUtil.deleteFile(any(), uri, any(), true) } returns mockk<IntentSender>()

        val outcome = useCase(uri, params)

        assertTrue(outcome is CompressImageUseCase.Outcome.Compressed)
        verify { settings.savePendingDeleteUri(any()) }
        verify { events.emit(CompressionEvents.Event.DeleteConfirmationRequired(uri)) }
    }

    @Test
    fun `replace-режим при отказе удаления пишет маркер в оригинал`() = runTest {
        every { FileOperationsUtil.isSaveModeReplace(any()) } returns true
        stubTest(compressedSize = 400_000L)
        stubSave(MediaStoreUtil.SaveResult.Saved(savedUri))
        coEvery { FileOperationsUtil.deleteFile(any(), uri, any(), true) } returns false

        val outcome = useCase(uri, params)

        assertTrue(outcome is CompressImageUseCase.Outcome.CompressedOriginalKept)
        coVerify { ExifUtil.writeExifDataFromMemory(any(), uri, any(), 99, sourceSize) }
    }

    @Test
    fun `изменённый во время сжатия оригинал сохраняется, копия удаляется`() = runTest {
        every { FileOperationsUtil.isSaveModeReplace(any()) } returns true
        stubTest(compressedSize = 400_000L)
        stubSave(MediaStoreUtil.SaveResult.Saved(savedUri))
        coEvery { MediaStoreUtil.isFileUnchanged(any(), uri, sourceSize) } returns false

        assertEquals(CompressImageUseCase.Outcome.Failed(), useCase(uri, params))
        verify { resolver.delete(savedUri, null, null) }
        coVerify(exactly = 0) { FileOperationsUtil.deleteFile(any(), any(), any(), any()) }
    }
}

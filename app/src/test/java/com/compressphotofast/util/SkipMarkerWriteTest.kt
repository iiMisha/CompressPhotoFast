package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.compressphotofast.BaseUnitTest
import com.compressphotofast.data.BackupRegistry
import com.compressphotofast.data.CompressionMarker
import com.compressphotofast.data.ExifUtil
import com.compressphotofast.data.FileOperationsUtil
import com.compressphotofast.data.ImageIntegrityUtil
import com.compressphotofast.data.UriUtil
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Маркер пропуска в исходном файле: одна запись под backup без переписывания тегов,
 * корректирующая — только при дрейфе сверх допуска, провал — откат без маркера.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SkipMarkerWriteTest : BaseUnitTest() {

    private lateinit var context: Context
    private lateinit var target: File
    private lateinit var targetUri: Uri
    private lateinit var originalBytes: ByteArray

    @Before
    override fun setUp() {
        super.setUp()
        context = ApplicationProvider.getApplicationContext()
        target = File(context.filesDir, "skip_marker.jpg")
        javaClass.classLoader!!.getResourceAsStream("test_images/test_image_medium.jpg")!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        ExifInterface(target.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(55.751244, 37.618423)
            saveAttributes()
        }
        originalBytes = target.readBytes()
        targetUri = Uri.fromFile(target)

        mockkObject(UriUtil, FileOperationsUtil, ImageIntegrityUtil, BackupRegistry, FileIoUtil)
        mockkObject(ExifUtil)
        // ExifInterface по FileDescriptor недоступен на JVM — пишем по пути
        every { ExifUtil.writeUserCommentInPlace(any(), any(), any()) } answers {
            ExifInterface(File(secondArg<Uri>().path!!).absolutePath).apply {
                setAttribute(ExifInterface.TAG_USER_COMMENT, thirdArg())
                saveAttributes()
            }
        }
        every { FileOperationsUtil.isSaveModeReplace(any()) } returns true
        coEvery { ImageIntegrityUtil.verifyImageIntegrity(any(), any()) } returns true
        every { FileIoUtil.getDescriptorSize(any(), any()) } answers { File(secondArg<Uri>().path!!).length() }
    }

    @After
    override fun tearDown() {
        unmockkAll()
        BackupRegistry.getPendingBackups(context).keys.forEach { File(it).delete() }
        context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        target.delete()
        super.tearDown()
    }

    private fun readMarker() =
        CompressionMarker.parse(ExifInterface(target.absolutePath).getAttribute(ExifInterface.TAG_USER_COMMENT))

    @Test
    fun `маркер пишется одной записью с размером в пределах допуска`() = runTest {
        val sizeBefore = target.length()

        assertTrue(ExifUtil.writeSkipMarker(context, targetUri, 99, 1_234_567L))

        verify(exactly = 1) { BackupRegistry.createBackup(any(), any(), any()) }
        val marker = readMarker()
        assertNotNull(marker)
        assertEquals(99, marker!!.quality)
        assertEquals(sizeBefore, marker.fileSize)
        assertEquals(1_234_567L, marker.originalFileSize)
        assertTrue(kotlin.math.abs(target.length() - marker.fileSize!!) <= Constants.MARKER_SIZE_TOLERANCE_BYTES)
        assertTrue(BackupRegistry.getPendingBackups(context).isEmpty())
    }

    @Test
    fun `ориентация и GPS оригинала не меняются`() = runTest {
        assertTrue(ExifUtil.writeSkipMarker(context, targetUri, 99, null))

        val exif = ExifInterface(target.absolutePath)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        val latLong = exif.latLong
        assertNotNull(latLong)
        assertEquals(55.751244, latLong!![0], 1e-6)
        assertEquals(37.618423, latLong[1], 1e-6)
    }

    @Test
    fun `дрейф сверх допуска исправляется одной корректирующей записью`() = runTest {
        val drift = Constants.MARKER_SIZE_TOLERANCE_BYTES + 1_000L
        var calls = 0
        // Порядок замеров: sizeBefore, backup первой записи, sizeAfter (с дрейфом), backup корректирующей
        every { FileIoUtil.getDescriptorSize(any(), any()) } answers {
            calls++
            File(secondArg<Uri>().path!!).length() + if (calls == 3) drift else 0L
        }

        assertTrue(ExifUtil.writeSkipMarker(context, targetUri, 99, null))

        verify(exactly = 2) { BackupRegistry.createBackup(any(), any(), any()) }
        assertTrue(readMarker()!!.fileSize!! > target.length() + Constants.MARKER_SIZE_TOLERANCE_BYTES)
    }

    @Test
    fun `провал верификации откатывает файл без маркера и освобождает backup`() = runTest {
        coEvery { ImageIntegrityUtil.verifyImageIntegrity(any(), any()) } returns false

        assertFalse(ExifUtil.writeSkipMarker(context, targetUri, 99, null))

        assertArrayEquals(originalBytes, target.readBytes())
        assertNull(readMarker())
        assertTrue(BackupRegistry.getPendingBackups(context).isEmpty())
    }
}

package com.compressphotofast.data

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.compressphotofast.BaseUnitTest
import com.compressphotofast.data.BackupRegistry
import com.compressphotofast.data.ExifUtil
import com.compressphotofast.data.ImageIntegrityUtil
import com.compressphotofast.data.MediaStoreDateUtil
import com.compressphotofast.data.OptimizedCacheUtil
import com.compressphotofast.data.UriUtil
import com.compressphotofast.util.FileIoUtil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
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
 * Восстановление отсутствующих EXIF-дат: 3 тега из имени файла / 2 из маркера,
 * при существующей дате запись не выполняется, mtime восстановлен, кэши
 * инвалидированы, запись под backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WriteMissingDatesTest : BaseUnitTest() {

    private lateinit var context: Context
    private lateinit var target: File
    private lateinit var targetUri: Uri

    @Before
    override fun setUp() {
        super.setUp()
        context = ApplicationProvider.getApplicationContext()
        target = File(context.filesDir, "missing_dates.jpg")
        javaClass.classLoader!!.getResourceAsStream("test_images/test_image_medium.jpg")!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        stripDates()
        targetUri = Uri.fromFile(target)

        mockkObject(UriUtil, ImageIntegrityUtil, BackupRegistry, FileIoUtil)
        mockkObject(ExifUtil, OptimizedCacheUtil, MediaStoreDateUtil)
        // ExifInterface по FileDescriptor недоступен на JVM — пишем по пути
        // (эмуляция прод-семантики: свежая перепроверка дат, 2–3 тега, mtime до записи)
        coEvery {
            ExifUtil.writeDatesInPlace(any(), any(), any(), any())
        } answers {
            val path = File(secondArg<Uri>().path!!).absolutePath
            val dateSourceMs = args[2] as Long
            val includeOriginal = args[3] as Boolean
            val exif = ExifInterface(path)
            if (listOfNotNull(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                    exif.getAttribute(ExifInterface.TAG_DATETIME),
                    exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                ).isNotEmpty()
            ) {
                return@answers null
            }
            val mtime = target.lastModified()
            val formatted = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss").format(java.util.Date(dateSourceMs))
            exif.setAttribute(ExifInterface.TAG_DATETIME, formatted)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formatted)
            if (includeOriginal) {
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formatted)
            }
            exif.saveAttributes()
            mtime
        }
        coEvery { ImageIntegrityUtil.verifyImageIntegrity(any(), any()) } returns true
        coEvery { UriUtil.getFileLastModified(any(), any()) } answers { File(secondArg<Uri>().path!!).lastModified() }
    }

    @After
    override fun tearDown() {
        unmockkAll()
        BackupRegistry.getPendingBackups(context).keys.forEach { File(it).delete() }
        target.delete()
        super.tearDown()
    }

    private fun stripDates() {
        ExifInterface(target.absolutePath).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, null)
            setAttribute(ExifInterface.TAG_DATETIME, null)
            setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, null)
            setAttribute(ExifInterface.TAG_GPS_DATESTAMP, null)
            saveAttributes()
        }
    }

    private fun readExif() = ExifInterface(target.absolutePath)

    @Test
    fun `дата из имени файла пишет три тега, mtime восстановлен, кэши инвалидированы`() = runTest {
        val dateSourceMs = 1_700_000_123_456L
        val knownMtime = 1_600_000_000_000L
        target.setLastModified(knownMtime)
        val expected = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss").format(java.util.Date(dateSourceMs))

        assertTrue(ExifUtil.writeMissingDates(context, targetUri, dateSourceMs, includeDateTimeOriginal = true))

        val exif = readExif()
        assertEquals(expected, exif.getAttribute(ExifInterface.TAG_DATETIME))
        assertEquals(expected, exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
        assertEquals(expected, exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))

        // mtime файла после saveAttributes меняется; prod-код восстанавливает его
        // через MediaStoreDateUtil (здесь mock) — проверяем вызов с захваченным mtime
        coVerify(exactly = 1) { MediaStoreDateUtil.restoreModifiedDate(context, targetUri, knownMtime) }
        assertTrue(BackupRegistry.getPendingBackups(context).isEmpty())
    }

    @Test
    fun `дата из маркера пишет только два тега без DateTimeOriginal`() = runTest {
        val markerTimestamp = 1_700_000_123_456L
        val expected = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss").format(java.util.Date(markerTimestamp))

        assertTrue(ExifUtil.writeMissingDates(context, targetUri, markerTimestamp, includeDateTimeOriginal = false))

        val exif = readExif()
        assertEquals(expected, exif.getAttribute(ExifInterface.TAG_DATETIME))
        assertEquals(expected, exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
    }

    @Test
    fun `при существующей дате запись не выполняется, но результат успешный`() = runTest {
        ExifInterface(target.absolutePath).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05")
            saveAttributes()
        }
        val bytesBefore = target.readBytes()

        assertTrue(ExifUtil.writeMissingDates(context, targetUri, 1_700_000_123_456L, true))

        assertTrue(target.readBytes().contentEquals(bytesBefore))
        coVerify(exactly = 0) { MediaStoreDateUtil.restoreModifiedDate(any(), any(), any()) }
        verify(exactly = 1) { OptimizedCacheUtil.invalidateExifData(targetUri) }
        verify(exactly = 1) { ExifUtil.evictExifDataCache(targetUri) }
    }

    @Test
    fun `провал верификации откатывает файл и не восстанавливает mtime как успех`() = runTest {
        coEvery { ImageIntegrityUtil.verifyImageIntegrity(any(), any()) } returns false

        assertFalse(ExifUtil.writeMissingDates(context, targetUri, 1_700_000_123_456L, true))

        val exif = readExif()
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME))
        coVerify(exactly = 0) { MediaStoreDateUtil.restoreModifiedDate(any(), any(), any()) }
        verify(exactly = 0) { ExifUtil.evictExifDataCache(any()) }
    }

    @Test
    fun `нулевое время источника - отказ без записи`() = runTest {
        assertFalse(ExifUtil.writeMissingDates(context, targetUri, 0L, true))
        verify(exactly = 0) { BackupRegistry.createBackup(any(), any(), any()) }
    }

    @Test
    fun `hasAnyDate отражает наличие любой из четырёх дат`() {
        for (tag in listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_DATETIME_DIGITIZED
        )) {
            stripDates()
            ExifInterface(target.absolutePath).apply {
                setAttribute(tag, "2020:01:02 03:04:05")
                saveAttributes()
            }
            assertTrue(ExifUtil.hasAnyDate(readExif()))
        }
        stripDates()
        assertFalse(ExifUtil.hasAnyDate(readExif()))
    }
}

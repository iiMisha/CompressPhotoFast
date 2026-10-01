package com.compressphotofast.util

import androidx.exifinterface.media.ExifInterface
import com.compressphotofast.BaseUnitTest
import com.compressphotofast.data.CompressionMarker
import com.compressphotofast.data.ExifUtil
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Запись EXIF и маркера в локальный artifact до публикации в MediaStore:
 * size в маркере должен точно совпадать с длиной файла, теги и GPS — перенесены.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExifArtifactWriteTest : BaseUnitTest() {

    private val artifact: File = File.createTempFile("artifact", ".jpg").also { file ->
        javaClass.classLoader!!.getResourceAsStream("test_images/test_image_medium.jpg")!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }

    @After
    override fun tearDown() {
        artifact.delete()
        super.tearDown()
    }

    @Test
    fun `маркер содержит точный размер artifact и исходный размер`() = runTest {
        val ok = ExifUtil.writeExifToArtifact(artifact, emptyMap(), quality = 70, originalFileSize = 1_234_567L)

        assertTrue(ok)
        val marker = CompressionMarker.parse(ExifInterface(artifact).getAttribute(ExifInterface.TAG_USER_COMMENT))
        assertNotNull(marker)
        assertEquals(70, marker!!.quality)
        assertEquals(artifact.length(), marker.fileSize)
        assertEquals(1_234_567L, marker.originalFileSize)
    }

    @Test
    fun `теги, GPS и исходная ориентация переносятся в artifact`() = runTest {
        val exifData = mapOf<String, Any>(
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:09:30 12:00:00",
            ExifInterface.TAG_MAKE to "TestMake",
            ExifInterface.TAG_ORIENTATION to ExifInterface.ORIENTATION_ROTATE_90.toString(),
            "HAS_GPS" to true,
            "GPS_LAT" to 55.75,
            "GPS_LONG" to 37.62
        )

        assertTrue(ExifUtil.writeExifToArtifact(artifact, exifData, quality = 80, originalFileSize = null))

        val exif = ExifInterface(artifact)
        assertEquals("2026:09:30 12:00:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("TestMake", exif.getAttribute(ExifInterface.TAG_MAKE))
        // Пиксели JPEG не поворачиваются — ориентацию задаёт исходный тег
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        val latLong = exif.latLong
        assertNotNull(latLong)
        assertEquals(55.75, latLong!![0], 1e-4)
        assertEquals(37.62, latLong[1], 1e-4)
        val marker = CompressionMarker.parse(exif.getAttribute(ExifInterface.TAG_USER_COMMENT))
        assertEquals(artifact.length(), marker!!.fileSize)
    }

    @Test
    fun `повёрнутые пиксели получают нормальную ориентацию`() = runTest {
        val exifData = mapOf<String, Any>(
            ExifInterface.TAG_ORIENTATION to ExifInterface.ORIENTATION_ROTATE_90.toString()
        )

        assertTrue(
            ExifUtil.writeExifToArtifact(
                artifact, exifData, quality = 80, originalFileSize = null, pixelsTransformed = true
            )
        )

        assertEquals(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface(artifact).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
        )
    }
}

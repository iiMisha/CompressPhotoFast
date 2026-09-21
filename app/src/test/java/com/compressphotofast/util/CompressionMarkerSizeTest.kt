package com.compressphotofast.util

import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.compressphotofast.BaseUnitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit тесты формата маркера сжатия с размером файла
 * CompressPhotoFast_Compressed:quality:timestamp:size
 *
 * Тестирует:
 * - Парсинг нового формата маркера с размером
 * - Обратную совместимость со старым форматом (без размера)
 * - Обработку заглушки размера (двухфазная запись) и некорректных значений
 * - Двухфазную запись маркера: размер в маркере совпадает с фактическим размером файла
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CompressionMarkerSizeTest : BaseUnitTest() {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun createTestJpeg(): File =
        TestImageGenerator.generateTestImage(width = 64, height = 64, quality = 90)

    private fun writeMarkerComment(file: File, userComment: String) {
        // ExifInterface(FileDescriptor) не работает под Robolectric (shadow fd),
        // используем путь к файлу — реальный файловый ввод-вывод
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, userComment)
        exif.saveAttributes()
    }

    /**
     * Новый формат маркера: размер парсится из четвёртой компоненты
     */
    @Test
    fun `getCompressionMarker parses size from new marker format`() {
        val file = createTestJpeg()
        val uri = Uri.fromFile(file)
        writeMarkerComment(file, "CompressPhotoFast_Compressed:85:1704067200000:000000012345678")

        val marker = kotlinx.coroutines.runBlocking {
            ExifUtil.getCompressionMarker(context, uri)
        }

        assertTrue(marker.isCompressed)
        assertEquals(85, marker.quality)
        assertEquals(1704067200000L, marker.timestamp)
        assertEquals("Size should be parsed without leading zeros", 12345678L, marker.fileSize)
    }

    /**
     * Старый формат маркера (без размера): fileSize == null — доверяем маркеру
     */
    @Test
    fun `getCompressionMarker returns null size for legacy marker`() {
        val file = createTestJpeg()
        val uri = Uri.fromFile(file)
        writeMarkerComment(file, "CompressPhotoFast_Compressed:70:1742629672908")

        val marker = kotlinx.coroutines.runBlocking {
            ExifUtil.getCompressionMarker(context, uri)
        }

        assertTrue("Legacy marker should still be recognized", marker.isCompressed)
        assertEquals(70, marker.quality)
        assertEquals(1742629672908L, marker.timestamp)
        assertNull("Legacy marker has no size", marker.fileSize)
    }

    /**
     * Заглушка размера (нули из фазы 1): fileSize == null — файл не блокируется
     */
    @Test
    fun `getCompressionMarker returns null size for zero placeholder`() {
        val file = createTestJpeg()
        val uri = Uri.fromFile(file)
        writeMarkerComment(file, "CompressPhotoFast_Compressed:85:1704067200000:000000000000000")

        val marker = kotlinx.coroutines.runBlocking {
            ExifUtil.getCompressionMarker(context, uri)
        }

        assertTrue(marker.isCompressed)
        assertNull("Zero placeholder should be treated as unknown size", marker.fileSize)
    }

    /**
     * Некорректное значение размера: fileSize == null
     */
    @Test
    fun `getCompressionMarker returns null size for garbage size field`() {
        val file = createTestJpeg()
        val uri = Uri.fromFile(file)
        writeMarkerComment(file, "CompressPhotoFast_Compressed:85:1704067200000:notanumber")

        val marker = kotlinx.coroutines.runBlocking {
            ExifUtil.getCompressionMarker(context, uri)
        }

        assertTrue(marker.isCompressed)
        assertNull("Garbage size should be treated as unknown", marker.fileSize)
    }

    /**
     * Валидация кэша EXIF по размеру: изменение размера инвалидирует кэш,
     * копирование (размер сохраняется) — нет
     */
    @Test
    fun `CachedExifData isStaleFor detects size change only`() {
        val cached = OptimizedCacheUtil.CachedExifData(
            isCompressed = true,
            quality = 85,
            compressionTimestamp = 1704067200000L,
            markerFileSize = 1000L,
            cachedFileSize = 1000L
        )

        assertFalse("Same size should not be stale", cached.isStaleFor(1000L))
        assertTrue("Changed size should be stale", cached.isStaleFor(2000L))
        assertFalse("Unknown size (0) should not invalidate cache", cached.isStaleFor(0L))

        val cachedWithoutSize = cached.copy(cachedFileSize = 0L)
        assertFalse("Cache computed with unknown size should not be stale", cachedWithoutSize.isStaleFor(2000L))
    }

    /**
     * Хелпер buildCompressionMarker фиксированной ширины поля размера
     */
    @Test
    fun `marker size field has fixed width`() {
        val buildMethod = ExifUtil.javaClass.getDeclaredMethod(
            "buildCompressionMarker", Int::class.java, Long::class.java, Long::class.javaObjectType
        )
        buildMethod.isAccessible = true

        val placeholder = buildMethod.invoke(ExifUtil, 85, 1704067200000L, null) as String
        val withSize = buildMethod.invoke(ExifUtil, 85, 1704067200000L, 12345678L) as String

        assertEquals("Placeholder and sized marker must have equal length", placeholder.length, withSize.length)
        assertTrue("Placeholder size field must be all zeros", placeholder.endsWith("0".repeat(15)))
        assertTrue("Sized marker must contain padded size", withSize.endsWith("000000012345678"))
        assertFalse("Placeholder must not parse as known size", placeholder.substringAfterLast(":").toLong() > 0)
    }
}

package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.compressphotofast.BaseUnitTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit тесты для функционала HEIC файлов в ExifUtil
 *
 * Тестирует:
 * - Проверку суффикса _compressed для HEIC файлов
 * - Определение маркера сжатия через суффикс в имени
 * - Интеграцию с getCompressionMarker и getCompressionInfo
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29]) // Android 10+
class ExifUtilHeicTest : BaseUnitTest() {

    /**
     * Тест 1: Проверка логики hasHeicCompressedSuffix через mock
     *
     * Проверяет различные варианты имен файлов:
     * - image_compressed.heic - имеет суффикс
     * - image.heic - не имеет суффикса
     * - image.HEIC - не имеет суффикса (uppercase)
     * - photo_compressed.heif - имеет суффикс
     */
    @Test
    fun `test hasHeicCompressedSuffix logic through reflection`() {
        // Создаем экземпляр ExifUtil через отражение для тестирования private метода

        val exifUtilClass = ExifUtil.javaClass
        val hasHeicCompressedSuffixMethod = exifUtilClass.getDeclaredMethod(
            "hasHeicCompressedSuffix",
            String::class.java
        )
        hasHeicCompressedSuffixMethod.isAccessible = true

        // Test Case 1: HEIC файл с суффиксом _compressed
        val result1 = hasHeicCompressedSuffixMethod.invoke(
            ExifUtil,
            "image_compressed.heic"
        ) as Boolean
        assertTrue("image_compressed.heic should have _compressed suffix", result1)

        // Test Case 2: HEIC файл без суффикса
        val result2 = hasHeicCompressedSuffixMethod.invoke(
            ExifUtil,
            "image.heic"
        ) as Boolean
        assertFalse("image.heic should not have _compressed suffix", result2)

        // Test Case 3: HEIC файл в uppercase
        val result3 = hasHeicCompressedSuffixMethod.invoke(
            ExifUtil,
            "IMAGE.HEIC"
        ) as Boolean
        assertFalse("IMAGE.HEIC (uppercase) should not have _compressed suffix", result3)

        // Test Case 4: HEIF файл с суффиксом
        val result4 = hasHeicCompressedSuffixMethod.invoke(
            ExifUtil,
            "photo_compressed.heif"
        ) as Boolean
        assertTrue("photo_compressed.heif should have _compressed suffix", result4)

        // Test Case 5: HEIF файл без суффикса
        val result5 = hasHeicCompressedSuffixMethod.invoke(
            ExifUtil,
            "photo.heif"
        ) as Boolean
        assertFalse("photo.heif should not have _compressed suffix", result5)

        // Test Case 6: null значение
        val result6 = hasHeicCompressedSuffixMethod.invoke(
            ExifUtil,
            null
        ) as Boolean
        assertFalse("null should return false", result6)

        // Test Case 7: Пустая строка
        val result7 = hasHeicCompressedSuffixMethod.invoke(
            ExifUtil,
            ""
        ) as Boolean
        assertFalse("empty string should return false", result7)

        // Test Case 8: JPEG файл (не HEIC)
        val result8 = hasHeicCompressedSuffixMethod.invoke(
            ExifUtil,
            "image_compressed.jpg"
        ) as Boolean
        assertFalse("image_compressed.jpg should not match HEIC/HEIF pattern", result8)
    }

    /**
     * Тест 2: Проверка isHeicFile через mock UriUtil.getMimeType
     *
     * Тестирует определение HEIC файлов по MIME типу
     */
    @Test
    fun `test isHeicFile with different MIME types`() {
        val exifUtilClass = ExifUtil.javaClass
        val isHeicFileMethod = exifUtilClass.getDeclaredMethod(
            "isHeicFile",
            Context::class.java,
            Uri::class.java
        )
        isHeicFileMethod.isAccessible = true

        val mockContext = mockk<Context>()

        // Test Case 1: image/heic
        val uri1 = Uri.parse("content://media/external/images/media/1")
        every { mockContext.contentResolver.getType(uri1) } returns "image/heic"

        val result1 = isHeicFileMethod.invoke(ExifUtil, mockContext, uri1) as Boolean
        assertTrue("image/heic should be recognized as HEIC", result1)

        // Test Case 2: image/heif
        val uri2 = Uri.parse("content://media/external/images/media/2")
        every { mockContext.contentResolver.getType(uri2) } returns "image/heif"

        val result2 = isHeicFileMethod.invoke(ExifUtil, mockContext, uri2) as Boolean
        assertTrue("image/heif should be recognized as HEIC", result2)

        // Test Case 3: image/jpeg
        val uri3 = Uri.parse("content://media/external/images/media/3")
        every { mockContext.contentResolver.getType(uri3) } returns "image/jpeg"

        val result3 = isHeicFileMethod.invoke(ExifUtil, mockContext, uri3) as Boolean
        assertFalse("image/jpeg should not be recognized as HEIC", result3)

        // Test Case 4: image/png
        val uri4 = Uri.parse("content://media/external/images/media/4")
        every { mockContext.contentResolver.getType(uri4) } returns "image/png"

        val result4 = isHeicFileMethod.invoke(ExifUtil, mockContext, uri4) as Boolean
        assertFalse("image/png should not be recognized as HEIC", result4)

        // Test Case 5: null MIME type
        val uri5 = Uri.parse("content://media/external/images/media/5")
        every { mockContext.contentResolver.getType(uri5) } returns null

        val result5 = isHeicFileMethod.invoke(ExifUtil, mockContext, uri5) as Boolean
        assertFalse("null MIME type should not be recognized as HEIC", result5)

        // Test Case 6: Uppercase MIME type
        val uri6 = Uri.parse("content://media/external/images/media/6")
        every { mockContext.contentResolver.getType(uri6) } returns "IMAGE/HEic"

        val result6 = isHeicFileMethod.invoke(ExifUtil, mockContext, uri6) as Boolean
        assertTrue("IMAGE/HEic (mixed case) should be recognized as HEIC", result6)
    }

    /**
     * Тест 3: Интеграционный тест getCompressionMarker для HEIC с суффиксом
     *
     * Проверяет, что getCompressionMarker правильно определяет HEIC файлы
     * с суффиксом _compressed как сжатые
     */
    @Test
    fun `test getCompressionMarker detects HEIC with compressed suffix`() {
        val mockContext = mockk<Context>()
        val heicUri = Uri.parse("content://media/external/images/media/100")

        // Настраиваем mock для HEIC файла с суффиксом _compressed
        every { mockContext.contentResolver.getType(heicUri) } returns "image/heic"

        // Создаем mock для MediaStore query
        val mockCursor = mockk<android.database.Cursor>()
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "photo_compressed.heic"
        every { mockCursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED) } returns 1
        every { mockCursor.isNull(1) } returns false
        every { mockCursor.getLong(1) } returns 1704067200L // seconds

        every {
            mockContext.contentResolver.query(
                heicUri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_MODIFIED),
                null,
                null,
                null
            )
        } returns mockCursor

        every { mockCursor.close() } returns Unit

        // Act
        val (isCompressed, quality, timestamp) = ExifUtil.getCompressionMarker(mockContext, heicUri)

        // Assert
        assertTrue("HEIC file with _compressed suffix should be marked as compressed", isCompressed)
        assertEquals("Quality should be default 85 for HEIC with suffix", 85, quality)
        assertEquals("Timestamp should be DATE_MODIFIED * 1000", 1704067200000L, timestamp)

        verify {
            mockContext.contentResolver.query(
                heicUri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_MODIFIED),
                null,
                null,
                null
            )
        }
        verify { mockCursor.close() }
    }

    /**
     * Тест 4: getCompressionMarker для HEIC БЕЗ суффикса
     *
     * Проверяет, что HEIC файл без суффикса не распознается как сжатый
     * (попадет в стандартную проверку EXIF)
     */
    @Test
    fun `test getCompressionMarker for HEIC without compressed suffix`() {
        val mockContext = mockk<Context>()
        val heicUri = Uri.parse("content://media/external/images/media/101")

        // Настраиваем mock для HEIC файла БЕЗ суффикса
        every { mockContext.contentResolver.getType(heicUri) } returns "image/heic"

        // Создаем mock cursor
        val mockCursor = mockk<android.database.Cursor>()
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "photo.heic" // БЕЗ суффикса
        every { mockCursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED) } returns 1
        every { mockCursor.isNull(1) } returns false
        every { mockCursor.getLong(1) } returns 1704067200L

        every {
            mockContext.contentResolver.query(
                heicUri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_MODIFIED),
                null,
                null,
                null
            )
        } returns mockCursor

        every { mockCursor.close() } returns Unit

        // Act
        val (isCompressed, quality, timestamp) = ExifUtil.getCompressionMarker(mockContext, heicUri)

        // Assert
        assertFalse("HEIC file without _compressed suffix should not be marked as compressed", isCompressed)
        assertEquals("Quality should be -1 when not compressed", -1, quality)
        assertEquals("Timestamp should be 0 when not compressed", 0L, timestamp)
    }

    /**
     * Тест 5: getCompressionMarker для JPEG файла (не HEIC)
     *
     * Проверяет, что JPEG файлы обрабатываются стандартным способом (через EXIF)
     */
    @Test
    fun `test getCompressionMarker for JPEG files uses standard EXIF check`() {
        val mockContext = mockk<Context>()
        val jpegUri = Uri.parse("content://media/external/images/media/102")

        // JPEG файл - не должен проверять суффикс
        every { mockContext.contentResolver.getType(jpegUri) } returns "image/jpeg"

        // Так как JPEG, cursor не будет вызван для проверки displayName
        // Вместо этого будет попытка открыть EXIF (который провалится в unit тесте)
        // Мы ожидаем, что getCompressionMarker вернет (false, -1, 0)

        val (isCompressed, quality, timestamp) = ExifUtil.getCompressionMarker(mockContext, jpegUri)

        // Assert
        assertFalse("JPEG without EXIF marker should not be compressed", isCompressed)
        assertEquals("Quality should be -1", -1, quality)
        assertEquals("Timestamp should be 0", 0L, timestamp)

        // Проверяем, что getType был вызван для определения MIME типа
        verify { mockContext.contentResolver.getType(jpegUri) }
    }

    /**
     * Тест 6: getCompressionMarker для HEIF с суффиксом (uppercase)
     *
     * Проверяет работу с HEIF форматом (HEIF = HEIC image container)
     */
    @Test
    fun `test getCompressionMarker detects HEIF with compressed suffix`() {
        val mockContext = mockk<Context>()
        val heifUri = Uri.parse("content://media/external/images/media/103")

        every { mockContext.contentResolver.getType(heifUri) } returns "image/heif"

        val mockCursor = mockk<android.database.Cursor>()
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "image_compressed.HEIF" // uppercase
        every { mockCursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED) } returns 1
        every { mockCursor.isNull(1) } returns false
        every { mockCursor.getLong(1) } returns 1704067200L

        every {
            mockContext.contentResolver.query(
                heifUri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_MODIFIED),
                null,
                null,
                null
            )
        } returns mockCursor

        every { mockCursor.close() } returns Unit

        // Act
        val (isCompressed, quality, timestamp) = ExifUtil.getCompressionMarker(mockContext, heifUri)

        // Assert
        assertTrue("HEIF file with _compressed suffix should be marked as compressed", isCompressed)
        assertEquals("Quality should be default 85", 85, quality)
        assertEquals("Timestamp should be correct", 1704067200000L, timestamp)
    }

    /**
     * Тест 7: getCompressionMarker для HEIC с null DATE_MODIFIED
     *
     * Проверяет обработку случая, когда DATE_MODIFIED отсутствует
     */
    @Test
    fun `test getCompressionMarker for HEIC with null DATE_MODIFIED`() {
        val mockContext = mockk<Context>()
        val heicUri = Uri.parse("content://media/external/images/media/104")

        every { mockContext.contentResolver.getType(heicUri) } returns "image/heic"

        val mockCursor = mockk<android.database.Cursor>()
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME) } returns 0
        every { mockCursor.getString(0) } returns "photo_compressed.heic"
        every { mockCursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED) } returns -1 // column doesn't exist

        every {
            mockContext.contentResolver.query(
                heicUri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_MODIFIED),
                null,
                null,
                null
            )
        } returns mockCursor

        every { mockCursor.close() } returns Unit

        // Act
        val (isCompressed, quality, timestamp) = ExifUtil.getCompressionMarker(mockContext, heicUri)

        // Assert
        assertTrue("Should be marked as compressed", isCompressed)
        assertEquals("Quality should be default 85", 85, quality)
        // Когда DATE_MODIFIED отсутствует, используем System.currentTimeMillis()
        // Это трудно проверить точно, так что проверяем только что > 0
        assertTrue("Timestamp should be positive (current time)", timestamp > 0)
    }

    /**
     * Тест 8: Различные варианты имен HEIC файлов
     *
     * Проверяет работу с различными вариантами имен файлов
     */
    @Test
    fun `test getCompressionMarker with various HEIC filename patterns`() {
        val testCases = mapOf(
            "IMG_2024_compressed.heic" to true,
            "IMG_2024.heic" to false,
            "20240131_175449_compressed.heic" to true,
            "20240131_175449.heic" to false,
            "photo_test_compressed.heic" to true,
            "photo-test.heic" to false,
            "MY_PHOTO_compressed.HEIC" to true, // uppercase extension
            "MY_PHOTO.HEIC" to false,
            "image_compressed_compressed.heic" to true, // double suffix
            "image.heic.jpg" to false // not HEIC
        )

        val mockContext = mockk<Context>()

        testCases.forEach { (fileName, shouldBeCompressed) ->
            val uri = Uri.parse("content://media/external/images/media/${fileName.hashCode()}")

            if (fileName.endsWith(".heic", ignoreCase = true)) {
                every { mockContext.contentResolver.getType(uri) } returns "image/heic"

                val mockCursor = mockk<android.database.Cursor>()
                every { mockCursor.moveToFirst() } returns true
                every { mockCursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME) } returns 0
                every { mockCursor.getString(0) } returns fileName
                every { mockCursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED) } returns 1
                every { mockCursor.isNull(1) } returns false
                every { mockCursor.getLong(1) } returns 1704067200L
                every { mockCursor.close() } returns Unit

                every {
                    mockContext.contentResolver.query(
                        uri,
                        arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_MODIFIED),
                        null,
                        null,
                        null
                    )
                } returns mockCursor
            }

            val (isCompressed, _, _) = ExifUtil.getCompressionMarker(mockContext, uri)

            assertEquals(
                "File '$fileName' compression status",
                shouldBeCompressed,
                isCompressed
            )
        }
    }
}

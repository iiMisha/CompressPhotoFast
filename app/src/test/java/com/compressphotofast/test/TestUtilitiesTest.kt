package com.compressphotofast.test

import com.compressphotofast.util.TestImageGenerator
import com.compressphotofast.util.TestUtilities
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Test
import org.junit.Assert.*
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit тесты для утилит генерации и проверки тестовых данных.
 *
 * Вынесены в отдельный пакет чтобы избежать проблем со статическими
 * инициализаторами из пакета util.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TestUtilitiesTest {

    @Test
    fun `test image generator creates valid file`() {
        val testFile = TestImageGenerator.generateMediumImage()

        try {
            assertTrue("Сгенерированный файл должен существовать", testFile.exists())
            assertTrue("Размер файла должен быть больше 0", testFile.length() > 0)
            assertTrue("Размер файла должен быть больше 10 КБ", testFile.length() > 10 * 1024)
        } finally {
            testFile.delete()
        }
    }

    @Test
    fun `test small image generator creates file under 100KB`() {
        val smallFile = TestImageGenerator.generateSmallImage()

        try {
            assertTrue("Маленький файл должен существовать", smallFile.exists())
            assertTrue("Размер маленького файла должен быть меньше 100 КБ",
                smallFile.length() < 100 * 1024)
        } finally {
            smallFile.delete()
        }
    }

    @Test
    fun `test large image generator creates big file`() {
        val largeFile = TestImageGenerator.generateLargeImage()
        val mediumFile = TestImageGenerator.generateMediumImage()

        try {
            assertTrue("Большой файл должен существовать", largeFile.exists())
            assertTrue("Большой файл должен быть больше среднего",
                largeFile.length() > mediumFile.length())
        } finally {
            largeFile.delete()
            mediumFile.delete()
        }
    }

    @Test
    fun `test PNG image generator creates PNG file`() {
        val pngFile = TestImageGenerator.generatePngImage()

        try {
            assertTrue("PNG файл должен существовать", pngFile.exists())
            assertTrue("PNG файл должен иметь расширение .png",
                pngFile.name.endsWith(".png"))
        } finally {
            pngFile.delete()
        }
    }

    @Test
    fun `test URI creation from file`() {
        val testFile = TestImageGenerator.generateMediumImage()
        val testUri = TestImageGenerator.createTestUri(testFile)

        try {
            assertNotNull("URI не должен быть null", testUri)
            assertTrue("URI должен быть file:// схемой",
                testUri.toString().startsWith("file://"))
        } finally {
            testFile.delete()
        }
    }

    @Test
    fun `test utilities create mock URI`() {
        val mockUri = TestUtilities.createMockUri("content", "/test/image.jpg")

        assertNotNull("Mock URI не должен быть null", mockUri)
        assertEquals("URI должен иметь content схему", "content", mockUri.scheme)
        assertTrue("URI должен содержать путь", mockUri.path?.endsWith("image.jpg") == true)
    }

    @Test
    fun `test utilities create temp file`() {
        val tempFile = TestUtilities.createTempFile("test", ".jpg")

        try {
            assertTrue("Временный файл должен существовать", tempFile.exists())
            assertTrue("Временный файл должен быть больше 0 байт", tempFile.length() > 0)
            assertTrue("Временный файл должен иметь указанное расширение",
                tempFile.name.endsWith(".jpg"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `test utilities create file of specific size`() {
        val expectedSize = 50 * 1024 // 50 КБ
        val testFile = TestUtilities.createTestFileOfSize(expectedSize)

        try {
            assertTrue("Файл должен существовать", testFile.exists())
            assertTrue("Размер файла должен быть около ожидаемого",
                TestUtilities.assertFileSize(testFile, expectedSize.toLong(), 100))
        } finally {
            testFile.delete()
        }
    }

    @Test
    fun `test utilities create content URI`() {
        val contentUri = TestUtilities.createContentUri(12345)

        assertNotNull("Content URI не должен быть null", contentUri)
        assertEquals("URI должен иметь content схему", "content", contentUri.scheme)
        assertTrue("URI должен заканчиваться ID", contentUri.lastPathSegment == "12345")
    }

    @Test
    fun `test utilities create file URI`() {
        val fileUri = TestUtilities.createFileUri("/sdcard/test/image.jpg")

        assertNotNull("File URI не должен быть null", fileUri)
        assertEquals("URI должен иметь file схему", "file", fileUri.scheme)
    }

    @Test
    fun `test utilities check valid test file`() {
        val validFile = TestImageGenerator.generateMediumImage()

        try {
            assertTrue("Файл должен быть валидным",
                TestUtilities.isValidTestFile(validFile, 1024))
            assertFalse("Null файл не должен быть валидным",
                TestUtilities.isValidTestFile(null))
        } finally {
            validFile.delete()
        }
    }

    @Test
    fun `test utilities check image file`() {
        val imageFile = TestImageGenerator.generateMediumImage()
        val tempFile = TestUtilities.createTempFile("test", ".txt")

        try {
            assertTrue("JPEG файл должен быть изображением",
                TestUtilities.isImageFile(imageFile))
            assertFalse("TXT файл не должен быть изображением",
                TestUtilities.isImageFile(tempFile))
            assertFalse("Null не должен быть изображением",
                TestUtilities.isImageFile(null))
        } finally {
            imageFile.delete()
            tempFile.delete()
        }
    }

    @Test
    fun `test utilities cleanup temp files`() {
        val file1 = TestUtilities.createTempFile("test1", ".tmp")
        val file2 = TestUtilities.createTempFile("test2", ".tmp")

        assertTrue("Файл 1 должен существовать до очистки", file1.exists())
        assertTrue("Файл 2 должен существовать до очистки", file2.exists())

        TestUtilities.cleanupTempFiles(file1, file2)

        assertFalse("Файл 1 не должен существовать после очистки", file1.exists())
        assertFalse("Файл 2 не должен существовать после очистки", file2.exists())
    }

    @Test
    fun `test utilities create test directory`() {
        val testDir = TestUtilities.createTestDirectory("test_images")

        assertTrue("Тестовая директория должна существовать", testDir.exists())
        assertTrue("Тестовая директория должна быть папкой", testDir.isDirectory)
        assertTrue("Тестовая директория должна быть доступна для записи", testDir.canWrite())
    }
}

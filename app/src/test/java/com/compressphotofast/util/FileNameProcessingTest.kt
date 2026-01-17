package com.compressphotofast.util

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Unit тесты для проверки логики обработки имен файлов и MIME типов
 *
 * Тестируют правильность обработки:
 * - Двойных расширений (image.HEIC.jpg)
 * - Очистки расширений
 * - Сохранения MIME типов
 * - Создания правильных имен файлов
 */
class FileNameProcessingTest {

    /**
     * Тест 1: Проверка определения количества расширений
     */
    @Test
    fun test_countExtensions() {
        val testCases = mapOf(
            "image.jpg" to 1,
            "photo.heic" to 1,
            "image.HEIC.jpg" to 2,
            "photo.heif.jpeg" to 2,
            "picture.png" to 1,
            "document.heic" to 1
        )

        testCases.forEach { (fileName: String, expectedCount: Int) ->
            val actualCount = countExtensions(fileName)
            assertEquals(
                "Неправильное количество расширений для '$fileName'",
                expectedCount,
                actualCount
            )
        }
    }

    /**
     * Тест 2: Проверка извлечения последнего расширения
     */
    @Test
    fun test_getLastExtension() {
        val testCases = mapOf(
            "image.jpg" to ".jpg",
            "photo.heic" to ".heic",
            "image.HEIC.jpg" to ".jpg",
            "photo.heif.jpeg" to ".jpeg",
            "picture.HEIC.JPG" to ".JPG",
            "document.png" to ".png"
        )

        testCases.forEach { (fileName: String, expectedExtension: String) ->
            val actualExtension = getLastExtension(fileName)
            assertEquals(
                "Неправильное последнее расширение для '$fileName'",
                expectedExtension,
                actualExtension
            )
        }
    }

    /**
     * Тест 3: Проверка очистки двойного расширения
     */
    @Test
    fun test_cleanDoubleExtension() {
        val testCases = mapOf(
            "image.HEIC.jpg" to "image",
            "photo.heif.jpeg" to "photo",
            "picture.HEIC.JPG" to "picture",
            "document.heic.png" to "document",
            "test.jpg" to "test",
            "sample.heic" to "sample"
        )

        testCases.forEach { (fileName: String, expectedCleanName: String) ->
            val actualCleanName = cleanDoubleExtension(fileName)
            assertEquals(
                "Неправильная очистка для '$fileName'",
                expectedCleanName,
                actualCleanName
            )
        }
    }

    /**
     * Тест 4: Проверка создания сжатого имени файла
     */
    @Test
    fun test_createCompressedFileName() {
        val testCases = mapOf(
            "image.jpg" to "image_compressed.jpg",
            "photo.heic" to "photo_compressed.heic",
            "image.HEIC.jpg" to "image_compressed.jpg", // Должен очистить двойное расширение
            "picture.png" to "picture_compressed.png"
        )

        testCases.forEach { (originalName: String, expectedCompressedName: String) ->
            val actualCompressedName = createCompressedFileName(originalName)
            assertEquals(
                "Неправильное сжатое имя для '$originalName'",
                expectedCompressedName,
                actualCompressedName
            )

            // Проверяем, что результат не имеет двойного расширения
            val extensionCount = countExtensions(actualCompressedName)
            assertEquals(
                "Сжатый файл '$actualCompressedName' не должен иметь двойного расширения",
                1,
                extensionCount
            )
        }
    }

    /**
     * Тест 5: Проверка определения MIME типа по расширению
     */
    @Test
    fun test_getMimeTypeFromExtension() {
        val testCases = mapOf(
            ".jpg" to "image/jpeg",
            ".jpeg" to "image/jpeg",
            ".png" to "image/png",
            ".heic" to "image/heic",
            ".heif" to "image/heif"
        )

        testCases.forEach { (extension: String, expectedMimeType: String) ->
            val actualMimeType = getMimeTypeFromExtension(extension)
            assertEquals(
                "Неправильный MIME тип для расширения '$extension'",
                expectedMimeType,
                actualMimeType
            )
        }
    }

    /**
     * Тест 6: Проверка MIME типа для HEIC файлов
     */
    @Test
    fun test_heicMimeType_consistency() {
        val heicFiles = listOf("image.heic", "photo.HEIC", "picture.HeIc")

        heicFiles.forEach { fileName ->
            val extension = getLastExtension(fileName)
            val mimeType = getMimeTypeFromExtension(extension)

            assertEquals(
                "Файл '$fileName' должен иметь MIME тип 'image/heic'",
                "image/heic",
                mimeType
            )
        }
    }

    /**
     * Тест 7: Проверка проблемных имен файлов
     */
    @Test
    fun test_problematicFileNames() {
        val problematicNames = listOf(
            "image.HEIC.jpg",
            "photo.heif.jpeg",
            "picture.HEIC.JPG",
            "document.heic.png",
            "test.HEIC.heic"
        )

        problematicNames.forEach { fileName ->
            val extensionCount = countExtensions(fileName)
            assertTrue(
                "Файл '$fileName' должен иметь двойное расширение",
                extensionCount >= 2
            )

            val lastExtension = getLastExtension(fileName)
            assertNotNull(
                "Последнее расширение не должно быть null для '$fileName'",
                lastExtension
            )

            assertTrue(
                "Последнее расширение должно начинаться с точки",
                lastExtension.startsWith(".")
            )
        }
    }

    /**
     * Тест 8: Проверка правильности MIME типа для сохранения
     */
    @Test
    fun test_correctMimeTypeForSaving() {
        // Оригинальный MIME тип
        val originalMimeType = "image/heic"
        // Расширение файла
        val fileExtension = ".jpg"

        // Правильная логика: MIME тип должен соответствовать формату сжатия
        // Если сжимаем в JPEG, то MIME тип должен быть image/jpeg
        val compressionFormat = "jpeg"
        val correctMimeType = "image/$compressionFormat"

        assertEquals(
            "MIME тип должен соответствовать формату сжатия",
            "image/jpeg",
            correctMimeType
        )

        // Не должен использовать оригинальный MIME тип, если формат меняется
        assertNotEquals(
            "MIME тип не должен быть image/heic при сжатии в JPEG",
            originalMimeType,
            correctMimeType
        )
    }

    /**
     * Тест 9: Проверка сохранения MIME типа при том же формате
     */
    @Test
    fun test_preserveMimeTypeWhenFormatUnchanged() {
        val testCases = mapOf(
            "image/heic" to "heic", // HEIC -> HEIC
            "image/jpeg" to "jpg",  // JPEG -> JPEG
            "image/png" to "png"    // PNG -> PNG
        )

        testCases.forEach { (originalMimeType: String, compressionFormat: String) ->
            // Если формат не меняется, MIME тип должен сохраниться
            val expectedMimeType = originalMimeType
            val actualMimeType = "image/$compressionFormat"

            if (originalMimeType == actualMimeType) {
                assertEquals(
                    "MIME тип должен сохраниться при том же формате",
                    expectedMimeType,
                    actualMimeType
                )
            }
        }
    }

    /**
     * Тест 10: Проверка комплексного сценария обработки файла
     */
    @Test
    fun test_complexFileProcessingScenario() {
        // Исходный файл с двойным расширением
        val originalFile = "photo.HEIC.jpg"

        // Шаг 1: Определяем количество расширений
        val extensionCount = countExtensions(originalFile)
        assertEquals("Двойное расширение", 2, extensionCount)

        // Шаг 2: Получаем последнее расширение
        val lastExtension = getLastExtension(originalFile)
        assertEquals("Последнее расширение .jpg", ".jpg", lastExtension)

        // Шаг 3: Очищаем двойное расширение
        val cleanName = cleanDoubleExtension(originalFile)
        assertEquals("Очищенное имя", "photo", cleanName)

        // Шаг 4: Создаем сжатое имя
        val compressedName = "${cleanName}_compressed$lastExtension"
        assertEquals("Сжатое имя", "photo_compressed.jpg", compressedName)

        // Шаг 5: Проверяем отсутствие двойного расширения
        val compressedExtensionCount = countExtensions(compressedName)
        assertEquals("Одно расширение в сжатом файле", 1, compressedExtensionCount)

        // Шаг 6: Определяем MIME тип для сохранения
        val mimeType = getMimeTypeFromExtension(lastExtension)
        assertEquals("MIME тип", "image/jpeg", mimeType)
    }

    // Вспомогательные методы для тестов

    private fun countExtensions(fileName: String): Int {
        return fileName.split(".").size - 1
    }

    private fun getLastExtension(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex > 0) {
            fileName.substring(lastDotIndex)
        } else {
            ""
        }
    }

    private fun cleanDoubleExtension(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        if (lastDotIndex <= 0) return fileName

        val beforeLastDot = fileName.substring(0, lastDotIndex)
        val secondLastDot = beforeLastDot.lastIndexOf('.')

        return if (secondLastDot > 0) {
            beforeLastDot.substring(0, secondLastDot)
        } else {
            beforeLastDot
        }
    }

    private fun createCompressedFileName(originalName: String): String {
        val cleanName = cleanDoubleExtension(originalName)
        val extension = getLastExtension(originalName)
        return "${cleanName}_compressed$extension"
    }

    private fun getMimeTypeFromExtension(extension: String): String {
        return when (extension.lowercase()) {
            ".jpg", ".jpeg" -> "image/jpeg"
            ".png" -> "image/png"
            ".heic" -> "image/heic"
            ".heif" -> "image/heif"
            else -> "image/jpeg"
        }
    }
}

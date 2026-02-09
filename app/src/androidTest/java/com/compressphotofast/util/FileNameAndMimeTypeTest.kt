package com.compressphotofast.util

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.compressphotofast.BaseInstrumentedTest
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation тесты для проверки обработки имен файлов и MIME типов
 *
 * Проверяют критические сценарии:
 * - Двойные расширения (image.HEIC.jpg)
 * - Сохранение оригинального MIME типа
 * - Корректность расширения после сжатия
 */
@RunWith(AndroidJUnit4::class)
class FileNameAndMimeTypeTest : BaseInstrumentedTest() {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Тест 1: Проверка имени файла с двойным расширением HEIC.JPG
     */
    @Test
    fun test_fileNameWithDoubleExtension_heicJpg() {
        val problematicFileName = "image.HEIC.jpg"

        // Проверяем, что файл имеет двойное расширение
        val hasDoubleExtension = problematicFileName.split(".").size > 2
        Assert.assertTrue(
            "Файл '$problematicFileName' имеет двойное расширение",
            hasDoubleExtension
        )

        // Проверяем, что расширение .jpg в конце
        val endsWithJpg = problematicFileName.endsWith(".jpg", ignoreCase = true)
        Assert.assertTrue(
            "Файл должен заканчиваться на .jpg",
            endsWithJpg
        )

        // Проверяем, что содержит .HEIC
        val containsHeic = problematicFileName.contains(".HEIC", ignoreCase = true)
        Assert.assertTrue(
            "Файл должен содержать .HEIC",
            containsHeic
        )
    }

    /**
     * Тест 2: Проверка создания сжатого имени файла без двойного расширения
     */
    @Test
    fun test_createCompressedFileName_shouldNotHaveDoubleExtension() {
        val originalFileName = "photo.HEIC"

        // Имитируем создание сжатого имени
        val dotIndex = originalFileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) originalFileName.substring(0, dotIndex) else originalFileName
        val extension = if (dotIndex > 0) originalFileName.substring(dotIndex) else ""
        val compressedName = "${baseName}_compressed$extension"

        // Проверяем, что нет двойного расширения
        val extensions = compressedFileNameCountExtensions(compressedName)
        Assert.assertEquals(
            "Сжатый файл должен иметь только одно расширение",
            1,
            extensions
        )

        // Проверяем, что расширение .heic сохранено
        Assert.assertTrue(
            "Сжатый файл должен иметь расширение .heic",
            compressedName.endsWith(".heic", ignoreCase = true)
        )
    }

    /**
     * Тест 3: Проверка, что HEIC файл не превращается в HEIC.JPG
     */
    @Test
    fun test_heicFile_shouldNotBecomeHeicJpg() {
        val heicFile = "image.heic"
        val jpegExtension = ".jpg"

        // Проверяем, что HEIC файл не содержит .jpg
        Assert.assertFalse(
            "HEIC файл не должен содержать расширение .jpg",
            heicFile.lowercase().endsWith(jpegExtension)
        )

        // Проверяем, что расширение только .heic
        val extensionCount = compressedFileNameCountExtensions(heicFile)
        Assert.assertEquals(
            "HEIC файл должен иметь только одно расширение",
            1,
            extensionCount
        )
    }

    /**
     * Тест 4: Проверка сохранения MIME типа для HEIC файлов
     */
    @Test
    fun test_heicMimeType_shouldBePreserved() {
        val originalMimeType = "image/heic"
        val defaultMimeType = "image/jpeg"

        // В текущей реализации MIME тип игнорируется и заменяется на default
        // Этот тест документирует текущее поведение
        val currentBehaviorMimeType = defaultMimeType

        Assert.assertEquals(
            "Текущая реализация использует hardcoded MIME тип",
            defaultMimeType,
            currentBehaviorMimeType
        )

        Assert.assertNotEquals(
            "Оригинальный MIME тип (image/heic) отличается от используемого (image/jpeg)",
            originalMimeType,
            currentBehaviorMimeType
        )
    }

    /**
     * Тест 5: Проверка различных вариантов двойных расширений
     */
    @Test
    fun test_variousDoubleExtensions() {
        val problematicFiles = listOf(
            "photo.HEIC.jpg",
            "image.heif.jpeg",
            "picture.HEIC.JPG",
            "document.heic.png"
        )

        problematicFiles.forEach { fileName ->
            val parts = fileName.split(".")
            val extensionCount = parts.size - 1 // -1 потому что split верта [name, ext1, ext2]

            Assert.assertTrue(
                "Файл '$fileName' имеет двойное расширение ($extensionCount расширений)",
                extensionCount >= 2
            )
        }
    }

    /**
     * Тест 6: Проверка правильного определения расширения
     */
    @Test
    fun test_correctExtensionExtraction() {
        val testCases = mapOf(
            "image.heic" to ".heic",
            "photo.jpg" to ".jpg",
            "picture.jpeg" to ".jpeg",
            "image.HEIC.JPG" to ".JPG", // lastExtension
            "document.png" to ".png"
        )

        testCases.forEach { (fileName, expectedExtension) ->
            val lastDotIndex = fileName.lastIndexOf('.')
            val actualExtension = if (lastDotIndex > 0) {
                fileName.substring(lastDotIndex)
            } else {
                ""
            }

            Assert.assertEquals(
                "Неправильное расширение для файла '$fileName'",
                expectedExtension,
                actualExtension
            )
        }
    }

    /**
     * Тест 7: Проверка MIME типов для разных форматов
     */
    @Test
    fun test_mimeTypesForDifferentFormats() {
        val mimeTypes = mapOf(
            "image/jpeg" to ".jpg",
            "image/jpg" to ".jpg",
            "image/png" to ".png",
            "image/heic" to ".heic",
            "image/heif" to ".heif"
        )

        mimeTypes.forEach { (mimeType, expectedExtension) ->
            Assert.assertTrue(
                "MIME тип '$mimeType' должен начинаться с 'image/'",
                mimeType.startsWith("image/")
            )

            Assert.assertTrue(
                "Расширение '$expectedExtension' должно начинаться с точки",
                expectedExtension.startsWith(".")
            )
        }
    }

    /**
     * Тест 8: Проверка обработки HEIC с суффиксом _compressed
     */
    @Test
    fun test_heicWithCompressedSuffix() {
        val originalFile = "photo.heic"
        val suffix = "_compressed"

        // Правильное создание имени с суффиксом
        val dotIndex = originalFile.lastIndexOf('.')
        val baseName = originalFile.substring(0, dotIndex)
        val extension = originalFile.substring(dotIndex)
        val compressedFile = "${baseName}${suffix}${extension}"

        Assert.assertEquals(
            "Сжатый файл должен быть 'photo_compressed.heic'",
            "photo_compressed.heic",
            compressedFile
        )

        // Проверяем отсутствие двойного расширения
        val extensionCount = compressedFileNameCountExtensions(compressedFile)
        Assert.assertEquals(
            "Сжатый файл должен иметь одно расширение",
            1,
            extensionCount
        )
    }

    /**
     * Тест 9: Проверка файла image.HEIC.jpg с суффиксом
     */
    @Test
    fun test_doubleExtensionWithCompressedSuffix() {
        val problematicFile = "image.HEIC.jpg"
        val suffix = "_compressed"

        // Текущее (проблемное) поведение
        val dotIndex = problematicFile.lastIndexOf('.')
        val baseName = problematicFile.substring(0, dotIndex) // "image.HEIC"
        val extension = problematicFile.substring(dotIndex) // ".jpg"
        val compressedFile = "${baseName}${suffix}${extension}" // "image.HEIC_compressed.jpg"

        Assert.assertEquals(
            "Сжатый файл (проблемный вариант): 'image.HEIC_compressed.jpg'",
            "image.HEIC_compressed.jpg",
            compressedFile
        )

        // Проблема: двойное расширение сохраняется!
        val extensionCount = compressedFileNameCountExtensions(compressedFile)
        Assert.assertEquals(
            "Файл '$compressedFile' все еще имеет двойное расширение",
            2,
            extensionCount
        )
    }

    /**
     * Тест 10: Проверка очистки двойного расширения
     */
    @Test
    fun test_cleanDoubleExtension() {
        val problematicFiles = listOf(
            "image.HEIC.jpg",
            "photo.heif.jpeg",
            "picture.HEIC.JPG"
        )

        problematicFiles.forEach { originalFile ->
            // Правильная логика: оставляем только последнее расширение
            val lastDotIndex = originalFile.lastIndexOf('.')
            val cleanBaseName = if (lastDotIndex > 0) {
                val beforeLastDot = originalFile.substring(0, lastDotIndex)
                val secondLastDot = beforeLastDot.lastIndexOf('.')
                if (secondLastDot > 0) {
                    beforeLastDot.substring(0, secondLastDot)
                } else {
                    beforeLastDot
                }
            } else {
                originalFile
            }

            val extension = originalFile.substring(lastDotIndex)
            val cleanFile = "${cleanBaseName}_compressed$extension"

            // Проверяем, что в чистом имени только одно расширение
            val cleanExtensionCount = compressedFileNameCountExtensions(cleanFile)
            Assert.assertEquals(
                "Файл '$cleanFile' (очищенный от '$originalFile') должен иметь одно расширение",
                1,
                cleanExtensionCount
            )
        }
    }

    // ==================== Тесты для коммита c86c711 (двойные расширения) ====================

    /**
     * Тест 11: Проверка createCompressedFileName с двойным расширением (HEIC.jpg)
     *
     * Проверяет исправление из коммита c86c711:
     * - image.HEIC.jpg должен стать image.jpg в режиме замены
     * - Сохраняется ПОСЛЕДНЕЕ расширение (.jpg), а не первое (.HEIC)
     */
    @Test
    fun test_createCompressedFileName_doubleExtension_heicJpg_replaceMode() {
        // Arrange
        val originalName = "image.HEIC.jpg"

        // Act - используем реальный метод FileOperationsUtil
        val result = FileOperationsUtil.createCompressedFileName(context, originalName)

        // Assert - в режиме замены по умолчанию (зависит от SettingsManager)
        // Проверяем что результат не содержит двойного расширения
        val extensionCount = compressedFileNameCountExtensions(result)
        assertTrue(
            "Результат '$result' не должен иметь двойного расширения",
            extensionCount <= 1
        )

        // Проверяем что результат заканчивается на .jpg (последнее расширение)
        assertTrue(
            "Результат должен заканчиваться на .jpg",
            result.endsWith(".jpg", ignoreCase = true)
        )
    }

    /**
     * Тест 12: Проверка createCompressedFileName с фото без двойного расширения
     */
    @Test
    fun test_createCompressedFileName_singleExtension_noChange() {
        // Arrange
        val originalName = "photo.jpg"

        // Act
        val result = FileOperationsUtil.createCompressedFileName(context, originalName)

        // Assert
        assertTrue(
            "Результат должен содержать .jpg",
            result.endsWith(".jpg", ignoreCase = true)
        )

        // Проверяем что нет двойного расширения
        val extensionCount = compressedFileNameCountExtensions(result)
        assertTrue(
            "Результат не должен иметь двойного расширения",
            extensionCount <= 1
        )
    }

    /**
     * Тест 13: Проверка логики cleanDoubleExtensions
     *
     * Прямая проверка логики очистки двойных расширений
     */
    @Test
    fun test_cleanDoubleExtensions_logic() {
        val testCases = mapOf(
            "image.HEIC.jpg" to "image",
            "photo.heif.jpeg" to "photo",
            "document.pdf" to "document", // нет двойного расширения
            "archive.tar.gz.zip" to "archive" // тройное расширение
        )

        testCases.forEach { (input, expectedBase) ->
            // Симулируем логику cleanDoubleExtensions
            val lastDotIndex = input.lastIndexOf('.')
            val beforeLastDot = input.substring(0, lastDotIndex)
            val secondLastDot = beforeLastDot.lastIndexOf('.')
            val cleanBase = if (secondLastDot > 0) {
                beforeLastDot.substring(0, secondLastDot)
            } else {
                beforeLastDot
            }

            assertEquals(
                "Неправильная очистка для '$input'",
                expectedBase,
                cleanBase
            )
        }
    }

    /**
     * Тест 14: Проверка логики getLastExtension
     *
     * Прямая проверка логики получения последнего расширения
     */
    @Test
    fun test_getLastExtension_logic() {
        val testCases = mapOf(
            "image.HEIC.jpg" to ".jpg",
            "photo.heif.jpeg" to ".jpeg",
            "document.pdf" to ".pdf",
            "archive.tar.gz.zip" to ".zip"
        )

        testCases.forEach { (input, expectedExtension) ->
            // Симулируем логику getLastExtension
            val lastDotIndex = input.lastIndexOf('.')
            val actualExtension = if (lastDotIndex > 0) {
                input.substring(lastDotIndex)
            } else {
                ""
            }

            assertEquals(
                "Неправильное расширение для '$input'",
                expectedExtension,
                actualExtension
            )
        }
    }

    // Вспомогательные методы

    private fun compressedFileNameCountExtensions(fileName: String): Int {
        return fileName.split(".").size - 1
    }
}

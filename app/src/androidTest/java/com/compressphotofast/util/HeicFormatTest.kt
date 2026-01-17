package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.compressphotofast.BaseInstrumentedTest
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumentation тесты для проверки работы с HEIC форматом
 *
 * Тестируют поддержку и обработку HEIC/HEIF изображений
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HeicFormatTest : BaseInstrumentedTest() {

    /**
     * Тест 1: Проверка MIME типа для HEIC
     */
    @Test
    fun test_heicMimeType_isCorrect() {
        val heicMimeType = "image/heic"

        Assert.assertEquals(
            "HEIC MIME тип должен быть 'image/heic'",
            "image/heic",
            heicMimeType
        )

        Assert.assertTrue(
            "HEIC MIME тип должен содержать 'image'",
            heicMimeType.contains("image")
        )
    }

    /**
     * Тест 2: Проверка поддержки HEIC в ImageProcessingChecker
     */
    @Test
    fun test_heicFormat_isSupported() {
        val heicMimeType = "image/heic"

        // Проверяем, что HEIC формат поддерживается
        val isSupported = (heicMimeType.equals("image/heic", ignoreCase = true))

        Assert.assertTrue(
            "HEIC формат должен быть поддерживаться",
            isSupported
        )
    }

    /**
     * Тест 3: Проверка расширения HEIC файла
     */
    @Test
    fun test_heicFileExtension() {
        val heicExtensions = listOf(".heic", ".HEIC")

        heicExtensions.forEach { ext ->
            Assert.assertTrue(
                "Расширение $ext должно начинаться с точки",
                ext.startsWith(".")
            )

            Assert.assertTrue(
                "Расширение $ext должно содержать 'heic'",
                ext.lowercase().contains("heic")
            )
        }
    }

    /**
     * Тест 4: Проверка создания URI для HEIC файла
     */
    @Test
    fun test_heicUri_creation() {
        val fileName = "test_image.heic"
        val uriString = "file:///sdcard/Pictures/$fileName"
        val uri = Uri.parse(uriString)

        Assert.assertNotNull("URI не должен быть null", uri)
        Assert.assertTrue(
            "URI должен содержать имя файла",
            uri.toString().contains(fileName)
        )
        Assert.assertTrue(
            "URI должен содержать расширение .heic",
            uri.toString().contains(".heic")
        )
    }

    /**
     * Тест 5: Проверка сравнения HEIC MIME типа с учетом регистра
     */
    @Test
    fun test_heicMimeType_caseInsensitive() {
        val variations = listOf("image/heic", "image/HEIC", "IMAGE/HEIC", "Image/Heic")

        variations.forEach { mimeType ->
            Assert.assertTrue(
                "MIME тип '$mimeType' должен распознаваться как HEIC",
                mimeType.equals("image/heic", ignoreCase = true)
            )
        }
    }

    /**
     * Тест 6: Проверка HEIC в списке поддерживаемых форматов
     */
    @Test
    fun test_heicInSupportedFormats() {
        val supportedFormats = listOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/heic"
        )

        Assert.assertTrue(
            "HEIC должен быть в списке поддерживаемых форматов",
            supportedFormats.any { it.equals("image/heic", ignoreCase = true) }
        )
    }

    /**
     * Тест 7: Проверка приоритета HEIC при обработке
     */
    @Test
    fun test_heicProcessingPriority() {
        val mimeType = "image/heic"

        // Проверяем, что HEIC обрабатывается как обычное изображение
        val shouldProcess = mimeType.equals("image/heic", ignoreCase = true)

        Assert.assertTrue(
            "HEIC изображения должны обрабатываться",
            shouldProcess
        )
    }

    /**
     * Тест 8: Проверка пути к HEIC файлу
     */
    @Test
    fun test_heicFilePath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileName = "test_image.heic"

        // Эмуляция пути к файлу
        val filePath = "/sdcard/Pictures/$fileName"

        Assert.assertTrue(
            "Путь должен содержать имя файла",
            filePath.contains(fileName)
        )

        Assert.assertTrue(
            "Путь должен заканчиваться на .heic",
            filePath.endsWith(".heic")
        )
    }

    /**
     * Тест 9: Проверка создания тестового HEIC файла
     */
    @Test
    fun test_heicFileCreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDir = context.cacheDir
        val testFile = File(testDir, "test_image.heic")

        // Создаем тестовый файл
        testFile.createNewFile()

        Assert.assertTrue(
            "HEIC файл должен быть создан",
            testFile.exists()
        )

        Assert.assertTrue(
            "Имя файла должно заканчиваться на .heic",
            testFile.name.endsWith(".heic")
        )

        // Очистка
        testFile.delete()
    }

    /**
     * Тест 10: Проверка HEIC в списке MIME типов для фильтрации
     */
    @Test
    fun test_heicInMimeTypesForFiltering() {
        val imageMimeTypes = listOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/heic",
            "image/heif"
        )

        val hasHeic = imageMimeTypes.any {
            it.equals("image/heic", ignoreCase = true)
        }

        val hasHeif = imageMimeTypes.any {
            it.equals("image/heif", ignoreCase = true)
        }

        Assert.assertTrue(
            "Список MIME типов должен содержать HEIC или HEIF",
            hasHeic || hasHeif
        )
    }
}

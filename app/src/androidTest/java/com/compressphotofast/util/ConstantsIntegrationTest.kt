package com.compressphotofast.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.compressphotofast.BaseInstrumentedTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation тесты для проверки констант приложения
 *
 * Тестируют значения констант и их корректность
 */
@RunWith(AndroidJUnit4::class)
class ConstantsIntegrationTest : BaseInstrumentedTest() {

    /**
     * Тест 1: Проверка имени файла настроек
     */
    @Test
    fun test_PREF_FILE_NAME_isCorrect() {
        org.junit.Assert.assertEquals(
            "Имя файла настроек должно быть 'compress_photo_prefs'",
            "compress_photo_prefs",
            Constants.PREF_FILE_NAME
        )
    }

    /**
     * Тест 2: Проверка константы оптимального размера файла
     */
    @Test
    fun test_OPTIMUM_FILE_SIZE_isCorrect() {
        org.junit.Assert.assertTrue(
            "Оптимальный размер файла должен быть больше 0",
            Constants.OPTIMUM_FILE_SIZE > 0
        )

        // 0.1 MB = 0.1 * 1024 * 1024 байт = 104857.6
        // Используем примерное равенство из-за потери точности при вычислениях с double
        val expectedSize = 1024L * 1024L / 10L // 104857
        val actualSize = Constants.OPTIMUM_FILE_SIZE.toLong()
        val sizeDiff = Math.abs(expectedSize - actualSize)
        org.junit.Assert.assertTrue(
            "Оптимальный размер должен быть примерно 0.1 MB (допустимая погрешность: 1 байт), ожидается: $expectedSize, фактически: $actualSize",
            sizeDiff <= 1L
        )
    }

    /**
     * Тест 3: Проверка максимально возможного размера файла
     */
    @Test
    fun test_MAX_POSSIBLE_FILE_SIZE_isCorrect() {
        org.junit.Assert.assertTrue(
            "Максимальный размер файла должен быть больше 0",
            Constants.MAX_FILE_SIZE > 0
        )

        org.junit.Assert.assertTrue(
            "Максимальный размер должен быть больше оптимального",
            Constants.MAX_FILE_SIZE > Constants.OPTIMUM_FILE_SIZE
        )
    }

    /**
     * Тест 4: Проверка максимального возраста временного файла
     */
    @Test
    fun test_TEMP_FILE_MAX_AGE_isCorrect() {
        org.junit.Assert.assertTrue(
            "Максимальный возраст временного файла должен быть больше 0",
            Constants.TEMP_FILE_MAX_AGE > 0
        )

        // Обычно это 30 минут в миллисекундах
        org.junit.Assert.assertEquals(
            "Максимальный возраст должен быть 30 минут",
            30 * 60 * 1000L,
            Constants.TEMP_FILE_MAX_AGE
        )
    }

    /**
     * Тест 5: Проверка названия директории приложения
     */
    @Test
    fun test_APP_DIRECTORY_isCorrect() {
        org.junit.Assert.assertNotNull(
            "Название директории не должно быть null",
            Constants.APP_DIRECTORY
        )

        org.junit.Assert.assertTrue(
            "Название директории не должно быть пустым",
            Constants.APP_DIRECTORY.isNotEmpty()
        )

        org.junit.Assert.assertEquals(
            "Директория должна называться 'CompressPhotoFast'",
            "CompressPhotoFast",
            Constants.APP_DIRECTORY
        )
    }

    /**
     * Тест 6: Проверка ID уведомлений
     */
    @Test
    fun test_notificationIds_areCorrect() {
        org.junit.Assert.assertTrue(
            "ID уведомления о сжатии должен быть >= 0",
            Constants.NOTIFICATION_ID_COMPRESSION >= 0
        )

        org.junit.Assert.assertTrue(
            "ID фонового сервиса должен быть >= 0",
            Constants.NOTIFICATION_ID_BACKGROUND_SERVICE >= 0
        )

        // ID должны быть разными
        org.junit.Assert.assertNotEquals(
            "ID уведомлений должны быть разными",
            Constants.NOTIFICATION_ID_COMPRESSION,
            Constants.NOTIFICATION_ID_BACKGROUND_SERVICE
        )
    }

    /**
     * Тест 7: Проверка Actions для Intent
     */
    @Test
    fun test_actionsAreCorrect() {
        org.junit.Assert.assertEquals(
            "Action для обработки изображения",
            "com.compressphotofast.PROCESS_IMAGE",
            Constants.ACTION_PROCESS_IMAGE
        )

        org.junit.Assert.assertEquals(
            "Action для завершения сжатия",
            "com.compressphotofast.ACTION_COMPRESSION_COMPLETED",
            Constants.ACTION_COMPRESSION_COMPLETED
        )

        org.junit.Assert.assertEquals(
            "Action для остановки сервиса",
            "com.compressphotofast.STOP_SERVICE",
            Constants.ACTION_STOP_SERVICE
        )
    }

    /**
     * Тест 8: Проверка Extras для Intent
     */
    @Test
    fun test_extrasAreCorrect() {
        org.junit.Assert.assertEquals(
            "Extra для URI",
            "extra_uri",
            Constants.EXTRA_URI
        )

        org.junit.Assert.assertEquals(
            "Extra для процента сокращения",
            "extra_reduction_percent",
            Constants.EXTRA_REDUCTION_PERCENT
        )

        org.junit.Assert.assertEquals(
            "Extra для имени файла",
            "file_name",
            Constants.EXTRA_FILE_NAME
        )

        org.junit.Assert.assertEquals(
            "Extra для оригинального размера",
            "original_size",
            Constants.EXTRA_ORIGINAL_SIZE
        )

        org.junit.Assert.assertEquals(
            "Extra для сжатого размера",
            "compressed_size",
            Constants.EXTRA_COMPRESSED_SIZE
        )
    }

    /**
     * Тест 9: Проверка уровней качества сжатия
     */
    @Test
    fun test_qualityLevels() {
        val lowQuality = Constants.COMPRESSION_QUALITY_LOW
        val mediumQuality = Constants.COMPRESSION_QUALITY_MEDIUM
        val highQuality = Constants.COMPRESSION_QUALITY_HIGH

        org.junit.Assert.assertTrue(
            "Низкое качество должно быть в диапазоне 1-100",
            lowQuality in 1..100
        )

        org.junit.Assert.assertTrue(
            "Среднее качество должно быть в диапазоне 1-100",
            mediumQuality in 1..100
        )

        org.junit.Assert.assertTrue(
            "Высокое качество должно быть в диапазоне 1-100",
            highQuality in 1..100
        )

        org.junit.Assert.assertTrue(
            "Низкое качество < среднего",
            lowQuality < mediumQuality
        )

        org.junit.Assert.assertTrue(
            "Среднее качество < высокого",
            mediumQuality < highQuality
        )
    }

    /**
     * Тест 10: Проверка логических значений по умолчанию
     */
    @Test
    fun test_defaultBooleanValues() {
        // Проверяем, что дефолтные значения для настроек определены правильно
        // В Constants.kt нет дефолтных булевых значений напрямую, 
        // но мы можем проверить другие важные константы
        org.junit.Assert.assertNotNull(
            "Минимальный шанс экономии должен быть определен",
            Constants.MIN_COMPRESSION_SAVING_PERCENT
        )
        
        org.junit.Assert.assertTrue(
            "Минимальный шанс экономии должен быть положительным",
            Constants.MIN_COMPRESSION_SAVING_PERCENT > 0
        )
    }
}

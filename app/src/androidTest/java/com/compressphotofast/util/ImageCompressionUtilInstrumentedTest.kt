package com.compressphotofast.util

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Instrumentation тесты для ImageCompressionUtil.
 *
 * Тестирует только методы, которые не требуют создания файлов:
 * - Определение эффективности сжатия (isImageProcessingEfficient)
 * - Расчет статистики сжатия
 * - Проверку граничных условий
 * - Обработку некорректных входных данных
 *
 * Эти тесты работают на реальном устройстве/эмуляторе с доступом к Android Context.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ImageCompressionUtilInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    /**
     * Тест 1: Эффективное сжатие с хорошей экономией.
     * Требует И 30%+ reduction И 10KB+ saving.
     */
    @Test
    fun test01_isImageProcessingEfficient_GoodCompression() {
        // 500KB -> 200KB = 60% reduction, 300KB saving
        val originalSize = 500 * 1024L
        val compressedSize = 200 * 1024L

        val result = ImageCompressionUtil.isImageProcessingEfficient(
            originalSize,
            compressedSize
        )

        assertTrue("60% reduction with 300KB saving should be efficient", result)
    }

    /**
     * Тест 2: Неэффективное сжатие с малой экономией.
     */
    @Test
    fun test02_isImageProcessingEfficient_PoorCompression() {
        val originalSize = 1000L
        val compressedSize = 800L

        val result = ImageCompressionUtil.isImageProcessingEfficient(
            originalSize,
            compressedSize
        )

        assertFalse("20% reduction should not be efficient", result)
    }

    /**
     * Тест 3: Граничное условие - точно 30% и 10KB.
     */
    @Test
    fun test03_isImageProcessingEfficient_BoundaryCase_30Percent_10Kb() {
        // 100KB -> 70KB = 30% reduction, 30KB saving
        val originalSize = 100 * 1024L
        val compressedSize = 70 * 1024L

        val result = ImageCompressionUtil.isImageProcessingEfficient(
            originalSize,
            compressedSize
        )

        assertTrue("Exactly 30% reduction with 30KB saving should be efficient", result)
    }

    /**
     * Тест 4: Меньше 10KB экономии.
     */
    @Test
    fun test04_isImageProcessingEfficient_LessThan10KbSaving() {
        val originalSize = 20 * 1024L
        val compressedSize = 11 * 1024L

        val result = ImageCompressionUtil.isImageProcessingEfficient(
            originalSize,
            compressedSize
        )

        assertFalse("Less than 10KB saving should not be efficient", result)
    }

    /**
     * Тест 5: Точно 10KB экономии.
     */
    @Test
    fun test05_isImageProcessingEfficient_Exactly10KbSaving() {
        val originalSize = 20 * 1024L
        val compressedSize = 10 * 1024L

        val result = ImageCompressionUtil.isImageProcessingEfficient(
            originalSize,
            compressedSize
        )

        assertTrue("Exactly 10KB saving should be efficient", result)
    }

    /**
     * Тест 6: Нулевой размер оригинала.
     */
    @Test
    fun test06_isImageProcessingEfficient_ZeroSize() {
        val result = ImageCompressionUtil.isImageProcessingEfficient(0L, 0L)
        assertFalse("Zero size should return false", result)
    }

    /**
     * Тест 7: Отрицательный размер.
     */
    @Test
    fun test07_isImageProcessingEfficient_NegativeSize() {
        val result = ImageCompressionUtil.isImageProcessingEfficient(-100L, 50L)
        assertFalse("Negative size should return false", result)
    }

    /**
     * Тест 8: Сжатие увеличило размер.
     */
    @Test
    fun test08_isImageProcessingEfficient_SizeIncreased() {
        val result = ImageCompressionUtil.isImageProcessingEfficient(1000L, 1200L)
        assertFalse("Compression that increases size should not be efficient", result)
    }

    /**
     * Тест 9: Реальные сценарии использования.
     */
    @Test
    fun test09_realWorldScenarios() {
        // Большой JPEG с камеры
        assertTrue(
            "5MB -> 2MB should be efficient",
            ImageCompressionUtil.isImageProcessingEfficient(5 * 1024 * 1024L, 2 * 1024 * 1024L)
        )

        // Уже оптимизированное изображение
        assertFalse(
            "500KB -> 450KB should not be efficient",
            ImageCompressionUtil.isImageProcessingEfficient(500 * 1024L, 450 * 1024L)
        )

        // Среднее изображение
        assertTrue(
            "1MB -> 600KB should be efficient",
            ImageCompressionUtil.isImageProcessingEfficient(1024 * 1024L, 600 * 1024L)
        )
    }

    /**
     * Тест 10: Очень большое изображение.
     */
    @Test
    fun test10_isImageProcessingEfficient_VeryLargeImage() {
        val originalSize = 10 * 1024 * 1024L // 10 MB
        val compressedSize = 6 * 1024 * 1024L // 6 MB (40% reduction)

        val result = ImageCompressionUtil.isImageProcessingEfficient(
            originalSize,
            compressedSize
        )

        assertTrue("Large image compression should work correctly", result)
    }

    /**
     * Тест 11: Проверка константы MIN_COMPRESSION_SAVING_PERCENT.
     */
    @Test
    fun test11_minCompressionSavingPercentConstant() {
        val minPercent = Constants.MIN_COMPRESSION_SAVING_PERCENT
        assertEquals("MIN_COMPRESSION_SAVING_PERCENT should be 30", 30.0f, minPercent.toFloat())
    }

    /**
     * Тест 12: Расчет процента сжатия.
     */
    @Test
    fun test12_compressionReductionCalculation() {
        val originalSize = 1000L
        val compressedSize = 700L
        val expectedReduction = 30f

        val actualReduction = ((originalSize - compressedSize).toFloat() / originalSize) * 100

        assertEquals(
            "Compression reduction calculation should be correct",
            expectedReduction,
            actualReduction,
            0.01f
        )
    }
}

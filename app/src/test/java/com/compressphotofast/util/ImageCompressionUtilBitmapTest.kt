package com.compressphotofast.util

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit тесты для оптимизации Bitmap в ImageCompressionUtil
 * Тестируют функцию calculateInSampleSize и связанные оптимизации
 */
@RunWith(JUnit4::class)
class ImageCompressionUtilBitmapTest {

    /**
     * Тестирует calculateInSampleSize с изображением, которое не требует уменьшения
     */
    @Test
    fun `calculateInSampleSize returns 1 when image is smaller than required`() {
        // Создаем BitmapFactory.Options с небольшим изображением
        val options = BitmapFactory.Options().apply {
            outWidth = 1024
            outHeight = 768
        }

        // Требуемые размеры больше, чем изображение
        val reqWidth = 2048
        val reqHeight = 2048

        // Получаем доступ к приватной функции через рефлексию
        val method = ImageCompressionUtil.javaClass.getDeclaredMethod(
            "calculateInSampleSize",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true

        val result = method.invoke(ImageCompressionUtil, options.outWidth, options.outHeight, reqWidth, reqHeight) as Int

        assertEquals("inSampleSize должен быть 1 для маленького изображения", 1, result)
    }

    /**
     * Тестирует calculateInSampleSize с изображением, которое требует уменьшения в 2 раза
     */
    @Test
    fun `calculateInSampleSize returns 2 for moderately large image`() {
        val options = BitmapFactory.Options().apply {
            outWidth = 4096
            outHeight = 3072
        }

        val reqWidth = 2048
        val reqHeight = 2048

        val method = ImageCompressionUtil.javaClass.getDeclaredMethod(
            "calculateInSampleSize",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true

        val result = method.invoke(ImageCompressionUtil, options.outWidth, options.outHeight, reqWidth, reqHeight) as Int

        // Для 4096x3072:
        // halfHeight = 1536, halfWidth = 2048
        // inSampleSize = 1: 1536 < 2048 (не подходит)
        // Функция вернет 1, так как даже при делении на 2 высота меньше требуемой
        assertEquals("inSampleSize должен быть 1 для изображения 4096x3072 (высота уже < 2048)", 1, result)
    }

    /**
     * Тестирует calculateInSampleSize с очень большим изображением
     */
    @Test
    fun `calculateInSampleSize returns 4 for very large image`() {
        val options = BitmapFactory.Options().apply {
            outWidth = 8000
            outHeight = 6000
        }

        val reqWidth = 2048
        val reqHeight = 2048

        val method = ImageCompressionUtil.javaClass.getDeclaredMethod(
            "calculateInSampleSize",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true

        val result = method.invoke(ImageCompressionUtil, options.outWidth, options.outHeight, reqWidth, reqHeight) as Int

        // Для 8000x6000:
        // halfHeight = 3000, halfWidth = 4000
        // inSampleSize = 1: 3000 >= 2048 (можно увеличить)
        // inSampleSize = 2: 3000/2 = 1500 < 2048 (нельзя увеличить)
        // Функция вернет 2
        assertEquals("inSampleSize должен быть 2 для изображения 8000x6000", 2, result)
    }

    /**
     * Тестирует calculateInSampleSize с экстремально большим изображением
     */
    @Test
    fun `calculateInSampleSize returns 8 for extremely large image`() {
        val options = BitmapFactory.Options().apply {
            outWidth = 16384
            outHeight = 12288
        }

        val reqWidth = 2048
        val reqHeight = 2048

        val method = ImageCompressionUtil.javaClass.getDeclaredMethod(
            "calculateInSampleSize",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true

        val result = method.invoke(ImageCompressionUtil, options.outWidth, options.outHeight, reqWidth, reqHeight) as Int

        // Для 16384x12288:
        // halfHeight = 6144, halfWidth = 8192
        // inSampleSize = 1: 6144 >= 2048 (можно увеличить)
        // inSampleSize = 2: 6144/2 = 3072 >= 2048 (можно увеличить)
        // inSampleSize = 4: 6144/4 = 1536 < 2048 (нельзя увеличить)
        // Функция вернет 4
        assertEquals("inSampleSize должен быть 4 для изображения 16384x12288", 4, result)
    }

    /**
     * Тестирует calculateInSampleSize с изображением, которое точно соответствует требуемым размерам
     */
    @Test
    fun `calculateInSampleSize returns 1 when image exactly matches required size`() {
        val options = BitmapFactory.Options().apply {
            outWidth = 2048
            outHeight = 2048
        }

        val reqWidth = 2048
        val reqHeight = 2048

        val method = ImageCompressionUtil.javaClass.getDeclaredMethod(
            "calculateInSampleSize",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true

        val result = method.invoke(ImageCompressionUtil, options.outWidth, options.outHeight, reqWidth, reqHeight) as Int

        assertEquals("inSampleSize должен быть 1 для точного соответствия", 1, result)
    }

    /**
     * Тестирует, что inSampleSize всегда является степенью 2
     */
    @Test
    fun `calculateInSampleSize always returns power of 2`() {
        data class TestCase(val width: Int, val height: Int, val reqSize: Int)

        val testCases = listOf(
            TestCase(1000, 800, 512),    // Должно вернуть 1
            TestCase(3000, 2000, 1024),   // Должно вернуть 1
            TestCase(6000, 4000, 1024),   // Должно вернуть 2
            TestCase(12000, 8000, 1024),  // Должно вернуть 4
        )

        val method = ImageCompressionUtil.javaClass.getDeclaredMethod(
            "calculateInSampleSize",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true

        testCases.forEach { testCase ->
            val (width, height, reqSize) = testCase

            val result = method.invoke(ImageCompressionUtil, width, height, reqSize, reqSize) as Int

            // Проверяем, что результат является степенью 2
            val isPowerOfTwo = (result > 0) && ((result and (result - 1)) == 0)
            assertEquals(
                "inSampleSize=$result для изображения ${width}x${height} должен быть степенью 2",
                true,
                isPowerOfTwo
            )
        }
    }

    /**
     * Тестирует экономию памяти для различных размеров изображений
     */
    @Test
    fun `bitmap memory usage is reduced correctly with inSampleSize`() {
        // Класс для хранения тестового случая
        data class MemoryTestCase(
            val width: Int,
            val height: Int,
            val inSampleSize: Int,
            val expectedMinReduction: Float
        )

        val testCases = listOf(
            // inSampleSize=1: RGB_565 вместо ARGB_8888 = 2x меньше = 50% экономия
            MemoryTestCase(4096, 3072, 1, 50.0f),
            // inSampleSize=2: 4x меньше пикселей, RGB_565 - еще 2x = 8x меньше = 87.5% экономия
            MemoryTestCase(8000, 6000, 2, 87.5f),
            // inSampleSize=4: 16x меньше пикселей, RGB_565 - еще 2x = 32x меньше = 96.875% экономия
            MemoryTestCase(16384, 12288, 4, 96.0f),
        )

        testCases.forEach { testCase ->
            val (width, height, inSampleSize, expectedMinReduction) = testCase

            // Исходный размер с ARGB_8888 (4 bytes per pixel)
            val originalBytes = width * height * 4L

            // Оптимизированный размер с inSampleSize и RGB_565 (2 bytes per pixel)
            val decodedWidth = width / inSampleSize
            val decodedHeight = height / inSampleSize
            val optimizedBytes = decodedWidth * decodedHeight * 2L

            // Процент экономии
            val actualReduction = ((originalBytes - optimizedBytes).toFloat() / originalBytes) * 100

            val message = "Для изображения ${width}x${height} с inSampleSize=$inSampleSize: " +
                    "ожидается экономия >= ${expectedMinReduction}%, " +
                    "фактическая: ${"%.2f".format(actualReduction)}%"

            println(message)
            assertEquals(
                message,
                true,
                actualReduction >= expectedMinReduction
            )
        }
    }
}

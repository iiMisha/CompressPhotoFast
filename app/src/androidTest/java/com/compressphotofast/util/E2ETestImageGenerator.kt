package com.compressphotofast.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import android.net.Uri
import android.provider.MediaStore

/**
 * Генератор больших тестовых изображений для E2E тестов сжатия.
 *
 * Создает изображения размером 250-500KB для обеспечения 20-40% экономии при сжатии.
 */
object E2ETestImageGenerator {

    /**
     * Создает большое изображение (250-500KB) для E2E тестов сжатия.
     *
     * @param context Контекст приложения
     * @param width Ширина изображения (по умолчанию 1920)
     * @param height Высота изображения (по умолчанию 1080)
     * @param quality Качество JPEG при сохранении (по умолчанию 90 для получения >100KB)
     * @return URI созданного изображения или null при ошибке
     */
    fun createLargeTestImage(
        context: Context,
        width: Int = 1920,
        height: Int = 1080,
        quality: Int = 90
    ): Uri? {
        val fileName = "test_large_${System.currentTimeMillis()}.jpg"

        // Создаем bitmap с复杂的 паттерном для увеличения размера файла
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        // Создаем сложный паттерн с шумом для высокого энтропии JPEG
        // Рисуем маленькие прямоугольники с разными цветами
        var x = 0
        var y = 0
        val blockSize = 4

        for (y in 0 until height step blockSize) {
            for (x in 0 until width step blockSize) {
                // Генерируем псевдо-случайный цвет на основе позиции
                val r = ((x * 7 + y * 13) % 256).toInt()
                val g = ((x * 11 + y * 17) % 256).toInt()
                val b = ((x * 13 + y * 19) % 256).toInt()
                paint.color = Color.rgb(r, g, b)
                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    (x + blockSize).toFloat().coerceAtMost(width.toFloat()),
                    (y + blockSize).toFloat().coerceAtMost(height.toFloat()),
                    paint
                )
            }
        }

        // Сохраняем в MediaStore с высоким качеством
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            }
        }

        bitmap.recycle()
        return uri
    }

    /**
     * Создает несколько больших тестовых изображений.
     *
     * @param context Контекст приложения
     * @param count Количество изображений для создания (по умолчанию 5)
     * @return Список URI созданных изображений
     */
    fun createLargeTestImages(context: Context, count: Int = 5): List<Uri> {
        return (1..count).mapNotNull { createLargeTestImage(context) }
    }
}

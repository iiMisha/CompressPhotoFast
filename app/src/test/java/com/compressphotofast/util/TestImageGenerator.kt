package com.compressphotofast.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Генератор тестовых изображений для unit тестов
 * Создает изображения различных размеров и характеристик
 */
object TestImageGenerator {

    /**
     * Поддерживаемые форматы изображений для генерации
     */
    enum class ImageFormat {
        JPG, PNG, HEIC
    }

    /**
     * Генерирует тестовое изображение заданного размера
     *
     * @param width Ширина изображения в пикселях
     * @param height Высота изображения в пикселях
     * @param quality Качество сжатия JPEG (0-100)
     * @return Файл сгенерированного изображения
     */
    fun generateTestImage(
        width: Int = 1920,
        height: Int = 1080,
        quality: Int = 85
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLUE)

        val file = File.createTempFile("test_image", ".jpg")
        file.deleteOnExit()

        FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        }

        return file
    }

    /**
     * Генерирует изображение размером меньше минимального (100 КБ)
     * Используется для тестирования проверки минимального размера
     */
    fun generateSmallImage(): File {
        return generateTestImage(320, 240, 70)
    }

    /**
     * Генерирует среднее изображение (~500 КБ)
     * Стандартное изображение для большинства тестов
     */
    fun generateMediumImage(): File {
        return generateTestImage(1920, 1080, 85)
    }

    /**
     * Генерирует большое изображение (~5 МБ)
     * Используется для тестирования обработки больших файлов
     */
    fun generateLargeImage(): File {
        return generateTestImage(3840, 2160, 95)
    }

    /**
     * Генерирует PNG изображение (для тестирования форматов)
     */
    fun generatePngImage(): File {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.RED)

        val file = File.createTempFile("test_image", ".png")
        file.deleteOnExit()

        FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }

        return file
    }

    /**
     * Создает Uri для тестового файла
     */
    fun createTestUri(file: File): Uri {
        return Uri.fromFile(file)
    }

    /**
     * Проверяет, что файл существует и имеет размер больше 0
     */
    fun validateImageFile(file: File): Boolean {
        return file.exists() && file.length() > 0
    }

    /**
     * Генерирует изображение размером ~5 МБ указанного формата
     * Используется для нагрузочного тестирования
     *
     * @param format Формат изображения (JPG, PNG, HEIC)
     * @param context Контекст приложения (опционально)
     * @return Файл сгенерированного изображения
     */
    fun generate5MbImage(
        format: ImageFormat,
        context: Context? = null
    ): File {
        val width = 4320  // 4K разрешение
        val height = 2880
        val quality = 95

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Заполняем градиентом для более реалистичного сжатия
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.RED, Color.GREEN, Color.BLUE),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPaint(paint)

        // Добавляем текст для увеличения энтропии
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 48f
        }
        repeat(100) { i ->
            canvas.drawText("Test image $i", 100f, (i * 28).toFloat(), textPaint)
        }

        val extension = when (format) {
            ImageFormat.JPG -> ".jpg"
            ImageFormat.PNG -> ".png"
            ImageFormat.HEIC -> ".heic"
        }

        val file = File.createTempFile("load_test_5mb_", extension)
        file.deleteOnExit()

        FileOutputStream(file).use { out ->
            when (format) {
                ImageFormat.JPG -> {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                }
                ImageFormat.PNG -> {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                ImageFormat.HEIC -> {
                    // HEIC не поддерживается напрямую Bitmap.compress
                    // Сохраняем как JPG, но с расширением .heic для теста
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                }
            }
        }

        bitmap.recycle()

        return file
    }

    /**
     * Генерирует указанное количество изображений смешанных форматов
     * Распределение: примерно 33% JPG, 33% HEIC, 34% PNG
     *
     * @param count Количество изображений для генерации
     * @param context Контекст приложения (опционально)
     * @return Список сгенерированных файлов
     */
    fun generateMixedImages(
        count: Int,
        context: Context? = null
    ): List<File> {
        val images = mutableListOf<File>()
        val jpgCount = (count * 0.33).toInt()
        val heicCount = (count * 0.33).toInt()
        val pngCount = count - jpgCount - heicCount

        repeat(jpgCount) {
            images.add(generate5MbImage(ImageFormat.JPG, context))
        }
        repeat(heicCount) {
            images.add(generate5MbImage(ImageFormat.HEIC, context))
        }
        repeat(pngCount) {
            images.add(generate5MbImage(ImageFormat.PNG, context))
        }

        return images
    }
}

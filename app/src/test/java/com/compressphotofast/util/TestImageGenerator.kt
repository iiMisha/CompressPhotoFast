package com.compressphotofast.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Генератор тестовых изображений для unit тестов
 * Создает изображения различных размеров и характеристик
 */
object TestImageGenerator {

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
}

package com.compressphotofast.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Утилитарный класс для тестирования эффективности сжатия изображений
 */
object CompressionTestUtil {

    /**
     * Выполняет тестовое сжатие и проверяет эффективность, полностью в памяти
     * @param context Контекст приложения
     * @param uri URI изображения для тестирования
     * @param originalSize Размер оригинального изображения в байтах
     * @param quality Качество сжатия (0-100)
     * @return true если сжатие эффективно (экономия > порогового значения), false в противном случае
     */
    suspend fun testCompression(
        context: Context, 
        uri: Uri, 
        originalSize: Long, 
        quality: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.d("Начинаем тестовое сжатие в RAM для URI: $uri")
            
            // Загружаем изображение в Bitmap
            val inputBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: throw IOException("Не удалось открыть изображение")
            
            // Создаем ByteArrayOutputStream для сжатия в память
            val outputStream = ByteArrayOutputStream()
            
            // Сжимаем Bitmap в ByteArrayOutputStream
            val success = inputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            
            if (!success) {
                Timber.w("Ошибка при сжатии Bitmap")
                inputBitmap.recycle() // Освобождаем ресурсы Bitmap
                return@withContext false
            }
            
            // Получаем размер сжатого изображения в байтах
            val compressedSize = outputStream.size().toLong()
            
            // Вычисляем процент сокращения размера
            val sizeReduction = if (originalSize > 0) {
                ((originalSize - compressedSize).toFloat() / originalSize) * 100
            } else 0f
            
            Timber.d("Результат тестового сжатия в RAM: оригинал=${originalSize/1024}KB, сжатый=${compressedSize/1024}KB, сокращение=${String.format("%.1f", sizeReduction)}%")
            
            // Освобождаем ресурсы
            inputBitmap.recycle()
            outputStream.close()
            
            // Если сжатие дало более 10% экономии
            if (sizeReduction > Constants.TEST_COMPRESSION_EFFICIENCY_THRESHOLD) {
                Timber.d("Тестовое сжатие в RAM эффективно (экономия ${String.format("%.1f", sizeReduction)}% > порогового значения ${Constants.TEST_COMPRESSION_EFFICIENCY_THRESHOLD}%), будем сжимать")
                return@withContext true
            } else {
                Timber.d("Тестовое сжатие в RAM неэффективно (экономия ${String.format("%.1f", sizeReduction)}% < порогового значения ${Constants.TEST_COMPRESSION_EFFICIENCY_THRESHOLD}%), пропускаем файл")
                return@withContext false
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при тестовом сжатии в RAM: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Возвращает информацию о результатах тестового сжатия
     * @param context Контекст приложения
     * @param uri URI изображения для тестирования
     * @param originalSize Размер оригинального изображения в байтах
     * @param quality Качество сжатия (0-100)
     * @return Объект с информацией о результатах сжатия или null при ошибке
     */
    suspend fun getTestCompressionStats(
        context: Context,
        uri: Uri,
        originalSize: Long,
        quality: Int
    ): CompressionStats? = withContext(Dispatchers.IO) {
        try {
            Timber.d("Получение статистики тестового сжатия для URI: $uri")
            
            // Загружаем изображение в Bitmap
            val inputBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: throw IOException("Не удалось открыть изображение")
            
            // Создаем ByteArrayOutputStream для сжатия в память
            val outputStream = ByteArrayOutputStream()
            
            // Сжимаем Bitmap в ByteArrayOutputStream
            val success = inputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            
            if (!success) {
                Timber.w("Ошибка при сжатии Bitmap")
                inputBitmap.recycle() // Освобождаем ресурсы Bitmap
                return@withContext null
            }
            
            // Получаем размер сжатого изображения в байтах
            val compressedSize = outputStream.size().toLong()
            
            // Вычисляем процент сокращения размера
            val sizeReduction = if (originalSize > 0) {
                ((originalSize - compressedSize).toFloat() / originalSize) * 100
            } else 0f
            
            Timber.d("Статистика тестового сжатия: оригинал=${originalSize/1024}KB, сжатый=${compressedSize/1024}KB, сокращение=${String.format("%.1f", sizeReduction)}%")
            
            // Освобождаем ресурсы
            inputBitmap.recycle()
            outputStream.close()
            
            return@withContext CompressionStats(originalSize, compressedSize, sizeReduction)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении статистики тестового сжатия: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Класс для хранения статистики сжатия
     */
    data class CompressionStats(
        val originalSize: Long,
        val compressedSize: Long,
        val reductionPercent: Float
    )
} 
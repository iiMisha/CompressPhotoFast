package com.compressphotofast.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Централизованная утилита для сжатия изображений
 * Объединяет дублирующуюся логику из CompressionTestUtil и других классов
 */
object ImageCompressionUtil {

    /**
     * Сжимает изображение из URI в ByteArrayOutputStream
     * 
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param quality Качество сжатия (0-100)
     * @return ByteArrayOutputStream с сжатым изображением или null при ошибке
     */
    suspend fun compressImageToStream(
        context: Context,
        uri: Uri,
        quality: Int
    ): ByteArrayOutputStream? = withContext(Dispatchers.IO) {
        try {
            LogUtil.uriInfo(uri, "Сжатие изображения в поток")
            
            // Загружаем изображение в Bitmap
            val inputBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: throw IOException("Не удалось открыть изображение")
            
            // Создаем ByteArrayOutputStream для сжатия в память
            val outputStream = ByteArrayOutputStream()
            
            // Сжимаем Bitmap в ByteArrayOutputStream
            val success = inputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            
            if (!success) {
                LogUtil.error(uri, "Сжатие", "Ошибка при сжатии Bitmap в поток")
                inputBitmap.recycle() // Освобождаем ресурсы Bitmap
                return@withContext null
            }
            
            // Освобождаем ресурсы Bitmap, но сохраняем поток
            inputBitmap.recycle()
            
            return@withContext outputStream
        } catch (e: Exception) {
            LogUtil.error(uri, "Сжатие в поток", e)
            return@withContext null
        }
    }
    
    /**
     * Тестирует эффективность сжатия изображения
     * 
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param originalSize Размер оригинального файла в байтах
     * @param quality Качество сжатия (0-100)
     * @return CompressionStats с результатами сжатия или null при ошибке
     */
    suspend fun testCompression(
        context: Context,
        uri: Uri,
        originalSize: Long,
        quality: Int
    ): CompressionStats? = withContext(Dispatchers.IO) {
        try {
            // Сжимаем изображение в поток
            val outputStream = compressImageToStream(context, uri, quality) ?: return@withContext null
            
            // Получаем размер сжатого изображения
            val compressedSize = outputStream.size().toLong()
            
            // Вычисляем процент сокращения размера
            val sizeReduction = if (originalSize > 0) {
                ((originalSize - compressedSize).toFloat() / originalSize) * 100
            } else 0f
            
            LogUtil.compression(uri, originalSize, compressedSize, sizeReduction.toInt())
            
            // Закрываем поток
            outputStream.close()
            
            return@withContext CompressionStats(originalSize, compressedSize, sizeReduction)
        } catch (e: Exception) {
            LogUtil.error(uri, "Тестирование сжатия", e)
            return@withContext null
        }
    }
    
    /**
     * Сжимает изображение из входного потока в выходной
     * 
     * @param context Контекст приложения
     * @param inputStream Входной поток с исходным изображением
     * @param outputStream Выходной поток для сжатого изображения
     * @param quality Качество сжатия (0-100)
     * @return true если сжатие успешно, false при ошибке
     */
    suspend fun compressStream(
        context: Context,
        inputStream: InputStream,
        outputStream: OutputStream,
        quality: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Загружаем изображение в Bitmap
            val inputBitmap = BitmapFactory.decodeStream(inputStream)
                ?: throw IOException("Не удалось декодировать изображение")
            
            // Сжимаем Bitmap в выходной поток
            val success = inputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            
            // Освобождаем ресурсы Bitmap
            inputBitmap.recycle()
            
            return@withContext success
        } catch (e: Exception) {
            LogUtil.error(null, "Сжатие потока", e)
            return@withContext false
        }
    }
    
    /**
     * Комплексно проверяет эффективность сжатия и возвращает оптимальный уровень качества
     * 
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param originalSize Размер оригинального файла
     * @return Оптимальный уровень качества (0-100) или Constants.COMPRESSION_QUALITY_MEDIUM при ошибке
     */
    suspend fun findOptimalQuality(
        context: Context,
        uri: Uri,
        originalSize: Long
    ): Int = withContext(Dispatchers.IO) {
        try {
            // Тестируем разные уровни качества
            val qualities = listOf(70, 60, 80, 50, 90)
            var bestQuality = Constants.COMPRESSION_QUALITY_MEDIUM
            var bestRatio = 1.0f
            
            for (quality in qualities) {
                val stats = testCompression(context, uri, originalSize, quality)
                
                if (stats != null) {
                    val ratio = stats.compressedSize.toFloat() / originalSize.toFloat()
                    
                    // Если новое соотношение лучше предыдущего, обновляем результат
                    if (ratio < bestRatio && ratio >= Constants.MIN_COMPRESSION_RATIO) {
                        bestRatio = ratio
                        bestQuality = quality
                    }
                    
                    // Если достигли достаточно хорошего сжатия, останавливаемся
                    if (ratio < 0.3f) {
                        break
                    }
                }
            }
            
            return@withContext bestQuality
        } catch (e: Exception) {
            LogUtil.error(uri, "Поиск оптимального качества", e)
            return@withContext Constants.COMPRESSION_QUALITY_MEDIUM
        }
    }
    
    /**
     * Модель для хранения результатов сжатия
     */
    data class CompressionStats(
        val originalSize: Long,
        val compressedSize: Long,
        val sizeReduction: Float
    )
} 
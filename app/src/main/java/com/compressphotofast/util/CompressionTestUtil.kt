package com.compressphotofast.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.compressphotofast.util.LogUtil
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
            LogUtil.uriInfo(uri, "Начало тестового сжатия в RAM")
            
            // Загружаем изображение в Bitmap
            val inputBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: throw IOException("Не удалось открыть изображение")
            
            // Создаем ByteArrayOutputStream для сжатия в память
            val outputStream = ByteArrayOutputStream()
            
            // Сжимаем Bitmap в ByteArrayOutputStream
            val success = inputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            
            if (!success) {
                LogUtil.error(uri, "Тестовое сжатие", "Ошибка при сжатии Bitmap")
                inputBitmap.recycle() // Освобождаем ресурсы Bitmap
                return@withContext false
            }
            
            // Получаем размер сжатого изображения в байтах
            val compressedSize = outputStream.size().toLong()
            
            // Вычисляем процент сокращения размера
            val sizeReduction = if (originalSize > 0) {
                ((originalSize - compressedSize).toFloat() / originalSize) * 100
            } else 0f
            
            LogUtil.compression(uri, originalSize, compressedSize, sizeReduction.toInt())
            
            // Освобождаем ресурсы
            inputBitmap.recycle()
            outputStream.close()
            
            // Если сжатие дало более 10% экономии
            if (sizeReduction > Constants.TEST_COMPRESSION_EFFICIENCY_THRESHOLD) {
                LogUtil.processInfo("Тестовое сжатие для ${getFileId(uri)} эффективно (экономия ${sizeReduction.toInt()}% > ${Constants.TEST_COMPRESSION_EFFICIENCY_THRESHOLD}%), выполняем полное сжатие")
                return@withContext true
            } else {
                LogUtil.skipImage(uri, "Тестовое сжатие неэффективно (экономия ${sizeReduction.toInt()}% < ${Constants.TEST_COMPRESSION_EFFICIENCY_THRESHOLD}%)")
                return@withContext false
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Тестовое сжатие", e)
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
            LogUtil.uriInfo(uri, "Получение статистики тестового сжатия")
            
            // Загружаем изображение в Bitmap
            val inputBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: throw IOException("Не удалось открыть изображение")
            
            // Создаем ByteArrayOutputStream для сжатия в память
            val outputStream = ByteArrayOutputStream()
            
            // Сжимаем Bitmap в ByteArrayOutputStream
            val success = inputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            
            if (!success) {
                LogUtil.error(uri, "Сжатие Bitmap", "Ошибка при получении статистики")
                inputBitmap.recycle() // Освобождаем ресурсы Bitmap
                return@withContext null
            }
            
            // Получаем размер сжатого изображения в байтах
            val compressedSize = outputStream.size().toLong()
            
            // Вычисляем процент сокращения размера
            val sizeReduction = if (originalSize > 0) {
                ((originalSize - compressedSize).toFloat() / originalSize) * 100
            } else 0f
            
            LogUtil.compression(uri, originalSize, compressedSize, sizeReduction.toInt())
            
            // Освобождаем ресурсы
            inputBitmap.recycle()
            outputStream.close()
            
            return@withContext CompressionStats(originalSize, compressedSize, sizeReduction)
        } catch (e: Exception) {
            LogUtil.error(uri, "Статистика сжатия", e)
            return@withContext null
        }
    }
    
    /**
     * Возвращает ByteArrayOutputStream с результатом тестового сжатия
     * Это позволяет использовать результат RAM-сжатия напрямую без повторного сжатия
     * 
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param quality Качество сжатия (0-100)
     * @return ByteArrayOutputStream с сжатым изображением или null при ошибке
     */
    suspend fun getCompressedImageStream(
        context: Context,
        uri: Uri,
        quality: Int
    ): ByteArrayOutputStream? = withContext(Dispatchers.IO) {
        try {
            LogUtil.uriInfo(uri, "Получение сжатого изображения")
            
            // Загружаем изображение в Bitmap
            val inputBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: throw IOException("Не удалось открыть изображение")
            
            // Создаем ByteArrayOutputStream для сжатия в память
            val outputStream = ByteArrayOutputStream()
            
            // Сжимаем Bitmap в ByteArrayOutputStream
            val success = inputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            
            if (!success) {
                LogUtil.error(uri, "Сжатие", "Ошибка при сжатии Bitmap в потоке")
                inputBitmap.recycle() // Освобождаем ресурсы Bitmap
                return@withContext null
            }
            
            // Освобождаем ресурсы Bitmap, но сохраняем поток
            inputBitmap.recycle()
            
            return@withContext outputStream
        } catch (e: Exception) {
            LogUtil.error(uri, "Получение сжатого потока", e)
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

    /**
     * Получает короткий идентификатор для URI, используемый в логах
     */
    private fun getFileId(uri: Uri): String {
        return uri.lastPathSegment?.takeLast(4) ?: uri.toString().takeLast(4)
    }
} 
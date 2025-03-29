package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

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
     * @return Объект CompressionStats или null при ошибке
     */
    suspend fun testCompression(
        context: Context, 
        uri: Uri, 
        originalSize: Long, 
        quality: Int
    ): ImageCompressionUtil.CompressionStats? = withContext(Dispatchers.IO) {
        try {
            LogUtil.uriInfo(uri, "Начало тестового сжатия в RAM")
            
            // Используем централизованный метод из ImageCompressionUtil
            val result = ImageCompressionUtil.testCompression(context, uri, originalSize, quality)
            
            if (result != null) {
                // Логируем результат
                if (result.sizeReduction > Constants.TEST_COMPRESSION_EFFICIENCY_THRESHOLD) {
                    LogUtil.processInfo("Тестовое сжатие для ${getFileId(uri)} эффективно (экономия ${result.sizeReduction.toInt()}% > ${Constants.TEST_COMPRESSION_EFFICIENCY_THRESHOLD}%), выполняем полное сжатие")
                } else {
                    LogUtil.skipImage(uri, "Тестовое сжатие неэффективно (экономия ${result.sizeReduction.toInt()}% < ${Constants.TEST_COMPRESSION_EFFICIENCY_THRESHOLD}%)")
                }
            }
            
            return@withContext result
        } catch (e: Exception) {
            LogUtil.error(uri, "Тестовое сжатие", e)
            return@withContext null
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
    ): ImageCompressionUtil.CompressionStats? = withContext(Dispatchers.IO) {
        try {
            LogUtil.uriInfo(uri, "Получение статистики тестового сжатия")
            return@withContext ImageCompressionUtil.testCompression(context, uri, originalSize, quality)
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
            // Используем централизованный метод из ImageCompressionUtil
            return@withContext ImageCompressionUtil.compressImageToStream(context, uri, quality)
        } catch (e: Exception) {
            LogUtil.error(uri, "Получение сжатого потока", e)
            return@withContext null
        }
    }

    /**
     * Получает короткий идентификатор для URI, используемый в логах
     */
    private fun getFileId(uri: Uri): String {
        return uri.lastPathSegment?.takeLast(4) ?: uri.toString().takeLast(4)
    }
} 
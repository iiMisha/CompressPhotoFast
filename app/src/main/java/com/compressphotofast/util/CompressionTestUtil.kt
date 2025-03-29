package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Утилитарный класс для тестирования эффективности сжатия изображений
 * @deprecated Вся функциональность перенесена в ImageCompressionUtil. Используйте методы из ImageCompressionUtil напрямую.
 */
@Deprecated("Используйте методы из ImageCompressionUtil напрямую")
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
        return@withContext ImageCompressionUtil.testCompression(context, uri, originalSize, quality)
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
        return@withContext ImageCompressionUtil.testCompression(context, uri, originalSize, quality)
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
        return@withContext ImageCompressionUtil.compressImageToStream(context, uri, quality)
    }
} 
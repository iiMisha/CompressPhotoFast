package com.compressphotofast.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import com.compressphotofast.data.FileOperationsUtil
import com.compressphotofast.data.UriUtil
import com.compressphotofast.util.Constants
import com.compressphotofast.util.LogUtil

/**
 * Базовый класс исключений сжатия изображения
 */
sealed class CompressionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * OOM ошибка - недостаточно памяти
     */
    data class OutOfMemory(
        val requiredBytes: Long,
        val availableBytes: Long,
        override val cause: Throwable
    ) : CompressionException(
        "Недостаточно памяти: требуется ${requiredBytes / 1024 / 1024}MB, " +
            "доступно ${availableBytes / 1024 / 1024}MB",
        cause
    )

    /** Память временно недоступна, повторная попытка безопаснее пропуска фото. */
    data class InsufficientMemory(
        val requiredBytes: Long,
        val availableBytes: Long
    ) : CompressionException(
        "Недостаточно headroom памяти: требуется ${requiredBytes / 1024 / 1024}MB, " +
            "доступно ${availableBytes / 1024 / 1024}MB"
    )

    /**
     * Поврежденный файл
     */
    data class CorruptedFile(
        val fileName: String,
        override val cause: Throwable
    ) : CompressionException(
        "Файл повреждён: $fileName",
        cause
    )
}

/**
 * Централизованная утилита для сжатия изображений
 * Объединяет дублирующуюся логику из CompressionTestUtil и других классов
 */
object ImageCompressionUtil {

    /**
     * Декодирует границы изображения (ширина, высота) через BitmapFactory.
     * HEIC/HEIF сюда не попадает: границы берутся в одном проходе ImageDecoder
     * ([decodeHeicSinglePass]).
     */
    private suspend fun decodeImageBounds(
        context: Context,
        uri: Uri
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        var width = 0
        var height = 0
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
                width = options.outWidth
                height = options.outHeight
                if (options.outWidth > 0 && options.outHeight > 0) {
                    return@withContext Pair(options.outWidth, options.outHeight)
                }
            }
        } catch (e: OutOfMemoryError) {
            val requiredMemory = width.toLong() * height.toLong() * 4L
            val availableMemory = Runtime.getRuntime().freeMemory()

            throw CompressionException.OutOfMemory(
                requiredMemory,
                availableMemory,
                e
            )
        } catch (e: Exception) {
            LogUtil.error(uri, "Декодирование границ изображения", e)
        }
        return@withContext null
    }

    /**
     * План масштабирования: степень двойки для первичного сэмплинга декодера
     * и точные целевые размеры по большей стороне.
     */
    data class ScalePlan(
        val inSampleSize: Int,
        val targetWidth: Int,
        val targetHeight: Int
    )

    /**
     * Вычисляет план масштабирования для ограничения разрешения по большей стороне.
     * @return null если масштабирование не требуется (maxDimension <= 0 или изображение уже меньше)
     */
    fun computeScalePlan(width: Int, height: Int, maxDimension: Int): ScalePlan? {
        if (maxDimension <= 0) return null
        val longest = maxOf(width, height)
        if (longest <= maxDimension) return null
        val scale = maxDimension.toDouble() / longest
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return ScalePlan(sample, targetWidth, targetHeight)
    }

    /** Результат одного прохода ImageDecoder для HEIC/HEIF. */
    private class HeicDecoded(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val scalePlan: ScalePlan?
    )

    /**
     * HEIC/HEIF: границы, admission-проверка памяти и декодирование за один вызов
     * ImageDecoder (заголовок разбирается в [ImageDecoder.OnHeaderDecodedListener]).
     * [CompressionException] из listener и [ImageDecoder.DecodeException] пробрасываются,
     * остальные сбои → null.
     */
    private suspend fun decodeHeicSinglePass(
        context: Context,
        uri: Uri,
        mimeType: String?,
        maxDimension: Int
    ): HeicDecoded? = withContext(Dispatchers.IO) {
        var width = 0
        var height = 0
        var scalePlan: ScalePlan? = null
        var bitmap: Bitmap? = null
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                width = info.size.width
                height = info.size.height
                scalePlan = computeScalePlan(width, height, maxDimension)
                requireMemory(context, width, height, mimeType)
                scalePlan?.let { decoder.setTargetSize(it.targetWidth, it.targetHeight) }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
            }
            return@withContext HeicDecoded(bitmap, width, height, scalePlan)
        } catch (e: CompressionException) {
            throw e
        } catch (e: ImageDecoder.DecodeException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Exception) {
            bitmap?.recycle()
            LogUtil.error(uri, "Декодирование HEIC", e)
            return@withContext null
        }
    }

    /** Admission-проверка: при нехватке headroom бросает [CompressionException.InsufficientMemory]. */
    private fun requireMemory(context: Context, width: Int, height: Int, mimeType: String?) {
        // Пиксели не поворачиваются: тег Orientation переносится в копию как есть
        // (как в CLI), второй bitmap под поворот не нужен. HEIC ImageDecoder
        // поворачивает сам — см. CompressionTestResult.pixelsOriented.
        val requiredBytes = estimatePeakMemoryBytes(width, height, mimeType)
        if (!FileOperationsUtil.hasEnoughMemory(context, requiredBytes)) {
            throw CompressionException.InsufficientMemory(
                requiredBytes, FileOperationsUtil.availableMemoryBytes(context)
            )
        }
    }

    /** Декодирует не-HEIC изображение через BitmapFactory. */
    private suspend fun decodeImageBitmap(
        context: Context,
        uri: Uri,
        mimeType: String?,
        inSampleSize: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = if (isRgb565Compatible(mimeType)) {
                    Bitmap.Config.RGB_565
                } else {
                    Bitmap.Config.ARGB_8888
                }
            }
            return@withContext context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Декодирование изображения", e)
            return@withContext null
        }
    }

    private fun isRgb565Compatible(mimeType: String?): Boolean {
        return mimeType?.startsWith("image/jpeg") == true || mimeType?.startsWith("image/jpg") == true
    }


    /** Legacy API for callers/tests that explicitly need an in-memory stream. */
    suspend fun compressImageToStream(context: Context, uri: Uri, quality: Int): ByteArrayOutputStream? {
        val artifact = try {
            compressImageToFile(context, uri, quality)
        } catch (e: CompressionException) {
            LogUtil.error(uri, "Сжатие в поток", e)
            null
        } catch (e: IOException) {
            LogUtil.error(uri, "Сжатие в поток", e)
            null
        }
        return artifact?.let { file ->
            try {
                ByteArrayOutputStream(file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).also { output ->
                    FileInputStream(file).use { it.copyTo(output) }
                }
            } finally {
                file.delete()
            }
        }
    }

    /**
     * Сжимает JPEG напрямую в cacheDir. Файл является disposable artifact и
     * удаляется вызывающим кодом после сохранения или в любом terminal path.
     */
    suspend fun compressImageToFile(
        context: Context,
        uri: Uri,
        quality: Int,
        maxDimension: Int = Constants.RESOLUTION_ORIGINAL,
        knownMimeType: String? = null
    ): File? =
        withTimeout(120_000L) {
            withContext(Dispatchers.IO) {
                var inputBitmap: Bitmap? = null
                var artifact: File? = null
                var completed = false
                var width = 0
                var height = 0
                try {
                    val mimeType = knownMimeType ?: UriUtil.getMimeType(context, uri)
                    // Масштабирование применяется только при явном выборе пресета;
                    // по умолчанию (RESOLUTION_ORIGINAL) разрешение сохраняется.
                    // Full-resolution decode is the default. Memory admission defers work
                    // instead of silently changing image dimensions.
                    val scalePlan: ScalePlan?
                    if (UriUtil.isHeicMimeType(mimeType)) {
                        val decoded = decodeHeicSinglePass(context, uri, mimeType, maxDimension)
                            ?: return@withContext null
                        width = decoded.width
                        height = decoded.height
                        scalePlan = decoded.scalePlan
                        inputBitmap = decoded.bitmap
                    } else {
                        val bounds = decodeImageBounds(context, uri)
                            ?: return@withContext null
                        width = bounds.first
                        height = bounds.second
                        scalePlan = computeScalePlan(width, height, maxDimension)
                        requireMemory(context, width, height, mimeType)
                        inputBitmap = decodeImageBitmap(context, uri, mimeType, scalePlan?.inSampleSize ?: 1)
                            ?: return@withContext null
                    }
                    if (scalePlan != null &&
                        (inputBitmap!!.width > scalePlan.targetWidth || inputBitmap!!.height > scalePlan.targetHeight)
                    ) {
                        val scaled = Bitmap.createScaledBitmap(
                            inputBitmap!!,
                            scalePlan.targetWidth,
                            scalePlan.targetHeight,
                            true
                        )
                        if (scaled !== inputBitmap) {
                            inputBitmap.recycle()
                            inputBitmap = scaled
                        }
                    }

                    artifact = File(context.cacheDir, "compressed_${uri.hashCode()}_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(artifact).buffered(com.compressphotofast.util.FileIoUtil.COPY_BUFFER_SIZE).use { output ->
                        if (!inputBitmap!!.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                            throw IOException("Bitmap.compress вернул false")
                        }
                    }
                    if (!validateJpegArtifact(artifact)) {
                        throw CompressionException.CorruptedFile(
                            UriUtil.getFileNameFromUri(context, uri) ?: "неизвестный",
                            IOException("JPEG artifact не прошёл валидацию")
                        )
                    }
                    completed = true
                    artifact
                } catch (e: OutOfMemoryError) {
                    val required = estimatePeakMemoryBytes(width, height, null)
                    throw CompressionException.OutOfMemory(
                        required,
                        FileOperationsUtil.availableMemoryBytes(context),
                        e
                    )
                } catch (e: ImageDecoder.DecodeException) {
                    throw CompressionException.CorruptedFile(
                        UriUtil.getFileNameFromUri(context, uri) ?: "неизвестный", e
                    )
                } catch (e: CompressionException) {
                    throw e
                } catch (e: Exception) {
                    throw if (e is IOException) e else IOException("Ошибка сжатия изображения", e)
                } finally {
                    inputBitmap?.recycle()
                    if (!completed) artifact?.delete()
                }
            }
        }

    private fun validateJpegArtifact(file: File): Boolean {
        if (!file.exists() || file.length() < 4L) return false
        RandomAccessFile(file, "r").use { random ->
            val soi = ByteArray(2)
            random.readFully(soi)
            random.seek(file.length() - 2)
            val eoi = ByteArray(2)
            random.readFully(eoi)
            if (soi[0] != 0xFF.toByte() || soi[1] != 0xD8.toByte() ||
                eoi[0] != 0xFF.toByte() || eoi[1] != 0xD9.toByte()) return false
        }
        FileInputStream(file).use { input ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, options)
            return options.outWidth > 0 && options.outHeight > 0
        }
    }

    fun estimatePeakMemoryBytes(
        width: Int,
        height: Int,
        mimeType: String?
    ): Long {
        val pixels = width.toLong().coerceAtLeast(0L) * height.toLong().coerceAtLeast(0L)
        val decodedBytes = pixels * if (UriUtil.isHeicMimeType(mimeType)) 4L else 2L
        val jpegBytes = maxOf(1L * 1024 * 1024, pixels)
        return decodedBytes + jpegBytes
    }
    
    /**
     * Тестирует эффективность сжатия изображения и возвращает результат вместе с потоком данных
     *
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param originalSize Размер оригинального файла в байтах
     * @param quality Качество сжатия (0-100)
     * @param keepStream Сохранять ли поток данных открытым в результате
     * @param knownMimeType MIME из снимка MediaStore (null — запросить)
     * @return CompressionTestResult с результатами сжатия или null при ошибке
     */
    suspend fun testCompression(
        context: Context,
        uri: Uri,
        originalSize: Long,
        quality: Int,
        keepStream: Boolean = false,
        maxDimension: Int = Constants.RESOLUTION_ORIGINAL,
        knownMimeType: String? = null
    ): CompressionTestResult? = withContext(Dispatchers.IO) {
        var artifact: File? = null
        try {
            val mimeType = knownMimeType ?: UriUtil.getMimeType(context, uri)
            // ImageDecoder применяет EXIF-ориентацию HEIC к пикселям; BitmapFactory — нет
            val pixelsOriented = UriUtil.isHeicMimeType(mimeType)
            artifact = try {
                compressImageToFile(context, uri, quality, maxDimension, mimeType)
            } catch (e: CompressionException) {
                throw e
            } catch (e: IOException) {
                throw e
            }
            ?: return@withContext null

            val compressedSize = artifact!!.length()
            
            // Вычисляем процент сокращения размера
            val sizeReduction = FileOperationsUtil.computeSizeReductionPercent(originalSize, compressedSize)
            
            LogUtil.compression(uri, originalSize, compressedSize, sizeReduction.toInt())
            
            val stats = CompressionStats(originalSize, compressedSize, sizeReduction)
            
            return@withContext if (keepStream) {
                val result = CompressionTestResult(stats, artifact, pixelsOriented)
                artifact = null
                result
            } else {
                artifact!!.delete()
                CompressionTestResult(stats, null, pixelsOriented)
            }
        } catch (e: CompressionException) {
            throw e
        } catch (e: Exception) {
            LogUtil.error(uri, "Тестирование сжатия", e)
            return@withContext null
        } finally {
            artifact?.delete()
        }
    }
    
    /**
     * Определяет, является ли сжатие изображения эффективным
     * на основе соотношения размеров и минимальной экономии
     * 
     * @param originalSize Размер оригинального файла в байтах
     * @param compressedSize Размер сжатого файла в байтах
     * @return true если сжатие эффективно, false в противном случае
     */
    fun isImageProcessingEfficient(originalSize: Long, compressedSize: Long): Boolean {
        if (originalSize <= 0) return false
        
        val sizeReduction = FileOperationsUtil.computeSizeReductionPercent(originalSize, compressedSize)
        val minSaving = Constants.MIN_COMPRESSION_SAVING_PERCENT
        val minBytesSaving = 10 * 1024 // Минимальная экономия 10KB
        
        return sizeReduction >= minSaving && (originalSize - compressedSize) >= minBytesSaving
    }
    
    
    
    /**
     * Модель для хранения результатов тестового сжатия
     */
    data class CompressionTestResult(
        val stats: CompressionStats,
        val compressedFile: File?,
        /** Ориентация уже применена к пикселям (HEIC): в копию пишется Orientation=NORMAL. */
        val pixelsOriented: Boolean = false
    ) {
        /**
         * Проверяет, было ли сжатие эффективным
         */
        fun isEfficient(): Boolean {
            return stats.isEfficient()
        }

        fun deleteArtifact() {
            compressedFile?.delete()
        }
    }

    /**
     * Модель для хранения статистики сжатия
     */
    data class CompressionStats(
        val originalSize: Long,
        val compressedSize: Long,
        val sizeReduction: Float
    ) {
        /**
         * Проверяет, было ли сжатие эффективным
         */
        fun isEfficient(): Boolean {
            return isImageProcessingEfficient(originalSize, compressedSize)
        }
    }
}


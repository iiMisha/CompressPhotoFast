package com.compressphotofast.util

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
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.compressphotofast.util.FileOperationsUtil
import com.compressphotofast.util.UriUtil
import com.compressphotofast.util.MediaStoreUtil
import com.compressphotofast.util.PerformanceMonitor
import com.compressphotofast.util.Constants
import com.compressphotofast.util.NotificationUtil

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
     * Проверяет, является ли MIME тип HEIC/HEIF
     */
    private fun isHeicFormat(mimeType: String?): Boolean = UriUtil.isHeicMimeType(mimeType)

    /**
     * Верифицирует целостность изображения: декодирует только заголовки
     * (inJustDecodeBounds=true) и проверяет корректность размеров.
     *
     * @return true если изображение корректно декодируется, false если повреждено
     */
    suspend fun verifyImageIntegrity(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    LogUtil.error(uri, "Верификация", "Файл повреждён или не является изображением: ${options.outWidth}x${options.outHeight}")
                    return@withContext false
                }
            } ?: run {
                LogUtil.error(uri, "Верификация", "Не удалось открыть поток для проверки целостности")
                return@withContext false
            }
            return@withContext true
        } catch (e: Exception) {
            LogUtil.error(uri, "Верификация", "Ошибка при проверке целостности файла", e)
            return@withContext false
        }
    }

    private data class OrientationTransform(
        val rotationDegrees: Int = 0,
        val flipHorizontal: Boolean = false,
        val flipVertical: Boolean = false
    )

    private fun getOrientationTransform(context: Context, uri: Uri): OrientationTransform {
        val exif = ExifUtil.getExifInterface(context, uri) 
            ?: return OrientationTransform()
        
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, 
            ExifInterface.ORIENTATION_NORMAL
        )
        
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> OrientationTransform(rotationDegrees = 90)
            ExifInterface.ORIENTATION_ROTATE_180 -> OrientationTransform(rotationDegrees = 180)
            ExifInterface.ORIENTATION_ROTATE_270 -> OrientationTransform(rotationDegrees = 270)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> OrientationTransform(flipHorizontal = true)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> OrientationTransform(flipVertical = true)
            ExifInterface.ORIENTATION_TRANSPOSE -> OrientationTransform(rotationDegrees = 90, flipHorizontal = true)
            ExifInterface.ORIENTATION_TRANSVERSE -> OrientationTransform(rotationDegrees = 270, flipHorizontal = true)
            else -> OrientationTransform()
        }
    }

    private fun applyOrientationTransform(bitmap: Bitmap, transform: OrientationTransform): Bitmap {
        if (transform.rotationDegrees == 0 && !transform.flipHorizontal && !transform.flipVertical) {
            return bitmap
        }
        
        val matrix = Matrix()
        
        if (transform.rotationDegrees != 0) {
            matrix.postRotate(transform.rotationDegrees.toFloat())
        }
        
        if (transform.flipHorizontal) {
            matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        }
        
        if (transform.flipVertical) {
            matrix.postScale(1f, -1f, bitmap.width / 2f, bitmap.height / 2f)
        }
        
        return Bitmap.createBitmap(
            bitmap, 0, 0,
            bitmap.width, bitmap.height,
            matrix, true
        )
    }

    /**
     * Декодирует границы изображения (ширина, высота) с поддержкой HEIC/HEIF
     * Для HEIC/HEIF использует ImageDecoder, для остальных - BitmapFactory
     */
    private suspend fun decodeImageBounds(
        context: Context,
        uri: Uri,
        mimeType: String?
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        var width = 0
        var height = 0
        try {
                if (isHeicFormat(mimeType)) {
                    // Используем ImageDecoder для HEIC/HEIF (API 28+)
                    val source = ImageDecoder.createSource(context.contentResolver, uri)

                    try {
                        // Используем ImageDecoder для получения размеров без полной аллокации
                        // Декодирование прерывается выбросом исключения после получения заголовка
                        ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                            width = info.size.width
                            height = info.size.height
                            throw StopDecodingException()
                        }
                    } catch (e: StopDecodingException) {
                        // Это ожидаемое прерывание
                    } catch (e: Exception) {
                        // Если ImageDecoder не справился, пробуем BitmapFactory как fallback
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            BitmapFactory.decodeStream(inputStream, null, options)
                            width = options.outWidth
                            height = options.outHeight
                        }
                    }

                    if (width > 0 && height > 0) {
                        return@withContext Pair(width, height)
                    }
                } else {
                // Используем BitmapFactory для остальных форматов
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
            }
        } catch (e: OutOfMemoryError) {
            val requiredMemory = width.toLong() * height.toLong() * 4L
            val availableMemory = Runtime.getRuntime().freeMemory()

            throw CompressionException.OutOfMemory(
                requiredMemory,
                availableMemory,
                e
            )
        } catch (e: ImageDecoder.DecodeException) {
            throw CompressionException.CorruptedFile(
                UriUtil.getFileNameFromUri(context, uri) ?: "неизвестный",
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

    /**
     * Декодирует изображение с поддержкой HEIC/HEIF
     * Для HEIC/HEIF использует ImageDecoder, для остальных - BitmapFactory
     */
    private suspend fun decodeImageBitmap(
        context: Context,
        uri: Uri,
        mimeType: String?,
        inSampleSize: Int,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            if (isHeicFormat(mimeType)) {
                // Используем ImageDecoder для HEIC/HEIF (API 28+)
                // OPTIMIZED: single-pass decode - получаем bounds и bitmap за один раз
                val source = ImageDecoder.createSource(context.contentResolver, uri)

                bitmap = ImageDecoder.decodeBitmap(source, { decoder, info, _ ->
                    if (targetWidth > 0 && targetHeight > 0) {
                        decoder.setTargetSize(targetWidth, targetHeight)
                    } else if (inSampleSize > 1) {
                        val scaledWidth = info.size.width / inSampleSize
                        val scaledHeight = info.size.height / inSampleSize
                        decoder.setTargetSize(scaledWidth, scaledHeight)
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
                })
                return@withContext bitmap
            } else {
                // Используем BitmapFactory для остальных форматов
                val options = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                    inPreferredConfig = if (isRgb565Compatible(mimeType)) {
                        Bitmap.Config.RGB_565
                    } else {
                        Bitmap.Config.ARGB_8888
                    }
                }

                bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream, null, options)
                }
                return@withContext bitmap
            }
        } catch (e: Exception) {
            bitmap?.recycle()
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
        maxDimension: Int = Constants.RESOLUTION_ORIGINAL
    ): File? =
        withTimeout(120_000L) {
            withContext(Dispatchers.IO) {
                var inputBitmap: Bitmap? = null
                var transformedBitmap: Bitmap? = null
                var artifact: File? = null
                var completed = false
                var width = 0
                var height = 0
                try {
                    val mimeType = UriUtil.getMimeType(context, uri)
                    val bounds = decodeImageBounds(context, uri, mimeType)
                        ?: return@withContext null
                    width = bounds.first
                    height = bounds.second
                    // Масштабирование применяется только при явном выборе пресета;
                    // по умолчанию (RESOLUTION_ORIGINAL) разрешение сохраняется.
                    val scalePlan = computeScalePlan(width, height, maxDimension)
                    val transform = if (isHeicFormat(mimeType)) OrientationTransform() else getOrientationTransform(context, uri)
                    val requiresSecondBitmap = transform.rotationDegrees != 0 ||
                        transform.flipHorizontal || transform.flipVertical
                    val requiredBytes = estimatePeakMemoryBytes(width, height, mimeType, requiresSecondBitmap)
                    val availableBytes = FileOperationsUtil.availableMemoryBytes(context)
                    if (!FileOperationsUtil.hasEnoughMemory(context, requiredBytes)) {
                        throw CompressionException.InsufficientMemory(requiredBytes, availableBytes)
                    }

                    // Full-resolution decode is the default. Memory admission above
                    // defers work instead of silently changing image dimensions.
                    // Даунскейл (если выбран пресет) дополнительно снижает память.
                    inputBitmap = decodeImageBitmap(
                        context,
                        uri,
                        mimeType,
                        scalePlan?.inSampleSize ?: 1,
                        scalePlan?.targetWidth ?: 0,
                        scalePlan?.targetHeight ?: 0
                    )
                        ?: return@withContext null
                    if (requiresSecondBitmap) {
                        transformedBitmap = applyOrientationTransform(inputBitmap!!, transform)
                        if (transformedBitmap !== inputBitmap) {
                            inputBitmap.recycle()
                            inputBitmap = transformedBitmap
                            transformedBitmap = null
                        }
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
                    FileOutputStream(artifact).use { output ->
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
                    val required = estimatePeakMemoryBytes(width, height, null, false)
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
                    transformedBitmap?.recycle()
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
        mimeType: String?,
        requiresSecondBitmap: Boolean = false
    ): Long {
        val pixels = width.toLong().coerceAtLeast(0L) * height.toLong().coerceAtLeast(0L)
        val decodedBytes = pixels * if (isHeicFormat(mimeType)) 4L else 2L
        val secondBitmapBytes = if (requiresSecondBitmap) pixels * 4L else 0L
        val jpegBytes = maxOf(1L * 1024 * 1024, pixels)
        return decodedBytes + secondBitmapBytes + jpegBytes
    }
    
    /**
     * Тестирует эффективность сжатия изображения и возвращает результат вместе с потоком данных
     *
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param originalSize Размер оригинального файла в байтах
     * @param quality Качество сжатия (0-100)
     * @param keepStream Сохранять ли поток данных открытым в результате
     * @return CompressionTestResult с результатами сжатия или null при ошибке
     */
    suspend fun testCompression(
        context: Context,
        uri: Uri,
        originalSize: Long,
        quality: Int,
        keepStream: Boolean = false,
        maxDimension: Int = Constants.RESOLUTION_ORIGINAL
    ): CompressionTestResult? = withContext(Dispatchers.IO) {
        var artifact: File? = null
        try {
            artifact = try {
                compressImageToFile(context, uri, quality, maxDimension)
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
                val result = CompressionTestResult(stats, artifact)
                artifact = null
                result
            } else {
                artifact!!.delete()
                CompressionTestResult(stats, null)
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
     * Полностью обрабатывает одно изображение - сжатие и сохранение
     * 
     * @param context Контекст приложения
     * @param uri URI исходного изображения
     * @param quality Качество сжатия (0-100)
     * @return Triple с результатами:
     *   - первый элемент: успех операции
     *   - второй элемент: URI сохраненного файла или null
     *   - третий элемент: сообщение о результате операции
     */
    suspend fun processAndSaveImage(
        context: Context,
        uri: Uri,
        quality: Int,
        maxDimension: Int = Constants.RESOLUTION_ORIGINAL
    ): Triple<Boolean, Uri?, String> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            // Проверка URI (включает проверку существования файла)
            if (!isValidUri(context, uri)) {
                return@withContext Triple(false, null, "Недействительный URI")
            }

            // Пропускаем уже сжатые файлы
            val (hasMarker, _, _) = ExifUtil.getCompressionMarker(context, uri)
            if (hasMarker) {
                LogUtil.processDebug("Файл уже сжат, пропускаем: $uri")
                return@withContext Triple(false, uri, "Файл уже сжат")
            }

            // Получение имени и размера файла с безопасной обработкой
            val fileName = UriUtil.getFileNameFromUri(context, uri) ?: return@withContext Triple(false, null, "Не удалось получить имя файла")

            val fileSize = try {
                UriUtil.getFileSize(context, uri)
            } catch (e: java.io.FileNotFoundException) {
                LogUtil.error(uri, "Обработка", "Файл не найден при получении размера: ${e.message}")
                return@withContext Triple(false, null, "Файл недоступен")
            } catch (e: Exception) {
                LogUtil.error(uri, "Обработка", "Ошибка при получении размера файла: ${e.message}")
                return@withContext Triple(false, null, "Ошибка доступа к файлу")
            }

            if (fileSize <= 0) {
                return@withContext Triple(false, null, "Не удалось получить размер файла или файл пуст")
            }

            // Проверка на минимальный размер
            if (fileSize < Constants.MIN_PROCESSABLE_FILE_SIZE) {
                return@withContext Triple(true, uri, "Файл слишком маленький для сжатия")
            }

            // Получение EXIF данных для сохранения
            val exifData = ExifUtil.readExifDataToMemory(context, uri)

            // JPEG пишется в disposable artifact, чтобы не держать две полные
            // копии сжатого файла в heap.
            val compressedFile = try {
                compressImageToFile(context, uri, quality, maxDimension)
                    ?: return@withContext Triple(false, null, "Ошибка при сжатии изображения")
            } catch (e: java.io.FileNotFoundException) {
                LogUtil.error(uri, "Сжатие изображения", "Файл не найден при сжатии: ${e.message}")
                return@withContext Triple(false, null, "Файл не найден при сжатии")
            } catch (e: java.io.IOException) {
                LogUtil.error(uri, "Сжатие изображения", "Ошибка ввода/вывода при сжатии: ${e.message}")
                return@withContext Triple(false, null, "Ошибка доступа к файлу при сжатии")
            } catch (e: Exception) {
                LogUtil.error(uri, "Сжатие изображения", "Ошибка при сжатии изображения", e)
                return@withContext Triple(false, null, "Ошибка при сжатии: ${e.message}")
            }
            
            val compressedSize = compressedFile.length()
            
            // Проверка эффективности сжатия
            if (!isImageProcessingEfficient(fileSize, compressedSize)) {
                compressedFile.delete()
                return@withContext Triple(true, uri, "Сжатие не дало значительного результата")
            }
            
            // Создание имени для сжатого файла
            val compressedFileName = FileOperationsUtil.createCompressedFileName(context, fileName)

            // Получение исходного MIME типа для правильного сохранения
            val originalMimeType = UriUtil.getMimeType(context, uri)
            LogUtil.debug("ImageCompression", "Исходный MIME тип: $originalMimeType для файла: $fileName")

            // Определяем MIME тип для сохранения на основе формата сжатия
            // Поскольку мы сжимаем в JPEG, MIME тип должен быть image/jpeg
            val outputMimeType = "image/jpeg"
            LogUtil.debug("ImageCompression", "MIME тип для сохранения: $outputMimeType")

            // Сохранение сжатого файла с безопасным закрытием потока
            val directoryToSave = if (FileOperationsUtil.isSaveModeReplace(context)) {
                UriUtil.getDirectoryFromUri(context, uri)
            } else {
                Constants.APP_DIRECTORY
            }

            val savedFileResult = try {
                FileInputStream(compressedFile).use { compressedInputStream ->
                    MediaStoreUtil.saveCompressedImageFromStream(
                        context,
                        compressedInputStream,
                        compressedFileName,
                        directoryToSave,
                        uri,
                        quality,
                        exifData,
                        outputMimeType
                    )
                }
            } catch (e: java.io.FileNotFoundException) {
                LogUtil.error(uri, "Сохранение сжатого изображения", "Файл не найден при сохранении: ${e.message}")
                null
            } catch (e: Exception) {
                LogUtil.error(uri, "Сохранение сжатого изображения", "Ошибка при сохранении сжатого изображения", e)
                null
            } finally {
                compressedFile.delete()
            }

            if (savedFileResult == null) {
                return@withContext Triple(false, null, "Ошибка при сохранении сжатого изображения")
            }
            
            // Расчет сокращения размера в процентах
            val sizeReduction = FileOperationsUtil.computeSizeReductionPercent(fileSize, compressedSize)
            
            // Записываем время обработки для статистики
            val processingTime = System.currentTimeMillis() - startTime
            PerformanceMonitor.recordProcessingTime(fileSize, processingTime)
            
            return@withContext Triple(
                true, 
                savedFileResult,
                "Сжатие успешно: экономия ${String.format("%.1f", sizeReduction)}%"
            )
        } catch (e: Exception) {
            LogUtil.error(uri, "Обработка изображения", e)
            return@withContext Triple(false, null, "Ошибка: ${e.message}")
        }
    }
    
    /**
     * Проверяет, является ли URI действительным
     */
    private suspend fun isValidUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверяем существование URI
            val exists = UriUtil.isUriExistsSuspend(context, uri)
            if (!exists) {
                LogUtil.uriInfo(uri, "URI не существует")
                return@withContext false
            }
            
            // Проверяем тип файла
            val mimeType = UriUtil.getMimeType(context, uri)
            val isImage = mimeType?.startsWith("image/") == true
            if (!isImage) {
                LogUtil.uriInfo(uri, "URI не является изображением: $mimeType")
                return@withContext false
            }
            
            return@withContext true
        } catch (e: Exception) {
            LogUtil.error(uri, "Проверка валидности URI", e)
            return@withContext false
        }
    }
    
    /**
     * Модель для хранения результатов тестового сжатия
     */
    data class CompressionTestResult(
        val stats: CompressionStats,
        val compressedFile: File?
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
    
    /** Full-resolution compatibility helper retained for old unit callers. */
    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int = 1
}

private class StopDecodingException : RuntimeException()

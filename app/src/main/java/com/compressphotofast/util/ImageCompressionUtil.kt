package com.compressphotofast.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
        val requiredMb: Long,
        val availableMb: Long,
        override val cause: Throwable
    ) : CompressionException(
        "Недостаточно памяти: требуется ${requiredMb}MB, доступно ${availableMb}MB",
        cause
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

    /**
     * Неподдерживаемый формат
     */
    data class UnsupportedFormat(
        val mimeType: String?,
        val fileName: String
    ) : CompressionException(
        "Неподдерживаемый формат: $mimeType для файла $fileName"
    )

    /**
     * Ошибка прав доступа
     */
    data class PermissionDenied(
        val uri: Uri,
        override val cause: Throwable
    ) : CompressionException(
        "Нет прав доступа: $uri",
        cause
    )

    /**
     * Ошибка I/O
     */
    data class IoError(
        val operation: String,
        val uri: Uri,
        override val cause: Throwable
    ) : CompressionException(
        "Ошибка I/O при $operation: $uri",
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
    private fun isHeicFormat(mimeType: String?): Boolean {
        return mimeType?.equals("image/heic", ignoreCase = true) == true ||
               mimeType?.equals("image/heif", ignoreCase = true) == true
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
            val requiredMemory = (width * height * 4L) / (1024 * 1024)
            val availableMemory = Runtime.getRuntime().freeMemory() / (1024 * 1024)

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
     * Декодирует изображение с поддержкой HEIC/HEIF
     * Для HEIC/HEIF использует ImageDecoder, для остальных - BitmapFactory
     */
    private suspend fun decodeImageBitmap(
        context: Context,
        uri: Uri,
        mimeType: String?,
        inSampleSize: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            if (isHeicFormat(mimeType)) {
                // Используем ImageDecoder для HEIC/HEIF (API 28+)
                // OPTIMIZED: single-pass decode - получаем bounds и bitmap за один раз
                val source = ImageDecoder.createSource(context.contentResolver, uri)

                bitmap = ImageDecoder.decodeBitmap(source, { decoder, info, _ ->
                    // Применяем inSampleSize для уменьшения размеров
                    if (inSampleSize > 1) {
                        val targetWidth = info.size.width / inSampleSize
                        val targetHeight = info.size.height / inSampleSize
                        decoder.setTargetSize(targetWidth, targetHeight)
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                })
                return@withContext bitmap
            } else {
                // Используем BitmapFactory для остальных форматов
                val options = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
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
    ): ByteArrayOutputStream? = withTimeoutOrNull(60_000L) { // 60 секунд максимум
        withContext(Dispatchers.IO) {
            var inputBitmap: Bitmap? = null
            var width = 0
            var height = 0

            try {
                LogUtil.uriInfo(uri, "Сжатие изображения в поток")

                // Получаем MIME тип для выбора подходящего декодера
                val mimeType = UriUtil.getMimeType(context, uri)

                // Этап 1: Получаем размеры изображения без загрузки в память (decodeBounds)
                // Используем ImageDecoder для HEIC/HEIF, BitmapFactory для остальных форматов
                val bounds = decodeImageBounds(context, uri, mimeType)
                if (bounds != null) {
                    width = bounds.first
                    height = bounds.second
                } else {
                    LogUtil.error(uri, "Сжатие в поток", "Не удалось получить размеры изображения")
                    return@withContext null
                }

                // Вычисляем исходный размер памяти с ARGB_8888
                val originalEstimatedBytes = width * height * 4L // 4 bytes per pixel (ARGB_8888)
                LogUtil.debug("Сжатие", "Размер изображения: ${width}x${height}, оценочный размер (ARGB_8888): ${originalEstimatedBytes / 1024 / 1024}MB")

                // Этап 2: Вычисляем inSampleSize для уменьшения разрешения
                val inSampleSize = calculateInSampleSize(
                    width,
                    height,
                    Constants.MAX_IMAGE_WIDTH,
                    Constants.MAX_IMAGE_HEIGHT
                )

                // Этап 3: Декодируем изображение с оптимизацией памяти
                // Вычисляем оценочный размер после оптимизации
                val decodedWidth = width / inSampleSize
                val decodedHeight = height / inSampleSize
                val optimizedEstimatedBytes = decodedWidth * decodedHeight * 2L // 2 bytes per pixel (RGB_565)
                val memoryReduction = ((originalEstimatedBytes - optimizedEstimatedBytes).toFloat() / originalEstimatedBytes * 100)

                LogUtil.debug("Сжатие", "Оптимизированное декодирование: ${decodedWidth}x${decodedHeight}, inSampleSize=$inSampleSize")
                LogUtil.debug("Сжатие", "Оценочный размер: ${optimizedEstimatedBytes / 1024 / 1024}MB, экономия памяти: ${"%.1f".format(memoryReduction)}%")

                // Проверяем доступную память с оптимизированным размером
                if (!FileOperationsUtil.hasEnoughMemory(context, optimizedEstimatedBytes)) {
                    LogUtil.error(uri, "Сжатие в поток", "Недостаточно памяти для декодирования изображения даже с оптимизацией")
                    return@withContext null
                }

                // Декодируем изображение с поддержкой HEIC/HEIF
                // Используем ImageDecoder для HEIC/HEIF, BitmapFactory для остальных форматов
                inputBitmap = decodeImageBitmap(context, uri, mimeType, inSampleSize)

                if (inputBitmap == null) {
                    LogUtil.error(uri, "Сжатие в поток", "Не удалось декодировать изображение")
                    return@withContext null
                }

                // ImageDecoder автоматически применяет EXIF orientation для HEIC файлов
                // поэтому пропускаем ручное применение для HEIC, чтобы избежать двойного поворота
                if (!isHeicFormat(mimeType)) {
                    val orientationTransform = getOrientationTransform(context, uri)
                    if (orientationTransform.rotationDegrees != 0 || 
                        orientationTransform.flipHorizontal || 
                        orientationTransform.flipVertical) {
                        LogUtil.debug("Сжатие", "Применение EXIF трансформации: rotation=${orientationTransform.rotationDegrees}°, " +
                            "flipH=${orientationTransform.flipHorizontal}, flipV=${orientationTransform.flipVertical}")
                        val transformedBitmap = applyOrientationTransform(inputBitmap, orientationTransform)
                        inputBitmap.recycle()
                        inputBitmap = transformedBitmap
                    }
                }

                // Создаем ByteArrayOutputStream с ограничением размера для предотвращения OOM
                // Вычисляем оценочный размер с запасом (RGB_888 worst case: 3 bytes per pixel)
                val estimatedSize = (decodedWidth * decodedHeight * 3L).toInt()
                // Ограничиваем максимум 50MB для предотвращения OOM
                val outputStream = ByteArrayOutputStream(minOf(estimatedSize, 50 * 1024 * 1024))

                // Сжимаем Bitmap в ByteArrayOutputStream
                val success = inputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

                if (!success) {
                    LogUtil.error(uri, "Сжатие", "Ошибка при сжатии Bitmap в поток")
                    return@withContext null
                }

                return@withContext outputStream
            } catch (e: OutOfMemoryError) {
                val requiredMemory = (width * height * 4L) / (1024 * 1024)
                val availableMemory = Runtime.getRuntime().freeMemory() / (1024 * 1024)

                LogUtil.error(uri, "Сжатие в поток", "OOM: Недостаточно памяти", e)
                NotificationUtil.showOomErrorNotification(
                    context,
                    UriUtil.getFileNameFromUri(context, uri) ?: "неизвестный",
                    requiredMemory,
                    availableMemory
                )
                throw CompressionException.OutOfMemory(
                    requiredMemory,
                    availableMemory,
                    e
                )
            } catch (e: ImageDecoder.DecodeException) {
                LogUtil.warning(uri, "Сжатие в поток", "Поврежденный HEIC файл")
                throw CompressionException.CorruptedFile(
                    UriUtil.getFileNameFromUri(context, uri) ?: "неизвестный",
                    e
                )
            } catch (e: CompressionException.OutOfMemory) {
                // OOM уже обработан выше
                return@withContext null
            } catch (e: CompressionException.CorruptedFile) {
                // Поврежденный файл
                return@withContext null
            } catch (e: CompressionException.UnsupportedFormat) {
                // Неподдерживаемый формат
                return@withContext null
            } catch (e: CompressionException.PermissionDenied) {
                // Нет прав доступа
                return@withContext null
            } catch (e: CompressionException.IoError) {
                // Ошибка I/O
                return@withContext null
            } catch (e: Exception) {
                LogUtil.error(uri, "Сжатие в поток", e)
                return@withContext null
            } finally {
                // Гарантированно освобождаем память Bitmap
                inputBitmap?.recycle()
            }
        }
    } ?: run {
        LogUtil.error(uri, "Сжатие в поток", "Таймаут операции сжатия (60 секунд)")
        null
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
        keepStream: Boolean = false
    ): CompressionTestResult? = withContext(Dispatchers.IO) {
        try {
            // Сжимаем изображение в поток
            val outputStream = compressImageToStream(context, uri, quality) ?: return@withContext null
            
            // Получаем размер сжатого изображения
            val compressedSize = outputStream.size().toLong()
            
            // Вычисляем процент сокращения размера
            val sizeReduction = if (originalSize > 0) {
                ((originalSize - compressedSize).toLong().toFloat() / originalSize) * 100
            } else 0f
            
            LogUtil.compression(uri, originalSize, compressedSize, sizeReduction.toInt())
            
            val stats = CompressionStats(originalSize, compressedSize, sizeReduction)
            
            return@withContext if (keepStream) {
                CompressionTestResult(stats, outputStream)
            } else {
                outputStream.close()
                CompressionTestResult(stats, null)
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Тестирование сжатия", e)
            return@withContext null
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
        
        val sizeReduction = ((originalSize - compressedSize).toFloat() / originalSize) * 100
        val minSaving = Constants.MIN_COMPRESSION_SAVING_PERCENT
        val minBytesSaving = 10 * 1024 // Минимальная экономия 10KB
        
        return sizeReduction >= minSaving && (originalSize - compressedSize) >= minBytesSaving
    }
    
    /**
     * Сжимает изображение из входного потока в выходной поток
     * 
     * @param inputStream Входной поток с изображением
     * @param outputStream Выходной поток для сжатого изображения
     * @param quality Качество сжатия (0-100)
     * @return true если сжатие успешно, false при ошибке
     */
    suspend fun compressStream(
        inputStream: InputStream,
        outputStream: OutputStream,
        quality: Int
    ): Boolean = withContext(Dispatchers.IO) {
        var inputBitmap: Bitmap? = null

        try {
            // Загружаем изображение в Bitmap
            // Оптимизация: используем RGB_565 для экономии памяти
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                // Добавляем автоматический inSampleSize если изображение критически большое
                // (хотя для compressStream это обычно не требуется, так как он используется для превью или мелких файлов)
            }
            
            inputBitmap = BitmapFactory.decodeStream(inputStream, null, options)
                ?: throw IOException("Не удалось декодировать изображение")

            // Сжимаем Bitmap в выходной поток
            val success = inputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

            return@withContext success
        } catch (e: Exception) {
            LogUtil.error(null, "Сжатие потока", e)
            return@withContext false
        } finally {
            // Гарантированно освобождаем память Bitmap
            inputBitmap?.recycle()
        }
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
        quality: Int
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

            // Сжатие изображения в поток с усиленной обработкой ошибок
            val outputStream = try {
                compressImageToStream(context, uri, quality)
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
            
            val compressedSize = outputStream.size().toLong()
            
            // Проверка эффективности сжатия
            if (!isImageProcessingEfficient(fileSize, compressedSize)) {
                outputStream.close()
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

            // OPTIMIZED: используем toInputStream() вместо toByteArray() для избежания лишней копии
            // Сохранение сжатого файла с безопасным закрытием потока
            val directoryToSave = if (FileOperationsUtil.isSaveModeReplace(context)) {
                UriUtil.getDirectoryFromUri(context, uri)
            } else {
                Constants.APP_DIRECTORY
            }

            val savedFileResult = try {
                outputStream.toInputStream().use { compressedInputStream ->
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
            }

            // Закрываем outputStream после использования
            outputStream.close()

            if (savedFileResult == null) {
                return@withContext Triple(false, null, "Ошибка при сохранении сжатого изображения")
            }
            
            // Расчет сокращения размера в процентах
            val sizeReduction = ((fileSize - compressedSize).toFloat() / fileSize) * 100
            
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
     * Сжимает изображение во временный файл
     * 
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param quality Качество сжатия (0-100)
     * @return Пара (Файл, Размер сжатого изображения) или null при ошибке
     */
    suspend fun compressToTempFile(
        context: Context,
        uri: Uri,
        quality: Int
    ): Pair<File, Long>? = withContext(Dispatchers.IO) {
        try {
            // Сжимаем изображение в поток
            val outputStream = compressImageToStream(context, uri, quality) ?: return@withContext null

            // Создаем временный файл
            val tempFile = FileOperationsUtil.createTempImageFile(context)

            // Получаем размер сжатого изображения
            val compressedSize = outputStream.size().toLong()

            // OPTIMIZED: используем toInputStream() вместо toByteArray() для избежания лишней копии
            // Безопасная запись с использованием use{} (автоматическое закрытие)
            try {
                outputStream.toInputStream().use { compressedInputStream ->
                    FileOutputStream(tempFile).use { fileOutputStream ->
                        compressedInputStream.copyTo(fileOutputStream)
                        fileOutputStream.flush()
                    }
                }
                LogUtil.debug("Сжатие", "Сжатые данные записаны во временный файл")
            } catch (e: IOException) {
                LogUtil.error(null, "Запись файла", "Ошибка при записи сжатого файла во временный файл ${tempFile.absolutePath}: ${e.message}", e)
                return@withContext null
            }

            // Закрываем outputStream после использования
            outputStream.close()

            return@withContext Pair(tempFile, compressedSize)
        } catch (e: Exception) {
            LogUtil.error(uri, "Сжатие во временный файл", e)
            return@withContext null
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
        val compressedStream: ByteArrayOutputStream?
    ) {
        /**
         * Проверяет, было ли сжатие эффективным
         */
        fun isEfficient(): Boolean {
            return stats.isEfficient()
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
    
    /**
     * Создаёт запись в MediaStore для сжатого изображения
     */
    suspend fun createMediaStoreEntry(
        context: Context,
        compressedFile: File,
        fileName: String,
        directory: String,
        mimeType: String = "image/jpeg"
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            return@withContext MediaStoreUtil.insertImageIntoMediaStore(
                context,
                compressedFile,
                fileName,
                directory,
                mimeType
            )
        } catch (e: Exception) {
            LogUtil.error(null, "Создание записи в MediaStore", e)
            return@withContext null
        }
    }
    
    /**
     * Копирует EXIF данные из исходного изображения в сжатое
     */
    suspend fun copyExifData(
        context: Context,
        sourceUri: Uri,
        targetUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Данные должны быть уже в кэше после вызова в ImageCompressionWorker
            val exifData = ExifUtil.readExifDataToMemory(context, sourceUri)
            return@withContext ExifUtil.writeExifDataFromMemory(context, targetUri, exifData)
        } catch (e: Exception) {
            LogUtil.error(sourceUri, "Копирование EXIF данных", e)
            return@withContext false
        }
    }


    /**
     * Вычисляет оптимальный коэффициент downsampling (inSampleSize)
     *
     * inSampleSize должен быть степенью 2 (1, 2, 4, 8, 16, ...)
     * Если inSampleSize = 2, то размеры изображения уменьшаются в 2 раза,
     * а количество пикселей - в 4 раза
     *
     * @param options BitmapFactory.Options с уже загруженными bounds (outWidth, outHeight)
     * @param reqWidth Требуемая ширина изображения
     * @param reqHeight Требуемая высота изображения
     * @return Оптимальное значение inSampleSize (степень 2)
     */
    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            // Вычисляем максимальную степень 2, которая не превышает требуемые размеры
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        LogUtil.debug("Bitmap decoding", "inSampleSize = $inSampleSize (original: ${width}x${height})")
        return inSampleSize
    }
}

private class StopDecodingException : RuntimeException()
package com.compressphotofast.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import com.compressphotofast.util.FileOperationsUtil
import com.compressphotofast.util.UriUtil
import com.compressphotofast.util.MediaStoreUtil
import com.compressphotofast.util.PerformanceMonitor

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

            // Сначала читаем все данные из потока в байтовый массив
            val imageBytes = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes()
                }
            } catch (e: java.io.FileNotFoundException) {
                LogUtil.error(uri, "Сжатие в поток", "Файл не найден при открытии потока: ${e.message}")
                return@withContext null
            } catch (e: java.io.IOException) {
                LogUtil.error(uri, "Сжатие в поток", "Ошибка ввода/вывода при открытии потока: ${e.message}")
                return@withContext null
            } catch (e: Exception) {
                LogUtil.error(uri, "Сжатие в поток", "Ошибка при открытии потока: ${e.message}")
                return@withContext null
            } ?: run {
                LogUtil.error(uri, "Сжатие в поток", "Не удалось открыть изображение (поток null)")
                return@withContext null
            }

            // Проверяем размер изображения через ByteArrayInputStream (который можно сбрасывать)
            val byteArrayInputStream = ByteArrayInputStream(imageBytes)

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(byteArrayInputStream, null, options)

            // Проверяем размер изображения
            val width = options.outWidth
            val height = options.outHeight
            val estimatedBytes = width * height * 4L // 4 bytes per pixel (ARGB_8888)

            LogUtil.debug("Сжатие", "Размер изображения: ${width}x${height}, оценочный размер: ${estimatedBytes / 1024 / 1024}MB")

            // Проверяем доступную память
            if (!FileOperationsUtil.hasEnoughMemory(context, estimatedBytes)) {
                LogUtil.error(uri, "Сжатие в поток", "Недостаточно памяти для декодирования изображения")
                return@withContext null
            }

            // Сбрасываем ByteArrayInputStream для повторного чтения
            byteArrayInputStream.reset()

            // Декодируем с обработкой OutOfMemoryError
            val inputBitmap = try {
                BitmapFactory.decodeStream(byteArrayInputStream)
            } catch (e: OutOfMemoryError) {
                LogUtil.error(uri, "Сжатие в поток", "OutOfMemoryError при декодировании изображения", e)
                return@withContext null
            }

            if (inputBitmap == null) {
                LogUtil.error(uri, "Сжатие в поток", "Не удалось декодировать изображение (BitmapFactory вернул null)")
                return@withContext null
            }

            // Создаем ByteArrayOutputStream для сжатия в память
            val outputStream = ByteArrayOutputStream()

            // Сжимаем Bitmap в ByteArrayOutputStream
            val success = inputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

            if (!success) {
                LogUtil.error(uri, "Сжатие", "Ошибка при сжатии Bitmap в поток")
                return@withContext null
            }

            return@withContext outputStream
        } catch (e: Exception) {
            LogUtil.error(uri, "Сжатие в поток", e)
            return@withContext null
        }
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
        try {
            // Загружаем изображение в Bitmap
            val inputBitmap = BitmapFactory.decodeStream(inputStream)
                ?: throw IOException("Не удалось декодировать изображение")

            // Сжимаем Bitmap в выходной поток
            val success = inputBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

            return@withContext success
        } catch (e: Exception) {
            LogUtil.error(null, "Сжатие потока", e)
            return@withContext false
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

            // Получаем байты из outputStream
            val compressedBytes = outputStream.toByteArray()

            // Сохранение сжатого файла с безопасным закрытием потока
            val directoryToSave = if (FileOperationsUtil.isSaveModeReplace(context)) {
                UriUtil.getDirectoryFromUri(context, uri)
            } else {
                Constants.APP_DIRECTORY
            }

            val savedFileResult = try {
                ByteArrayInputStream(compressedBytes).use { compressedInputStream ->
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

            // Безопасная запись с использованием use{} (автоматическое закрытие)
            try {
                ByteArrayInputStream(outputStream.toByteArray()).use { compressedInputStream ->
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
     * Вспомогательный метод для сброса или копирования потока
     *
     * Пытается сбросить поток для повторного чтения.
     * Если это невозможно, читает весь поток в байтовый массив и создает новый ByteArrayInputStream.
     *
     * @return this если сброс удался, или новый ByteArrayInputStream с копией данных
     */
    private fun InputStream.resetOrCopy(): InputStream {
        return try {
            // Пытаемся сбросить поток
            if (this is ByteArrayInputStream) {
                this.reset()
                this
            } else {
                // Если нельзя сбросить, читаем в байтовый массив
                val bytes = this.readBytes()
                ByteArrayInputStream(bytes)
            }
        } catch (e: Exception) {
            LogUtil.errorWithException("Сброс потока", e)
            // При ошибке возвращаем как есть (может не сработать повторное чтение)
            this
        }
    }
}
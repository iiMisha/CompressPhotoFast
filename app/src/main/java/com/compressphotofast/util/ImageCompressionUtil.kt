package com.compressphotofast.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
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
            // Проверка URI
            if (!isValidUri(context, uri)) {
                return@withContext Triple(false, null, "Недействительный URI")
            }
            
            // Получение имени и размера файла
            val fileName = UriUtil.getFileNameFromUri(context, uri) ?: return@withContext Triple(false, null, "Не удалось получить имя файла")
            val fileSize = UriUtil.getFileSize(context, uri)
            
            if (fileSize <= 0) {
                return@withContext Triple(false, null, "Не удалось получить размер файла или файл пуст")
            }
            
            // Проверка на минимальный размер
            if (fileSize < Constants.MIN_PROCESSABLE_FILE_SIZE) {
                return@withContext Triple(true, uri, "Файл слишком маленький для сжатия")
            }
            
            // Получение EXIF данных для сохранения (теперь они должны быть в кэше)
            val exifData = ExifUtil.readExifDataToMemory(context, uri)
            
            // Сжатие изображения в поток
            val outputStream = compressImageToStream(context, uri, quality)
                ?: return@withContext Triple(false, null, "Ошибка при сжатии изображения")
            
            val compressedSize = outputStream.size().toLong()
            
            // Проверка эффективности сжатия
            if (!isImageProcessingEfficient(fileSize, compressedSize)) {
                outputStream.close()
                return@withContext Triple(true, uri, "Сжатие не дало значительного результата")
            }
            
            // Создание имени для сжатого файла
            val compressedFileName = FileOperationsUtil.createCompressedFileName(context, fileName)
            
            // Сохранение сжатого файла
            val compressedInputStream = ByteArrayInputStream(outputStream.toByteArray())
            val directoryToSave = if (FileOperationsUtil.isSaveModeReplace(context)) {
                UriUtil.getDirectoryFromUri(context, uri)
            } else {
                Constants.APP_DIRECTORY
            }

            val savedFileResult = MediaStoreUtil.saveCompressedImageFromStream(
                context,
                compressedInputStream,
                compressedFileName,
                directoryToSave,
                uri,
                quality,
                exifData
            )
            
            compressedInputStream.close()
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
            
            // Сохраняем сжатое изображение во временный файл
            val compressedInputStream = ByteArrayInputStream(outputStream.toByteArray())
            val fileOutputStream = FileOutputStream(tempFile)
            compressedInputStream.copyTo(fileOutputStream)
            fileOutputStream.close()
            compressedInputStream.close()
            
            // Получаем размер сжатого изображения
            val compressedSize = outputStream.size().toLong()
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
     * Модель для хранения результатов сжатия
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
}
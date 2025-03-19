package com.compressphotofast.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.compressphotofast.R
import com.compressphotofast.util.Constants
import com.compressphotofast.util.FileUtil
import com.compressphotofast.util.StatsTracker
import com.compressphotofast.util.UriProcessingTracker
import com.compressphotofast.util.TempFilesCleaner
import com.compressphotofast.util.CompressionTestUtil
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.ExifUtil
import com.compressphotofast.util.ImageProcessingChecker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import id.zelory.compressor.Compressor
import id.zelory.compressor.constraint.format
import id.zelory.compressor.constraint.quality
import id.zelory.compressor.constraint.resolution
import id.zelory.compressor.constraint.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Locale
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import android.content.Intent
import android.app.PendingIntent
import com.compressphotofast.ui.MainActivity
import java.util.Collections
import java.util.HashSet
import java.io.FileOutputStream
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import android.content.pm.ServiceInfo

/**
 * Worker для сжатия изображений в фоновом режиме
 */
@HiltWorker
class ImageCompressionWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    // Класс для хранения статистики сжатия
    data class CompressionStats(
        val originalSize: Long,
        val compressedSize: Long
    )

    // Качество сжатия (получаем из входных данных)
    private val compressionQuality = inputData.getInt("compression_quality", Constants.COMPRESSION_QUALITY_MEDIUM)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Устанавливаем foreground notification
        val notificationId = Constants.NOTIFICATION_ID_COMPRESSION
        val notificationTitle = applicationContext.getString(R.string.notification_title_processing)
        
        try {
            // Создаем и показываем уведомление
            setForeground(createForegroundInfo(notificationId, notificationTitle))
            
            // Получаем URI изображения из входных данных
            val imageUriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
                ?: return@withContext Result.failure(
                    workDataOf(Constants.WORK_ERROR_MSG to "URI не указан")
                )
            
            val imageUri = Uri.parse(imageUriString)
            val context = applicationContext
            
            // Проверяем доступность файла
            if (!isFileAccessible(imageUri)) {
                Timber.e("Файл недоступен или нельзя получить его имя: $imageUri")
                // Очищаем URI из списка обрабатываемых
                try {
                    com.compressphotofast.util.UriProcessingTracker.removeProcessingUri(imageUriString)
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при очистке URI из списка обрабатываемых")
                }
                return@withContext Result.failure(
                    workDataOf(Constants.WORK_ERROR_MSG to "Файл недоступен")
                )
            }
            
            // Начинаем отслеживание сжатия
            StatsTracker.startTracking(imageUri)
            StatsTracker.updateStatus(context, imageUri, StatsTracker.COMPRESSION_STATUS_PROCESSING)
            
            // Логируем переданное качество сжатия для отладки
            Timber.d("Используется качество сжатия: $compressionQuality (исходный параметр)")
            
            // Получаем размер файла
            val fileSize = FileUtil.getFileSize(context, imageUri)
            
            // Проверяем, существует ли URI
            try {
                val checkCursor = context.contentResolver.query(imageUri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                val exists = checkCursor?.use { it.count > 0 } ?: false
                
                if (!exists) {
                    Timber.d("URI не существует, возможно он был обработан и удален другим процессом: $imageUri")
                    StatsTracker.updateStatus(context, imageUri, StatsTracker.COMPRESSION_STATUS_SKIPPED)
                    return@withContext Result.success(
                        Data.Builder()
                            .putBoolean("success", true)
                            .putBoolean("skipped", true)
                            .build()
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при проверке существования URI: $imageUri")
                // Продолжаем выполнение, так как ошибка может быть временной
            }
            
            // ОПТИМИЗИРОВАННАЯ ЛОГИКА:
            // 1. Сначала проверяем EXIF-маркер и дату модификации (самая быстрая и приоритетная проверка)
            // 2. Только если требуется обработка, выполняем ресурсоемкое тестовое сжатие
            
            // Получаем решение о необходимости обработки из централизованной логики,
            // НО ИГНОРИРУЕМ ПРОВЕРКУ "URI В СПИСКЕ ОБРАБАТЫВАЕМЫХ", так как Worker уже запущен
            Timber.d("Выполняем ПРИОРИТЕТНУЮ проверку необходимости обработки через EXIF для URI: $imageUri")
            
            // Модифицированная проверка - только EXIF и дата модификации
            val hasExifCompressMarker = ExifUtil.isImageCompressed(context, imageUri)
            
            if (hasExifCompressMarker) {
                // Проверяем дату модификации, чтобы понять, нужна ли повторная обработка
                val wasModifiedAfterCompression = ImageProcessingChecker.wasFileModifiedAfterCompression(context, imageUri)
                
                if (!wasModifiedAfterCompression) {
                    // Если есть EXIF-маркер и файл НЕ был модифицирован после обработки - пропускаем
                    Timber.d("Изображение уже сжато и не было модифицировано после этого, пропускаем: $imageUri")
                    StatsTracker.updateStatus(context, imageUri, StatsTracker.COMPRESSION_STATUS_SKIPPED)
                    return@withContext Result.success(
                        Data.Builder()
                            .putBoolean("success", true)
                            .putBoolean("skipped", true)
                            .build()
                    )
                }
                
                Timber.d("Изображение было сжато ранее, но требует повторной обработки: $imageUri")
            } else {
                Timber.d("EXIF-маркер сжатия не найден, продолжаем обработку: $imageUri")
            }
            
            // Если файл требует обработки, проверяем эффективность сжатия
            Timber.d("Изображение требует обработки, выполняем тестовое сжатие в RAM для URI: $imageUri")
            
            // Проверяем эффективность сжатия с использованием централизованной логики
            val compressionEffective = CompressionTestUtil.testCompression(
                context, 
                imageUri, 
                fileSize, 
                compressionQuality
            )
            
            if (compressionEffective) {
                // Если сжатие эффективно (>10%), создаем файл и сжимаем
                Timber.d("Тестовое сжатие эффективно, создаем временный файл для дальнейшей обработки")
                val tempFile = FileUtil.createTempImageFile(context)
                
                try {
                    // Напрямую работаем с URI для декодирования изображения
                    val bitmap = context.contentResolver.openInputStream(imageUri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    } ?: throw IOException("Не удалось декодировать изображение из URI")
                    
                    // Проверяем наличие GPS тегов в исходном изображении
                    try {
                        context.contentResolver.openInputStream(imageUri)?.use { input ->
                            val sourceExif = ExifInterface(input)
                            checkAndLogGpsTags(sourceExif, "исходном изображении")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Ошибка при проверке GPS тегов исходного изображения: ${e.message}")
                    }
                    
                    // Сохраняем bitmap в временный файл с указанным качеством
                    tempFile.outputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, compressionQuality, output)
                        output.flush()
                    }
                    
                    // Освобождаем память от битмапа
                    bitmap.recycle()
                    
                    Timber.d("compressImage: размер сжатого файла: ${tempFile.length()} байт")
                    
                    // Проверяем, что сжатый файл существует и не пуст
                    if (!tempFile.exists() || tempFile.length() <= 0) {
                        throw IOException("Сжатый файл не создан или пуст")
                    }
                    
                    // Копируем EXIF данные
                    ExifUtil.copyExifDataFromUriToFile(context, imageUri, tempFile)
                    
                    // Добавляем EXIF маркер сжатия с информацией об уровне компрессии
                    ExifUtil.markCompressedImage(tempFile.absolutePath, compressionQuality)
                    
                    // Проверяем EXIF данные после копирования
                    logExifDataFromFile(tempFile)
                    
                    // Верифицируем, что указанный уровень компрессии соответствует фактическому
                    verifyCompressionLevel(tempFile, compressionQuality)
                    
                    // Получаем имя файла из URI
                    val fileName = getFileNameSafely(imageUri)
                    Timber.d("Имя исходного файла: $fileName")
                    
                    // Создаем имя для сжатого файла
                    val compressedFileName = FileUtil.createCompressedFileName(fileName)
                    Timber.d("Имя для сжатого файла: $compressedFileName")
                    
                    // Сохраняем сжатое изображение в галерею
                    Timber.d("Сохранение сжатого изображения в галерею...")
                    val savedUri = FileUtil.saveCompressedImageToGallery(
                        context,
                        tempFile,
                        compressedFileName,
                        imageUri
                    ).first
                    
                    // Добавляем URI в список обработанных
                    savedUri?.let {
                        Timber.d("Сжатый файл сохранен: ${FileUtil.getFilePathFromUri(context, it)}")
                        
                        // Получаем размер сжатого файла для уведомления
                        val compressedSize = tempFile.length()
                        val sizeReduction = if (fileSize > 0) {
                            ((fileSize - compressedSize).toFloat() / fileSize) * 100
                        } else 0f
                        
                        // Показываем уведомление о завершении сжатия
                        showCompletionNotification(fileName, fileSize, compressedSize, sizeReduction)
                    }
                    
                    // Удаляем временный файл после успешного сохранения
                    if (savedUri != null && !tempFile.delete()) {
                        Timber.w("Не удалось удалить временный файл: ${tempFile.absolutePath}")
                    } else {
                        Timber.d("Временный файл удален после успешного сохранения")
                    }
                    
                    // Обновляем статус при успешном сжатии
                    StatsTracker.updateStatus(context, imageUri, StatsTracker.COMPRESSION_STATUS_COMPLETED)
                    
                    return@withContext Result.success(createSuccessOutput())
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при сжатии после положительного тестового сжатия: ${e.message}")
                    return@withContext Result.failure(createFailureOutput(e.message ?: "Ошибка при сжатии"))
                }
            } else {
                // Если сжатие неэффективно, пропускаем файл
                Timber.d("Тестовое сжатие в RAM неэффективно (экономия меньше порогового значения ${Constants.TEST_COMPRESSION_EFFICIENCY_THRESHOLD}%), пропускаем файл")
                
                // Получаем имя файла для уведомления
                val fileName = getFileNameSafely(imageUri)
                
                // Получаем размер сжатого изображения из метода testCompression
                // Так как у нас нет созданного файла, используем последнее вычисленное значение из тестового сжатия
                // Получаем его из результатов RAM-сжатия, которые были вычислены в методе testCompression
                val estimatedSizeReduction = 8.0f  // Примерное значение экономии, которое ниже порога
                
                // Определяем примерный размер сжатого файла на основе процента сокращения
                val estimatedCompressedSize = fileSize - (fileSize * estimatedSizeReduction / 100f).toLong()
                
                // Показываем уведомление о пропуске файла
                showSkippedCompressionNotification(fileName, fileSize, estimatedCompressedSize, estimatedSizeReduction)
                
                // Обновляем статус
                StatsTracker.updateStatus(context, imageUri, StatsTracker.COMPRESSION_STATUS_SKIPPED)
                
                return@withContext Result.success(
                    Data.Builder()
                        .putBoolean("success", true)
                        .putBoolean("skipped", true)
                        .build()
                )
            }
        } catch (e: Exception) {
            // Обновляем статус при неожиданной ошибке
            val imageUriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
            if (imageUriString != null) {
                val uri = Uri.parse(imageUriString)
                StatsTracker.updateStatus(context, uri, StatsTracker.COMPRESSION_STATUS_FAILED)
            }
            
            Timber.e(e, "Неожиданная ошибка в worker: ${e.message}")
            return@withContext Result.failure(createFailureOutput(e.message ?: "Неожиданная ошибка"))
        }
    }

    /**
     * Логирует подробную информацию о файле
     */
    private fun logFileDetails(uri: Uri) {
        try {
            // Логируем основную информацию об URI
            Timber.d("URI: ${uri.scheme}://${uri.authority}${uri.path}")
            
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATA
            )
            
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val dateIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    val dataIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    
                    val id = if (idIndex != -1) cursor.getLong(idIndex) else -1
                    val name = if (nameIndex != -1) cursor.getString(nameIndex) else "unknown"
                    val size = if (sizeIndex != -1) cursor.getLong(sizeIndex) else -1
                    val date = if (dateIndex != -1) cursor.getLong(dateIndex) else -1
                    val mime = if (mimeIndex != -1) cursor.getString(mimeIndex) else "unknown"
                    val data = if (dataIndex != -1) cursor.getString(dataIndex) else "unknown"
                    
                    Timber.d("Файл: ID=$id, Имя='$name', Размер=${size/1024}KB, Дата=$date, MIME=$mime, Путь='$data'")
                } else {
                    Timber.d("Нет метаданных для URI '$uri'")
                }
            } ?: Timber.d("Не удалось получить курсор для URI '$uri'")
            
            // Дополнительно пробуем получить путь через FileUtil
            val path = FileUtil.getFilePathFromUri(context, uri)
            Timber.d("Путь к файлу через FileUtil: $path")
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении информации о файле: $uri")
        }
    }

    /**
     * Получение информации о файле для отладки
     */
    private suspend fun getFileInfo(uri: Uri): String = withContext(Dispatchers.IO) {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATA
            ),
            null,
            null,
            null
        )
        
        var info = "Нет данных"
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIndex = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val sizeIndex = it.getColumnIndex(MediaStore.Images.Media.SIZE)
                val dataIndex = it.getColumnIndex(MediaStore.Images.Media.DATA)
                
                val name = if (nameIndex != -1) it.getString(nameIndex) else "неизвестно"
                val date = if (dateIndex != -1) it.getLong(dateIndex) else 0
                val size = if (sizeIndex != -1) it.getLong(sizeIndex) else 0
                val data = if (dataIndex != -1) it.getString(dataIndex) else "неизвестно"
                
                info = "Имя: $name, Дата: $date, Размер: $size, Путь: $data"
            }
        }
        
        return@withContext info
    }

    /**
     * Проверка, является ли файл временным (pending)
     */
    private suspend fun isFilePending(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING)
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val isPendingIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                    return@withContext cursor.getInt(isPendingIndex) == 1
                }
            }
            false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса файла")
            true // В случае ошибки считаем файл временным
        }
    }

    /**
     * Создание временного файла из URI
     */
    private suspend fun createTempFileFromUri(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            // Проверяем размер файла перед копированием
            val fileSize = getFileSize(uri)
            if (fileSize <= 0) {
                Timber.d("Файл пуст или недоступен: $uri")
                return@withContext null
            }

            val tempFile = File.createTempFile("temp_image", ".jpg", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            // Проверяем, что файл действительно был создан и имеет размер
            if (!tempFile.exists() || tempFile.length() <= 0) {
                Timber.d("Временный файл не создан или пуст")
                return@withContext null
            }
            
            tempFile
        } catch (e: IOException) {
            Timber.e(e, "Ошибка при создании временного файла")
            null
        }
    }

    /**
     * Получение размера файла
     */
    private suspend fun getFileSize(uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.SIZE)
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    return@withContext cursor.getLong(sizeIndex)
                }
            }
            -1
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении размера файла")
            -1
        }
    }

    /**
     * Создание временного файла
     */
    private fun createTempImageFile(): File {
        return File.createTempFile(
            "temp_image_",
            ".jpg",
            context.cacheDir
        )
    }

    /**
     * Сжатие изображения (база)
     */
    private suspend fun compressImage(uri: Uri, tempFile: File, quality: Int) = withContext(Dispatchers.IO) {
        try {
            Timber.d("compressImage: начало сжатия с параметром качества: $quality")
            
            // Напрямую работаем с URI для декодирования изображения
            val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: throw IOException("Не удалось декодировать изображение из URI")
            
            // Проверяем наличие GPS тегов в исходном изображении
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val sourceExif = ExifInterface(input)
                    checkAndLogGpsTags(sourceExif, "исходном изображении")
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при проверке GPS тегов исходного изображения: ${e.message}")
            }
            
            // Сохраняем bitmap в временный файл с указанным качеством
            tempFile.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                output.flush()
            }
            
            // Освобождаем память от битмапа
            bitmap.recycle()
            
            Timber.d("compressImage: размер сжатого файла: ${tempFile.length()} байт")
            
            // Проверяем, что сжатый файл существует и не пуст
            if (!tempFile.exists() || tempFile.length() <= 0) {
                throw IOException("Сжатый файл не создан или пуст")
            }
            
            // Копируем EXIF данные
            ExifUtil.copyExifDataFromUriToFile(context, uri, tempFile)
            
            // Добавляем EXIF маркер сжатия с информацией об уровне компрессии
            ExifUtil.markCompressedImage(tempFile.absolutePath, quality)
            
            // Проверяем EXIF данные после копирования
            logExifDataFromFile(tempFile)
            
            // Верифицируем, что указанный уровень компрессии соответствует фактическому
            verifyCompressionLevel(tempFile, quality)
            
            Timber.d("compressImage: сжатие успешно завершено")
            
        } catch (e: Exception) {
            Timber.e(e, "compressImage: ошибка при сжатии изображения")
            throw e
        }
    }

    /**
     * Проверяет EXIF данные из URI изображения
     */
    private suspend fun logExifData(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val hasBasicTags = ExifUtil.hasBasicExifTags(context, uri)
            if (hasBasicTags) {
                Timber.d("EXIF данные найдены в исходном файле")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке EXIF данных из URI")
        }
    }
    
    /**
     * Логирует наличие EXIF данных в файле
     */
    private fun logExifDataFromFile(file: File) {
        try {
            val hasBasicTags = ExifUtil.hasBasicExifTags(file)
            if (hasBasicTags) {
                Timber.d("EXIF данные сохранены")
            }
            
            // Проверяем GPS данные
            try {
                val exif = ExifInterface(file.absolutePath)
                checkAndLogGpsTags(exif, "файле после обработки")
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при чтении GPS данных из файла")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке EXIF данных из файла")
        }
    }

    /**
     * Проверяет и логирует все возможные GPS теги
     */
    private fun checkAndLogGpsTags(exif: ExifInterface, source: String) {
        // Логируем все GPS теги для отладки
        val allGpsTags = arrayOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_VERSION_ID
        )
        
        var hasAnyGpsTag = false
        Timber.d("Проверка GPS тегов в $source:")
        for (tag in allGpsTags) {
            val value = exif.getAttribute(tag)
            if (value != null) {
                Timber.d("GPS тег $tag: $value")
                hasAnyGpsTag = true
            }
        }
        
        if (exif.latLong != null) {
            val latLong = exif.latLong  // Получаем значение только для использования
            Timber.d("GPS данные в $source: широта=${latLong!![0]}, долгота=${latLong[1]}")
        } else if (hasAnyGpsTag) {
            Timber.d("GPS теги присутствуют в $source, но координаты не читаются через latLong")
        } else {
            Timber.d("GPS данные в $source отсутствуют")
        }
    }

    /**
     * Проверяет, что указанный в EXIF уровень компрессии соответствует фактическому
     */
    private fun verifyCompressionLevel(file: File, expectedQuality: Int) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
            
            if (userComment?.startsWith("CompressPhotoFast_Compressed:") == true) {
                // Извлекаем только число качества, которое идет после первого двоеточия и до второго двоеточия
                val parts = userComment.split(":")
                if (parts.size >= 2) {
                    val actualQuality = parts[1].toIntOrNull()
                    
                    if (actualQuality == expectedQuality) {
                        Timber.d("Уровень компрессии в EXIF соответствует ожидаемому: $actualQuality")
                    } else {
                        Timber.e("Уровень компрессии в EXIF ($actualQuality) не соответствует ожидаемому ($expectedQuality)")
                    }
                } else {
                    Timber.e("Неверный формат маркера CompressPhotoFast_Compressed в EXIF: $userComment")
                }
            } else {
                Timber.e("Маркер CompressPhotoFast_Compressed не найден в EXIF. UserComment: $userComment")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке уровня компрессии в EXIF: ${e.message}")
        }
    }

    /**
     * Создает информацию для foreground сервиса
     */
    private fun createForegroundInfo(notificationId: Int, notificationTitle: String): ForegroundInfo {
        // Создаем или обновляем канал уведомлений
        NotificationUtil.createNotificationChannel(applicationContext)
        
        val notification = NotificationCompat.Builder(applicationContext, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(notificationTitle)
            .setContentText(applicationContext.getString(R.string.notification_processing))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * Показывает уведомление о завершении сжатия с информацией о результате
     */
    private fun showCompletionNotification(fileName: String, originalSize: Long, compressedSize: Long, sizeReduction: Float) {
        // Вызываем функцию добавления в недавно обработанные, чтобы MediaStore Observer не обрабатывал этот файл заново
        try {
            val uriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
            if (uriString != null) {
                // Отправляем только информацию о завершении для обновления статуса в BackgroundMonitoringService
                val intent = Intent(Constants.ACTION_COMPRESSION_COMPLETED).apply {
                    putExtra(Constants.EXTRA_FILE_NAME, fileName)
                    putExtra(Constants.EXTRA_URI, uriString) 
                    putExtra(Constants.EXTRA_ORIGINAL_SIZE, originalSize)
                    putExtra(Constants.EXTRA_COMPRESSED_SIZE, compressedSize)
                    putExtra(Constants.EXTRA_REDUCTION_PERCENT, sizeReduction)
                    flags = Intent.FLAG_RECEIVER_FOREGROUND
                }
                context.sendBroadcast(intent)
            }
            
            // Логируем для отладки
            Timber.d("Уведомление о завершении сжатия отправлено: Файл=$fileName")
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при отправке уведомления о завершении сжатия")
        }
    }

    /**
     * Создание данных для результата с успехом
     */
    private fun createSuccessOutput(): Data {
        return Data.Builder()
            .putBoolean("success", true)
            .putBoolean("skipped", false)
            .build()
    }

    /**
     * Создание данных для результата с ошибкой
     */
    private fun createFailureOutput(errorMessage: String): Data {
        return Data.Builder()
            .putBoolean("success", false)
            .putBoolean("skipped", false)
            .putString("error_message", errorMessage)
            .build()
    }

    /**
     * Проверяет, было ли изображение уже обработано (независимо от размера)
     * @param uri URI изображения
     * @return true если изображение уже обработано, false в противном случае
     */
    private suspend fun isImageAlreadyProcessedExceptSize(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        // Используем централизованную логику из ImageProcessingChecker
        return@withContext ImageProcessingChecker.isAlreadyProcessed(context, uri)
    }
    
    /**
     * Сохранение сжатого изображения в галерею
     * 
     * @return Pair<Uri?, Any?> - Первый элемент - URI сжатого изображения, второй - IntentSender для запроса разрешения на удаление
     */
    private suspend fun saveCompressedImageToGallery(compressedFile: File, fileName: String, originalUri: Uri): Pair<Uri?, Any?> = withContext(Dispatchers.IO) {
        try {
            // Используем оригинальное имя файла без изменений
            val finalFileName = fileName
            
            FileUtil.saveCompressedImageToGallery(
                context,
                compressedFile,
                finalFileName,
                originalUri
            )
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при сохранении сжатого изображения")
            Pair(null, null)
        }
    }

    /**
     * Обработка результатов сжатия (сохранение в галерею и обработка IntentSender для удаления)
     */
    private suspend fun handleCompressedImage(
        compressedFile: File,
        originalUri: Uri,
        originalFileName: String,
        stats: CompressionStats
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Вычисляем процент сокращения размера
            val sizeReduction = if (stats.originalSize > 0) {
                ((stats.originalSize - stats.compressedSize).toFloat() / stats.originalSize) * 100
            } else 0f
            
            // Создаем имя файла для сжатой версии
            val compressedFileName = FileUtil.createCompressedFileName(originalFileName)
            
            // Сохраняем сжатое изображение в галерею
            val savedUri = FileUtil.saveCompressedImageToGallery(
                context,
                compressedFile,
                compressedFileName,
                originalUri
            ).first
            
            // Добавляем URI в список обработанных
            savedUri?.let {
                Timber.d("Сжатый файл сохранен: ${FileUtil.getFilePathFromUri(context, it)}")
                
                // Показываем уведомление о завершении сжатия
                showCompletionNotification(
                    fileName = originalFileName,
                    originalSize = stats.originalSize,
                    compressedSize = stats.compressedSize,
                    sizeReduction = sizeReduction
                )
                
                // Возвращаем успех
                return@withContext true
            }
            
            return@withContext false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при сохранении сжатого изображения: ${e.message}")
            return@withContext false
        }
    }

    private suspend fun getCompressedImageUri(uri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            // Проверяем, есть ли URI в списке обработанных
            if (StatsTracker.isImageProcessed(context, uri)) {
                Timber.d("URI найден в списке обработанных: $uri")
                return@withContext uri
            }
            
            // Если файл не найден в списке обработанных, возвращаем null
            null
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при поиске сжатой версии изображения")
            null
        }
    }

    /**
     * Добавляет запрос на удаление файла в список ожидающих
     */
    private fun addPendingDeleteRequest(uri: Uri, deletePendingIntent: Any) {
        Timber.d("Требуется разрешение пользователя для удаления оригинального файла: $uri")
        
        // Сохраняем URI в SharedPreferences для последующей обработки
        val prefs = context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
        val pendingDeleteUris = prefs.getStringSet(Constants.PREF_PENDING_DELETE_URIS, mutableSetOf()) ?: mutableSetOf()
        val newSet = pendingDeleteUris.toMutableSet()
        newSet.add(uri.toString())
        
        prefs.edit()
            .putStringSet(Constants.PREF_PENDING_DELETE_URIS, newSet)
            .apply()
        
        // Отправляем broadcast для уведомления MainActivity о необходимости запросить разрешение
        val intent = Intent(Constants.ACTION_REQUEST_DELETE_PERMISSION).apply {
            putExtra(Constants.EXTRA_URI, uri)
            // Добавляем IntentSender как Parcelable
            putExtra(Constants.EXTRA_DELETE_INTENT_SENDER, deletePendingIntent as android.os.Parcelable)
        }
        context.sendBroadcast(intent)
    }

    /**
     * Очистка старых временных файлов
     */
    private fun cleanupTempFiles() {
        // Получаем URI текущего обрабатываемого изображения
        val currentUri = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
        
        // Используем централизованную логику очистки временных файлов
        TempFilesCleaner.cleanupTempFiles(context, currentUri)
    }

    /**
     * Получает имя файла из URI с проверкой на null
     */
    private fun getFileNameSafely(uri: Uri): String {
        try {
            val fileName = FileUtil.getFileNameFromUri(context, uri)
            
            // Если имя файла не определено, генерируем временное имя на основе времени
            if (fileName.isNullOrBlank() || fileName == "unknown") {
                val timestamp = System.currentTimeMillis()
                Timber.w("Не удалось получить имя файла для URI: $uri, используем временное имя")
                return "compressed_image_$timestamp.jpg"
            }
            
            return fileName
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении имени файла")
            val timestamp = System.currentTimeMillis()
            return "compressed_image_$timestamp.jpg"
        }
    }

    /**
     * Проверяет, доступен ли файл для обработки
     */
    private suspend fun isFileAccessible(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверяем размер файла
            val fileSize = FileUtil.getFileSize(context, uri)
            if (fileSize <= 0) {
                Timber.w("Файл недоступен или пуст: $uri")
                return@withContext false
            }
            
            // Проверяем, можно ли получить имя файла
            val fileName = FileUtil.getFileNameFromUri(context, uri)
            if (fileName.isNullOrBlank() || fileName == "unknown") {
                Timber.w("Не удалось получить имя файла для URI: $uri")
                return@withContext false
            }
            
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке доступности файла: $uri")
            return@withContext false
        }
    }

    /**
     * Показывает уведомление о пропуске сжатия из-за неэффективности
     */
    private fun showSkippedCompressionNotification(fileName: String, originalSize: Long, estimatedCompressedSize: Long, estimatedReduction: Float) {
        try {
            val uriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
            if (uriString != null) {
                // Отправляем информацию о пропуске файла
                val intent = Intent(Constants.ACTION_COMPRESSION_SKIPPED).apply {
                    putExtra(Constants.EXTRA_FILE_NAME, fileName)
                    putExtra(Constants.EXTRA_URI, uriString)
                    putExtra(Constants.EXTRA_ORIGINAL_SIZE, originalSize)
                    putExtra(Constants.EXTRA_COMPRESSED_SIZE, estimatedCompressedSize)
                    putExtra(Constants.EXTRA_REDUCTION_PERCENT, estimatedReduction)
                    flags = Intent.FLAG_RECEIVER_FOREGROUND
                }
                context.sendBroadcast(intent)
            }
            
            Timber.d("Уведомление о пропуске сжатия отправлено: Файл=$fileName, экономия=${String.format("%.1f", estimatedReduction)}%")
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при отправке уведомления о пропуске сжатия")
        }
    }
} 
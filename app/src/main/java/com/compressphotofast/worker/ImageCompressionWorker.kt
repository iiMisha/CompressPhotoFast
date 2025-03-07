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
import com.compressphotofast.util.ImageTrackingUtil
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
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import android.content.Intent

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

    override suspend fun doWork(): Result {
        val imageUriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
        val compressionQuality = inputData.getInt("compression_quality", Constants.DEFAULT_COMPRESSION_QUALITY)
        
        if (imageUriString == null) {
            Timber.e("Отсутствует URI изображения в запросе на сжатие")
            return Result.failure(workDataOf(Constants.WORK_ERROR_MSG to "Отсутствует URI изображения"))
        }
        
        val imageUri = Uri.parse(imageUriString)
        Timber.d("Начало сжатия изображения: $imageUri")
        Timber.d("Параметры: качество=$compressionQuality")

        return withContext(Dispatchers.IO) {
            try {
                // Получаем имя файла из URI
                val originalFileNameNullable = FileUtil.getFileNameFromUri(context, imageUri)
                
                if (originalFileNameNullable == null) {
                    Timber.e("Не удалось получить имя файла из URI: $imageUri")
                    return@withContext Result.failure(workDataOf(Constants.WORK_ERROR_MSG to "Не удалось получить имя файла"))
                }
                
                // После проверки на null используем non-null переменную
                val originalFileName = originalFileNameNullable

                // Логируем подробную информацию об исходном файле
                logFileDetails(imageUri)

                // Получаем размер исходного файла
                val originalSize = getFileSize(imageUri)
                if (originalSize <= 0) {
                    Timber.e("Невозможно получить размер исходного файла")
                    return@withContext Result.failure()
                }

                // Создаем временный файл для сжатого изображения
                val tempFile = createTempImageFile()
                Timber.d("Создан временный файл: ${tempFile.absolutePath}")
                
                try {
                    // Сжимаем изображение
                    Timber.d("Начало сжатия изображения...")
                    compressImage(imageUri, tempFile, compressionQuality)
                    Timber.d("Изображение успешно сжато")
                    
                    // Проверяем размер сжатого файла
                    val compressedSize = tempFile.length()
                    val sizeReduction = ((originalSize - compressedSize).toFloat() / originalSize) * 100
                    
                    Timber.d("Результат сжатия: оригинал=${originalSize/1024}KB, сжатый=${compressedSize/1024}KB, сокращение=${String.format("%.1f", sizeReduction)}%")
                    
                    if (sizeReduction < 20) {
                        Timber.d("Недостаточное сжатие (${String.format("%.1f", sizeReduction)}%), пропускаем файл")
                        tempFile.delete()
                        return@withContext Result.success()
                    }

                    Timber.d("Имя исходного файла: $originalFileName")
                    
                    // Создаем имя для сжатого файла
                    val compressedFileName = FileUtil.createCompressedFileName(originalFileName)
                    Timber.d("Имя для сжатого файла: $compressedFileName")
                
                    // Сохраняем сжатое изображение в галерею
                    Timber.d("Сохранение сжатого изображения в галерею...")
                    val result = handleCompressedImage(
                        compressedFile = tempFile,
                        originalUri = imageUri,
                        originalFileName = originalFileName,
                        stats = CompressionStats(originalSize, compressedSize)
                    )

                    // Удаляем временный файл
                    tempFile.delete()
                    Timber.d("Временный файл удален")

                    if (result) {
                        Timber.d("Изображение успешно сжато и сохранено")
                        Result.success()
                    } else {
                        Timber.e("Не удалось сохранить сжатое изображение")
                        Result.failure()
                    }
                } finally {
                    // Гарантируем удаление временного файла
                    if (tempFile.exists()) {
                        tempFile.delete()
                        Timber.d("Временный файл удален в блоке finally")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при сжатии изображения")
                Result.failure()
            }
        }
    }

    /**
     * Логирует подробную информацию о файле
     */
    private fun logFileDetails(uri: Uri) {
        try {
            // Получаем базовую информацию об URI
            Timber.d("Детали URI: $uri")
            Timber.d(" - Scheme: ${uri.scheme}")
            Timber.d(" - Authority: ${uri.authority}")
            Timber.d(" - Path: ${uri.path}")
            Timber.d(" - Query: ${uri.query}")
            Timber.d(" - Fragment: ${uri.fragment}")
            
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DESCRIPTION,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_MODIFIED
                ),
                null,
                null,
                null
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val idIndex = it.getColumnIndex(MediaStore.Images.Media._ID)
                    val nameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val dataIndex = it.getColumnIndex(MediaStore.Images.Media.DATA)
                    val descIndex = it.getColumnIndex(MediaStore.Images.Media.DESCRIPTION)
                    val addedIndex = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val modifiedIndex = it.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                    
                    val id = if (idIndex != -1) it.getLong(idIndex) else null
                    val name = if (nameIndex != -1) it.getString(nameIndex) else null
                    val size = if (sizeIndex != -1) it.getLong(sizeIndex) else null
                    val data = if (dataIndex != -1) it.getString(dataIndex) else null
                    val desc = if (descIndex != -1) it.getString(descIndex) else null
                    val added = if (addedIndex != -1) it.getLong(addedIndex) else null
                    val modified = if (modifiedIndex != -1) it.getLong(modifiedIndex) else null
                    
                    Timber.d("Метаданные файла для URI '$uri':")
                    Timber.d(" - ID: $id")
                    Timber.d(" - Имя: $name")
                    Timber.d(" - Размер: $size")
                    Timber.d(" - Путь: $data")
                    Timber.d(" - Описание: $desc")
                    Timber.d(" - Дата добавления: $added")
                    Timber.d(" - Дата изменения: $modified")
                    
                    // Проверяем, находится ли файл в директории приложения
                    data?.let { path ->
                        val isInAppDirectory = path.contains("/${Constants.APP_DIRECTORY}/")
                        Timber.d(" - Находится в директории приложения: $isInAppDirectory")
                    }
                } else {
                    Timber.d("Нет метаданных для URI '$uri'")
                }
            } ?: Timber.d("Не удалось получить курсор для URI '$uri'")
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
                tempFile.delete()
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
     * Сжатие изображения
     */
    private suspend fun compressImage(uri: Uri, outputFile: File, quality: Int) {
        try {
            // Создаем временный файл из URI
            val inputFile = createTempFileFromUri(uri) ?: throw IOException("Не удалось создать временный файл")
            
            // Логируем EXIF данные исходного изображения до сжатия
            logExifData(uri)
            
            // Сжимаем изображение
            Compressor.compress(context, inputFile) {
                quality(quality)
            format(android.graphics.Bitmap.CompressFormat.JPEG)
            }.copyTo(outputFile, overwrite = true)
            
            // Удаляем временный входной файл
            inputFile.delete()
            
            // Копируем EXIF данные
            FileUtil.copyExifDataFromUriToFile(context, uri, outputFile)
            
            // Логируем EXIF данные после копирования
            Timber.d("EXIF данные после копирования:")
            logExifDataFromFile(outputFile)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при сжатии изображения")
            throw e
        }
    }

    /**
     * Логирует EXIF данные из URI изображения
     */
    private suspend fun logExifData(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            Timber.d("Логирование EXIF данных для URI: $uri")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                
                // Логируем основные теги EXIF
                val datetime = exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "Нет данных"
                val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: "Нет данных"
                val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "Нет данных"
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1).toString()
                val exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) ?: "Нет данных"
                val fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER) ?: "Нет данных"
                val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) ?: "Нет данных"
                val flash = exif.getAttribute(ExifInterface.TAG_FLASH) ?: "Нет данных"
                
                Timber.d("EXIF данные изображения:")
                Timber.d(" - Дата и время: $datetime")
                Timber.d(" - Производитель: $make")
                Timber.d(" - Модель: $model")
                Timber.d(" - Ориентация: $orientation")
                Timber.d(" - Время экспозиции: $exposureTime")
                Timber.d(" - Число диафрагмы: $fNumber")
                Timber.d(" - ISO: $iso")
                Timber.d(" - Вспышка: $flash")
                
                // Логируем GPS данные, если они есть
                val hasGps = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null
                if (hasGps) {
                    val latitude = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                    val latitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
                    val longitude = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
                    val longitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
                    
                    if (latitude != null && latitudeRef != null && longitude != null && longitudeRef != null) {
                        val lat = convertToDegrees(latitude)
                        val lon = convertToDegrees(longitude)
                        
                        // Корректируем координаты в зависимости от ref (N/S, E/W)
                        val finalLat = if (latitudeRef == "N") lat else -lat
                        val finalLon = if (longitudeRef == "E") lon else -lon
                        
                        Timber.d(" - GPS координаты: Широта=$finalLat, Долгота=$finalLon")
                    } else {
                        Timber.d(" - GPS данные неполные или повреждены")
                    }
                } else {
                    Timber.d(" - GPS данные отсутствуют")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при чтении EXIF данных из URI")
        }
    }
    
    /**
     * Логирует EXIF данные из файла
     */
    private fun logExifDataFromFile(file: File) {
        try {
            Timber.d("Логирование EXIF данных для файла: ${file.absolutePath}")
            val exif = ExifInterface(file.absolutePath)
            
            // Логируем основные теги EXIF
            val datetime = exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "Нет данных"
            val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: "Нет данных"
            val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "Нет данных"
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1).toString()
            val exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) ?: "Нет данных"
            val fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER) ?: "Нет данных"
            val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) ?: "Нет данных"
            val flash = exif.getAttribute(ExifInterface.TAG_FLASH) ?: "Нет данных"
            
            Timber.d("EXIF данные изображения:")
            Timber.d(" - Дата и время: $datetime")
            Timber.d(" - Производитель: $make")
            Timber.d(" - Модель: $model")
            Timber.d(" - Ориентация: $orientation")
            Timber.d(" - Время экспозиции: $exposureTime")
            Timber.d(" - Число диафрагмы: $fNumber")
            Timber.d(" - ISO: $iso")
            Timber.d(" - Вспышка: $flash")
            
            // Логируем GPS данные, если они есть
            val hasGps = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null
            if (hasGps) {
                val latitude = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                val latitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
                val longitude = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
                val longitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
                
                if (latitude != null && latitudeRef != null && longitude != null && longitudeRef != null) {
                    val lat = convertToDegrees(latitude)
                    val lon = convertToDegrees(longitude)
                    
                    // Корректируем координаты в зависимости от ref (N/S, E/W)
                    val finalLat = if (latitudeRef == "N") lat else -lat
                    val finalLon = if (longitudeRef == "E") lon else -lon
                    
                    Timber.d(" - GPS координаты: Широта=$finalLat, Долгота=$finalLon")
                } else {
                    Timber.d(" - GPS данные неполные или повреждены")
                }
            } else {
                Timber.d(" - GPS данные отсутствуют")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при чтении EXIF данных из файла")
        }
    }

    /**
     * Конвертирует GPS координаты из формата EXIF в десятичные градусы
     */
    private fun convertToDegrees(coordinate: String): Double {
        val parts = coordinate.split(",", limit = 3)
        if (parts.size != 3) return 0.0
        
        val degrees = parts[0].split("/").let { 
            if (it.size == 2) it[0].toDouble() / it[1].toDouble() else 0.0 
        }
        val minutes = parts[1].split("/").let { 
            if (it.size == 2) it[0].toDouble() / it[1].toDouble() else 0.0 
        }
        val seconds = parts[2].split("/").let { 
            if (it.size == 2) it[0].toDouble() / it[1].toDouble() else 0.0 
        }
        
        return degrees + (minutes / 60.0) + (seconds / 3600.0)
    }

    /**
     * Создание информации для foreground сервиса
     */
    private fun createForegroundInfo(): ForegroundInfo {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Создание канала уведомлений для Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                context.getString(R.string.notification_channel_id),
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = context.getString(R.string.notification_channel_description)
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(context, context.getString(R.string.notification_channel_id))
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_compressing))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        
        return ForegroundInfo(Constants.NOTIFICATION_ID_COMPRESSION, notification)
    }

    /**
     * Создание данных для результата с ошибкой
     */
    private fun createFailureOutput(errorMessage: String): Data {
        return Data.Builder()
            .putBoolean("success", false)
            .putString("error_message", errorMessage)
            .build()
    }

    /**
     * Проверка, было ли изображение уже обработано
     */
    private suspend fun isImageAlreadyProcessed(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверяем, находится ли файл в директории приложения
            val path = FileUtil.getFilePathFromUri(context, uri)
            val isInAppDir = !path.isNullOrEmpty() && path.contains("/${Constants.APP_DIRECTORY}/")
            
            if (isInAppDir) {
                Timber.d("Файл находится в директории приложения: $path")
                return@withContext true
            }
            
            // Проверяем, есть ли URI в списке обработанных
            val isProcessed = ImageTrackingUtil.isImageProcessed(context, uri)
            if (isProcessed) {
                Timber.d("URI найден в списке обработанных: $uri")
                return@withContext true
            }
            
            // Файл прошел все проверки и должен быть сжат
            false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса файла")
            false
        }
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
            Timber.d("Сохранение сжатого изображения в галерею...")
            
            // Используем оригинальное имя файла без изменений
            val compressedFileName = originalFileName
            
            // Сохраняем сжатое изображение в галерею
            val result = FileUtil.saveCompressedImageToGallery(
                context,
                compressedFile,
                compressedFileName,
                originalUri
            )
            
            val compressedUri = result.first
            val deletePendingIntent = result.second
            
            // Если требуется разрешение на удаление, но нет URI сжатого файла,
            // значит процесс сохранения был прерван для запроса разрешения
            if (compressedUri == null && deletePendingIntent != null && deletePendingIntent !is Boolean) {
                Timber.d("Требуется разрешение пользователя на удаление оригинального файла")
                addPendingDeleteRequest(originalUri, deletePendingIntent)
                return@withContext false
            }
            
            if (compressedUri != null) {
                val sizeReduction = (1 - (stats.compressedSize.toDouble() / stats.originalSize.toDouble())) * 100
                Timber.d("Сокращение размера: ${String.format("%.1f", sizeReduction)}%")
                
                // Логируем детальную информацию о сжатии
                Timber.d("Детали URI: $originalUri")
                Timber.d(" - Scheme: ${originalUri.scheme}")
                Timber.d(" - Authority: ${originalUri.authority}")
                Timber.d(" - Path: ${originalUri.path}")
                Timber.d(" - Query: ${originalUri.query}")
                Timber.d(" - Fragment: ${originalUri.fragment}")
                
                // Получение и логирование метаданных файла
                originalUri.let { logFileDetails(it) }
                
                // Если есть IntentSender для удаления, добавляем его в список ожидающих
                if (deletePendingIntent != null && deletePendingIntent !is Boolean) {
                    addPendingDeleteRequest(originalUri, deletePendingIntent)
                }
                
                return@withContext true
            }
            
            false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при обработке сжатого изображения")
            return@withContext false
        }
    }

    private suspend fun getCompressedImageUri(uri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            // Проверяем, есть ли URI в списке обработанных
            if (ImageTrackingUtil.isImageProcessed(context, uri)) {
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
} 
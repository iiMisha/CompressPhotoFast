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
import com.compressphotofast.R
import com.compressphotofast.util.Constants
import com.compressphotofast.util.FileUtil
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

/**
 * Worker для сжатия изображений в фоновом режиме
 */
@HiltWorker
class ImageCompressionWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val imageUri = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
                ?: return@withContext Result.failure()

            val uri = Uri.parse(imageUri)
            
            // Проверяем, не является ли файл временным
            if (isFilePending(uri)) {
                Timber.d("Файл все еще в процессе создания, пропускаем: $uri")
                return@withContext Result.retry()
            }
            
            // Создаем временный файл из URI
            val tempFile = createTempFileFromUri(uri) ?: return@withContext Result.failure()
            
            // Получаем оригинальное имя файла
            val originalFileName = getFileNameFromUri(uri) ?: return@withContext Result.failure()
            
            // Создаем безопасное имя файла
            val safeFileName = getSafeFileName(originalFileName)
            
            // Сжимаем изображение
            val compressedFile = compressImage(tempFile)
            
            // Копируем EXIF данные из оригинального файла в сжатый
            copyExifData(uri, compressedFile)
            
            // Сохраняем сжатое изображение
            val resultUri = saveCompressedImageToGallery(compressedFile, safeFileName)
            
            // Очищаем временные файлы
            tempFile.delete()
            compressedFile.delete()
            
            if (resultUri != null) {
                Timber.d("Изображение успешно сжато и сохранено: $resultUri")
                Result.success()
            } else {
                Result.failure()
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при сжатии изображения")
            if (e.message?.contains("pending") == true) {
                Timber.d("Файл временно недоступен, повторяем позже")
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * Получает безопасное имя файла с ограничением длины
     */
    private fun getSafeFileName(originalName: String): String {
        val extension = originalName.substringAfterLast(".", "")
        var nameWithoutExt = originalName.substringBeforeLast(".")
        
        // Удаляем все предыдущие маркеры сжатия
        val compressionMarkers = listOf("_compressed", "_сжатое", "_small")
        compressionMarkers.forEach { marker ->
            nameWithoutExt = nameWithoutExt.replace(marker, "")
        }
        
        // Удаляем хеши и другие дополнительные части из имени файла
        nameWithoutExt = nameWithoutExt.replace(Regex("-\\d+_[a-f0-9]{32}"), "")
        
        // Ограничиваем длину имени файла
        val maxBaseLength = 100 - extension.length - "_compressed".length - 1
        if (nameWithoutExt.length > maxBaseLength) {
            nameWithoutExt = nameWithoutExt.take(maxBaseLength)
        }
        
        return "${nameWithoutExt}_compressed.$extension"
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
     * Сжатие изображения с помощью библиотеки Compressor
     */
    private suspend fun compressImage(imageFile: File): File {
        val compressionQuality = inputData.getInt("compression_quality", Constants.DEFAULT_COMPRESSION_QUALITY)
        
        Timber.d("Сжатие изображения с качеством: $compressionQuality")
        
        return Compressor.compress(context, imageFile) {
            resolution(Constants.MAX_IMAGE_WIDTH, Constants.MAX_IMAGE_HEIGHT)
            quality(compressionQuality)
            format(android.graphics.Bitmap.CompressFormat.JPEG)
            size(1024 * 1024) // Максимальный размер 1MB
        }
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
        // Базовая проверка по суффиксу
        if (FileUtil.isAlreadyCompressed(uri)) {
            Timber.d("Файл содержит суффикс сжатия: $uri")
            return@withContext true
        }
        
        // Проверка, находится ли изображение в нашей директории
        val path = uri.toString()
        if (path.contains("/${Constants.APP_DIRECTORY}/")) {
            Timber.d("Файл находится в директории приложения: $uri")
            return@withContext true
        }
        
        // Получаем имя файла
        var fileName: String? = null
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            null,
            null,
            null
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = it.getString(nameIndex)
                }
            }
        }
        
        // Проверяем, существует ли уже сжатая версия этого файла с таким же именем
        if (fileName != null) {
            Timber.d("Проверка существования сжатой версии для файла: $fileName")
            
            val compressedName = FileUtil.createCompressedFileName(fileName!!)
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(compressedName)
            
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use {
                if (it.count > 0) {
                    Timber.d("Найдена существующая сжатая версия для $fileName")
                    return@withContext true
                } else {
                    Timber.d("Сжатая версия для $fileName не найдена")
                }
            }
        }
        
        // Файл прошел все проверки и должен быть сжат
        return@withContext false
    }

    /**
     * Получение имени файла из URI
     */
    private suspend fun getFileNameFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    return@withContext cursor.getString(displayNameIndex)
                }
            }
            // Если не удалось получить имя через MediaStore, пробуем получить из последнего сегмента URI
            uri.lastPathSegment?.let { segment ->
                if (segment.contains(".")) {
                    return@withContext segment
                }
            }
            null
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении имени файла из URI")
            null
        }
    }

    /**
     * Сохранение сжатого изображения в галерею
     */
    private suspend fun saveCompressedImageToGallery(compressedFile: File, fileName: String): Uri? = withContext(Dispatchers.IO) {
        try {
            FileUtil.saveCompressedImageToGallery(
                context,
                compressedFile,
                fileName // Передаем имя файла как есть
            )
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при сохранении сжатого изображения")
            null
        }
    }

    /**
     * Копирование EXIF данных из оригинального изображения в сжатое
     */
    private suspend fun copyExifData(sourceUri: Uri, destinationFile: File) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                val sourceExif = ExifInterface(inputStream)
                val destinationExif = ExifInterface(destinationFile.absolutePath)

                // Копируем все доступные EXIF теги
                val tags = arrayOf(
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_FLASH,
                    ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_GPS_ALTITUDE,
                    ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_PROCESSING_METHOD,
                    ExifInterface.TAG_GPS_TIMESTAMP,
                    ExifInterface.TAG_IMAGE_LENGTH,
                    ExifInterface.TAG_IMAGE_WIDTH,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.TAG_SUBSEC_TIME,
                    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
                    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                    ExifInterface.TAG_WHITE_BALANCE,
                    ExifInterface.TAG_APERTURE_VALUE,
                    ExifInterface.TAG_BRIGHTNESS_VALUE,
                    ExifInterface.TAG_COLOR_SPACE,
                    ExifInterface.TAG_CONTRAST,
                    ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
                    ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                    ExifInterface.TAG_EXPOSURE_INDEX,
                    ExifInterface.TAG_EXPOSURE_MODE,
                    ExifInterface.TAG_EXPOSURE_PROGRAM,
                    ExifInterface.TAG_FLASH_ENERGY,
                    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                    ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_GAIN_CONTROL,
                    ExifInterface.TAG_ISO_SPEED_RATINGS,
                    ExifInterface.TAG_LIGHT_SOURCE,
                    ExifInterface.TAG_MAKER_NOTE,
                    ExifInterface.TAG_MAX_APERTURE_VALUE,
                    ExifInterface.TAG_METERING_MODE,
                    ExifInterface.TAG_SATURATION,
                    ExifInterface.TAG_SCENE_CAPTURE_TYPE,
                    ExifInterface.TAG_SCENE_TYPE,
                    ExifInterface.TAG_SENSING_METHOD,
                    ExifInterface.TAG_SHARPNESS,
                    ExifInterface.TAG_SHUTTER_SPEED_VALUE,
                    ExifInterface.TAG_SOFTWARE,
                    ExifInterface.TAG_SUBJECT_DISTANCE,
                    ExifInterface.TAG_USER_COMMENT
                )

                for (tag in tags) {
                    val value = sourceExif.getAttribute(tag)
                    if (value != null) {
                        destinationExif.setAttribute(tag, value)
                    }
                }

                // Сохраняем EXIF данные
                destinationExif.saveAttributes()
                Timber.d("EXIF данные успешно скопированы")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при копировании EXIF данных")
        }
    }
} 
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
                    
                    // Анализируем имя файла на наличие маркеров сжатия
                    name?.let { fileName ->
                        val hasCompressionMarker = ImageTrackingUtil.COMPRESSION_MARKERS.any { marker ->
                            fileName.lowercase().contains(marker.lowercase())
                        }
                        Timber.d(" - Имеет маркер сжатия в имени: $hasCompressionMarker")
                    }
                    
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
            
            // Сжимаем изображение
            Compressor.compress(context, inputFile) {
                quality(quality)
            format(android.graphics.Bitmap.CompressFormat.JPEG)
            }.copyTo(outputFile, overwrite = true)
            
            // Удаляем временный входной файл
            inputFile.delete()
            
            // Копируем EXIF данные
            copyExifData(uri, outputFile)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при сжатии изображения")
            throw e
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
        try {
            // Получаем имя файла
            val fileName = FileUtil.getFileNameFromUri(context, uri) ?: return@withContext false
            
            // Если файл уже имеет маркер сжатия, пропускаем его
            if (fileName.contains("_compressed")) {
                Timber.d("Файл содержит маркер сжатия: $fileName")
            return@withContext true
        }
        
            // Проверяем, существует ли уже сжатая версия этого файла
            val baseFileName = fileName.substringBeforeLast(".")
            val extension = fileName.substringAfterLast(".", "")
            val compressedFileName = "${baseFileName}_compressed.$extension"
            
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("$compressedFileName%") // Используем LIKE для поиска всех вариантов
            
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.count > 0) {
                    Timber.d("Найдена существующая сжатая версия для $fileName")
                    return@withContext true
                }
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
            // Проверяем существование файла с таким именем
            var finalFileName = fileName
            var counter = 1
            
            while (true) {
                val testFileName = if (counter == 1) finalFileName else {
                    val baseName = finalFileName.substringBeforeLast(".")
                    val ext = finalFileName.substringAfterLast(".", "")
                    "${baseName}_$counter.$ext"
                }
                
                val exists = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                    arrayOf(testFileName),
                    null
                )?.use { cursor -> cursor.count > 0 } ?: false

                if (!exists) {
                    finalFileName = testFileName
                    break
                }
                counter++
            }
            
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
     * Сохраняет EXIF данные в выходной файл
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
                    ExifInterface.TAG_ISO_SPEED_RATINGS,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.TAG_SUBSEC_TIME,
                    ExifInterface.TAG_WHITE_BALANCE
                )

                for (tag in tags) {
                    val value = sourceExif.getAttribute(tag)
                    if (value != null) {
                        destinationExif.setAttribute(tag, value)
                    }
                }

                // Сохраняем изменения
                destinationExif.saveAttributes()
                Timber.d("EXIF данные успешно скопированы")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при копировании EXIF данных: ${e.message}")
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
    ) = withContext(Dispatchers.IO) {
        try {
            Timber.d("Сохранение сжатого изображения в галерею...")
            
            // Получаем финальное имя файла для сжатого изображения
            val compressedFileName = FileUtil.createCompressedFileName(originalFileName)
            
            // Сохраняем сжатое изображение в галерею
            val result = FileUtil.saveCompressedImageToGallery(
                context,
                compressedFile,
                compressedFileName,
                originalUri
            )
            
            val compressedUri = result.first
            val deletePendingIntent = result.second
            
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
                
                // Проверяем, нужно ли добавить IntentSender в список ожидающих запросов на удаление
                if (deletePendingIntent != null && deletePendingIntent !is Boolean) {
                    Timber.d("Требуется разрешение пользователя для удаления оригинального файла: $originalUri")
                    
                    // Сохраняем URI в SharedPreferences для последующей обработки
                    val prefs = context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
                    val pendingDeleteUris = prefs.getStringSet(Constants.PREF_PENDING_DELETE_URIS, mutableSetOf()) ?: mutableSetOf()
                    val newSet = pendingDeleteUris.toMutableSet()
                    newSet.add(originalUri.toString())
                    
                    prefs.edit()
                        .putStringSet(Constants.PREF_PENDING_DELETE_URIS, newSet)
                        .apply()
                    
                    // Отправляем broadcast для уведомления MainActivity о необходимости запросить разрешение
                    val intent = Intent(Constants.ACTION_REQUEST_DELETE_PERMISSION)
                    intent.putExtra(Constants.EXTRA_URI, originalUri)
                    context.sendBroadcast(intent)
                }
                
                // Удаляем временный файл
                if (compressedFile.exists()) {
                    val deleted = compressedFile.delete()
                    Timber.d("Временный файл удален: $deleted")
                }
                
                Timber.d("Изображение успешно сжато и сохранено. URI сжатого файла: $compressedUri")
                return@withContext true
            } else {
                Timber.e("Не удалось сохранить сжатое изображение")
                return@withContext false
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при обработке сжатого изображения")
            return@withContext false
        }
    }
} 
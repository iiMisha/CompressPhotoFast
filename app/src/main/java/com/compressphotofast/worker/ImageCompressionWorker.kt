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
            
            // Создаем временный файл из URI
            val tempFile = createTempFileFromUri(uri) ?: return@withContext Result.failure()
            
            // Получаем оригинальное имя файла
            val originalFileName = getFileNameFromUri(uri) ?: return@withContext Result.failure()
            
            // Создаем безопасное имя файла
            val safeFileName = getSafeFileName(originalFileName)
            
            // Сжимаем изображение
            val compressedFile = compressImage(tempFile)
            
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
            Result.failure()
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
     * Создание временного файла из URI
     */
    private suspend fun createTempFileFromUri(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val tempFile = File.createTempFile("temp_image", ".jpg", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: IOException) {
            Timber.e(e, "Ошибка при создании временного файла")
            null
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
} 
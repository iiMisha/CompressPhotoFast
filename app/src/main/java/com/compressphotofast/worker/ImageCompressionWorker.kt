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
import com.compressphotofast.util.NotificationHelper
import com.compressphotofast.util.StatsTracker
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

    // Множество для отслеживания отправленных уведомлений
    private val notificationsSent = Collections.synchronizedSet(HashSet<String>())

    // Качество сжатия (получаем из входных данных)
    private val compressionQuality = inputData.getInt("compression_quality", Constants.DEFAULT_COMPRESSION_QUALITY)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Устанавливаем foreground notification
        setForegroundAsync(createForegroundInfo())
        
        try {
            // Получаем URI из InputData
            val imageUriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
                ?: return@withContext Result.failure(workDataOf(Constants.WORK_ERROR_MSG to "Отсутствует URI"))
            
            val imageUri = Uri.parse(imageUriString)
            
            // Начинаем отслеживание сжатия
            StatsTracker.startTracking(imageUri)
            StatsTracker.updateStatus(context, imageUri, StatsTracker.COMPRESSION_STATUS_PROCESSING)
            
            // Логируем переданное качество сжатия для отладки
            Timber.d("★★★ Используется качество сжатия: $compressionQuality (исходный параметр)")
            
            // Получаем качество из настроек для сравнения
            val settingsQuality = context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
                .getInt(Constants.PREF_COMPRESSION_QUALITY, Constants.DEFAULT_COMPRESSION_QUALITY)
            Timber.d("★★★ Сохраненное в настройках качество: $settingsQuality")
            
            // Проверяем, обрабатывается ли уже это изображение
            if (isImageAlreadyProcessed(imageUri)) {
                Timber.d("Изображение уже обработано: $imageUri")
                return@withContext Result.success()
            }
            
            // Проверяем, существует ли URI
            try {
                val checkCursor = context.contentResolver.query(imageUri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                val exists = checkCursor?.use { it.count > 0 } ?: false
                
                if (!exists) {
                    Timber.d("URI не существует, возможно он был обработан и удален другим процессом: $imageUri")
                    return@withContext Result.success()
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при проверке существования URI: $imageUri")
                // Продолжаем выполнение, так как ошибка может быть временной
            }
            
            // Создаем временный файл
            val tempFile = createTempImageFile()
            Timber.d("Создан временный файл: ${tempFile.absolutePath}")
            
            try {
                // Сжимаем изображение
                Timber.d("Начало сжатия изображения с качеством: $compressionQuality")
                compressImage(imageUri, tempFile, compressionQuality)
                Timber.d("Изображение успешно сжато с качеством: $compressionQuality")
                
                // Получаем размеры для логирования
                val originalSize = getFileSize(imageUri)
                val compressedSize = tempFile.length()
                
                // Вычисляем процент сокращения размера
                val sizeReduction = if (originalSize > 0) {
                    ((originalSize - compressedSize).toFloat() / originalSize) * 100
                } else 0f
                
                Timber.d("Результат сжатия: оригинал=${originalSize/1024}KB, сжатый=${compressedSize/1024}KB, сокращение=${String.format("%.1f", sizeReduction)}%")
                
                // Получаем имя файла из URI
                val fileName = FileUtil.getFileNameFromUri(context, imageUri) ?: "unknown"
                Timber.d("Имя исходного файла: $fileName")
                
                // Создаем имя для сжатого файла
                val compressedFileName = FileUtil.createCompressedFileName(fileName)
                Timber.d("Имя для сжатого файла: $compressedFileName")
                
                // Сохраняем сжатое изображение в галерею
                Timber.d("Сохранение сжатого изображения в галерею...")
                val (savedUri, deleteIntentSender) = FileUtil.saveCompressedImageToGallery(
                    context,
                    tempFile,
                    compressedFileName,
                    imageUri
                )
                
                // Добавляем URI в список обработанных
                savedUri?.let {
                    Timber.d("Сжатый файл сохранен: ${FileUtil.getFilePathFromUri(context, it)}")
                    
                    // Отправляем broadcast о завершении сжатия
                    sendCompletionBroadcast(
                        fileName = fileName,
                        originalSize = originalSize,
                        compressedSize = compressedSize,
                        sizeReduction = sizeReduction
                    )
                }
                
                // Удаляем временный файл
                if (!tempFile.delete()) {
                    Timber.w("Не удалось удалить временный файл: ${tempFile.absolutePath}")
                } else {
                    Timber.d("Временный файл удален")
                }
                
                // Показываем уведомление о завершении сжатия только если было достаточное сжатие
                showCompletionNotification(fileName, originalSize, compressedSize, sizeReduction)
                
                // Не помечаем оригинальный URI как обработанный, так как файл мог быть заменен
                // StatsTracker.addProcessedImage(context, imageUri)
                
                Timber.d("Изображение успешно сжато и сохранено")
                
                // Обновляем статус при успешном сжатии
                StatsTracker.updateStatus(context, imageUri, StatsTracker.COMPRESSION_STATUS_COMPLETED)
                
                return@withContext Result.success()
            } catch (e: Exception) {
                // Обновляем статус при ошибке
                StatsTracker.updateStatus(context, imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
                
                // В случае ошибки, удаляем временный файл
                if (!tempFile.delete()) {
                    Timber.w("Не удалось удалить временный файл при ошибке: ${tempFile.absolutePath}")
                }
                
                Timber.e(e, "Ошибка при сжатии изображения: ${e.message}")
                return@withContext Result.failure(createFailureOutput(e.message ?: "Неизвестная ошибка"))
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
        var inputFile: File? = null
        
        try {
            // Создаем временный файл из URI с уникальным именем
            val inputFileName = "input_${System.currentTimeMillis()}_${uri.lastPathSegment}"
            inputFile = File(context.cacheDir, inputFileName)
            
            // Копируем данные из URI во временный файл
            context.contentResolver.openInputStream(uri)?.use { input ->
                inputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Не удалось открыть входной поток")
            
            if (!inputFile.exists() || inputFile.length() <= 0) {
                throw IOException("Временный входной файл не создан или пуст")
            }
            
            // Дополнительное логирование перед сжатием для отладки
            Timber.d("★★★ Сжимаю изображение с параметром качества: $quality")
            
            // Сжимаем изображение
            Compressor.compress(context, inputFile) {
                quality(quality)
                format(android.graphics.Bitmap.CompressFormat.JPEG)
            }.copyTo(tempFile, overwrite = true)
            
            // Проверяем, что сжатый файл существует и не пуст
            if (!tempFile.exists() || tempFile.length() <= 0) {
                throw IOException("Сжатый файл не создан или пуст")
            }
            
            // Копируем EXIF данные
            FileUtil.copyExifDataFromUriToFile(context, uri, tempFile)
            
            // Добавляем EXIF маркер сжатия с информацией об уровне компрессии
            FileUtil.markCompressedImage(tempFile.absolutePath, quality)
            
            // Проверяем EXIF данные после копирования
            logExifDataFromFile(tempFile)
            
            // Верифицируем, что указанный уровень компрессии соответствует фактическому
            verifyCompressionLevel(tempFile, quality)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при сжатии изображения: ${e.message}")
            throw e
        }
    }

    /**
     * Проверяет EXIF данные из URI изображения
     */
    private suspend fun logExifData(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                // Просто проверяем наличие основных тегов
                val hasBasicTags = exif.getAttribute(ExifInterface.TAG_DATETIME) != null ||
                                 exif.getAttribute(ExifInterface.TAG_MAKE) != null ||
                                 exif.getAttribute(ExifInterface.TAG_MODEL) != null
                
                if (hasBasicTags) {
                    Timber.d("EXIF данные найдены в исходном файле")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке EXIF данных из URI")
        }
    }
    
    /**
     * Проверяет EXIF данные из файла
     */
    private fun logExifDataFromFile(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            // Просто проверяем наличие основных тегов
            val hasBasicTags = exif.getAttribute(ExifInterface.TAG_DATETIME) != null ||
                             exif.getAttribute(ExifInterface.TAG_MAKE) != null ||
                             exif.getAttribute(ExifInterface.TAG_MODEL) != null
            
            if (hasBasicTags) {
                Timber.d("EXIF данные сохранены")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке EXIF данных из файла")
        }
    }

    /**
     * Проверяет, что указанный в EXIF уровень компрессии соответствует фактическому
     */
    private fun verifyCompressionLevel(file: File, expectedQuality: Int) {
        try {
            Timber.d("★★★ Проверка уровня компрессии в файле: ${file.absolutePath}")
            Timber.d("★★★ Ожидаемый уровень компрессии: $expectedQuality")
            
            val exif = ExifInterface(file.absolutePath)
            val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
            
            Timber.d("★★★ Обнаружен EXIF UserComment: $userComment")
            
            if (userComment?.startsWith("CompressPhotoFast_Compressed:") == true) {
                val actualQuality = userComment.substringAfter("CompressPhotoFast_Compressed:").toIntOrNull()
                
                Timber.d("★★★ Извлеченный уровень компрессии из EXIF: $actualQuality")
                
                if (actualQuality == expectedQuality) {
                    Timber.d("★★★ Уровень компрессии в EXIF соответствует фактическому: $actualQuality")
                } else {
                    Timber.e("★★★ ОШИБКА: уровень компрессии в EXIF ($actualQuality) не соответствует фактическому ($expectedQuality)")
                }
            } else {
                Timber.e("★★★ ОШИБКА: маркер CompressPhotoFast_Compressed не найден в EXIF. UserComment: $userComment")
            }
        } catch (e: Exception) {
            Timber.e(e, "★★★ Ошибка при проверке уровня компрессии в EXIF: ${e.message}")
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
            channel.setShowBadge(false)
            channel.enableLights(false)
            channel.enableVibration(false)
            notificationManager.createNotificationChannel(channel)
        }
        
        // Создаем Intent для открытия приложения при нажатии на уведомление
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, context.getString(R.string.notification_channel_id))
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_compressing))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setProgress(0, 0, true) // Индикатор прогресса в неопределенном состоянии
            .setSilent(true) // Отключаем звук для foreground уведомления
            .build()
        
        // Если Android 14 и выше, указываем тип сервиса
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                Constants.NOTIFICATION_ID_COMPRESSION,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(Constants.NOTIFICATION_ID_COMPRESSION, notification)
        }
    }

    /**
     * Показывает уведомление о завершении сжатия с информацией о результате
     */
    private fun showCompletionNotification(fileName: String, originalSize: Long, compressedSize: Long, sizeReduction: Float) {
        // Вызываем отправку бродкаста только один раз
        try {
            // Отправляем только broadcast для MainActivity, без системного уведомления
            sendCompletionBroadcast(fileName, originalSize, compressedSize, sizeReduction)
            
            // Логируем для отладки
            Timber.d("Уведомление о завершении сжатия отправлено: Файл=$fileName")
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при отправке уведомления о завершении сжатия")
        }
    }
    
    /**
     * Отправляет broadcast о завершении сжатия
     */
    private fun sendCompletionBroadcast(fileName: String, originalSize: Long, compressedSize: Long, sizeReduction: Float) {
        // Получаем исходный URI из входных данных
        val uriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
        
        // Предотвращаем отправку бродкаста, если URI отсутствует
        if (uriString == null) {
            Timber.w("Невозможно отправить broadcast о завершении сжатия: отсутствует URI")
            return
        }
        
        // Проверяем, не отправляли ли мы уже бродкаст для этого URI
        val wasSent = notificationsSent.contains(uriString)
        if (wasSent) {
            Timber.d("Пропуск отправки бродкаста: уже был отправлен для URI=$uriString")
            return
        }
        
        // Логируем отправку
        Timber.d("Отправка broadcast о завершении сжатия: Файл=$fileName, URI=$uriString")
        
        val intent = Intent(Constants.ACTION_COMPRESSION_COMPLETED).apply {
            putExtra(Constants.EXTRA_FILE_NAME, fileName)
            putExtra(Constants.EXTRA_ORIGINAL_SIZE, originalSize)
            putExtra(Constants.EXTRA_COMPRESSED_SIZE, compressedSize)
            putExtra(Constants.EXTRA_REDUCTION_PERCENT, sizeReduction)
            putExtra(Constants.EXTRA_URI, uriString) // Важно: добавляем URI для отслеживания завершения
            // Добавляем флаг для предотвращения многократного создания активити
            flags = Intent.FLAG_RECEIVER_FOREGROUND
        }
        
        // Отмечаем, что бродкаст для этого URI уже был отправлен
        notificationsSent.add(uriString)
        
        context.sendBroadcast(intent)
        Timber.d("Отправлен broadcast о завершении сжатия: $fileName")
    }
    
    /**
     * Показывает уведомление об ошибке сжатия
     */
    private fun showErrorNotification(fileName: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Создание канала уведомлений для Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "compression_completion_channel",
                context.getString(R.string.notification_completion_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = context.getString(R.string.notification_completion_channel_description)
            channel.setShowBadge(true)
            channel.enableLights(true)
            channel.enableVibration(true)
            notificationManager.createNotificationChannel(channel)
        }
        
        // Создаем Intent для открытия приложения при нажатии на уведомление
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Создаем уведомление об ошибке
        val notification = NotificationCompat.Builder(context, "compression_completion_channel")
            .setContentTitle(context.getString(R.string.notification_compression_error))
            .setContentText(context.getString(R.string.notification_compression_error_details, fileName))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        
        // Используем уникальный ID для каждого уведомления
        val notificationId = System.currentTimeMillis().toInt() + 1
        
        // Логируем перед отправкой уведомления
        Timber.d("Отправка уведомления об ошибке сжатия: ID=$notificationId, Файл=$fileName")
        
        notificationManager.notify(notificationId, notification)
    }
    
    /**
     * Форматирует размер файла в удобочитаемом виде (KB, MB)
     */
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        }
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
            // Получаем путь к файлу
            val path = FileUtil.getFilePathFromUri(context, uri)
            
            // Проверяем, находится ли файл в директории приложения
            val isInAppDir = !path.isNullOrEmpty() && 
                (path.contains("/${Constants.APP_DIRECTORY}/") || 
                 path.contains("content://media/external/images/media") && 
                 path.contains(Constants.APP_DIRECTORY))
            
            if (isInAppDir) {
                Timber.d("Файл находится в директории приложения: $path")
                return@withContext true
            }
            
            // Проверяем, есть ли URI в списке обработанных
            val isProcessed = StatsTracker.isImageProcessed(context, uri)
            if (isProcessed) {
                Timber.d("URI найден в списке обработанных: $uri")
                return@withContext true
            }

            // Дополнительная проверка - если файл уже был сжат (по размеру)
            val fileSize = getFileSize(uri)
            if (fileSize > 0 && fileSize < Constants.MIN_FILE_SIZE * 2) { // Если размер меньше 100KB
                Timber.d("Файл уже достаточно мал (${fileSize/1024}KB), пропускаем")
                return@withContext true
            }

            // Проверяем, не является ли этот файл результатом предыдущего сжатия
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    
                    if (nameIndex != -1 && dateIndex != -1) {
                        val fileName = cursor.getString(nameIndex)
                        val dateAdded = cursor.getLong(dateIndex)
                        
                        // Если файл был создан менее 5 секунд назад и его размер уже оптимальный
                        if (System.currentTimeMillis() / 1000 - dateAdded < 5 && fileSize < 1024 * 1024) {
                            Timber.d("Файл был недавно создан и уже оптимального размера: $fileName")
                            return@withContext true
                        }
                    }
                }
            }
            
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
            // Вычисляем процент сокращения размера
            val sizeReduction = if (stats.originalSize > 0) {
                ((stats.originalSize - stats.compressedSize).toFloat() / stats.originalSize) * 100
            } else 0f
            
            // Создаем имя файла для сжатой версии
            val compressedFileName = FileUtil.createCompressedFileName(originalFileName)
            
            // Сохраняем сжатое изображение в галерею
            val (savedUri, deleteIntentSender) = FileUtil.saveCompressedImageToGallery(
                context,
                compressedFile,
                compressedFileName,
                originalUri
            )
            
            // Добавляем URI в список обработанных
            savedUri?.let {
                Timber.d("Сжатый файл сохранен: ${FileUtil.getFilePathFromUri(context, it)}")
                
                // Отправляем broadcast о завершении сжатия
                sendCompletionBroadcast(
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
        try {
            val cacheDir = context.cacheDir
            val currentTime = System.currentTimeMillis()
            
            // Получаем список файлов, которые сейчас используются в текущем процессе
            val currentTempFile = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)?.let { uri ->
                Uri.parse(uri).lastPathSegment
            }
            
            // Синхронизируем доступ к файловой системе
            synchronized(this) {
                // Получаем все временные файлы в кэше
                val files = cacheDir.listFiles { file ->
                    // Проверяем все временные файлы, созданные приложением
                    val isTempFile = file.name.startsWith("temp_image_") || 
                                    file.name.startsWith("input_") ||
                                    file.name.endsWith(".jpg") ||
                                    file.name.endsWith(".jpeg")
                    
                    // Проверяем, что файл достаточно старый или имеет нулевой размер
                    val isOldOrEmpty = (currentTime - file.lastModified() > Constants.TEMP_FILE_MAX_AGE) || 
                                       file.length() == 0L
                    
                    // Не удаляем файл, если он используется в текущем процессе
                    val isCurrentlyInUse = currentTempFile != null && file.name.contains(currentTempFile)
                    
                    isTempFile && (isOldOrEmpty || !isCurrentlyInUse)
                }
                
                var deletedCount = 0
                var totalSize = 0L
                
                files?.forEach { file ->
                    // Дополнительная проверка перед удалением
                    if (file.exists() && !isFileInUse(file)) {
                        val fileSize = file.length()
                        
                        try {
                            // Пробуем сначала очистить содержимое файла
                            if (fileSize > 0) {
                                FileOutputStream(file).use { it.channel.truncate(0) }
                            }
                            
                            // Теперь пытаемся удалить файл
                            if (file.delete()) {
                                totalSize += fileSize
                                deletedCount++
                                Timber.d("Удален временный файл: ${file.absolutePath}, размер: ${fileSize/1024}KB")
                            } else {
                                Timber.w("Не удалось удалить временный файл: ${file.absolutePath}")
                                // Помечаем файл для удаления при выходе
                                file.deleteOnExit()
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Ошибка при удалении файла: ${file.absolutePath}")
                            // Помечаем файл для удаления при выходе
                            file.deleteOnExit()
                        }
                    }
                }
                
                if (deletedCount > 0) {
                    Timber.d("Очистка временных файлов завершена, удалено файлов: $deletedCount, освобождено: ${totalSize/1024}KB")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при очистке временных файлов")
        }
    }
    
    /**
     * Проверяет, используется ли файл в данный момент
     */
    private fun isFileInUse(file: File): Boolean {
        return try {
            synchronized(this) {
                // Пробуем открыть файл для записи - если не получается, значит файл используется
                val channel = FileOutputStream(file, true).channel
                channel.close()
                false // Файл не используется
            }
        } catch (e: Exception) {
            Timber.d("Файл используется другим процессом: ${file.absolutePath}")
            true // Файл используется
        }
    }
} 
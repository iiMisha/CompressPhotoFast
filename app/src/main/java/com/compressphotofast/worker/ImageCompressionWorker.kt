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
import android.content.IntentSender
import java.io.ByteArrayInputStream

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
        val uriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
        if (uriString.isNullOrEmpty()) {
            Timber.e("URI не установлен для компрессии")
            return@withContext Result.failure()
        }

        val uri = Uri.parse(uriString)
        
        // Проверка существования файла с использованием централизованного метода
        if (!FileUtil.isUriExistsSuspend(applicationContext, uri)) {
            Timber.e("Файл не существует: $uriString")
            return@withContext Result.failure()
        }
        
        // Проверка на временный файл с использованием централизованного метода
        if (FileUtil.isFilePendingSuspend(applicationContext, uri)) {
            Timber.d("Файл находится в процессе записи, пропускаем: $uriString")
            return@withContext Result.failure()
        }
        
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
                    return@withContext updateStatusAndReturn(
                        imageUri, 
                        StatsTracker.COMPRESSION_STATUS_SKIPPED, 
                        skipped = true
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при проверке существования URI: $imageUri")
                // Продолжаем выполнение, так как ошибка может быть временной
            }
            
            // Используем централизованную логику из ImageProcessingChecker для определения необходимости обработки
            // Это заменяет старый код с дублирующейся логикой
            val isAlreadyProcessed = ImageProcessingChecker.isAlreadyProcessed(context, imageUri)
            
            if (isAlreadyProcessed) {
                Timber.d("Изображение уже обработано (централизованная проверка): $imageUri")
                return@withContext updateStatusAndReturn(
                    imageUri, 
                    StatsTracker.COMPRESSION_STATUS_SKIPPED, 
                    skipped = true
                )
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
                // Если сжатие эффективно (>10%), начинаем процесс сжатия
                Timber.d("Тестовое сжатие эффективно, начинаем процесс сжатия с новой стратегией")
                
                try {
                    // Получаем имя файла из URI
                    val fileName = getFileNameSafely(imageUri)
                    if (fileName.isNullOrEmpty()) {
                        Timber.e("Не удалось получить имя файла из URI: $imageUri")
                        return@withContext updateStatusAndReturn(
                            imageUri, 
                            StatsTracker.COMPRESSION_STATUS_FAILED, 
                            errorMessage = "Не удалось получить имя файла"
                        )
                    }
                    Timber.d("Имя исходного файла: $fileName")
                    
                    // Получаем директорию для сохранения сжатого файла
                    val directory = if (FileUtil.isSaveModeReplace(context)) {
                        // Если включен режим замены, сохраняем в той же директории
                        FileUtil.getDirectoryFromUri(context, imageUri) ?: Constants.APP_DIRECTORY
                    } else {
                        // Иначе сохраняем в директории приложения
                        Constants.APP_DIRECTORY
                    }
                    Timber.d("Директория для сохранения: $directory")
                    
                    // Проверяем, является ли URI документом из MediaDocumentsProvider
                    val isMediaDocumentsUri = imageUri.authority == "com.android.providers.media.documents"
                    Timber.d("Проверка типа URI: isMediaDocumentsUri=$isMediaDocumentsUri")
                    
                    // Получаем поток с сжатым изображением напрямую из RAM-сжатия
                    val compressedStream = CompressionTestUtil.getCompressedImageStream(
                        context,
                        imageUri,
                        compressionQuality
                    )
                    
                    if (compressedStream == null) {
                        Timber.e("Не удалось получить поток с сжатым изображением")
                        return@withContext updateStatusAndReturn(
                            imageUri,
                            StatsTracker.COMPRESSION_STATUS_FAILED,
                            errorMessage = "Ошибка при сжатии изображения"
                        )
                    }
                    
                    // Для MediaDocumentsProvider URI не пытаемся переименовывать оригинальный файл
                    var backupUri: Uri? = null
                    if (!isMediaDocumentsUri) {
                        // Переименовываем оригинальный файл только для обычных URI
                        backupUri = FileUtil.renameOriginalFile(context, imageUri)
                        if (backupUri == null) {
                            Timber.e("Не удалось переименовать оригинальный файл, останавливаем процесс сжатия")
                            // Закрываем поток сжатого изображения
                            compressedStream.close()
                            return@withContext updateStatusAndReturn(
                                imageUri,
                                StatsTracker.COMPRESSION_STATUS_FAILED,
                                errorMessage = "Не удалось переименовать оригинальный файл"
                            )
                        }
                        Timber.d("Оригинальный файл успешно переименован")
                    } else {
                        Timber.d("URI относится к MediaDocumentsProvider, пропускаем переименование оригинала")
                        // Используем исходный URI в качестве backupUri для копирования EXIF
                        backupUri = imageUri
                    }
                    
                    // Сохраняем сжатое изображение с именем оригинала
                    val savedUri = FileUtil.saveCompressedImageFromStream(
                        context,
                        ByteArrayInputStream(compressedStream.toByteArray()),
                        fileName,
                        directory,
                        backupUri ?: imageUri,
                        compressionQuality
                    )
                    
                    // Закрываем поток после использования
                    compressedStream.close()
                    
                    if (savedUri == null) {
                        Timber.e("Не удалось сохранить сжатый файл")
                        return@withContext updateStatusAndReturn(
                            imageUri,
                            StatsTracker.COMPRESSION_STATUS_FAILED,
                            errorMessage = "Ошибка при сохранении сжатого изображения"
                        )
                    }
                    
                    Timber.d("Сжатый файл успешно сохранен: $savedUri")
                    
                    // Удаляем переименованный оригинальный файл только если это не MediaDocumentsProvider URI
                    // и если включен режим замены
                    if (!isMediaDocumentsUri && FileUtil.isSaveModeReplace(context)) {
                        try {
                            // Удаляем переименованный оригинальный файл
                            backupUri?.let { uri ->
                                val deleteResult = FileUtil.deleteFile(context, uri)
                                
                                if (deleteResult is Boolean && deleteResult) {
                                    Timber.d("Переименованный оригинальный файл успешно удален: $uri")
                                } else if (deleteResult is IntentSender) {
                                    Timber.d("Для удаления переименованного оригинального файла требуется разрешение пользователя")
                                    // Отправляем запрос на удаление с разрешением пользователя
                                    addPendingDeleteRequest(uri, deleteResult)
                                } else {
                                    Timber.e("Не удалось удалить переименованный оригинальный файл: $uri")
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Ошибка при удалении переименованного оригинального файла: $backupUri")
                        }
                    } else if (isMediaDocumentsUri) {
                        Timber.d("URI относится к MediaDocumentsProvider, пропускаем удаление оригинала")
                    } else {
                        Timber.d("Режим замены выключен, оригинальный файл сохранен как ${backupUri}")
                    }
                        
                        // Получаем размер сжатого файла для уведомления
                    val compressedSize = FileUtil.getFileSize(context, savedUri)
                    val sizeReduction = if (fileSize > 0 && compressedSize > 0) {
                            ((fileSize - compressedSize).toFloat() / fileSize) * 100
                        } else 0f
                        
                        // Показываем уведомление о завершении сжатия
                        sendCompressionStatusNotification(fileName, fileSize, compressedSize, sizeReduction, false)
                    
                    // Обновляем статус при успешном сжатии
                    return@withContext updateStatusAndReturn(
                        imageUri, 
                        StatsTracker.COMPRESSION_STATUS_COMPLETED
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при сжатии с новой стратегией: ${e.message}")
                    return@withContext updateStatusAndReturn(
                        imageUri, 
                        StatsTracker.COMPRESSION_STATUS_FAILED, 
                        errorMessage = e.message ?: "Ошибка при сжатии"
                    )
                }
            } else {
                // Если сжатие неэффективно, пропускаем файл
                Timber.d("Тестовое сжатие в RAM неэффективно (экономия меньше порогового значения ${Constants.TEST_COMPRESSION_EFFICIENCY_THRESHOLD}%), пропускаем файл")
                
                // Получаем имя файла для уведомления
                val fileName = getFileNameSafely(imageUri)
                
                // Получаем более точную статистику о результатах тестового сжатия
                val stats = CompressionTestUtil.getTestCompressionStats(
                    context,
                    imageUri,
                    fileSize,
                    compressionQuality
                )
                
                // Определяем размер сжатого файла и процент сокращения
                val estimatedCompressedSize = stats?.compressedSize ?: (fileSize - (fileSize * 0.08f).toLong())
                val estimatedSizeReduction = stats?.reductionPercent ?: 8.0f
                
                // Проверяем использование памяти и форсируем GC при необходимости
                val usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                Timber.d("Использование памяти после тестового сжатия: ${usedMemory / 1024 / 1024}MB")
                
                if (usedMemory > 100 * 1024 * 1024) { // Если используется больше 100MB
                    Timber.d("Запрашиваем сборку мусора для освобождения памяти")
                    System.gc()
                }
                
                // Показываем уведомление о пропуске файла
                sendCompressionStatusNotification(fileName, fileSize, estimatedCompressedSize, estimatedSizeReduction, true)
                
                // Обновляем статус
                return@withContext updateStatusAndReturn(
                    imageUri, 
                    StatsTracker.COMPRESSION_STATUS_SKIPPED, 
                    skipped = true
                )
            }
        } catch (e: Exception) {
            // Обновляем статус при неожиданной ошибке
            val imageUriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
            if (imageUriString != null) {
                val uri = Uri.parse(imageUriString)
                Timber.e(e, "Неожиданная ошибка в worker: ${e.message}")
                return@withContext updateStatusAndReturn(
                    uri,
                    StatsTracker.COMPRESSION_STATUS_FAILED,
                    errorMessage = e.message ?: "Неожиданная ошибка"
                )
            }
            
            // Если не удалось получить URI, возвращаем стандартную ошибку
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
                    val id = getCursorLong(cursor, MediaStore.Images.Media._ID)
                    val name = getCursorString(cursor, MediaStore.Images.Media.DISPLAY_NAME)
                    val size = getCursorLong(cursor, MediaStore.Images.Media.SIZE)
                    val date = getCursorLong(cursor, MediaStore.Images.Media.DATE_ADDED)
                    val mime = getCursorString(cursor, MediaStore.Images.Media.MIME_TYPE)
                    val data = getCursorString(cursor, MediaStore.Images.Media.DATA)
                    
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
                val name = getCursorString(it, MediaStore.Images.Media.DISPLAY_NAME, "неизвестно")
                val date = getCursorLong(it, MediaStore.Images.Media.DATE_ADDED, 0)
                val size = getCursorLong(it, MediaStore.Images.Media.SIZE, 0)
                val data = getCursorString(it, MediaStore.Images.Media.DATA, "неизвестно")
                
                info = "Имя: $name, Дата: $date, Размер: $size, Путь: $data"
            }
        }
        
        return@withContext info
    }

    /**
     * Проверка, является ли файл временным/в процессе записи
     */
    private suspend fun isFilePending(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        return@withContext FileUtil.isFilePendingSuspend(applicationContext, uri)
    }

    /**
     * Создает информацию для foreground сервиса
     */
    private fun createForegroundInfo(notificationId: Int, notificationTitle: String): ForegroundInfo {
        // Создаем или обновляем канал уведомлений
        NotificationUtil.createDefaultNotificationChannel(applicationContext)
        
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
     * Показывает уведомление о завершении или пропуске сжатия с информацией о результате
     */
    private fun sendCompressionStatusNotification(
        fileName: String, 
        originalSize: Long, 
        compressedSize: Long, 
        sizeReduction: Float,
        skipped: Boolean
    ) {
        try {
            val uriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
            if (uriString != null) {
                // Определяем тип уведомления: о завершении или о пропуске
                val action = if (skipped) 
                    Constants.ACTION_COMPRESSION_SKIPPED 
                else 
                    Constants.ACTION_COMPRESSION_COMPLETED
                    
                // Отправляем информацию через broadcast
                val intent = Intent(action).apply {
                    putExtra(Constants.EXTRA_FILE_NAME, fileName)
                    putExtra(Constants.EXTRA_URI, uriString)
                    putExtra(Constants.EXTRA_ORIGINAL_SIZE, originalSize)
                    putExtra(Constants.EXTRA_COMPRESSED_SIZE, compressedSize)
                    putExtra(Constants.EXTRA_REDUCTION_PERCENT, sizeReduction)
                    flags = Intent.FLAG_RECEIVER_FOREGROUND
                }
                context.sendBroadcast(intent)
            }
            
            // Логируем информацию о результате
            val message = if (skipped) {
                "Уведомление о пропуске сжатия отправлено: Файл=$fileName, экономия=${String.format("%.1f", sizeReduction)}%"
            } else {
                "Уведомление о завершении сжатия отправлено: Файл=$fileName"
            }
            Timber.d(message)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при отправке уведомления о ${if (skipped) "пропуске" else "завершении"} сжатия")
        }
    }

    /**
     * Создание данных для результата с успехом
     */
    private fun createSuccessOutput(skipped: Boolean = false): Data {
        return Data.Builder()
            .putBoolean("success", true)
            .putBoolean("skipped", skipped)
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
                sendCompressionStatusNotification(
                    fileName = originalFileName,
                    originalSize = stats.originalSize,
                    compressedSize = stats.compressedSize,
                    sizeReduction = sizeReduction,
                    skipped = false
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
    private fun addPendingDeleteRequest(uri: Uri, deletePendingIntent: IntentSender) {
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
            putExtra(Constants.EXTRA_DELETE_INTENT_SENDER, deletePendingIntent)
        }
        context.sendBroadcast(intent)
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
     * Безопасно получает строковое значение из курсора по имени колонки
     */
    private fun getCursorString(cursor: android.database.Cursor, columnName: String, defaultValue: String = "unknown"): String {
        val index = cursor.getColumnIndex(columnName)
        return if (index != -1) cursor.getString(index) else defaultValue
    }
    
    /**
     * Безопасно получает числовое значение из курсора по имени колонки
     */
    private fun getCursorLong(cursor: android.database.Cursor, columnName: String, defaultValue: Long = -1): Long {
        val index = cursor.getColumnIndex(columnName)
        return if (index != -1) cursor.getLong(index) else defaultValue
    }

    /**
     * Обновляет статус и возвращает результат с указанным статусом
     */
    private suspend fun updateStatusAndReturn(
        uri: Uri, 
        status: Int, 
        skipped: Boolean = false, 
        errorMessage: String? = null
    ): Result {
        StatsTracker.updateStatus(context, uri, status)
        
        return if (errorMessage != null) {
            Result.failure(createFailureOutput(errorMessage))
        } else {
            Result.success(createSuccessOutput(skipped))
        }
    }

    /**
     * Проверка на наличие файла
     */
    private suspend fun isUriExists(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        return@withContext FileUtil.isUriExistsSuspend(applicationContext, uri)
    }
} 
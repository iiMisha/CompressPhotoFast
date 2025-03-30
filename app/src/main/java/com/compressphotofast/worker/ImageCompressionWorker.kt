package com.compressphotofast.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.exifinterface.media.ExifInterface
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.compressphotofast.R
import com.compressphotofast.ui.MainActivity
import com.compressphotofast.util.Constants
import com.compressphotofast.util.FileUtil
import com.compressphotofast.util.StatsTracker
import com.compressphotofast.util.UriProcessingTracker
import com.compressphotofast.util.TempFilesCleaner
import com.compressphotofast.util.ImageCompressionUtil
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.ExifUtil
import com.compressphotofast.util.ImageProcessingChecker
import com.compressphotofast.util.LogUtil
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import id.zelory.compressor.Compressor
import id.zelory.compressor.constraint.format
import id.zelory.compressor.constraint.quality
import id.zelory.compressor.constraint.resolution
import id.zelory.compressor.constraint.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import java.util.HashSet
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Worker для сжатия изображений в фоновом режиме
 */
@HiltWorker
class ImageCompressionWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    // Переопределяем поле applicationContext для удобного доступа
    private val appContext: Context
        get() = context

    // Качество сжатия (получаем из входных данных)
    private val compressionQuality = inputData.getInt("compression_quality", Constants.COMPRESSION_QUALITY_MEDIUM)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Получаем параметры задачи
        val uriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
        if (uriString.isNullOrEmpty()) {
                LogUtil.processInfo("URI не установлен для компрессии")
            return@withContext Result.failure()
        }

            val imageUri = Uri.parse(uriString)
            val compressionQuality = inputData.getInt(Constants.WORK_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_MEDIUM)
            
            // Обновляем уведомление
            setForeground(createForegroundInfo(appContext.getString(R.string.notification_compression_in_progress)))
            
            LogUtil.processInfo("[ПРОЦЕСС] Используется качество сжатия: $compressionQuality")
            
            // 1. Загружаем EXIF данные в память перед любыми операциями с файлом
            val exifDataMemory = ExifUtil.readExifDataToMemory(appContext, imageUri)
            LogUtil.uriInfo(imageUri, "[ПРОЦЕСС] Загружены EXIF данные в память: ${exifDataMemory.size} тегов")
            
            // Используем централизованную логику для проверки необходимости обработки
            val processingCheckResult = ImageProcessingChecker.isProcessingRequired(appContext, imageUri)
            
            // Если файл уже обработан и не требует повторной обработки, пропускаем его
            if (!processingCheckResult.processingRequired && 
                processingCheckResult.reason == ImageProcessingChecker.ProcessingSkipReason.ALREADY_COMPRESSED) {
                LogUtil.uriInfo(imageUri, "Изображение уже сжато и не требует повторной обработки, пропускаем")
                setForeground(createForegroundInfo(appContext.getString(R.string.notification_skipping_compressed)))
                return@withContext Result.success()
            } else if (processingCheckResult.hasCompressionMarker) {
                // Если файл имеет маркер сжатия, но требует повторной обработки
                LogUtil.uriInfo(imageUri, "Изображение было модифицировано после сжатия, требуется повторная обработка")
            }
            
            // Проверка существования файла
            if (!FileUtil.isUriExistsSuspend(appContext, imageUri)) {
                LogUtil.error(imageUri, "Проверка файла", "Файл не существует")
                return@withContext Result.failure()
            }
            
            // Проверка на временный файл
            if (FileUtil.isFilePendingSuspend(appContext, imageUri)) {
                LogUtil.skipImage(imageUri, "Файл находится в процессе записи")
                return@withContext Result.failure()
            }
            
            // Начинаем отслеживание сжатия
            StatsTracker.startTracking(imageUri)
            StatsTracker.updateStatus(appContext, imageUri, StatsTracker.COMPRESSION_STATUS_PROCESSING)
            
            // Проверяем размер исходного файла
            val sourceSize = FileUtil.getFileSize(appContext, imageUri)
            
            // Если размер слишком маленький или слишком большой, пропускаем
            if (!FileUtil.isFileSizeValid(sourceSize)) {
                LogUtil.uriInfo(imageUri, "Размер файла невалидный: $sourceSize, пропускаем")
                setForeground(createForegroundInfo(appContext.getString(R.string.notification_skipping_invalid_size)))
                return@withContext Result.success()
            }
            
            // Выполняем тестовое сжатие для оценки эффективности
            LogUtil.processInfo("[ПРОЦЕСС] Выполняем тестовое сжатие в RAM")
            LogUtil.uriInfo(imageUri, "Изображение требует обработки")
            LogUtil.uriInfo(imageUri, "Начало тестового сжатия в RAM")
            
            val testCompressionResult = ImageCompressionUtil.testCompression(
                appContext,
                imageUri, 
                sourceSize,
                compressionQuality
            )
            
            if (testCompressionResult == null) {
                LogUtil.error(imageUri, "Тестовое сжатие", "Ошибка при тестовом сжатии")
                setForeground(createForegroundInfo(appContext.getString(R.string.notification_compression_failed)))
                StatsTracker.updateStatus(appContext, imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
                return@withContext Result.failure()
            }
            
            val sourceSizeKB = testCompressionResult.originalSize / 1024
            val compressedSizeKB = testCompressionResult.compressedSize / 1024
            val compressionSavingPercent = testCompressionResult.sizeReduction
            
            LogUtil.imageCompression(imageUri, "${sourceSizeKB}KB → ${compressedSizeKB}KB (-${compressionSavingPercent}%)")
            
            // Если сжатие эффективно, продолжаем с полным сжатием и сохранением
            if (testCompressionResult.isEfficient()) {
                LogUtil.processInfo("[ПРОЦЕСС] Тестовое сжатие для ${imageUri.lastPathSegment} эффективно (экономия $compressionSavingPercent%), выполняем полное сжатие")
                LogUtil.processInfo("[ПРОЦЕСС] Тестовое сжатие эффективно, начинаем полное сжатие")
                
                // Получаем имя файла
                val fileName = FileUtil.getFileNameFromUri(appContext, imageUri)
                
                    if (fileName.isNullOrEmpty()) {
                    LogUtil.error(imageUri, "Имя файла", "Не удалось получить имя файла")
                    setForeground(createForegroundInfo(appContext.getString(R.string.notification_compression_failed)))
                    StatsTracker.updateStatus(appContext, imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
                    return@withContext Result.failure()
                }
                
                LogUtil.uriInfo(imageUri, "Имя файла: $fileName")
                
                // Проверяем, является ли URI из MediaDocumentProvider
                val isMediaDocumentsUri = imageUri.authority == "com.android.providers.media.documents"
                
                // Определяем директорию для сохранения
                val directory = if (FileUtil.isSaveModeReplace(appContext)) {
                        // Если включен режим замены, сохраняем в той же директории
                    FileUtil.getDirectoryFromUri(appContext, imageUri)
                    } else {
                        // Иначе сохраняем в директории приложения
                        Constants.APP_DIRECTORY
                    }
                
                LogUtil.uriInfo(imageUri, "Директория для сохранения: $directory")
                
                // Получаем поток с изображением
                val imageStream = appContext.contentResolver.openInputStream(imageUri)
                
                if (imageStream == null) {
                    LogUtil.error(imageUri, "Открытие потока", "Не удалось открыть поток изображения")
                    setForeground(createForegroundInfo(appContext.getString(R.string.notification_compression_failed)))
                    StatsTracker.updateStatus(appContext, imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
                    return@withContext Result.failure()
                }
                
                // Сжимаем изображение
                val compressedImageStream = ImageCompressionUtil.compressImageToStream(
                    appContext,
                    imageUri,
                    compressionQuality
                )
                    
                // Закрываем исходный поток
                imageStream.close()
                
                if (compressedImageStream == null) {
                    LogUtil.error(imageUri, "Сжатие", "Ошибка при сжатии изображения")
                    setForeground(createForegroundInfo(appContext.getString(R.string.notification_compression_failed)))
                    StatsTracker.updateStatus(appContext, imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
                    return@withContext Result.failure()
                }
                
                // Переименовываем оригинальный файл перед сохранением сжатой версии, если нужно
                var backupUri = imageUri
                
                    if (!isMediaDocumentsUri) {
                    backupUri = FileUtil.renameOriginalFileIfNeeded(appContext, imageUri) ?: imageUri
                }
                
                // Сохраняем сжатое изображение
                    val savedUri = FileUtil.saveCompressedImageFromStream(
                    appContext,
                    ByteArrayInputStream(compressedImageStream.toByteArray()),
                        fileName,
                    directory ?: Constants.APP_DIRECTORY,
                    backupUri,
                    compressionQuality,
                    exifDataMemory // Передаем заранее загруженные EXIF данные
                )
                
                // Закрываем поток сжатого изображения
                compressedImageStream.close()
                
                // Проверяем результат сохранения
                    if (savedUri == null) {
                    LogUtil.error(imageUri, "Сохранение", "Не удалось сохранить сжатое изображение")
                    setForeground(createForegroundInfo(appContext.getString(R.string.notification_compression_failed)))
                    StatsTracker.updateStatus(appContext, imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
                    return@withContext Result.failure()
                }
                
                LogUtil.fileOperation(imageUri, "Сохранение", "Сжатый файл успешно сохранен: $savedUri")
                
                // Если режим замены включен и URI был переименован, удаляем переименованный оригинальный файл
                try {
                    if (FileUtil.isSaveModeReplace(appContext) && backupUri != imageUri) {
                        LogUtil.processInfo("[ПРОЦЕСС] Начинаем удаление файла: $backupUri")
                        val deleteResult = FileUtil.deleteFile(appContext, backupUri)
                        
                        when (deleteResult) {
                            is Boolean -> {
                                if (deleteResult) {
                                    LogUtil.fileOperation(imageUri, "Удаление", "Переименованный оригинальный файл успешно удален")
                                } else {
                                    LogUtil.error(imageUri, "Удаление", "Не удалось удалить переименованный оригинальный файл")
                                }
                            }
                            is android.content.IntentSender -> {
                                LogUtil.fileOperation(imageUri, "Удаление", "Требуется разрешение пользователя на удаление переименованного оригинала")
                                // В WorkManager мы не можем запросить разрешение через IntentSender
                                addPendingDeleteRequest(backupUri, deleteResult)
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogUtil.error(imageUri, "Удаление", "Ошибка при удалении переименованного оригинального файла", e)
                }
                
                // Получаем размер сжатого файла для уведомления
                val compressedSize = FileUtil.getFileSize(appContext, savedUri)
                val sizeReduction = if (sourceSize > 0 && compressedSize > 0) {
                    ((sourceSize - compressedSize).toFloat() / sourceSize) * 100
                } else 0f
                
                // Отправляем уведомление о завершении сжатия
                sendCompressionStatusNotification(
                    fileName,
                    sourceSize,
                    compressedSize,
                    sizeReduction,
                    false
                )
                
                LogUtil.processInfo("[ПРОЦЕСС] Уведомление о завершении сжатия отправлено: Файл=$fileName")
                setForeground(createForegroundInfo(appContext.getString(R.string.notification_compression_completed)))
                
                // Обновляем статус и возвращаем успех
                StatsTracker.updateStatus(appContext, imageUri, StatsTracker.COMPRESSION_STATUS_COMPLETED)
                return@withContext Result.success()
            } else {
                // Если сжатие неэффективно, пропускаем
                LogUtil.processInfo("[ПРОЦЕСС] Тестовое сжатие для ${imageUri.lastPathSegment} неэффективно (экономия $compressionSavingPercent%), пропускаем")
                setForeground(createForegroundInfo(appContext.getString(R.string.notification_skipping_inefficient)))
                
                // Получаем имя файла для уведомления
                val fileName = getFileNameSafely(imageUri)
                
                // Получаем более точную статистику о результатах тестового сжатия
                val stats = ImageCompressionUtil.testCompression(
                    appContext,
                    imageUri,
                    sourceSize,
                    compressionQuality
                )
                
                // Определяем размер сжатого файла и процент сокращения
                val estimatedCompressedSize = stats?.compressedSize ?: (sourceSize - (sourceSize * 0.08f).toLong())
                val estimatedSizeReduction = stats?.sizeReduction ?: 8.0f
                
                // Проверяем использование памяти и форсируем GC при необходимости
                val usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                LogUtil.processInfo("Использование памяти после тестового сжатия: ${usedMemory / 1024 / 1024}MB")
                
                if (usedMemory > 100 * 1024 * 1024) { // Если используется больше 100MB
                    LogUtil.processInfo("Запрашиваем сборку мусора для освобождения памяти")
                    System.gc()
                }
                
                // Показываем уведомление о пропуске файла
                sendCompressionStatusNotification(
                    fileName,
                    sourceSize,
                    estimatedCompressedSize,
                    estimatedSizeReduction,
                    true
                )
                
                // Обновляем статус и возвращаем успех с пропуском
                StatsTracker.updateStatus(appContext, imageUri, StatsTracker.COMPRESSION_STATUS_SKIPPED)
                return@withContext Result.success()
            }
        } catch (e: Exception) {
            LogUtil.error(null, "Сжатие", "Ошибка при сжатии изображения", e)
            setForeground(createForegroundInfo(appContext.getString(R.string.notification_compression_failed)))
            
            val uriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
            if (uriString != null) {
                val uri = Uri.parse(uriString)
                StatsTracker.updateStatus(appContext, uri, StatsTracker.COMPRESSION_STATUS_FAILED)
            }
            
            return@withContext Result.failure()
        }
    }

    /**
     * Создает информацию для foreground сервиса
     */
    private fun createForegroundInfo(notificationTitle: String): ForegroundInfo {
        return NotificationUtil.createForegroundInfo(
            context = appContext,
            notificationTitle = notificationTitle,
            notificationId = Constants.NOTIFICATION_ID_COMPRESSION
        )
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
                // Используем централизованный метод для отправки broadcast и показа уведомления
                NotificationUtil.sendCompressionResultBroadcast(
                    context = appContext,
                    uriString = uriString,
                    fileName = fileName,
                    originalSize = originalSize,
                    compressedSize = compressedSize,
                    sizeReduction = sizeReduction,
                    skipped = skipped
                )
            }
        } catch (e: Exception) {
            LogUtil.error(Uri.EMPTY, "Отправка уведомления", "Ошибка при отправке уведомления", e)
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
     * Добавляет запрос на удаление файла в список ожидающих
     */
    private fun addPendingDeleteRequest(uri: Uri, deletePendingIntent: IntentSender) {
        LogUtil.processInfo("Требуется разрешение пользователя для удаления оригинального файла: $uri")
        
        // Сохраняем URI в SharedPreferences для последующей обработки
        val prefs = appContext.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
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
        appContext.sendBroadcast(intent)
    }

    /**
     * Получает имя файла из URI с проверкой на null
     */
    private fun getFileNameSafely(uri: Uri): String {
        try {
            val fileName = FileUtil.getFileNameFromUri(appContext, uri)
            
            // Если имя файла не определено, генерируем временное имя на основе времени
            if (fileName.isNullOrBlank() || fileName == "unknown") {
                val timestamp = System.currentTimeMillis()
                LogUtil.processInfo("Не удалось получить имя файла для URI: $uri, используем временное имя")
                return "compressed_image_$timestamp.jpg"
            }
            
            return fileName
        } catch (e: Exception) {
            LogUtil.error(Uri.EMPTY, "Получение имени файла", e)
            val timestamp = System.currentTimeMillis()
            return "compressed_image_$timestamp.jpg"
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
} 
package com.compressphotofast.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.compressphotofast.R
import com.compressphotofast.ui.MainActivity
import com.compressphotofast.util.Constants
import com.compressphotofast.util.StatsTracker
import com.compressphotofast.util.UriProcessingTracker
import com.compressphotofast.util.PendingItemException
import com.compressphotofast.util.TempFilesCleaner
import com.compressphotofast.util.ImageCompressionUtil
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.ExifUtil
import com.compressphotofast.util.ImageProcessingChecker
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.UriUtil
import com.compressphotofast.util.MediaStoreUtil
import com.compressphotofast.util.FileOperationsUtil
import com.compressphotofast.util.CompressionBatchTracker
import com.compressphotofast.util.OptimizedCacheUtil
import com.compressphotofast.util.toInputStream
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Worker для сжатия изображений в фоновом режиме
 */
@HiltWorker
class ImageCompressionWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val uriProcessingTracker: UriProcessingTracker,
    private val compressionBatchTracker: CompressionBatchTracker
) : CoroutineWorker(context, workerParams) {

    // Переопределяем поле applicationContext для удобного доступа
    private val appContext: Context
        get() = context

    // Качество сжатия (получаем из входных данных)
    private val compressionQuality = inputData.getInt(Constants.WORK_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_MEDIUM)
    
    // ID батча для группировки результатов (может быть null для старых задач)
    private val batchId = inputData.getString(Constants.WORK_BATCH_ID)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        LogUtil.processDebug("ImageCompressionWorker.doWork() НАЧАЛО: ${inputData.getString(Constants.WORK_INPUT_IMAGE_URI)}")
        var testResult: ImageCompressionUtil.CompressionTestResult? = null
        var isLockOwner = false
        val uriStringInput = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
        val globalImageUri = if (uriStringInput != null) Uri.parse(uriStringInput) else null
        try {
            // Получаем параметры задачи
            val uriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
            if (uriString.isNullOrEmpty()) {
                LogUtil.processInfo("URI не задан")
                return@withContext Result.failure()
            }

            val imageUri = Uri.parse(uriString)

            // ЗАХВАТ БЛОКИРОВКИ (в самом начале, до чтения EXIF)
            val isHandledByIpu = inputData.getBoolean("is_handled_by_ipu", false)
            val addedToProcessing = if (isHandledByIpu) {
                // IPU добавил URI в трекер и не снял — Worker наследует владение.
                // addProcessingUriSafe — перестраховка: если cleanup уже удалил URI, добавим заново.
                // Результат игнорируем: IPU гарантирует эксклюзивность через per-URI WorkManager tag
                uriProcessingTracker.addProcessingUriSafe(imageUri, "ImageCompressionWorker_Takeover")
                true
            } else {
                uriProcessingTracker.addProcessingUriSafe(imageUri, "ImageCompressionWorker")
            }
            
            isLockOwner = addedToProcessing
            if (!addedToProcessing) {
                LogUtil.processDebug("URI уже обрабатывается другим потоком, пропускаем Worker: $imageUri")
                return@withContext Result.success() // success чтобы не блокировать цепочку WorkManager
            }

            // Если URI помечен как недоступный, проверяем его повторно перед выходом
            if (uriProcessingTracker.isUriUnavailable(imageUri)) {
                val exists = try {
                    UriUtil.isUriExistsSuspend(appContext, imageUri)
                } catch (e: Exception) {
                    false
                }
                
                val isPending = UriUtil.isFilePending(appContext, imageUri)
                
                if (exists && !isPending) {
                    uriProcessingTracker.removeUnavailable(imageUri)
                } else {
                    return@withContext Result.failure()
                }
            }

            // Ранняя проверка существования файла перед любыми операциями
            try {
                if (!UriUtil.isUriExistsSuspend(appContext, imageUri)) {
                    LogUtil.error(imageUri, "Ранняя проверка", "Файл не существует")
                    uriProcessingTracker.markUriUnavailable(imageUri)
                    return@withContext Result.failure()
                }
            } catch (e: PendingItemException) {
                // Если файл pending, возвращаем failure но НЕ помечаем как unavailable
                // это позволит GalleryScan или MediaStoreObserver попробовать позже еще раз
                return@withContext Result.failure()
            } catch (e: Exception) {
                LogUtil.error(imageUri, "Ранняя проверка", "Ошибка при проверке существования", e)
                return@withContext Result.failure()
            }

            // Обновляем уведомление
            // Для batch-обработки используем тихий режим для предотвращения спама уведомлений
            if (batchId.isNullOrEmpty()) {
                setForeground(createForegroundInfo("🔧 ${appContext.getString(R.string.notification_compression_in_progress)}"))
            } else {
                setForeground(NotificationUtil.createSilentForegroundInfo(
                    appContext,
                    Constants.NOTIFICATION_ID_COMPRESSION
                ))
            }

            // 1. Загружаем EXIF данные в память перед любыми операциями с файлом
            val exifDataMemory = try {
                ExifUtil.readExifDataToMemory(appContext, imageUri)
            } catch (e: java.io.FileNotFoundException) {
                LogUtil.error(imageUri, "Чтение EXIF", "Файл не найден при чтении EXIF: ${e.message}")
                uriProcessingTracker.markUriUnavailable(imageUri)
                return@withContext Result.failure()
            } catch (e: java.io.IOException) {
                LogUtil.error(imageUri, "Чтение EXIF", "Ошибка ввода/вывода при чтении EXIF: ${e.message}")
                uriProcessingTracker.markUriUnavailable(imageUri)
                return@withContext Result.failure()
            } catch (e: Exception) {
                LogUtil.error(imageUri, "Чтение EXIF", "Не удалось прочитать EXIF-данные, отмена задачи.", e)
                return@withContext Result.failure()
            }
            
            // Используем централизованную логику для проверки необходимости обработки
            val processingCheckResult = ImageProcessingChecker.isProcessingRequired(appContext, imageUri, forceProcess = true)
            
            // Если файл уже обработан и не требует повторной обработки, пропускаем его
            if (!processingCheckResult.processingRequired && 
                processingCheckResult.reason == ImageProcessingChecker.ProcessingSkipReason.ALREADY_COMPRESSED) {
                if (batchId.isNullOrEmpty()) {
                    setForeground(createForegroundInfo("🖼️ ${appContext.getString(R.string.notification_skipping_compressed)}"))
                } else {
                    setForeground(NotificationUtil.createSilentForegroundInfo(
                        appContext,
                        Constants.NOTIFICATION_ID_COMPRESSION
                    ))
                }
                return@withContext Result.success()
            }

            // Блокировка URI теперь захватывается в самом начале doWork()
            
            // Проверка на временный файл
            if (UriUtil.isFilePendingSuspend(appContext, imageUri)) {
                LogUtil.skipImage(imageUri, "Файл находится в процессе записи")
                return@withContext Result.failure()
            }
            
            // Начинаем отслеживание сжатия
            StatsTracker.startTracking(imageUri)
            StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_PROCESSING)
            
            // Проверяем размер исходного файла
            val sourceSize = try {
                UriUtil.getFileSize(appContext, imageUri)
            } catch (e: java.io.FileNotFoundException) {
                LogUtil.error(imageUri, "Проверка размера", "Файл не найден при получении размера: ${e.message}")
                return@withContext Result.failure()
            }

            // Если размер слишком маленький или слишком большой, пропускаем
            if (!FileOperationsUtil.isFileSizeValid(sourceSize)) {
                LogUtil.uriInfo(imageUri, "Размер файла невалидный: $sourceSize, пропускаем")
                if (batchId.isNullOrEmpty()) {
                    setForeground(createForegroundInfo("📏 ${appContext.getString(R.string.notification_skipping_invalid_size)}"))
                } else {
                    setForeground(NotificationUtil.createSilentForegroundInfo(
                        appContext,
                        Constants.NOTIFICATION_ID_COMPRESSION
                    ))
                }
                return@withContext Result.success()
            }
            
            // Выполняем тестовое сжатие для оценки эффективности
            testResult = ImageCompressionUtil.testCompression(
                appContext,
                imageUri, 
                sourceSize,
                compressionQuality,
                keepStream = true // Сохраняем поток для повторного использования
            )
            
            if (testResult == null) {
                LogUtil.error(imageUri, "Тестовое сжатие", "Ошибка при тестовом сжатии")
                if (batchId.isNullOrEmpty()) {
                    setForeground(createForegroundInfo("❌ ${appContext.getString(R.string.notification_compression_failed)}"))
                } else {
                    setForeground(NotificationUtil.createSilentForegroundInfo(
                        appContext,
                        Constants.NOTIFICATION_ID_COMPRESSION
                    ))
                }
                StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
                return@withContext Result.failure()
            }
            
            val testCompressionResult = testResult.stats
            val sourceSizeKB = testCompressionResult.originalSize / 1024
            val compressedSizeKB = testCompressionResult.compressedSize / 1024
            val compressionSavingPercent = testCompressionResult.sizeReduction
            
            LogUtil.imageCompression(imageUri, "${sourceSizeKB}KB → ${compressedSizeKB}KB (-${compressionSavingPercent}%)")

            // Определяем, нужно ли пропускать сжатие
            val shouldSkipCompression = !testResult.isEfficient()

            // Если сжатие эффективно, продолжаем
            if (!shouldSkipCompression) {
                // Получаем имя файла
                val fileName = UriUtil.getFileNameFromUri(appContext, imageUri)
                
                if (fileName.isNullOrEmpty()) {
                    LogUtil.error(imageUri, "Имя файла", "Не удалось получить имя файла")
                    if (batchId.isNullOrEmpty()) {
                        setForeground(createForegroundInfo("❌ ${appContext.getString(R.string.notification_compression_failed)}"))
                    } else {
                        setForeground(NotificationUtil.createSilentForegroundInfo(
                            appContext,
                            Constants.NOTIFICATION_ID_COMPRESSION
                        ))
                    }
                    StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
                    return@withContext Result.failure()
                }
                
                // Определяем правильное имя файла в зависимости от режима сохранения
                val finalFileName = FileOperationsUtil.createCompressedFileName(appContext, fileName)
                
                // Проверяем, является ли URI из MediaDocumentProvider
                val isMediaDocumentsUri = imageUri.authority == "com.android.providers.media.documents"
                
                // Определяем директорию для сохранения
                val directory = if (FileOperationsUtil.isSaveModeReplace(appContext)) {
                        // Если включен режим замены, сохраняем в той же директории
                    UriUtil.getDirectoryFromUri(appContext, imageUri)
                    } else {
                        // Иначе сохраняем в директории приложения
                        Constants.APP_DIRECTORY
                    }

                // Используем уже сжатый поток из параметров теста
                val compressedImageStream = testResult.compressedStream
                
                if (compressedImageStream == null) {
                    LogUtil.error(imageUri, "Сжатие", "Сжатый поток утерян (null)")
                    if (batchId.isNullOrEmpty()) {
                        setForeground(createForegroundInfo("❌ ${appContext.getString(R.string.notification_compression_failed)}"))
                    } else {
                        setForeground(NotificationUtil.createSilentForegroundInfo(
                            appContext,
                            Constants.NOTIFICATION_ID_COMPRESSION
                        ))
                    }
                    StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
                    return@withContext Result.failure()
                }
                
                // Сохраняем сжатое изображение с гарантированным закрытием потока
                val savedUri = compressedImageStream.use { stream ->
                    MediaStoreUtil.saveCompressedImageFromStream(
                        context = appContext,
                        inputStream = stream.toInputStream(),
                        fileName = finalFileName,
                        directory = directory,
                        originalUri = imageUri,
                        quality = compressionQuality,
                        exifDataMemory = exifDataMemory
                    )
                }

                if (savedUri == null) {
                    LogUtil.error(imageUri, "Сохранение", "Не удалось сохранить сжатое изображение")
                    if (batchId.isNullOrEmpty()) {
                        setForeground(createForegroundInfo("❌ ${appContext.getString(R.string.notification_compression_failed)}"))
                    } else {
                        setForeground(NotificationUtil.createSilentForegroundInfo(
                            appContext,
                            Constants.NOTIFICATION_ID_COMPRESSION
                        ))
                    }
                    StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
                    return@withContext Result.failure()
                }

                uriProcessingTracker.setIgnorePeriod(savedUri)
                if (savedUri != imageUri) {
                    uriProcessingTracker.setIgnorePeriod(imageUri)
                }

                // Верификация целостности ВСЕГДА, не только в режиме замены
                // Надёжность важнее скорости — повреждённый файл не должен попасть в галерею
                val isSavedFileValid = ImageCompressionUtil.verifyImageIntegrity(context, savedUri)
                if (!isSavedFileValid) {
                    LogUtil.error(imageUri, "Верификация", "КРИТИЧЕСКАЯ ОШИБКА: Сохранённый файл повреждён!")
                    // Удаляем повреждённый файл из MediaStore
                    try {
                        appContext.contentResolver.delete(savedUri, null, null)
                        LogUtil.error(imageUri, "Верификация", "Повреждённый файл удалён из MediaStore: $savedUri")
                    } catch (e: Exception) {
                        LogUtil.error(savedUri, "Верификация", "Не удалось удалить повреждённый файл", e)
                    }
                    StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
                    return@withContext Result.failure()
                }

                // Если режим замены включен, удаляем оригинальный файл ПОСЛЕ успешного сохранения нового
                // НО: если savedUri == imageUri, значит файл был перезаписан на месте и удалять не нужно
                var deleteFailed = false
                var deleteErrorMessage: String? = null
                if (FileOperationsUtil.isSaveModeReplace(appContext) && savedUri != imageUri) {
                    try {
                        if (UriUtil.isUriExistsSuspend(appContext, imageUri)) {
                            val deleteResult = FileOperationsUtil.deleteFile(appContext, imageUri, uriProcessingTracker, forceDelete = true)
                            if (deleteResult is IntentSender) {
                                addPendingDeleteRequest(imageUri, deleteResult)
                            }
                        } else {
                            LogUtil.warning(imageUri, "Удаление", "Файл уже не существует к моменту удаления")
                        }
                    } catch (e: Exception) {
                        LogUtil.error(imageUri, "Удаление", "Ошибка при удалении оригинального файла", e)
                        deleteFailed = true
                        deleteErrorMessage = e.message
                    }
                }

                // Если удаление не удалось, возвращаем failure вместо success
                if (deleteFailed) {
                    LogUtil.error(imageUri, "Удаление", "Не удалось удалить оригинальный файл после успешного сжатия. Причина: ${deleteErrorMessage ?: "неизвестно"}")

                    try {
                        ExifUtil.writeExifDataFromMemory(appContext, imageUri, exifDataMemory, 99)
                        LogUtil.processInfo("Маркер сжатия записан в неудалённый оригинал для предотвращения повторной обработки")
                    } catch (e: Exception) {
                        LogUtil.error(imageUri, "Маркер", "Не удалось записать маркер в оригинал", e)
                    }

                    NotificationUtil.showErrorNotification(
                        context = appContext,
                        title = "Ошибка удаления оригинала",
                        message = "Сжатый файл сохранён, но не удалось удалить оригинал. Возможен дубликат."
                    )
 
                    if (batchId.isNullOrEmpty()) {
                        setForeground(createForegroundInfo("⚠️ Ошибка удаления оригинала"))
                    } else {
                        setForeground(NotificationUtil.createSilentForegroundInfo(
                            appContext,
                            Constants.NOTIFICATION_ID_COMPRESSION
                        ))
                    }

                    StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_COMPLETED)
                    return@withContext Result.success()
                }

                // Получаем размер сжатого файла для уведомления
                val compressedSize = UriUtil.getFileSize(appContext, savedUri) ?: testCompressionResult.compressedSize
                val sizeReduction = if (sourceSize > 0 && compressedSize > 0) {
                    FileOperationsUtil.computeSizeReductionPercent(sourceSize, compressedSize)
                } else testCompressionResult.sizeReduction

                // Отправляем уведомление о завершении сжатия
                sendCompressionStatusNotification(
                    finalFileName,
                    sourceSize,
                    compressedSize,
                    sizeReduction,
                    false
                )

                if (batchId.isNullOrEmpty()) {
                    setForeground(createForegroundInfo("✅ ${appContext.getString(R.string.notification_compression_completed)}"))
                } else {
                    setForeground(NotificationUtil.createSilentForegroundInfo(
                        appContext,
                        Constants.NOTIFICATION_ID_COMPRESSION
                    ))
                }

                // Обновляем статус и возвращаем успех
                StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_COMPLETED)

                return@withContext Result.success()
            } else {
                // Если сжатие неэффективно, пропускаем сжатие, но обновляем EXIF с маркером
                // Устанавливаем маркер для неэффективного сжатия
                val qualityForMarker = 99
                val skipReason: String? = null

                // Сохраняем обновленные EXIF-данные и маркер сжатия
                ExifUtil.writeExifDataFromMemory(appContext, imageUri, exifDataMemory, qualityForMarker)
                
                if (batchId.isNullOrEmpty()) {
                    setForeground(createForegroundInfo("📉 ${appContext.getString(R.string.notification_skipping_inefficient)}"))
                } else {
                    setForeground(NotificationUtil.createSilentForegroundInfo(
                        appContext,
                        Constants.NOTIFICATION_ID_COMPRESSION
                    ))
                }
                
                // Получаем имя файла для уведомления
                val fileName = getFileNameSafely(imageUri)
                
                // Используем статистику из уже выполненного теста
                val stats = testResult.stats

                // Определяем размер сжатого файла и процент сокращения
                val estimatedCompressedSize = stats.compressedSize
                val estimatedSizeReduction = stats.sizeReduction

                // Показываем уведомление о пропуске файла
                sendCompressionStatusNotification(
                    fileName,
                    sourceSize,
                    estimatedCompressedSize,
                    estimatedSizeReduction,
                    true,
                    skipReason
                )
                
                // Обновляем статус и возвращаем успех с пропуском
                StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_SKIPPED)

                return@withContext Result.success()
            }
        } catch (e: Exception) {
            LogUtil.error(null, "Сжатие", "Ошибка при сжатии изображения", e)
            if (batchId.isNullOrEmpty()) {
                setForeground(createForegroundInfo("❌ ${appContext.getString(R.string.notification_compression_failed)}"))
            } else {
                setForeground(NotificationUtil.createSilentForegroundInfo(
                    appContext,
                    Constants.NOTIFICATION_ID_COMPRESSION
                ))
            }
            
            val uriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
            if (uriString != null) {
                val uri = Uri.parse(uriString)
                StatsTracker.updateStatus(uri, StatsTracker.COMPRESSION_STATUS_FAILED)
            }
            
            return@withContext Result.failure()
        } finally {
            if (isLockOwner && globalImageUri != null) {
                uriProcessingTracker.removeProcessingUriSafe(globalImageUri)
                uriProcessingTracker.addRecentlyProcessedUri(globalImageUri)
            }
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
        skipped: Boolean,
        skipReason: String? = null
    ) {
        try {
            val uriString = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)
            if (uriString != null) {
                // Если есть batch ID, добавляем результат в батч-трекер вместо показа индивидуального Toast
                if (!batchId.isNullOrEmpty()) {
                    compressionBatchTracker.addResult(
                        batchId = batchId,
                        fileName = fileName,
                        originalSize = originalSize,
                        compressedSize = compressedSize,
                        sizeReduction = sizeReduction,
                        skipped = skipped,
                        skipReason = skipReason
                    )
                } else {
                    // Старое поведение для задач без batch ID - показываем индивидуальный результат
                    NotificationUtil.sendCompressionResultBroadcast(
                        context = appContext,
                        uriString = uriString,
                        fileName = fileName,
                        originalSize = originalSize,
                        compressedSize = compressedSize,
                        sizeReduction = sizeReduction,
                        skipped = skipped,
                        skipReason = skipReason,
                        batchId = null // Явно указываем null для старого поведения
                    )
                    
                    // Показываем индивидуальное уведомление только для задач без batch ID
                    NotificationUtil.showCompressionResultNotification(
                        context = appContext,
                        fileName = fileName,
                        originalSize = originalSize,
                        compressedSize = compressedSize,
                        sizeReduction = sizeReduction,
                        skipped = skipped
                    )
                }
                
                // Для задач с batch ID уведомления будут показаны через CompressionBatchTracker
            }
        } catch (e: Exception) {
            LogUtil.error(Uri.EMPTY, "Отправка уведомления", "Критическая ошибка при отправке уведомления: ${e.message}", e)
            // Fallback: показываем error notification
            NotificationUtil.showErrorNotification(
                context = appContext,
                title = "Ошибка уведомления",
                message = "Не удалось отправить уведомление. Проверьте настройки."
            )
        }
    }

    /**
     * Добавляет запрос на удаление файла в список ожидающих
     */
    private fun addPendingDeleteRequest(uri: Uri, deletePendingIntent: IntentSender) {
        
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
            setPackage(appContext.packageName)
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
            val fileName = UriUtil.getFileNameFromUri(appContext, uri)
            
            // Если имя файла не определено, генерируем временное имя на основе времени
            if (fileName.isNullOrBlank() || fileName == "unknown") {
                val timestamp = System.currentTimeMillis()
                return "compressed_image_$timestamp.jpg"
            }
            
            return fileName
        } catch (e: Exception) {
            val timestamp = System.currentTimeMillis()
            return "compressed_image_$timestamp.jpg"
        }
    }
}
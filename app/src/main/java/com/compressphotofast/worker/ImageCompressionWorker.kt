package com.compressphotofast.worker

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.compressphotofast.R
import com.compressphotofast.util.Constants
import com.compressphotofast.util.StatsTracker
import com.compressphotofast.util.UriProcessingTracker
import com.compressphotofast.util.PendingItemException
import com.compressphotofast.util.ImageCompressionUtil
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.ExifUtil
import com.compressphotofast.util.ImageProcessingChecker
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.UriUtil
import com.compressphotofast.util.MediaStoreUtil
import com.compressphotofast.util.FileOperationsUtil
import com.compressphotofast.util.CompressionBatchTracker
import com.compressphotofast.util.toInputStream
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                // Если файл pending — это временное состояние, планируем retry.
                // Файл всё ещё пишется другим процессом/приложением; повторная попытка позже
                // позволит корректно обработать его после завершения записи.
                LogUtil.warning(imageUri, "Ранняя проверка", "Файл в pending-состоянии, планирую retry")
                return@withContext Result.retry()
            } catch (e: Exception) {
                LogUtil.error(imageUri, "Ранняя проверка", "Ошибка при проверке существования", e)
                return@withContext Result.failure()
            }

            // Обновляем уведомление
            // Для batch-обработки используем тихий режим для предотвращения спама уведомлений
            updateForegroundForMode("🔧 ${appContext.getString(R.string.notification_compression_in_progress)}")

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
                updateForegroundForMode("🖼️ ${appContext.getString(R.string.notification_skipping_compressed)}")
                return@withContext Result.success()
            }

            // Блокировка URI теперь захватывается в самом начале doWork()
            
            // Проверка на временный файл
            if (UriUtil.isFilePendingSuspend(appContext, imageUri)) {
                LogUtil.skipImage(imageUri, "Файл находится в процессе записи, планирую retry")
                return@withContext Result.retry()
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
                updateForegroundForMode("📏 ${appContext.getString(R.string.notification_skipping_invalid_size)}")
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
                updateForegroundForMode("❌ ${appContext.getString(R.string.notification_compression_failed)}")
                StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
                return@withContext Result.failure()
            }
            
            // testResult проверен на null выше; фиксируем non-null ссылку для передачи в функции.
            val effectiveTestResult = testResult!!
            val testCompressionResult = effectiveTestResult.stats
            val sourceSizeKB = testCompressionResult.originalSize / 1024
            val compressedSizeKB = testCompressionResult.compressedSize / 1024
            val compressionSavingPercent = testCompressionResult.sizeReduction

            LogUtil.imageCompression(imageUri, "${sourceSizeKB}KB → ${compressedSizeKB}KB (-${compressionSavingPercent}%)")

            // Главная развилка: эффективное сжатие vs пропуск как неэффективное.
            // Логика вынесена в отдельные suspend-функции для уменьшения размера
            // единого state machine `invokeSuspend` (см. ART-предупреждение
            // `Method exceeds compiler instruction limit`).
            return@withContext if (effectiveTestResult.isEfficient()) {
                performCompression(
                    imageUri = imageUri,
                    exifDataMemory = exifDataMemory,
                    testResult = effectiveTestResult,
                    sourceSize = sourceSize,
                    testCompressionResult = testCompressionResult
                )
            } else {
                handleInefficientSkip(
                    imageUri = imageUri,
                    exifDataMemory = exifDataMemory,
                    testResult = effectiveTestResult,
                    sourceSize = sourceSize
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Отмена корутины (WorkManager отменил задачу / таймаут / shutdown).
            // Пробрасываем, чтобы структурированная конкуренция корректно завершила корутину.
            // WorkManager интерпретирует это как отмену, а не как failure.
            LogUtil.processDebug("Сжатие отменено (CancellationException) для $globalImageUri")
            throw e
        } catch (e: Exception) {
            LogUtil.error(null, "Сжатие", "Ошибка при сжатии изображения", e)

            // Для временных (transient) ошибок планируем retry вместо необратимого
            // пропуска фото. ExistingWorkPolicy.APPEND_OR_REPLACE выбрасывает провалившуюся
            // задачу из очереди, поэтому без retry фото будет потеряно навсегда.
            val isTransient = e is java.io.IOException ||
                e is PendingItemException ||
                e is RecoverableSecurityException
            if (isTransient) {
                LogUtil.warning(
                    globalImageUri,
                    "Сжатие",
                    "Временная ошибка (${e.javaClass.simpleName}), планирую retry: ${e.message}"
                )
                return@withContext Result.retry()
            }

            updateForegroundForMode("❌ ${appContext.getString(R.string.notification_compression_failed)}")

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
     * Обновляет foreground-уведомление в зависимости от режима обработки.
     *
     * Для одиночной задачи (без batchId) показывается заметное уведомление с [singleModeText];
     * для пакетной обработки — тихое (silent) уведомление, чтобы избежать спама при последовательном
     * сжатии множества фото.
     */
    private suspend fun updateForegroundForMode(singleModeText: String) {
        if (batchId.isNullOrEmpty()) {
            setForeground(createForegroundInfo(singleModeText))
        } else {
            setForeground(NotificationUtil.createSilentForegroundInfo(
                appContext,
                Constants.NOTIFICATION_ID_COMPRESSION
            ))
        }
    }

    /**
     * Выполняет эффективное сжатие: сохранение сжатого потока в MediaStore, верификацию
     * целостности, удаление оригинала в режиме замены и отправку уведомления о результате.
     *
     * Вынесено из [doWork] для уменьшения размера единого state machine `invokeSuspend`
     * (см. ART-предупреждение `Method exceeds compiler instruction limit`).
     *
     * @return [Result.success] при успешном завершении (включая случай, когда оригинал не удалось
     *   удалить — сжатый файл уже сохранён, маркер записан в неудалённый оригинал).
     */
    private suspend fun performCompression(
        imageUri: Uri,
        exifDataMemory: Map<String, Any>,
        testResult: ImageCompressionUtil.CompressionTestResult,
        sourceSize: Long,
        testCompressionResult: ImageCompressionUtil.CompressionStats
    ): Result {
        // Получаем имя файла
        val fileName = UriUtil.getFileNameFromUri(appContext, imageUri)

        if (fileName.isNullOrEmpty()) {
            LogUtil.error(imageUri, "Имя файла", "Не удалось получить имя файла")
            updateForegroundForMode("❌ ${appContext.getString(R.string.notification_compression_failed)}")
            StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
            return Result.failure()
        }

        // Определяем правильное имя файла в зависимости от режима сохранения
        val finalFileName = FileOperationsUtil.createCompressedFileName(appContext, fileName)

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
            updateForegroundForMode("❌ ${appContext.getString(R.string.notification_compression_failed)}")
            StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
            return Result.failure()
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
            updateForegroundForMode("❌ ${appContext.getString(R.string.notification_compression_failed)}")
            StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_FAILED)
            return Result.failure()
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
            return Result.failure()
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

        // Если удаление не удалось, возвращаем success вместо failure: сжатый файл уже сохранён.
        // Маркер записывается в неудалённый оригинал, чтобы избежать повторной обработки.
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

            updateForegroundForMode("⚠️ Ошибка удаления оригинала")
            StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_COMPLETED)
            return Result.success()
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

        updateForegroundForMode("✅ ${appContext.getString(R.string.notification_compression_completed)}")

        StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_COMPLETED)
        return Result.success()
    }

    /**
     * Обрабатывает случай, когда сжатие признано неэффективным: файл не пережимается,
     * но в его EXIF записывается маркер сжатия (quality=99), чтобы исключить повторную
     * обработку. Показывается уведомление о пропуске.
     *
     * Вынесено из [doWork] для уменьшения размера единого state machine `invokeSuspend`.
     */
    private suspend fun handleInefficientSkip(
        imageUri: Uri,
        exifDataMemory: Map<String, Any>,
        testResult: ImageCompressionUtil.CompressionTestResult,
        sourceSize: Long
    ): Result {
        // Устанавливаем маркер для неэффективного сжатия
        val qualityForMarker = 99
        val skipReason: String? = null

        // Сохраняем обновленные EXIF-данные и маркер сжатия
        ExifUtil.writeExifDataFromMemory(appContext, imageUri, exifDataMemory, qualityForMarker)

        updateForegroundForMode("📉 ${appContext.getString(R.string.notification_skipping_inefficient)}")

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

        StatsTracker.updateStatus(imageUri, StatsTracker.COMPRESSION_STATUS_SKIPPED)
        return Result.success()
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
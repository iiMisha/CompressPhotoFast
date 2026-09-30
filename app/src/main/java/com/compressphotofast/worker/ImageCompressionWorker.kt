package com.compressphotofast.worker

import android.app.RecoverableSecurityException
import android.app.ForegroundServiceStartNotAllowedException
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
import com.compressphotofast.util.ImageIntegrityUtil
import com.compressphotofast.util.CompressionException
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.ExifUtil
import com.compressphotofast.util.ImageProcessingChecker
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.UriUtil
import com.compressphotofast.util.MediaStoreUtil
import com.compressphotofast.util.FileOperationsUtil
import com.compressphotofast.util.CompressionBatchTracker
import com.compressphotofast.util.CompressionExecutionGate
import com.compressphotofast.util.SettingsManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import java.io.FileInputStream

/**
 * Worker для сжатия изображений в фоновом режиме
 */
@HiltWorker
class ImageCompressionWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val uriProcessingTracker: UriProcessingTracker,
    private val compressionBatchTracker: CompressionBatchTracker,
    private val executionGate: CompressionExecutionGate,
    private val settingsManager: SettingsManager,
    private val imageProcessingChecker: ImageProcessingChecker
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val MAX_TRANSIENT_ATTEMPTS = 5
    }

    // Переопределяем поле applicationContext для удобного доступа
    private val appContext: Context
        get() = context

    // Качество сжатия (получаем из входных данных)
    private val compressionQuality = inputData.getInt(Constants.WORK_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_MEDIUM)

    // Максимальное разрешение по большей стороне (0 — исходное разрешение)
    private val maxResolution = inputData.getInt(Constants.WORK_MAX_RESOLUTION, Constants.DEFAULT_MAX_RESOLUTION)
    
    // ID батча для группировки результатов (может быть null для старых задач)
    private val batchId = inputData.getString(Constants.WORK_BATCH_ID)
    private val workOrigin = inputData.getString(Constants.WORK_ORIGIN)
        ?: if (batchId.isNullOrEmpty()) "AUTO" else "MANUAL"
    private var markRecentlyProcessed = false

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        LogUtil.processDebug(
            "ImageCompressionWorker.doWork() НАЧАЛО: uri=${inputData.getString(Constants.WORK_INPUT_IMAGE_URI)}, " +
                "origin=$workOrigin, discoveredAt=${inputData.getLong(Constants.WORK_DISCOVERED_AT, 0L)}, " +
                "enqueuedAt=${inputData.getLong(Constants.WORK_ENQUEUED_AT, 0L)}, attempt=$runAttemptCount"
        )
        var testResult: ImageCompressionUtil.CompressionTestResult? = null
        var isLockOwner = false
        var isExecutionGateOwner = false
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

            // Durable enqueue не владеет process-local lock. Владелец определяется
            // только фактическим Worker, включая legacy WorkSpec.
            val addedToProcessing = uriProcessingTracker.addProcessingUriSafe(imageUri, "ImageCompressionWorker")
            
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
                return@withContext retryOrFinish(imageUri, "early pending", e)
            } catch (e: Exception) {
                LogUtil.error(imageUri, "Ранняя проверка", "Ошибка при проверке существования", e)
                return@withContext Result.failure()
            }

            // Дешёвую проверку pending выполняем до gate, чтобы один пишущийся
            // файл не удерживал тяжёлую фазу соседних URI.
            if (UriUtil.isFilePendingSuspend(appContext, imageUri)) {
                LogUtil.skipImage(imageUri, "Файл находится в процессе записи, планирую retry")
                return@withContext retryOrFinish(imageUri, "pending before execution gate", null)
            }

            // Один permit на тяжёлую фазу. Не меняем глобальный executor WorkManager.
            executionGate.acquire()
            isExecutionGateOwner = true

            // Повторная проверка после входа в gate закрывает race legacy/v2
            // и изменения EXIF между discovery и фактическим запуском.
            val gatedProcessingCheck = imageProcessingChecker.isProcessingRequired(imageUri, forceProcess = true)
            if (!gatedProcessingCheck.processingRequired &&
                gatedProcessingCheck.reason == ImageProcessingChecker.ProcessingSkipReason.ALREADY_COMPRESSED
            ) {
                updateForegroundForMode("🖼️ ${appContext.getString(R.string.notification_skipping_compressed)}")
                markRecentlyProcessed = true
                return@withContext Result.success()
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
                markRecentlyProcessed = true
                return@withContext Result.success()
            }
            
            // Выполняем тестовое сжатие для оценки эффективности
            testResult = ImageCompressionUtil.testCompression(
                appContext,
                imageUri, 
                sourceSize,
                compressionQuality,
                keepStream = true, // Сохраняем поток для повторного использования
                maxDimension = maxResolution
            )
            
            if (testResult == null) {
                LogUtil.error(imageUri, "Тестовое сжатие", "Ошибка при тестовом сжатии")
                updateForegroundForMode("❌ ${appContext.getString(R.string.notification_compression_failed)}")
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
        } catch (e: TimeoutCancellationException) {
            LogUtil.warning(globalImageUri, "Сжатие", "Превышен лимит времени, планирую retry")
            return@withContext retryOrFinish(globalImageUri, "timeout", e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Отмена корутины (WorkManager отменил задачу / таймаут / shutdown).
            // Пробрасываем, чтобы структурированная конкуренция корректно завершила корутину.
            // WorkManager интерпретирует это как отмену, а не как failure.
            LogUtil.processDebug("Сжатие отменено (CancellationException) для $globalImageUri")
            throw e
        } catch (e: Exception) {
            LogUtil.error(null, "Сжатие", "Ошибка при сжатии изображения", e)

            // Все transient-ветки используют единый bounded retry.
            val isTransient = e is java.io.IOException ||
                e is PendingItemException ||
                e is RecoverableSecurityException ||
                e is CompressionException.InsufficientMemory ||
                e is CompressionException.OutOfMemory ||
                e is ForegroundServiceStartNotAllowedException ||
                e is SecurityException ||
                (e is IllegalStateException && e.message?.contains("foreground", ignoreCase = true) == true)
            if (isTransient) {
                return@withContext retryOrFinish(
                    globalImageUri,
                    "${e.javaClass.simpleName}: ${e.message}",
                    e
                )
            }

            updateForegroundForMode("❌ ${appContext.getString(R.string.notification_compression_failed)}")
            return@withContext Result.failure()
        } finally {
            testResult?.deleteArtifact()
            if (isExecutionGateOwner) executionGate.release()
            if (isLockOwner && globalImageUri != null) {
                uriProcessingTracker.removeProcessingUriSafe(globalImageUri)
                if (markRecentlyProcessed) {
                    uriProcessingTracker.addRecentlyProcessedUri(globalImageUri)
                }
            }
        }
    }

    private fun retryOrFinish(uri: Uri?, reason: String, error: Throwable?): Result {
        val attempt = runAttemptCount + 1
        if (attempt < MAX_TRANSIENT_ATTEMPTS) {
            LogUtil.warning(uri, "Сжатие", "Transient attempt $attempt/$MAX_TRANSIENT_ATTEMPTS: $reason")
            if (error != null) LogUtil.error(uri, "Сжатие", "Transient detail", error)
            return Result.retry()
        }
        LogUtil.warning(uri, "Сжатие", "Transient retry исчерпан ($attempt): $reason")
        if (error != null) LogUtil.error(uri, "Сжатие", "Transient detail", error)
        return Result.failure()
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
        if (workOrigin == "MANUAL") {
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
        val compressedImageFile = testResult.compressedFile

        if (compressedImageFile == null || !compressedImageFile.exists()) {
            LogUtil.error(imageUri, "Сжатие", "Сжатый artifact утерян (null или удалён)")
            updateForegroundForMode("❌ ${appContext.getString(R.string.notification_compression_failed)}")
            return Result.failure()
        }

        // Сохраняем сжатое изображение с гарантированным закрытием потока
        val saveResult = FileInputStream(compressedImageFile).use { stream ->
            MediaStoreUtil.saveCompressedImageFromStream(
                context = appContext,
                inputStream = stream,
                fileName = finalFileName,
                directory = directory,
                originalUri = imageUri,
                quality = compressionQuality,
                exifDataMemory = exifDataMemory,
                originalFileSize = sourceSize
            )
        }

        if (saveResult is MediaStoreUtil.SaveResult.Failed) {
            LogUtil.error(imageUri, "Сохранение", "Не удалось сохранить сжатое изображение: ${saveResult.reason}")
            notifySaveFailure(saveResult.reason)
            updateForegroundForMode("❌ ${appContext.getString(R.string.notification_compression_failed)}")
            return Result.failure()
        }
        val savedUri = (saveResult as MediaStoreUtil.SaveResult.Saved).uri

        // Перезапись на месте определяется по ID MediaStore: savedUri строится как
        // content://media/external/..., а imageUri может прийти через external_primary.
        val overwrittenInPlace = savedUri == imageUri || MediaStoreUtil.isSameMediaItem(savedUri, imageUri)

        uriProcessingTracker.setIgnorePeriod(savedUri)
        if (!overwrittenInPlace) {
            uriProcessingTracker.setIgnorePeriod(imageUri)
        }

        // Верификация целостности ВСЕГДА, не только в режиме замены
        // Надёжность важнее скорости — повреждённый файл не должен попасть в галерею
        val isSavedFileValid = ImageIntegrityUtil.verifyImageIntegrity(context, savedUri)
        if (!isSavedFileValid) {
            LogUtil.error(imageUri, "Верификация", "КРИТИЧЕСКАЯ ОШИБКА: Сохранённый файл повреждён!")
            // ИНВАРИАНТ БЕЗОПАСНОСТИ: удалять можно только новый файл. Если оригинал
            // перезаписан на месте (overwrittenInPlace), это файл пользователя.
            if (!overwrittenInPlace) {
                try {
                    appContext.contentResolver.delete(savedUri, null, null)
                    LogUtil.error(imageUri, "Верификация", "Повреждённый файл удалён из MediaStore: $savedUri")
                } catch (e: Exception) {
                    LogUtil.error(savedUri, "Верификация", "Не удалось удалить повреждённый файл", e)
                }
            }
            return Result.failure()
        }

        // Защита от потери правок: если оригинал изменился за время сжатия,
        // сжатая копия устарела — удаляем её, оригинал не трогаем.
        if (FileOperationsUtil.isSaveModeReplace(appContext) && !overwrittenInPlace &&
            !MediaStoreUtil.isFileUnchanged(appContext, imageUri, sourceSize)
        ) {
            LogUtil.warning(imageUri, "Replace", "Оригинал изменён во время сжатия — сжатая копия удалена, оригинал сохранён")
            try {
                appContext.contentResolver.delete(savedUri, null, null)
            } catch (e: Exception) {
                LogUtil.error(savedUri, "Replace", "Не удалось удалить устаревшую сжатую копию", e)
            }
            return Result.failure()
        }

        // Учитываем только сохранённый и проверенный файл, независимо от удаления оригинала.
        val compressedSize = UriUtil.getFileSize(appContext, savedUri) ?: testCompressionResult.compressedSize
        StatsTracker.recordSuccessfulCompression(appContext, sourceSize, compressedSize)?.let {
            NotificationUtil.updateBackgroundServiceNotification(appContext, it)
        }

        // Если режим замены включен, удаляем оригинальный файл ПОСЛЕ успешного сохранения нового
        // НО: если файл перезаписан на месте (overwrittenInPlace), удалять не нужно
        var deleteFailed = false
        var deleteErrorMessage: String? = null
        if (FileOperationsUtil.isSaveModeReplace(appContext) && !overwrittenInPlace) {
            try {
                if (UriUtil.isUriExistsSuspend(appContext, imageUri)) {
                    val deleteResult = FileOperationsUtil.deleteFile(appContext, imageUri, uriProcessingTracker, forceDelete = true)
                    if (deleteResult is IntentSender) {
                        addPendingDeleteRequest(imageUri, deleteResult)
                    } else if (deleteResult != true) {
                        // Тихий отказ MediaStore: оригинал остался без маркера и был бы
                        // пережат повторно, порождая дубликаты
                        deleteFailed = true
                        deleteErrorMessage = "MediaStore не удалил файл"
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
                ExifUtil.writeExifDataFromMemory(appContext, imageUri, exifDataMemory, 99, sourceSize)
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
            markRecentlyProcessed = true
            return Result.success()
        }

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

        markRecentlyProcessed = true
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
        ExifUtil.writeExifDataFromMemory(appContext, imageUri, exifDataMemory, qualityForMarker, sourceSize)

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

        markRecentlyProcessed = true
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
        
        // Сохраняем URI для последующей обработки в MainActivity
        settingsManager.savePendingDeleteUri(uri.toString())
        
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
     * Сообщает пользователю о сбоях сохранения, требующих его внимания.
     */
    private fun notifySaveFailure(reason: MediaStoreUtil.SaveFailure) {
        val message = when (reason) {
            MediaStoreUtil.SaveFailure.CORRUPTED_OUTPUT ->
                "Сжатый файл был повреждён и удалён"
            MediaStoreUtil.SaveFailure.ROLLBACK_FAILED ->
                "Не удалось восстановить оригинал. Копия сохранена и будет восстановлена при следующем запуске приложения."
            MediaStoreUtil.SaveFailure.OTHER -> return
        }
        NotificationUtil.showErrorNotification(appContext, "Ошибка сохранения", message)
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

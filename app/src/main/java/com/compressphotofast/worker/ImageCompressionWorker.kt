package com.compressphotofast.worker

import android.app.RecoverableSecurityException
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.compressphotofast.R
import com.compressphotofast.domain.CompressImageUseCase
import com.compressphotofast.util.Constants
import com.compressphotofast.data.UriProcessingTracker
import com.compressphotofast.data.PendingItemException
import com.compressphotofast.domain.CompressionException
import com.compressphotofast.platform.NotificationUtil
import com.compressphotofast.domain.ImageProcessingChecker
import com.compressphotofast.util.LogUtil
import com.compressphotofast.data.UriUtil
import com.compressphotofast.data.MediaStoreUtil
import com.compressphotofast.domain.CompressionBatchTracker
import com.compressphotofast.domain.CompressionEvents
import com.compressphotofast.domain.CompressionExecutionGate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException

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
    private val imageProcessingChecker: ImageProcessingChecker,
    private val compressionEvents: CompressionEvents,
    private val compressImage: CompressImageUseCase
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

            // Для batch-обработки используем тихий режим для предотвращения спама уведомлений
            updateForegroundForMode("🔧 ${appContext.getString(R.string.notification_compression_in_progress)}")

            val outcome = compressImage(imageUri, CompressImageUseCase.Params(compressionQuality, maxResolution))
            return@withContext handleOutcome(outcome)
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
     * Переводит результат сжатия в Result WorkManager, foreground-статус и уведомления.
     */
    private suspend fun handleOutcome(outcome: CompressImageUseCase.Outcome): Result {
        when (outcome) {
            is CompressImageUseCase.Outcome.Compressed -> {
                outcome.dailyStats?.let { NotificationUtil.updateBackgroundServiceNotification(appContext, it) }
                sendCompressionStatusNotification(
                    outcome.fileName, outcome.originalSize, outcome.compressedSize, outcome.sizeReduction, false
                )
                updateForegroundForMode("✅ ${appContext.getString(R.string.notification_compression_completed)}")
            }
            is CompressImageUseCase.Outcome.CompressedOriginalKept -> {
                outcome.dailyStats?.let { NotificationUtil.updateBackgroundServiceNotification(appContext, it) }
                NotificationUtil.showErrorNotification(
                    context = appContext,
                    title = "Ошибка удаления оригинала",
                    message = "Сжатый файл сохранён, но не удалось удалить оригинал. Возможен дубликат."
                )
                updateForegroundForMode("⚠️ Ошибка удаления оригинала")
            }
            is CompressImageUseCase.Outcome.SkippedInefficient -> {
                updateForegroundForMode("📉 ${appContext.getString(R.string.notification_skipping_inefficient)}")
                sendCompressionStatusNotification(
                    outcome.fileName, outcome.originalSize, outcome.estimatedCompressedSize, outcome.estimatedReduction, true
                )
            }
            CompressImageUseCase.Outcome.SkippedInvalidSize ->
                updateForegroundForMode("📏 ${appContext.getString(R.string.notification_skipping_invalid_size)}")
            is CompressImageUseCase.Outcome.Failed -> {
                outcome.saveFailure?.let { notifySaveFailure(it) }
                updateForegroundForMode("❌ ${appContext.getString(R.string.notification_compression_failed)}")
                return Result.failure()
            }
        }
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
                    // Задачи без batch ID (автосжатие): индивидуальный результат
                    compressionEvents.emit(
                        CompressionEvents.Event.Result(
                            uri = Uri.parse(uriString),
                            fileName = fileName,
                            originalSize = originalSize,
                            compressedSize = compressedSize,
                            sizeReduction = sizeReduction,
                            skipped = skipped
                        )
                    )
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
}

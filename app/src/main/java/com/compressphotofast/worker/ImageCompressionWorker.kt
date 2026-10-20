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
import com.compressphotofast.data.MediaItemSnapshot
import com.compressphotofast.data.ExifUtil
import com.compressphotofast.domain.CompressionBatchTracker
import com.compressphotofast.domain.CompressionEvents
import com.compressphotofast.domain.CompressionExecutionGate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
        internal const val MAX_OOM_ATTEMPTS = 2

        /** Причины, при которых файл не сжимается повторно и отчитывается как SKIPPED */
        internal val ALREADY_HANDLED_REASONS = setOf(
            ImageProcessingChecker.ProcessingSkipReason.ALREADY_COMPRESSED,
            ImageProcessingChecker.ProcessingSkipReason.COMPRESSED_VERSION_EXISTS,
            ImageProcessingChecker.ProcessingSkipReason.IN_APP_DIRECTORY,
            ImageProcessingChecker.ProcessingSkipReason.ALREADY_SMALL,
        )

        /** Разрешён ли ещё один запуск после попытки [runAttemptCount] (0-based). */
        internal fun shouldRetry(runAttemptCount: Int, maxAttempts: Int = MAX_TRANSIENT_ATTEMPTS): Boolean =
            runAttemptCount + 1 < maxAttempts
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
    // Исход этой попытки уже записан в батч: повторная запись (например, отмена после результата) не нужна
    private var batchOutcomeReported = false
    // Имя файла из последнего снимка MediaStore — для отчёта в батч без повторного запроса
    private var snapshotDisplayName: String? = null


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
                reportBatchOutcome(CompressionBatchTracker.Status.FAILED)
                return@withContext Result.failure()
            }

            val imageUri = Uri.parse(uriString)

            // Durable enqueue не владеет process-local lock. Владелец определяется
            // только фактическим Worker, включая legacy WorkSpec.
            val addedToProcessing = uriProcessingTracker.addProcessingUriSafe(imageUri, "ImageCompressionWorker")
            
            isLockOwner = addedToProcessing
            if (!addedToProcessing) {
                LogUtil.processDebug("URI уже обрабатывается другим потоком, пропускаем Worker: $imageUri")
                // Результат за этот URI даёт владелец lock; батч получает SKIPPED, а не ждёт таймаут
                reportBatchOutcome(CompressionBatchTracker.Status.SKIPPED)
                return@withContext Result.success() // success чтобы не блокировать цепочку WorkManager
            }

            // Существование, pending и остальные метаданные — одним запросом MediaStore
            val preGateSnapshot = try {
                MediaItemSnapshot.query(appContext, imageUri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.error(imageUri, "Ранняя проверка", "Ошибка при проверке существования", e)
                reportBatchOutcome(CompressionBatchTracker.Status.FAILED)
                return@withContext Result.failure()
            }
            snapshotDisplayName = preGateSnapshot?.displayName
            val exists = preGateSnapshot?.exists == true

            // Если URI помечен как недоступный, проверяем его повторно перед выходом
            if (uriProcessingTracker.isUriUnavailable(imageUri)) {
                if (exists && !UriUtil.isPendingEffective(appContext, preGateSnapshot!!)) {
                    uriProcessingTracker.removeUnavailable(imageUri)
                } else {
                    reportBatchOutcome(CompressionBatchTracker.Status.FAILED)
                    return@withContext Result.failure()
                }
            }

            // Ранняя проверка существования файла перед любыми операциями
            if (!exists) {
                LogUtil.warning(imageUri, "Ранняя проверка", "Файл не существует")
                uriProcessingTracker.markUriUnavailable(imageUri)
                reportBatchOutcome(CompressionBatchTracker.Status.FAILED)
                return@withContext Result.failure()
            }

            // Дешёвую проверку pending выполняем до gate, чтобы один пишущийся
            // файл не удерживал тяжёлую фазу соседних URI.
            if (UriUtil.isPendingEffective(appContext, preGateSnapshot!!)) {
                LogUtil.skipImage(imageUri, "Файл находится в процессе записи, планирую retry")
                return@withContext retryOrFinish(imageUri, "pending before execution gate", null)
            }

            // Один permit на тяжёлую фазу. Не меняем глобальный executor WorkManager.
            executionGate.acquire()
            isExecutionGateOwner = true

            // Повторная проверка после входа в gate закрывает race legacy/v2
            // и изменения EXIF между discovery и фактическим запуском.
            // Свежий снимок и одно чтение EXIF: маркер идёт в проверку, теги — в сжатие.
            val gatedSnapshot = MediaItemSnapshot.query(appContext, imageUri)
            gatedSnapshot?.displayName?.let { snapshotDisplayName = it }
            val sourceExif = ExifUtil.readSourceExif(appContext, imageUri, gatedSnapshot)
            val gatedProcessingCheck = imageProcessingChecker.isProcessingRequired(
                imageUri,
                forceProcess = true,
                snapshot = gatedSnapshot,
                precomputedMarker = sourceExif?.marker
            )
            // Повтор постановки (retry, kill, повторный ручной батч) не должен давать
            // вторую копию: все «уже обработано / слишком мало» причины останавливают работу
            if (!gatedProcessingCheck.processingRequired && gatedProcessingCheck.reason in ALREADY_HANDLED_REASONS) {
                LogUtil.processDebug("Пропуск $imageUri: ${gatedProcessingCheck.reason}")
                updateForegroundForMode("🖼️ ${appContext.getString(R.string.notification_skipping_compressed)}")
                reportBatchOutcome(CompressionBatchTracker.Status.SKIPPED)
                markRecentlyProcessed = true
                return@withContext Result.success()
            }

            // Для batch-обработки используем тихий режим для предотвращения спама уведомлений
            updateForegroundForMode("🔧 ${appContext.getString(R.string.notification_compression_in_progress)}")

            val outcome = compressImage(
                imageUri,
                CompressImageUseCase.Params(compressionQuality, maxResolution),
                snapshot = gatedSnapshot,
                preloadedExif = sourceExif?.data
            )
            // Тяжёлая фаза закончена: уведомления и статистика не должны держать permit соседних URI
            executionGate.release()
            isExecutionGateOwner = false
            return@withContext handleOutcome(outcome)
        } catch (e: TimeoutCancellationException) {
            LogUtil.warning(globalImageUri, "Сжатие", "Превышен лимит времени, планирую retry")
            return@withContext retryOrFinish(globalImageUri, "timeout", e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Отмена корутины (WorkManager отменил задачу / таймаут / shutdown).
            // Пробрасываем, чтобы структурированная конкуренция корректно завершила корутину.
            // WorkManager интерпретирует это как отмену, а не как failure.
            // Батч отчитывается сразу, иначе ждёт таймаут 120 с.
            LogUtil.processDebug("Сжатие отменено (CancellationException) для $globalImageUri")
            reportBatchOutcome(CompressionBatchTracker.Status.FAILED)
            throw e
        } catch (e: OutOfMemoryError) {
            // Голый OOM — не Exception, мимо catch ниже он бы не дошёл до отчёта батча
            LogUtil.error(globalImageUri, "Сжатие", "OutOfMemoryError", e)
            return@withContext retryOrFinish(globalImageUri, "OutOfMemoryError", e, MAX_OOM_ATTEMPTS)
        } catch (e: Exception) {
            LogUtil.error(null, "Сжатие", "Ошибка при сжатии изображения", e)

            // OOM может быть следствием давления на heap от других компонентов
            // процесса (Coil, UI): одна повторная попытка, а не весь transient-бюджет.
            if (e is CompressionException.OutOfMemory) {
                return@withContext retryOrFinish(
                    globalImageUri, "OutOfMemory: ${e.message}", e, MAX_OOM_ATTEMPTS
                )
            }

            // Все transient-ветки используют единый bounded retry.
            val isTransient = e is java.io.IOException ||
                e is PendingItemException ||
                e is RecoverableSecurityException ||
                e is CompressionException.InsufficientMemory ||
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

            reportBatchOutcome(CompressionBatchTracker.Status.FAILED)
            updateForegroundAfterReport("❌ ${appContext.getString(R.string.notification_compression_failed)}")
            return@withContext Result.failure()
        } finally {
            if (isExecutionGateOwner) executionGate.release()
            if (isLockOwner && globalImageUri != null) {
                // Снятие lock не должно прерываться отменой: иначе URI до stale-порога (30 мин) игнорируется
                withContext(NonCancellable) {
                    uriProcessingTracker.removeProcessingUriSafe(globalImageUri)
                }
                if (markRecentlyProcessed) {
                    uriProcessingTracker.addRecentlyProcessedUri(globalImageUri)
                }
            }
        }
    }

    private fun retryOrFinish(
        uri: Uri?,
        reason: String,
        error: Throwable?,
        maxAttempts: Int = MAX_TRANSIENT_ATTEMPTS
    ): Result {
        val attempt = runAttemptCount + 1
        if (shouldRetry(runAttemptCount, maxAttempts)) {
            LogUtil.warning(uri, "Сжатие", "Transient attempt $attempt/$maxAttempts: $reason")
            if (error != null) LogUtil.error(uri, "Сжатие", "Transient detail", error)
            return Result.retry()
        }
        LogUtil.warning(uri, "Сжатие", "Transient retry исчерпан ($attempt): $reason")
        if (error != null) LogUtil.error(uri, "Сжатие", "Transient detail", error)
        reportBatchOutcome(CompressionBatchTracker.Status.FAILED)
        return Result.failure()
    }

    /**
     * Сообщает финальный исход ручного батча для исходов без размеров
     * (пропуск/ошибка): без этого батч не набирает expectedCount и UI не получает итог.
     * Не вызывается при retry — отчитается следующая попытка.
     */
    private fun reportBatchOutcome(status: CompressionBatchTracker.Status) {
        if (batchId.isNullOrEmpty() || batchOutcomeReported) return
        batchOutcomeReported = true
        val uri = inputData.getString(Constants.WORK_INPUT_IMAGE_URI)?.let(Uri::parse)
        val fileName = snapshotDisplayName ?: uri?.let {
            try {
                UriUtil.getFileNameFromUri(appContext, it)
            } catch (_: Exception) {
                null
            } ?: it.lastPathSegment
        } ?: "unknown"
        compressionBatchTracker.addResult(
            batchId = batchId,
            fileName = fileName,
            originalSize = 0L,
            compressedSize = 0L,
            sizeReduction = 0f,
            skipped = status != CompressionBatchTracker.Status.COMPRESSED,
            status = status
        )
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
     * Foreground и заметное уведомление с [singleModeText] — только для ручных работ;
     * автоматические идут без setForeground.
     */
    private suspend fun updateForegroundForMode(singleModeText: String) {
        // AUTO-работы короткие и идут при живом FGS мониторинга: десятки параллельных
        // setForeground гоняют SystemForegroundService в stop/start и роняют процесс
        // (ForegroundServiceDidNotStartInTimeException).
        if (workOrigin == "MANUAL") {
            setForeground(createForegroundInfo(singleModeText))
        }
    }

    /**
     * Foreground-статус после того, как исход уже записан в батч. Сбой здесь не должен
     * давать retry: повторная попытка увидит маркер и отчитает тот же файл второй раз.
     */
    private suspend fun updateForegroundAfterReport(singleModeText: String) {
        try {
            updateForegroundForMode(singleModeText)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.warning(null, "Сжатие", "Не удалось обновить уведомление после результата: ${e.message}")
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
                updateForegroundAfterReport("✅ ${appContext.getString(R.string.notification_compression_completed)}")
            }
            is CompressImageUseCase.Outcome.CompressedOriginalKept -> {
                outcome.dailyStats?.let { NotificationUtil.updateBackgroundServiceNotification(appContext, it) }
                NotificationUtil.showErrorNotification(
                    context = appContext,
                    title = "Ошибка удаления оригинала",
                    message = "Сжатый файл сохранён, но не удалось удалить оригинал. Возможен дубликат."
                )
                reportBatchOutcome(CompressionBatchTracker.Status.COMPRESSED)
                updateForegroundAfterReport("⚠️ Ошибка удаления оригинала")
            }
            is CompressImageUseCase.Outcome.SkippedInefficient -> {
                updateForegroundAfterReport("📉 ${appContext.getString(R.string.notification_skipping_inefficient)}")
                sendCompressionStatusNotification(
                    outcome.fileName, outcome.originalSize, outcome.estimatedCompressedSize, outcome.estimatedReduction, true
                )
            }
            CompressImageUseCase.Outcome.SkippedInvalidSize -> {
                reportBatchOutcome(CompressionBatchTracker.Status.SKIPPED)
                updateForegroundAfterReport("📏 ${appContext.getString(R.string.notification_skipping_invalid_size)}")
            }
            is CompressImageUseCase.Outcome.Failed -> {
                reportBatchOutcome(CompressionBatchTracker.Status.FAILED)
                outcome.saveFailure?.let { notifySaveFailure(it) }
                updateForegroundAfterReport("❌ ${appContext.getString(R.string.notification_compression_failed)}")
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
                    if (!batchOutcomeReported) {
                        batchOutcomeReported = true
                        compressionBatchTracker.addResult(
                            batchId = batchId,
                            fileName = fileName,
                            originalSize = originalSize,
                            compressedSize = compressedSize,
                            sizeReduction = sizeReduction,
                            skipped = skipped,
                            skipReason = skipReason
                        )
                    }
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

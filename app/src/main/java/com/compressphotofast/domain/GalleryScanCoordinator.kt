package com.compressphotofast.domain

import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import com.compressphotofast.data.SettingsManager
import com.compressphotofast.util.Constants
import com.compressphotofast.util.LogUtil

/**
 * Единая точка сканирования галереи для FGS, content-trigger Job и reconciliation Worker:
 * расчёт окна, durable enqueue найденных URI и продвижение watermark.
 *
 * Watermark продвигается только если скан завершился успешно и все URI приняты
 * в WorkManager; значением служит момент начала скана, чтобы фото, добавленные
 * во время скана, попали в следующее окно.
 */
@Singleton
class GalleryScanCoordinator @Inject constructor(
    private val settingsManager: SettingsManager,
    private val scheduler: CompressionWorkScheduler,
    private val galleryScanUtil: GalleryScanUtil,
    private val imageProcessingChecker: ImageProcessingChecker
) {
    enum class Window {
        /** От последнего watermark (с перекрытием), в пределах истории. */
        SINCE_WATERMARK,
        /** Полная история ([Constants.HISTORY_SCAN_WINDOW_SECONDS]). */
        HISTORY
    }

    data class Outcome(val foundCount: Int, val durable: Boolean)

    private val mutex = Mutex()

    suspend fun scan(window: Window): Outcome = mutex.withLock {
        if (!settingsManager.isAutoCompressionEnabled()) return@withLock Outcome(0, durable = false)

        val startedAt = System.currentTimeMillis()
        val windowSeconds = when (window) {
            Window.HISTORY -> Constants.HISTORY_SCAN_WINDOW_SECONDS.toInt()
            Window.SINCE_WATERMARK -> windowSinceWatermark(settingsManager.getLastScanTimestamp(), startedAt)
        }
        val scan = galleryScanUtil.scanRecentImages(windowSeconds)
        val durable = scan.completedSuccessfully && enqueueAll(scan.foundUris)
        if (durable) {
            settingsManager.setLastScanTimestamp(startedAt)
            if (window == Window.HISTORY) settingsManager.setLastHistoryScanTimestamp(startedAt)
        }
        LogUtil.processDebug(
            "GalleryScan: window=$window(${windowSeconds}s), found=${scan.foundUris.size}, durable=$durable"
        )
        Outcome(scan.foundUris.size, durable)
    }

    /**
     * Ставит URI в очередь без продвижения watermark (content-trigger: это не скан галереи).
     * @return true, если все URI приняты или не требуют обработки
     */
    suspend fun enqueueAll(uris: List<Uri>): Boolean {
        var durable = true
        uris.forEach { uri ->
            if (scheduler.enqueue(uri, origin = CompressionOrigin.AUTO) == CompressionEnqueueResult.RETRYABLE_FAILURE) {
                durable = false
            }
        }
        return durable
    }

    /**
     * Ставит в очередь URI от live-источников (observer, content-trigger Job), пропуская
     * заведомо не требующие обработки: сжатые (ALREADY_COMPRESSED), сами сжатые копии
     * в директории приложения (IN_APP_DIRECTORY) и оригиналы, у которых копия уже есть
     * (COMPRESSED_VERSION_EXISTS). Media scanner и собственные записи приложения будят
     * их постоянно, а воркер всё равно отбросил бы такой URI после 30 с задержки.
     * Остальные причины решает воркер: недописанный файл выглядит «маленьким» и не должен теряться.
     * @return true, если все URI приняты или не требуют обработки
     */
    suspend fun enqueueTriggered(uris: List<Uri>): Boolean {
        val pending = uris.filterNot { isSkippableTriggered(it) }
        return enqueueAll(pending)
    }

    private suspend fun isSkippableTriggered(uri: Uri): Boolean = try {
        val check = imageProcessingChecker.isProcessingRequired(uri)
        !check.processingRequired && check.reason in SKIPPABLE_TRIGGER_REASONS
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    companion object {
        private val SKIPPABLE_TRIGGER_REASONS = setOf(
            ImageProcessingChecker.ProcessingSkipReason.ALREADY_COMPRESSED,
            ImageProcessingChecker.ProcessingSkipReason.IN_APP_DIRECTORY,
            ImageProcessingChecker.ProcessingSkipReason.COMPRESSED_VERSION_EXISTS
        )

        /**
         * Нужен ли HISTORY-скан при холодном старте: не чаще
         * [Constants.HISTORY_CATCH_UP_MIN_INTERVAL_MS]. Время из будущего (перевод часов) — нужен.
         */
        fun isHistoryCatchUpDue(lastHistoryMs: Long, nowMs: Long): Boolean {
            val elapsed = nowMs - lastHistoryMs
            return elapsed < 0L || elapsed >= Constants.HISTORY_CATCH_UP_MIN_INTERVAL_MS
        }

        internal fun windowSinceWatermark(lastScanMs: Long, nowMs: Long): Int =
            ((nowMs - lastScanMs) / 1000L + Constants.RECENT_SCAN_WINDOW_SECONDS)
                .coerceIn(Constants.RECENT_SCAN_WINDOW_SECONDS, Constants.HISTORY_SCAN_WINDOW_SECONDS)
                .toInt()
    }
}

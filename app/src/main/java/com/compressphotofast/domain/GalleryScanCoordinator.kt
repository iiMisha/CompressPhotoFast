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
    private val galleryScanUtil: GalleryScanUtil
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

    companion object {
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

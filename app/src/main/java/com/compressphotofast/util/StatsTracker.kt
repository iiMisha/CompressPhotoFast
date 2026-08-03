package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class DailyCompressionStats(
    val epochDay: Long,
    val successfulCount: Int,
    val totalOriginalBytes: Long,
    val totalCompressedBytes: Long
) {
    val savedBytes: Long
        get() = (totalOriginalBytes - totalCompressedBytes).coerceAtLeast(0)

    val reductionPercent: Float
        get() = if (totalOriginalBytes > 0) {
            savedBytes.toFloat() / totalOriginalBytes * 100
        } else {
            0f
        }
}

/**
 * Утилитарный класс для отслеживания статистики и статуса сжатия изображений
 */
object StatsTracker {
    private val dailyStatsLock = Any()
    // Константы статусов сжатия
    const val COMPRESSION_STATUS_NONE = 0
    const val COMPRESSION_STATUS_PROCESSING = 1
    const val COMPRESSION_STATUS_COMPLETED = 2
    const val COMPRESSION_STATUS_FAILED = 3
    const val COMPRESSION_STATUS_SKIPPED = 4

    /**
     * Начинает отслеживание URI (для логирования)
     */
    fun startTracking(uri: Uri) {
        LogUtil.processDebug("Начато отслеживание URI: $uri")
    }

    /**
     * Обновляет статус сжатия для указанного URI (для логирования)
     */
    fun updateStatus(uri: Uri, status: Int) {
        try {
            // Если статус завершающий (COMPLETED или FAILED), логируем
            if (status == COMPRESSION_STATUS_COMPLETED || status == COMPRESSION_STATUS_FAILED || status == COMPRESSION_STATUS_SKIPPED) {
                LogUtil.processDebug("URI завершил обработку со статусом $status: $uri")
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "UPDATE_STATUS", "Ошибка при обновлении статуса", e)
        }
    }

    /**
     * Проверяет, нужно ли обрабатывать изображение
     * Делегирует к централизованной логике в ImageProcessingChecker
     */
    suspend fun shouldProcessImage(context: Context, uri: Uri): Boolean {
        return ImageProcessingChecker.shouldProcessImage(context, uri)
    }


    /**
     * Регистрирует ошибку удаления файла для метрик
     */
    fun recordDeleteFailure(uri: Uri?) {
        LogUtil.warning(uri, "StatsTracker", "Зафиксирована ошибка удаления файла")
    }

    /**
     * Сохраняет успешное сжатие в статистику текущих локальных суток.
     * Возвращает null, если данные нельзя надёжно сохранить.
     */
    fun recordSuccessfulCompression(
        context: Context,
        originalSize: Long,
        compressedSize: Long,
        epochDay: Long = LocalDate.now().toEpochDay()
    ): DailyCompressionStats? {
        if (originalSize <= 0 || compressedSize <= 0) {
            LogUtil.warning(null, "StatsTracker", "Суточная статистика не обновлена: некорректный размер файла")
            return null
        }

        synchronized(dailyStatsLock) {
            val preferences = context.applicationContext.getSharedPreferences(
                Constants.DAILY_COMPRESSION_STATS_PREF_FILE,
                Context.MODE_PRIVATE
            )
            val isCurrentDay = preferences.getLong(Constants.PREF_DAILY_STATS_EPOCH_DAY, Long.MIN_VALUE) == epochDay
            val currentCount = if (isCurrentDay) {
                preferences.getInt(Constants.PREF_DAILY_STATS_SUCCESSFUL_COUNT, 0)
            } else {
                0
            }
            val currentOriginalBytes = if (isCurrentDay) {
                preferences.getLong(Constants.PREF_DAILY_STATS_ORIGINAL_BYTES, 0)
            } else {
                0
            }
            val currentCompressedBytes = if (isCurrentDay) {
                preferences.getLong(Constants.PREF_DAILY_STATS_COMPRESSED_BYTES, 0)
            } else {
                0
            }
            val stats = DailyCompressionStats(
                epochDay = epochDay,
                successfulCount = currentCount + 1,
                totalOriginalBytes = currentOriginalBytes + originalSize,
                totalCompressedBytes = currentCompressedBytes + compressedSize
            )

            val saved = preferences.edit()
                .putLong(Constants.PREF_DAILY_STATS_EPOCH_DAY, stats.epochDay)
                .putInt(Constants.PREF_DAILY_STATS_SUCCESSFUL_COUNT, stats.successfulCount)
                .putLong(Constants.PREF_DAILY_STATS_ORIGINAL_BYTES, stats.totalOriginalBytes)
                .putLong(Constants.PREF_DAILY_STATS_COMPRESSED_BYTES, stats.totalCompressedBytes)
                .commit()
            if (!saved) {
                LogUtil.warning(null, "StatsTracker", "Не удалось сохранить суточную статистику сжатия")
                return null
            }
            return stats
        }
    }

    fun getDailyCompressionStats(
        context: Context,
        epochDay: Long = LocalDate.now().toEpochDay()
    ): DailyCompressionStats? = synchronized(dailyStatsLock) {
        val preferences = context.applicationContext.getSharedPreferences(
            Constants.DAILY_COMPRESSION_STATS_PREF_FILE,
            Context.MODE_PRIVATE
        )
        if (preferences.getLong(Constants.PREF_DAILY_STATS_EPOCH_DAY, Long.MIN_VALUE) != epochDay) {
            return@synchronized null
        }
        DailyCompressionStats(
            epochDay = epochDay,
            successfulCount = preferences.getInt(Constants.PREF_DAILY_STATS_SUCCESSFUL_COUNT, 0),
            totalOriginalBytes = preferences.getLong(Constants.PREF_DAILY_STATS_ORIGINAL_BYTES, 0),
            totalCompressedBytes = preferences.getLong(Constants.PREF_DAILY_STATS_COMPRESSED_BYTES, 0)
        )
    }
}

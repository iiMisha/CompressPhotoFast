package com.compressphotofast.data

import android.content.Context
import java.time.LocalDate
import com.compressphotofast.util.Constants
import com.compressphotofast.util.LogUtil

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
 * Утилитарный класс для накопления дневной статистики сжатия
 */
object StatsTracker {
    private val dailyStatsLock = Any()

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

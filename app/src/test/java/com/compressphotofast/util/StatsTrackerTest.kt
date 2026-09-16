package com.compressphotofast.util

import android.content.Context
import com.compressphotofast.BaseUnitTest
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.concurrent.thread

/**
 * Unit тесты для класса StatsTracker
 */
@RunWith(RobolectricTestRunner::class)
class StatsTrackerTest : BaseUnitTest() {

    private lateinit var context: Context

    @Before
    override fun setUp() {
        super.setUp()
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(Constants.DAILY_COMPRESSION_STATS_PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }



    @Test
    fun `Инициализация с нулевыми значениями`() {
        // Arrange & Act & Assert
        // Проверяем, что константы статусов имеют правильные значения
        assert(StatsTracker.COMPRESSION_STATUS_NONE == 0)
        assert(StatsTracker.COMPRESSION_STATUS_PROCESSING == 1)
        assert(StatsTracker.COMPRESSION_STATUS_COMPLETED == 2)
        assert(StatsTracker.COMPRESSION_STATUS_FAILED == 3)
        assert(StatsTracker.COMPRESSION_STATUS_SKIPPED == 4)
    }

    @Test
    fun `Обновление статуса для несуществующего URI не вызывает ошибку`() {
        // Arrange & Act & Assert
        // Не должно выбрасываться исключение
        StatsTracker.updateStatus(mockk(), StatsTracker.COMPRESSION_STATUS_COMPLETED)
    }

    @Test
    fun `Обновление статуса на PROCESSING не вызывает ошибку`() {
        // Arrange & Act & Assert
        // Не должно выбрасываться исключение
        StatsTracker.updateStatus(mockk(), StatsTracker.COMPRESSION_STATUS_PROCESSING)
    }

    @Test
    fun `Обновление статуса на COMPLETED не вызывает ошибку`() {
        // Arrange & Act & Assert
        // Не должно выбрасываться исключение
        StatsTracker.updateStatus(mockk(), StatsTracker.COMPRESSION_STATUS_COMPLETED)
    }

    @Test
    fun `Обновление статуса на FAILED не вызывает ошибку`() {
        // Arrange & Act & Assert
        // Не должно выбрасываться исключение
        StatsTracker.updateStatus(mockk(), StatsTracker.COMPRESSION_STATUS_FAILED)
    }

    @Test
    fun `Обновление статуса на SKIPPED не вызывает ошибку`() {
        // Arrange & Act & Assert
        // Не должно выбрасываться исключение
        StatsTracker.updateStatus(mockk(), StatsTracker.COMPRESSION_STATUS_SKIPPED)
    }

    @Test
    fun `StartTracking не вызывает ошибку`() {
        // Arrange & Act & Assert
        // Не должно выбрасываться исключение
        StatsTracker.startTracking(mockk())
    }

    @Test
    fun `Несколько вызовов startTracking не вызывают ошибку`() {
        // Arrange & Act & Assert
        // Не должно выбрасываться исключение
        repeat(10) {
            StatsTracker.startTracking(mockk())
        }
    }

    @Test
    fun `Успешные сжатия за один день суммируются`() {
        val epochDay = 20_000L

        StatsTracker.recordSuccessfulCompression(context, 1_000L, 600L, epochDay)
        val stats = StatsTracker.recordSuccessfulCompression(context, 2_000L, 1_000L, epochDay)

        requireNotNull(stats)
        assertEquals(2, stats.successfulCount)
        assertEquals(3_000L, stats.totalOriginalBytes)
        assertEquals(1_600L, stats.totalCompressedBytes)
        assertEquals(1_400L, stats.savedBytes)
        assertEquals(46.666668f, stats.reductionPercent, 0.0001f)
    }

    @Test
    fun `Новые сутки начинают статистику заново`() {
        StatsTracker.recordSuccessfulCompression(context, 1_000L, 500L, 20_000L)
        val stats = StatsTracker.recordSuccessfulCompression(context, 2_000L, 1_200L, 20_001L)

        requireNotNull(stats)
        assertEquals(20_001L, stats.epochDay)
        assertEquals(1, stats.successfulCount)
        assertEquals(2_000L, stats.totalOriginalBytes)
        assertEquals(1_200L, stats.totalCompressedBytes)
        assertNull(StatsTracker.getDailyCompressionStats(context, 20_000L))
    }

    @Test
    fun `Суточная статистика читается из сохраненного хранилища`() {
        StatsTracker.recordSuccessfulCompression(context, 1_500L, 750L, 20_000L)

        val stats = StatsTracker.getDailyCompressionStats(context, 20_000L)

        requireNotNull(stats)
        assertEquals(1, stats.successfulCount)
        assertEquals(1_500L, stats.totalOriginalBytes)
        assertEquals(750L, stats.totalCompressedBytes)
    }

    @Test
    fun `Некорректные размеры не изменяют статистику`() {
        assertNull(StatsTracker.recordSuccessfulCompression(context, 0L, 100L, 20_000L))
        assertNull(StatsTracker.recordSuccessfulCompression(context, 100L, 0L, 20_000L))
        assertNull(StatsTracker.getDailyCompressionStats(context, 20_000L))
    }

    @Test
    fun `Параллельные обновления не теряются`() {
        val updates = List(20) {
            thread { StatsTracker.recordSuccessfulCompression(context, 1_000L, 500L, 20_000L) }
        }
        updates.forEach(Thread::join)

        val stats = StatsTracker.getDailyCompressionStats(context, 20_000L)

        requireNotNull(stats)
        assertEquals(20, stats.successfulCount)
        assertEquals(20_000L, stats.totalOriginalBytes)
        assertEquals(10_000L, stats.totalCompressedBytes)
    }
}

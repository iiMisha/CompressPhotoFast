package com.compressphotofast.util

import com.compressphotofast.BaseUnitTest
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit тесты для класса StatsTracker
 */
class StatsTrackerTest : BaseUnitTest() {

    @Before
    override fun setUp() {
        super.setUp()
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
}

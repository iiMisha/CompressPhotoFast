package com.compressphotofast.util

import com.compressphotofast.BaseUnitTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.compressphotofast.domain.PerformanceMonitor

/**
 * Unit тесты для класса PerformanceMonitor
 */
class PerformanceMonitorTest : BaseUnitTest() {

    private lateinit var mockContext: android.content.Context

    @Before
    override fun setUp() {
        super.setUp()
        // Используем relaxed mock для автоматического мокания всех вызовов Context
        mockContext = mockk(relaxed = true)
        every { mockContext.applicationContext } returns mockContext

        // Сбрасываем статистику перед каждым тестом
        PerformanceMonitor.resetStats()
    }

    @After
    override fun tearDown() {
        super.tearDown()
        // Сбрасываем статистику после каждого теста
        PerformanceMonitor.resetStats()
    }

    @Test
    fun `Инициализация монитора`() = runTest {
        // Arrange & Act & Assert
        // Проверяем, что при инициализации все счетчики равны 0
        val quickStats = PerformanceMonitor.getQuickStats()
        assert(quickStats.contains("пакетные=0")) { "Счетчики должны быть равны 0" }
    }

    @Test
    fun `Измерение пакетного получения метаданных`() = runTest {
        // Arrange
        val expectedResult = "test_result"

        // Act
        val result = PerformanceMonitor.measureBatchMetadata {
            expectedResult
        }

        // Assert
        assert(result == expectedResult) { "Результат должен быть возвращен" }
        val quickStats = PerformanceMonitor.getQuickStats()
        assert(quickStats.contains("пакетные=1")) { "Счетчик пакетных запросов должен быть равен 1" }
    }

    @Test
    fun `Запись попадания в кэш`() {
        // Arrange & Act
        PerformanceMonitor.recordCacheHit("test_cache")

        // Assert
        val quickStats = PerformanceMonitor.getQuickStats()
        assert(quickStats.contains("кэш=100%")) { "Коэффициент попаданий должен быть 100%" }
    }

    @Test
    fun `Запись промаха кэша`() {
        // Arrange & Act
        PerformanceMonitor.recordCacheMiss("test_cache")

        // Assert
        val quickStats = PerformanceMonitor.getQuickStats()
        assert(quickStats.contains("кэш=0%")) { "Коэффициент попаданий должен быть 0%" }
    }

    @Test
    fun `Коэффициент попаданий в кэш`() {
        // Arrange & Act
        PerformanceMonitor.recordCacheHit("cache1")
        PerformanceMonitor.recordCacheHit("cache2")
        PerformanceMonitor.recordCacheMiss("cache3")

        // Assert
        val quickStats = PerformanceMonitor.getQuickStats()
        assert(quickStats.contains("кэш=67%")) { "Коэффициент попаданий должен быть 67%" }
    }

    @Test
    fun `Измерение времени проверки EXIF`() = runTest {
        // Arrange
        val expectedResult = "exif_check_result"

        // Act
        val result = PerformanceMonitor.measureExifCheck {
            expectedResult
        }

        // Assert
        assert(result == expectedResult) { "Результат должен быть возвращен" }
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)
        assert(detailedStats.contains("EXIF:")) { "Статистика должна содержать время проверки EXIF" }
    }

    @Test
    fun `Получение подробной статистики`() = runTest {
        // Arrange
        PerformanceMonitor.measureBatchMetadata { "test" }
        PerformanceMonitor.recordCacheHit("test")

        // Act
        val stats = PerformanceMonitor.getDetailedStats(mockContext)

        // Assert
        assert(stats.contains("СТАТИСТИКА ПРОИЗВОДИТЕЛЬНОСТИ")) { "Статистика должна содержать заголовок" }
        assert(stats.contains("Получение метаданных:")) { "Статистика должна содержать раздел метаданных" }
        assert(stats.contains("Кэширование:")) { "Статистика должна содержать раздел кэширования" }
    }

    @Test
    fun `Получение краткой статистики`() = runTest {
        // Arrange
        PerformanceMonitor.measureBatchMetadata { "test" }
        PerformanceMonitor.recordCacheHit("test")

        // Act
        val stats = PerformanceMonitor.getQuickStats()

        // Assert
        assert(stats.contains("PerformanceMonitor:")) { "Статистика должна содержать префикс" }
        assert(stats.contains("пакетные=1")) { "Статистика должна содержать счетчики" }
        assert(stats.contains("кэш=100%")) { "Статистика должна содержать коэффициент попаданий" }
    }

    @Test
    fun `Сброс статистики`() = runTest {
        // Arrange
        PerformanceMonitor.measureBatchMetadata { "test" }
        PerformanceMonitor.recordCacheHit("test")

        // Act
        PerformanceMonitor.resetStats()

        // Assert
        val quickStats = PerformanceMonitor.getQuickStats()
        assert(quickStats.contains("пакетные=0")) { "Все счетчики должны быть сброшены" }
        assert(quickStats.contains("кэш=0%")) { "Коэффициент попаданий должен быть сброшен" }
    }

    @Test
    fun `Генерация отчета о производительности`() = runTest {
        // Arrange
        PerformanceMonitor.measureBatchMetadata { "test" }
        PerformanceMonitor.recordCacheHit("test")

        // Act
        val report = PerformanceMonitor.generatePerformanceReport(mockContext)

        // Assert
        assert(report.contains("ОТЧЕТ О ПРОИЗВОДИТЕЛЬНОСТИ")) { "Отчет должен содержать заголовок" }
        assert(report.contains("СТАТИСТИКА ПРОИЗВОДИТЕЛЬНОСТИ")) { "Отчет должен содержать статистику" }
        assert(report.contains("Использование памяти:")) { "Отчет должен содержать информацию о памяти" }
    }

    @Test
    fun `Автоматический отчет каждые 50 операций`() = runTest {
        // Arrange
        repeat(50) {
            PerformanceMonitor.measureBatchMetadata { "test$it" }
        }

        // Act & Assert
        // Не должно выбрасываться исключение
        PerformanceMonitor.autoReportIfNeeded(mockContext)
    }

    @Test
    fun `Автоматический отчет каждые 100 операций`() = runTest {
        // Arrange
        repeat(100) {
            PerformanceMonitor.measureBatchMetadata { "test$it" }
        }

        // Act & Assert
        // Не должно выбрасываться исключение
        PerformanceMonitor.autoReportIfNeeded(mockContext)
    }

    @Test
    fun `Несколько измерений подряд`() = runTest {
        // Arrange
        repeat(10) {
            PerformanceMonitor.measureBatchMetadata { "test$it" }
        }

        // Act
        val quickStats = PerformanceMonitor.getQuickStats()

        // Assert
        assert(quickStats.contains("пакетные=10")) { "Счетчик пакетных запросов должен быть равен 10" }
    }

    @Test
    fun `Среднее время пакетных запросов`() = runTest {
        // Arrange
        PerformanceMonitor.measureBatchMetadata { "test1" }
        PerformanceMonitor.measureBatchMetadata { "test2" }

        // Act
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)

        // Assert
        assert(detailedStats.contains("Пакетные запросы: 2")) { "Должно быть 2 пакетных запроса" }
        assert(detailedStats.contains("среднее время:")) { "Должно быть указано среднее время" }
    }

    @Test
    fun `Информация о памяти в отчете`() = runTest {
        // Arrange & Act
        val report = PerformanceMonitor.generatePerformanceReport(mockContext)

        // Assert
        assert(report.contains("Использование памяти:")) { "Отчет должен содержать информацию о памяти" }
        assert(report.contains("MB")) { "Отчет должен содержать единицы измерения памяти" }
    }
}

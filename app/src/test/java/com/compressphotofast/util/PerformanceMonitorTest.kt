package com.compressphotofast.util

import com.compressphotofast.BaseUnitTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

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
        assert(quickStats.contains("пакетные/индивид=0/0")) { "Счетчики должны быть равны 0" }
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
        assert(quickStats.contains("пакетные/индивид=1/0")) { "Счетчик пакетных запросов должен быть равен 1" }
    }

    @Test
    fun `Измерение индивидуального получения метаданных`() = runTest {
        // Arrange
        val expectedResult = "test_result"

        // Act
        val result = PerformanceMonitor.measureIndividualMetadata {
            expectedResult
        }

        // Assert
        assert(result == expectedResult) { "Результат должен быть возвращен" }
        val quickStats = PerformanceMonitor.getQuickStats()
        assert(quickStats.contains("пакетные/индивид=0/1")) { "Счетчик индивидуальных запросов должен быть равен 1" }
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
    fun `Измерение времени проверки директорий`() = runTest {
        // Arrange
        val expectedResult = "directory_check_result"

        // Act
        val result = PerformanceMonitor.measureDirectoryCheck {
            expectedResult
        }

        // Assert
        assert(result == expectedResult) { "Результат должен быть возвращен" }
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)
        assert(detailedStats.contains("Директории:")) { "Статистика должна содержать время проверки директорий" }
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
    fun `Измерение времени проверки MIME-типа`() = runTest {
        // Arrange
        val expectedResult = "mime_check_result"

        // Act
        val result = PerformanceMonitor.measureMimeTypeCheck {
            expectedResult
        }

        // Assert
        assert(result == expectedResult) { "Результат должен быть возвращен" }
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)
        assert(detailedStats.contains("MIME-типы:")) { "Статистика должна содержать время проверки MIME-типов" }
    }

    @Test
    fun `Запись времени обработки для small файла`() {
        // Arrange & Act
        PerformanceMonitor.recordProcessingTime(500000L, 100L) // 500KB

        // Assert
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)
        assert(detailedStats.contains("small файлы")) { "Статистика должна содержать small файлы" }
    }

    @Test
    fun `Запись времени обработки для medium файла`() {
        // Arrange & Act
        PerformanceMonitor.recordProcessingTime(2 * 1024 * 1024L, 200L) // 2MB

        // Assert
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)
        assert(detailedStats.contains("medium файлы")) { "Статистика должна содержать medium файлы" }
    }

    @Test
    fun `Запись времени обработки для large файла`() {
        // Arrange & Act
        PerformanceMonitor.recordProcessingTime(10 * 1024 * 1024L, 500L) // 10MB

        // Assert
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)
        assert(detailedStats.contains("large файлы")) { "Статистика должна содержать large файлы" }
    }

    @Test
    fun `Запись времени обработки для xlarge файла`() {
        // Arrange & Act
        PerformanceMonitor.recordProcessingTime(25 * 1024 * 1024L, 1000L) // 25MB

        // Assert
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)
        assert(detailedStats.contains("xlarge файлы")) { "Статистика должна содержать xlarge файлы" }
    }

    @Test
    fun `Запись оптимизированной обработки`() {
        // Arrange & Act
        PerformanceMonitor.recordOptimizedBatchProcessing()

        // Assert
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)
        assert(detailedStats.contains("Оптимизированная: 1")) { "Счетчик оптимизированной обработки должен быть равен 1" }
    }

    @Test
    fun `Запись устаревшей обработки`() {
        // Arrange & Act
        PerformanceMonitor.recordLegacyProcessing()

        // Assert
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)
        assert(detailedStats.contains("Устаревшая: 1")) { "Счетчик устаревшей обработки должен быть равен 1" }
    }

    @Test
    fun `Запись дебаунс-батча`() {
        // Arrange & Act
        PerformanceMonitor.recordDebouncedBatch(5)

        // Assert
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)
        assert(detailedStats.contains("Дебаунснутых батчей: 1")) { "Счетчик дебаунс-батчей должен быть равен 1" }
    }

    @Test
    fun `Запись немедленной обработки`() {
        // Arrange & Act
        PerformanceMonitor.recordImmediateProcessing()

        // Assert
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)
        assert(detailedStats.contains("Немедленных обработок: 1")) { "Счетчик немедленных обработок должен быть равен 1" }
    }

    @Test
    fun `Получение подробной статистики`() = runTest {
        // Arrange
        PerformanceMonitor.measureBatchMetadata { "test" }
        PerformanceMonitor.recordCacheHit("test")
        PerformanceMonitor.recordOptimizedBatchProcessing()

        // Act
        val stats = PerformanceMonitor.getDetailedStats(mockContext)

        // Assert
        assert(stats.contains("СТАТИСТИКА ПРОИЗВОДИТЕЛЬНОСТИ")) { "Статистика должна содержать заголовок" }
        assert(stats.contains("Получение метаданных:")) { "Статистика должна содержать раздел метаданных" }
        assert(stats.contains("Кэширование:")) { "Статистика должна содержать раздел кэширования" }
        assert(stats.contains("Обработка:")) { "Статистика должна содержать раздел обработки" }
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
        assert(stats.contains("пакетные/индивид=1/0")) { "Статистика должна содержать счетчики" }
        assert(stats.contains("кэш=100%")) { "Статистика должна содержать коэффициент попаданий" }
    }

    @Test
    fun `Сброс статистики`() = runTest {
        // Arrange
        PerformanceMonitor.measureBatchMetadata { "test" }
        PerformanceMonitor.recordCacheHit("test")
        PerformanceMonitor.recordOptimizedBatchProcessing()

        // Act
        PerformanceMonitor.resetStats()

        // Assert
        val quickStats = PerformanceMonitor.getQuickStats()
        assert(quickStats.contains("пакетные/индивид=0/0")) { "Все счетчики должны быть сброшены" }
        assert(quickStats.contains("кэш=0%")) { "Коэффициент попаданий должен быть сброшен" }
    }

    @Test
    fun `Расчет экономии от оптимизаций`() = runTest {
        // Arrange
        PerformanceMonitor.measureBatchMetadata { "test1" }
        PerformanceMonitor.measureBatchMetadata { "test2" }
        PerformanceMonitor.measureIndividualMetadata { "test3" }

        // Act
        val savings = PerformanceMonitor.calculateOptimizationSavings()

        // Assert
        assert(savings.contains("Экономия от пакетной обработки:")) { "Отчет должен содержать заголовок" }
        assert(savings.contains("Фактическое время пакетных запросов:")) { "Отчет должен содержать фактическое время" }
    }

    @Test
    fun `Расчет экономии с недостаточными данными`() {
        // Arrange & Act
        val savings = PerformanceMonitor.calculateOptimizationSavings()

        // Assert
        assert(savings.contains("Недостаточно данных для расчета экономии")) { "Должно быть сообщение о недостаточных данных" }
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
        assert(quickStats.contains("пакетные/индивид=10/0")) { "Счетчик пакетных запросов должен быть равен 10" }
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
    fun `Среднее время индивидуальных запросов`() = runTest {
        // Arrange
        PerformanceMonitor.measureIndividualMetadata { "test1" }
        PerformanceMonitor.measureIndividualMetadata { "test2" }

        // Act
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)

        // Assert
        assert(detailedStats.contains("Индивидуальные запросы: 2")) { "Должно быть 2 индивидуальных запроса" }
        assert(detailedStats.contains("среднее время:")) { "Должно быть указано среднее время" }
    }

    @Test
    fun `Количество измерений для разных размеров файлов`() = runTest {
        // Arrange & Act
        PerformanceMonitor.recordProcessingTime(500000L, 100L) // small
        PerformanceMonitor.recordProcessingTime(500000L, 150L) // small
        PerformanceMonitor.recordProcessingTime(2 * 1024 * 1024L, 200L) // medium

        // Assert
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)
        assert(detailedStats.contains("small файлы (2 шт.)")) { "Должно быть 2 small файла" }
        assert(detailedStats.contains("medium файлы (1 шт.)")) { "Должен быть 1 medium файл" }
    }

    @Test
    fun `Минимальное и максимальное время обработки`() = runTest {
        // Arrange & Act
        PerformanceMonitor.recordProcessingTime(500000L, 100L)
        PerformanceMonitor.recordProcessingTime(500000L, 200L)
        PerformanceMonitor.recordProcessingTime(500000L, 150L)

        // Assert
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)
        assert(detailedStats.contains("мин 100ms")) { "Должно быть указано минимальное время" }
        assert(detailedStats.contains("макс 200ms")) { "Должно быть указано максимальное время" }
    }

    @Test
    fun `Эффективность пакетной обработки`() = runTest {
        // Arrange
        PerformanceMonitor.measureBatchMetadata { "test" }
        PerformanceMonitor.measureIndividualMetadata { "test" }

        // Act
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)

        // Assert
        assert(detailedStats.contains("Эффективность пакетной обработки:")) { "Должна быть указана эффективность" }
    }

    @Test
    fun `Эффективность дебаунсинга`() {
        // Arrange
        PerformanceMonitor.recordDebouncedBatch(5)
        PerformanceMonitor.recordImmediateProcessing()

        // Act
        val detailedStats = PerformanceMonitor.getDetailedStats(mockContext)

        // Assert
        assert(detailedStats.contains("Эффективность дебаунсинга:")) { "Должна быть указана эффективность" }
        // Проверяем что эффективность существует и содержит "%", но не жёстко 50%, так как может быть 50.0%
        assert(detailedStats.contains("%")) { "Эффективность должна содержать %" }
        assert(detailedStats.contains("50")) { "Эффективность должна быть около 50%" }
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

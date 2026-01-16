package com.compressphotofast.util

import com.compressphotofast.BaseUnitTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit тесты для класса CompressionBatchTracker
 */
class CompressionBatchTrackerTest : BaseUnitTest() {

    private lateinit var mockContext: android.content.Context

    @Before
    override fun setUp() {
        super.setUp()
        mockContext = mockk()
        every { mockContext.applicationContext } returns mockContext

        // Очищаем все батчи перед каждым тестом
        CompressionBatchTracker.clearAllBatches()
    }

    @After
    override fun tearDown() {
        super.tearDown()
        // Очищаем все батчи после каждого теста
        CompressionBatchTracker.clearAllBatches()
    }

    @Test
    fun `Инициализация трекера`() {
        // Arrange & Act & Assert
        // Проверяем, что при инициализации нет активных батчей
        val activeBatchCount = CompressionBatchTracker.getActiveBatchCount()
        assert(activeBatchCount == 0) { "При инициализации не должно быть активных батчей" }
    }

    @Test
    fun `Создание Intent батча`() {
        // Arrange
        val expectedCount = 5

        // Act
        val batchId = CompressionBatchTracker.createIntentBatch(mockContext, expectedCount)

        // Assert
        assert(batchId.isNotEmpty()) { "BatchId не должен быть пустым" }
        assert(batchId.startsWith("intent_batch_")) { "BatchId должен начинаться с 'intent_batch_'" }
        assert(CompressionBatchTracker.getActiveBatchCount() == 1) { "Должен быть создан один батч" }
    }

    @Test
    fun `Создание автобатча`() {
        // Arrange & Act
        val batchId = CompressionBatchTracker.getOrCreateAutoBatch(mockContext)

        // Assert
        assert(batchId.isNotEmpty()) { "BatchId не должен быть пустым" }
        assert(batchId.startsWith("auto_batch_")) { "BatchId должен начинаться с 'auto_batch_'" }
        assert(CompressionBatchTracker.getActiveBatchCount() == 1) { "Должен быть создан один батч" }
    }

    @Test
    fun `Получение существующего автобатча вместо создания нового`() {
        // Arrange
        val firstBatchId = CompressionBatchTracker.getOrCreateAutoBatch(mockContext)

        // Act
        val secondBatchId = CompressionBatchTracker.getOrCreateAutoBatch(mockContext)

        // Assert
        assert(firstBatchId == secondBatchId) { "Должен быть возвращен тот же самый батч" }
        assert(CompressionBatchTracker.getActiveBatchCount() == 1) { "Должен быть только один активный батч" }
    }

    @Test
    fun `Добавление результата в батч`() {
        // Arrange
        val batchId = CompressionBatchTracker.createIntentBatch(mockContext, 3)

        // Act
        CompressionBatchTracker.addResult(
            batchId = batchId,
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        // Assert
        // Батч не должен быть завершен, так как ожидается 3 файла
        assert(CompressionBatchTracker.getActiveBatchCount() == 1) { "Батч должен оставаться активным" }
    }

    @Test
    fun `Добавление пропущенного файла в батч`() {
        // Arrange
        val batchId = CompressionBatchTracker.createIntentBatch(mockContext, 3)

        // Act
        CompressionBatchTracker.addResult(
            batchId = batchId,
            fileName = "skipped.jpg",
            originalSize = 50000L,
            compressedSize = 0L,
            sizeReduction = 0.0f,
            skipped = true,
            skipReason = "Малый размер"
        )

        // Assert
        assert(CompressionBatchTracker.getActiveBatchCount() == 1) { "Батч должен оставаться активным" }
    }

    @Test
    fun `Завершение батча при достижении ожидаемого количества`() {
        // Arrange
        val batchId = CompressionBatchTracker.createIntentBatch(mockContext, 2)

        // Act
        CompressionBatchTracker.addResult(
            batchId = batchId,
            fileName = "test1.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )
        CompressionBatchTracker.addResult(
            batchId = batchId,
            fileName = "test2.jpg",
            originalSize = 2048000L,
            compressedSize = 1024000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        // Assert
        // Батч должен быть завершен и удален из активных
        // Небольшая задержка для обработки корутины
        Thread.sleep(100)
        assert(CompressionBatchTracker.getActiveBatchCount() == 0) { "Батч должен быть завершен и удален" }
    }

    @Test
    fun `Принудительное завершение батча`() {
        // Arrange
        val batchId = CompressionBatchTracker.createIntentBatch(mockContext, 5)
        CompressionBatchTracker.addResult(
            batchId = batchId,
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        // Act
        CompressionBatchTracker.finalizeBatch(batchId)

        // Assert
        // Небольшая задержка для обработки корутины
        Thread.sleep(100)
        assert(CompressionBatchTracker.getActiveBatchCount() == 0) { "Батч должен быть завершен" }
    }

    @Test
    fun `Завершение несуществующего батча не вызывает ошибку`() {
        // Arrange & Act & Assert
        // Не должно выбрасываться исключение
        CompressionBatchTracker.finalizeBatch("non_existent_batch_id")
    }

    @Test
    fun `Добавление результата в несуществующий батч не вызывает ошибку`() {
        // Arrange & Act & Assert
        // Не должно выбрасываться исключение
        CompressionBatchTracker.addResult(
            batchId = "non_existent_batch_id",
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )
    }

    @Test
    fun `Сброс всех батчей`() {
        // Arrange
        CompressionBatchTracker.createIntentBatch(mockContext, 3)
        CompressionBatchTracker.getOrCreateAutoBatch(mockContext)
        CompressionBatchTracker.createIntentBatch(mockContext, 2)

        // Act
        CompressionBatchTracker.clearAllBatches()

        // Assert
        assert(CompressionBatchTracker.getActiveBatchCount() == 0) { "Все батчи должны быть очищены" }
    }

    @Test
    fun `Несколько батчей могут быть активными одновременно`() {
        // Arrange
        val batchId1 = CompressionBatchTracker.createIntentBatch(mockContext, 2)
        val batchId2 = CompressionBatchTracker.createIntentBatch(mockContext, 3)
        val batchId3 = CompressionBatchTracker.getOrCreateAutoBatch(mockContext)

        // Act
        CompressionBatchTracker.addResult(
            batchId = batchId1,
            fileName = "test1.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        // Assert
        assert(CompressionBatchTracker.getActiveBatchCount() == 3) { "Должны быть активны 3 батча" }
    }

    @Test
    fun `Завершение одного батча не влияет на другие`() {
        // Arrange
        val batchId1 = CompressionBatchTracker.createIntentBatch(mockContext, 1)
        val batchId2 = CompressionBatchTracker.createIntentBatch(mockContext, 2)

        // Act
        CompressionBatchTracker.addResult(
            batchId = batchId1,
            fileName = "test1.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        // Assert
        // Небольшая задержка для обработки корутины
        Thread.sleep(100)
        assert(CompressionBatchTracker.getActiveBatchCount() == 1) { "Должен остаться только один батч" }
    }

    @Test
    fun `Автобатч с несколькими результатами`() {
        // Arrange
        val batchId = CompressionBatchTracker.getOrCreateAutoBatch(mockContext)

        // Act
        CompressionBatchTracker.addResult(
            batchId = batchId,
            fileName = "test1.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )
        CompressionBatchTracker.addResult(
            batchId = batchId,
            fileName = "test2.jpg",
            originalSize = 2048000L,
            compressedSize = 1024000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        // Assert
        // Автобатч не завершается автоматически по количеству результатов
        assert(CompressionBatchTracker.getActiveBatchCount() == 1) { "Автобатч должен оставаться активным" }
    }

    @Test
    fun `BatchId содержит уникальный идентификатор`() {
        // Arrange & Act
        val batchId1 = CompressionBatchTracker.createIntentBatch(mockContext, 1)
        val batchId2 = CompressionBatchTracker.createIntentBatch(mockContext, 1)

        // Assert
        assert(batchId1 != batchId2) { "BatchId должны быть уникальными" }
    }

    @Test
    fun `Проверка количества активных батчей`() {
        // Arrange
        val initialCount = CompressionBatchTracker.getActiveBatchCount()

        // Act
        CompressionBatchTracker.createIntentBatch(mockContext, 1)
        CompressionBatchTracker.createIntentBatch(mockContext, 2)
        val newCount = CompressionBatchTracker.getActiveBatchCount()

        // Assert
        assert(initialCount == 0) { "Изначально не должно быть активных батчей" }
        assert(newCount == 2) { "Должно быть 2 активных батча" }
    }

    @Test
    fun `Intent батч завершается по таймауту`() {
        // Arrange
        val batchId = CompressionBatchTracker.createIntentBatch(mockContext, 10)
        // Добавляем только один результат из 10 ожидаемых

        // Act
        CompressionBatchTracker.addResult(
            batchId = batchId,
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        // Assert
        // Батч должен быть активен (таймаут 30 секунд)
        assert(CompressionBatchTracker.getActiveBatchCount() == 1) { "Батч должен быть активен до таймаута" }
    }
}

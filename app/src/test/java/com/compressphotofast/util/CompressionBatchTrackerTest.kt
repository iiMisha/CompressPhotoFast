package com.compressphotofast.util

import com.compressphotofast.BaseUnitTest
import io.mockk.every
import io.mockk.mockk
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
        mockContext = mockk(relaxed = true)
        every { mockContext.applicationContext } returns mockContext

        CompressionBatchTracker.clearAllBatchesCompat()
    }

    @After
    override fun tearDown() {
        super.tearDown()
        CompressionBatchTracker.clearAllBatchesCompat()
    }

    @Test
    fun `Инициализация трекера`() {
        val activeBatchCount = CompressionBatchTracker.getActiveBatchCountCompat()
        assert(activeBatchCount == 0) { "При инициализации не должно быть активных батчей" }
    }

    @Test
    fun `Создание Intent батча`() {
        val expectedCount = 5

        val batchId = CompressionBatchTracker.createIntentBatchCompat(mockContext, expectedCount)

        assert(batchId.isNotEmpty()) { "BatchId не должен быть пустым" }
        assert(batchId.startsWith("intent_batch_")) { "BatchId должен начинаться с 'intent_batch_'" }
        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 1) { "Должен быть создан один батч" }
    }

    @Test
    fun `Создание автобатча`() {
        val batchId = CompressionBatchTracker.getOrCreateAutoBatchCompat(mockContext)

        assert(batchId.isNotEmpty()) { "BatchId не должен быть пустым" }
        assert(batchId.startsWith("auto_batch_")) { "BatchId должен начинаться с 'auto_batch_'" }
        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 1) { "Должен быть создан один батч" }
    }

    @Test
    fun `Получение существующего автобатча вместо создания нового`() {
        val firstBatchId = CompressionBatchTracker.getOrCreateAutoBatchCompat(mockContext)

        val secondBatchId = CompressionBatchTracker.getOrCreateAutoBatchCompat(mockContext)

        assert(firstBatchId == secondBatchId) { "Должен быть возвращен тот же самый батч" }
        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 1) { "Должен быть только один активный батч" }
    }

    @Test
    fun `Добавление результата в батч`() {
        val batchId = CompressionBatchTracker.createIntentBatchCompat(mockContext, 3)

        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 1) { "Батч должен оставаться активным" }
    }

    @Test
    fun `Добавление пропущенного файла в батч`() {
        val batchId = CompressionBatchTracker.createIntentBatchCompat(mockContext, 3)

        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "skipped.jpg",
            originalSize = 50000L,
            compressedSize = 0L,
            sizeReduction = 0.0f,
            skipped = true,
            skipReason = "Малый размер"
        )

        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 1) { "Батч должен оставаться активным" }
    }

    @Test
    fun `Завершение батча при достижении ожидаемого количества`() {
        val batchId = CompressionBatchTracker.createIntentBatchCompat(mockContext, 2)

        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test1.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )
        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test2.jpg",
            originalSize = 2048000L,
            compressedSize = 1024000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        Thread.sleep(100)
        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 0) { "Батч должен быть завершен и удален" }
    }

    @Test
    fun `Принудительное завершение батча`() {
        val batchId = CompressionBatchTracker.createIntentBatchCompat(mockContext, 5)
        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        CompressionBatchTracker.finalizeBatchCompat(batchId)

        Thread.sleep(100)
        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 0) { "Батч должен быть завершен" }
    }

    @Test
    fun `Завершение несуществующего батча не вызывает ошибку`() {
        CompressionBatchTracker.finalizeBatchCompat("non_existent_batch_id")
    }

    @Test
    fun `Добавление результата в несуществующий батч не вызывает ошибку`() {
        CompressionBatchTracker.addResultCompat(
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
        CompressionBatchTracker.createIntentBatchCompat(mockContext, 3)
        CompressionBatchTracker.getOrCreateAutoBatchCompat(mockContext)
        CompressionBatchTracker.createIntentBatchCompat(mockContext, 2)

        CompressionBatchTracker.clearAllBatchesCompat()

        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 0) { "Все батчи должны быть очищены" }
    }

    @Test
    fun `Несколько батчей могут быть активными одновременно`() {
        val batchId1 = CompressionBatchTracker.createIntentBatchCompat(mockContext, 2)
        val batchId2 = CompressionBatchTracker.createIntentBatchCompat(mockContext, 3)
        val batchId3 = CompressionBatchTracker.getOrCreateAutoBatchCompat(mockContext)

        CompressionBatchTracker.addResultCompat(
            batchId = batchId1,
            fileName = "test1.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 3) { "Должны быть активны 3 батча" }
    }

    @Test
    fun `Завершение одного батча не влияет на другие`() {
        val batchId1 = CompressionBatchTracker.createIntentBatchCompat(mockContext, 1)
        val batchId2 = CompressionBatchTracker.createIntentBatchCompat(mockContext, 2)

        CompressionBatchTracker.addResultCompat(
            batchId = batchId1,
            fileName = "test1.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        Thread.sleep(100)
        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 1) { "Должен остаться только один батч" }
    }

    @Test
    fun `Автобатч с несколькими результатами остаётся активным`() {
        val batchId = CompressionBatchTracker.getOrCreateAutoBatchCompat(mockContext)

        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test1.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )
        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test2.jpg",
            originalSize = 2048000L,
            compressedSize = 1024000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        // Автобатч не завершается автоматически по количеству результатов,
        // а ждёт idle таймаут (10 сек) после последнего addResult()
        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 1) { "Автобатч должен оставаться активным" }
    }

    @Test
    fun `BatchId содержит уникальный идентификатор`() {
        val batchId1 = CompressionBatchTracker.createIntentBatchCompat(mockContext, 1)
        val batchId2 = CompressionBatchTracker.createIntentBatchCompat(mockContext, 1)

        assert(batchId1 != batchId2) { "BatchId должны быть уникальными" }
    }

    @Test
    fun `Проверка количества активных батчей`() {
        val initialCount = CompressionBatchTracker.getActiveBatchCountCompat()

        CompressionBatchTracker.createIntentBatchCompat(mockContext, 1)
        CompressionBatchTracker.createIntentBatchCompat(mockContext, 2)
        val newCount = CompressionBatchTracker.getActiveBatchCountCompat()

        assert(initialCount == 0) { "Изначально не должно быть активных батчей" }
        assert(newCount == 2) { "Должно быть 2 активных батча" }
    }

    @Test
    fun `Intent батч остаётся активен до достижения expectedCount`() {
        val batchId = CompressionBatchTracker.createIntentBatchCompat(mockContext, 10)

        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 1) { "Батч должен быть активен до достижения expectedCount" }
    }

    @Test
    fun `Статический экземпляр инициализируется при создании`() {
        val tracker = CompressionBatchTracker(mockContext)

        val batchId = CompressionBatchTracker.createIntentBatchCompat(mockContext, 1)

        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        Thread.sleep(100)

        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 0) { "Батч должен быть завершен через статический экземпляр" }
    }

    @Test
    fun `Несколько результатов через статический экземпляр`() {
        val tracker = CompressionBatchTracker(mockContext)
        val batchId = CompressionBatchTracker.createIntentBatchCompat(mockContext, 3)

        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test1.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )
        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test2.jpg",
            originalSize = 2048000L,
            compressedSize = 1024000L,
            sizeReduction = 50.0f,
            skipped = false
        )
        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test3.jpg",
            originalSize = 512000L,
            compressedSize = 256000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        Thread.sleep(100)
        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 0) { "Батч должен быть завершен после добавления всех результатов" }
    }

    @Test
    fun `Автобатч продлевается при каждом addResult и остаётся активным`() {
        val batchId = CompressionBatchTracker.getOrCreateAutoBatchCompat(mockContext)

        // Добавляем 5 результатов с интервалом
        for (i in 1..5) {
            CompressionBatchTracker.addResultCompat(
                batchId = batchId,
                fileName = "test$i.jpg",
                originalSize = 1024000L * i,
                compressedSize = 512000L * i,
                sizeReduction = 50.0f,
                skipped = false
            )
            Thread.sleep(50)
        }

        // Автобатч должен оставаться активным, так как таймаут продлевается при каждом addResult
        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 1) { "Автобатч должен оставаться активным после addResult" }
    }

    @Test
    fun `Автобатч финализируется по idle таймауту после последнего addResult`() {
        val tracker = CompressionBatchTracker(mockContext)
        val batchId = CompressionBatchTracker.getOrCreateAutoBatchCompat(mockContext)

        // Добавляем результаты — каждый продлевает таймаут
        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test1.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )
        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test2.jpg",
            originalSize = 2048000L,
            compressedSize = 1024000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        // Батч активен сразу после добавления результатов
        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 1) { "Автобатч должен быть активен сразу после addResult" }

        // Ждём idle таймаут (20 сек) + запас
        Thread.sleep(21000)

        // После idle таймаута батч должен быть финализирован
        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 0) { "Автобатч должен быть финализирован после idle таймаута" }
    }

    @Test
    fun `Добавление результата в удалённый автобатч не вызывает ошибку`() {
        val batchId = CompressionBatchTracker.getOrCreateAutoBatchCompat(mockContext)

        // Принудительно финализируем батч
        CompressionBatchTracker.finalizeBatchCompat(batchId)

        // Пытаемся добавить результат в уже удалённый батч
        CompressionBatchTracker.addResultCompat(
            batchId = batchId,
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        // Не должно быть исключений
        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 0) { "Не должно быть активных батчей" }
    }

    @Test
    fun `Новый автобатч создаётся после финализации предыдущего`() {
        val firstBatchId = CompressionBatchTracker.getOrCreateAutoBatchCompat(mockContext)

        // Финализируем первый автобатч
        CompressionBatchTracker.addResultCompat(
            batchId = firstBatchId,
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )
        CompressionBatchTracker.finalizeBatchCompat(firstBatchId)

        // Получаем новый автобатч
        val secondBatchId = CompressionBatchTracker.getOrCreateAutoBatchCompat(mockContext)

        assert(secondBatchId != firstBatchId) { "Новый автобатч должен иметь другой ID" }
        assert(CompressionBatchTracker.getActiveBatchCountCompat() == 1) { "Должен быть один активный батч" }
    }
}

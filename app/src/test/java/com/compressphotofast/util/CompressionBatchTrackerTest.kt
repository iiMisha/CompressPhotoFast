package com.compressphotofast.util

import com.compressphotofast.BaseUnitTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.just
import io.mockk.Runs
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit тесты для класса CompressionBatchTracker
 */
class CompressionBatchTrackerTest : BaseUnitTest() {

    private lateinit var mockContext: android.content.Context
    private lateinit var tracker: CompressionBatchTracker

    @Before
    override fun setUp() {
        super.setUp()
        mockContext = mockk(relaxed = true)
        every { mockContext.applicationContext } returns mockContext

        mockkObject(NotificationUtil)
        every { NotificationUtil.showCompressionResultToast(any<android.content.Context>(), any<String>(), any<Long>(), any<Long>(), any<Float>()) } just Runs
        every { NotificationUtil.showToast(any<android.content.Context>(), any<String>(), any<Int>()) } just Runs

        tracker = CompressionBatchTracker(mockContext, mockk(relaxed = true))
    }

    @After
    override fun tearDown() {
        tracker.clearAllBatches()
        // Ждем завершения фоновых корутин в batchScope (Dispatchers.Default),
        // которые могли быть запущены в конце теста (например, при финализации автобатча),
        // чтобы они успели использовать замоканный NotificationUtil
        Thread.sleep(500)
        unmockkObject(NotificationUtil)
        super.tearDown()
    }

    @Test
    fun `Инициализация трекера`() {
        val activeBatchCount = tracker.getActiveBatchCount()
        assert(activeBatchCount == 0) { "При инициализации не должно быть активных батчей" }
    }

    @Test
    fun `Создание Intent батча`() {
        val expectedCount = 5

        val batchId = tracker.createIntentBatch(expectedCount)

        assert(batchId.isNotEmpty()) { "BatchId не должен быть пустым" }
        assert(batchId.startsWith("intent_batch_")) { "BatchId должен начинаться с 'intent_batch_'" }
        assert(tracker.getActiveBatchCount() == 1) { "Должен быть создан один батч" }
    }

    @Test
    fun `Добавление результата в батч`() {
        val batchId = tracker.createIntentBatch(3)

        tracker.addResult(
            batchId = batchId,
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        assert(tracker.getActiveBatchCount() == 1) { "Батч должен оставаться активным" }
    }

    @Test
    fun `Добавление пропущенного файла в батч`() {
        val batchId = tracker.createIntentBatch(3)

        tracker.addResult(
            batchId = batchId,
            fileName = "skipped.jpg",
            originalSize = 50000L,
            compressedSize = 0L,
            sizeReduction = 0.0f,
            skipped = true,
            skipReason = "Малый размер"
        )

        assert(tracker.getActiveBatchCount() == 1) { "Батч должен оставаться активным" }
    }

    @Test
    fun `Завершение батча при достижении ожидаемого количества`() {
        val batchId = tracker.createIntentBatch(2)

        tracker.addResult(
            batchId = batchId,
            fileName = "test1.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )
        tracker.addResult(
            batchId = batchId,
            fileName = "test2.jpg",
            originalSize = 2048000L,
            compressedSize = 1024000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        Thread.sleep(100)
        assert(tracker.getActiveBatchCount() == 0) { "Батч должен быть завершен и удален" }
    }

    @Test
    fun `Принудительное завершение батча`() {
        val batchId = tracker.createIntentBatch(5)
        tracker.addResult(
            batchId = batchId,
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        tracker.finalizeBatch(batchId)

        Thread.sleep(100)
        assert(tracker.getActiveBatchCount() == 0) { "Батч должен быть завершен" }
    }

    @Test
    fun `Завершение несуществующего батча не вызывает ошибку`() {
        tracker.finalizeBatch("non_existent_batch_id")
    }

    @Test
    fun `Добавление результата в несуществующий батч не вызывает ошибку`() {
        tracker.addResult(
            batchId = "non_existent_batch_id",
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )
    }

    @Test
    fun `Завершение одного батча не влияет на другие`() {
        val batchId1 = tracker.createIntentBatch(1)
        val batchId2 = tracker.createIntentBatch(2)

        tracker.addResult(
            batchId = batchId1,
            fileName = "test1.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        Thread.sleep(100)
        assert(tracker.getActiveBatchCount() == 1) { "Должен остаться только один батч" }
    }

    @Test
    fun `Сброс всех батчей`() {
        tracker.createIntentBatch(3)
        tracker.createIntentBatch(2)

        tracker.clearAllBatches()

        assert(tracker.getActiveBatchCount() == 0) { "Все батчи должны быть очищены" }
    }

    @Test
    fun `Несколько батчей могут быть активными одновременно`() {
        val batchId1 = tracker.createIntentBatch(2)
        tracker.createIntentBatch(3)

        tracker.addResult(
            batchId = batchId1,
            fileName = "test1.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        assert(tracker.getActiveBatchCount() == 2) { "Должны быть активны 2 батча" }
    }

    @Test
    fun `BatchId содержит уникальный идентификатор`() {
        val batchId1 = tracker.createIntentBatch(1)
        val batchId2 = tracker.createIntentBatch(1)

        assert(batchId1 != batchId2) { "BatchId должны быть уникальными" }
    }

    @Test
    fun `Проверка количества активных батчей`() {
        val initialCount = tracker.getActiveBatchCount()

        tracker.createIntentBatch(1)
        tracker.createIntentBatch(2)
        val newCount = tracker.getActiveBatchCount()

        assert(initialCount == 0) { "Изначально не должно быть активных батчей" }
        assert(newCount == 2) { "Должно быть 2 активных батча" }
    }

    @Test
    fun `Intent батч остаётся активен до достижения expectedCount`() {
        val batchId = tracker.createIntentBatch(10)

        tracker.addResult(
            batchId = batchId,
            fileName = "test.jpg",
            originalSize = 1024000L,
            compressedSize = 512000L,
            sizeReduction = 50.0f,
            skipped = false
        )

        assert(tracker.getActiveBatchCount() == 1) { "Батч должен быть активен до достижения expectedCount" }
    }
}

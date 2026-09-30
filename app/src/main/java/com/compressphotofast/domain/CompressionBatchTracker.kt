package com.compressphotofast.domain

import android.content.Context
import com.compressphotofast.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import com.compressphotofast.data.FileOperationsUtil
import com.compressphotofast.data.SettingsManager
import com.compressphotofast.platform.NotificationUtil
import com.compressphotofast.util.LogUtil

/**
 * Утилита для группировки результатов сжатия изображений
 * Определяет когда показать индивидуальный Toast (1 файл) или групповой (несколько файлов)
 *
 * Использует Application Context для предотвращения утечек памяти
 */
@Singleton
class CompressionBatchTracker @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsManager: SettingsManager,
    @ApplicationScope private val appScope: CoroutineScope,
    private val compressionEvents: CompressionEvents
) {

    private val batches = ConcurrentHashMap<String, CompressionBatch>()
    private val batchIdCounter = AtomicInteger(1)

    // Константы таймаутов
    // Скользящий таймаут безопасности: отсчитывается от создания батча и от каждого результата,
    // чтобы длинные ручные батчи (сериализованные CompressionExecutionGate) не обрывались
    private val INTENT_BATCH_TIMEOUT_MS = 120_000L
    private val MAX_BATCHES = 50 // Максимальное количество отслеживаемых батчей

    /** Суммарный прогресс активных ручных батчей: обработано [done] из [expected]. */
    data class BatchProgress(val done: Int, val expected: Int)

    private val _progress = MutableStateFlow<BatchProgress?>(null)
    val progress: StateFlow<BatchProgress?> = _progress.asStateFlow()

    /** Исход обработки одного файла батча. */
    enum class Status { COMPRESSED, SKIPPED, FAILED }

    /**
     * Данные одного результата сжатия
     */
    data class CompressionResult(
        val fileName: String,
        val originalSize: Long,
        val compressedSize: Long,
        val sizeReduction: Float,
        val skipped: Boolean,
        val skipReason: String? = null,
        val status: Status = if (skipped) Status.SKIPPED else Status.COMPRESSED
    )

    /**
     * Информация о батче сжатия
     */
    private data class CompressionBatch(
        val batchId: String,
        @Volatile var expectedCount: Int,
        var results: MutableList<CompressionResult> = Collections.synchronizedList(mutableListOf()),
        var timeoutJob: Job? = null,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun isComplete(): Boolean = results.size >= expectedCount
    }

    /**
     * Создает новый батч для Intent-сжатия с известным количеством файлов
     */
    fun createIntentBatch(expectedCount: Int): String {
        val batchId = "intent_batch_${batchIdCounter.getAndIncrement()}_${System.currentTimeMillis()}"
        // Application Context инжектируется через конструктор
        val batch = CompressionBatch(
            batchId = batchId,
            expectedCount = expectedCount
        )

        batches[batchId] = batch
        LogUtil.processDebug("Создан Intent батч: $batchId, ожидается файлов: $expectedCount")

        // Устанавливаем таймаут безопасности для Intent батчей
        scheduleTimeout(batchId, INTENT_BATCH_TIMEOUT_MS)

        cleanupOldBatches()
        updateProgress()
        return batchId
    }

    /**
     * Добавляет результат сжатия в батч
     */
    fun addResult(
        batchId: String,
        fileName: String,
        originalSize: Long,
        compressedSize: Long,
        sizeReduction: Float,
        skipped: Boolean,
        skipReason: String? = null,
        status: Status = if (skipped) Status.SKIPPED else Status.COMPRESSED
    ) {
        val batch = batches[batchId] ?: return
        
        synchronized(batch) {
            val result = CompressionResult(
                fileName = fileName,
                originalSize = originalSize,
                compressedSize = compressedSize,
                sizeReduction = sizeReduction,
                skipped = status != Status.COMPRESSED,
                skipReason = skipReason,
                status = status
            )
            
            batch.results.add(result)
            LogUtil.processDebug("Добавлен результат в батч $batchId: $fileName (${batch.results.size}/${batch.expectedCount})")
            
            // Проверяем, завершен ли батч
            if (batch.isComplete()) {
                processBatch(batchId)
            } else {
                scheduleTimeout(batchId, INTENT_BATCH_TIMEOUT_MS)
                updateProgress()
            }
        }
    }

    /**
     * Уточняет ожидаемое число результатов после постановки в очередь:
     * URI, не принятые в durable очередь, результата не пришлют.
     */
    fun setExpectedCount(batchId: String, expectedCount: Int) {
        val batch = batches[batchId] ?: return
        synchronized(batch) {
            batch.expectedCount = expectedCount
            if (batch.isComplete()) {
                processBatch(batchId)
            } else {
                updateProgress()
            }
        }
    }

    private fun updateProgress() {
        val active = batches.values
        _progress.value = if (active.isEmpty()) {
            null
        } else {
            BatchProgress(
                done = active.sumOf { it.results.size },
                expected = active.sumOf { it.expectedCount }
            )
        }
    }

    /**
     * Принудительно завершает батч (например, при ошибке)
     */
    fun finalizeBatch(batchId: String) {
        if (batches.containsKey(batchId)) {
            processBatch(batchId)
        }
    }

    /**
     * Обрабатывает завершенный батч и показывает результат
     */
    private fun processBatch(batchId: String) {
        val batch = batches.remove(batchId) ?: return

        // Отменяем таймаут
        batch.timeoutJob?.cancel()
        updateProgress()

        val results = batch.results.toList()
        compressionEvents.emit(
            CompressionEvents.Event.BatchCompleted(
                expected = batch.expectedCount,
                compressed = results.count { it.status == Status.COMPRESSED },
                skipped = results.count { it.status == Status.SKIPPED },
                failed = results.count { it.status == Status.FAILED },
                totalOriginalSize = results.filter { it.status == Status.COMPRESSED }.sumOf { it.originalSize },
                totalCompressedSize = results.filter { it.status == Status.COMPRESSED }.sumOf { it.compressedSize }
            )
        )
        if (results.isEmpty()) {
            LogUtil.processDebug("Пустой батч $batchId, результат не показывается")
            return
        }

        // Используем Application Context из конструктора
        appScope.launch {
            if (results.size == 1) {
                // Показываем индивидуальный результат
                showIndividualResult(appContext, results[0])
            } else {
                // Показываем групповой результат
                showBatchResult(appContext, results)
            }
        }

        LogUtil.processDebug("Обработан батч $batchId: ${results.size} результатов")
    }

    /**
     * Показывает индивидуальный результат (как было раньше)
     */
    private fun showIndividualResult(context: Context, result: CompressionResult) {
        // Показываем Toast для 1 файла
        if (result.skipped) {
            // Для пропущенных файлов не показываем Toast в индивидуальном режиме
            LogUtil.processDebug("Индивидуальный результат пропущен (файл был пропущен): ${result.fileName}")
        } else {
            NotificationUtil.showCompressionResultToast(
                context = context,
                fileName = result.fileName,
                originalSize = result.originalSize,
                compressedSize = result.compressedSize,
                reduction = result.sizeReduction
            )
        }
        
    }

    /**
     * Показывает групповой результат для нескольких файлов
     */
    private fun showBatchResult(context: Context, results: List<CompressionResult>) {
        val successfulResults = results.filter { it.status == Status.COMPRESSED }
        val skippedCount = results.count { it.status != Status.COMPRESSED }
        
        if (successfulResults.isEmpty() && skippedCount == 0) {
            return // Нет результатов для показа
        }
        
        // Показываем групповой Toast
        showBatchToast(context, successfulResults, skippedCount)
        
        LogUtil.processDebug("Показан групповой результат: ${results.size} файлов (${successfulResults.size} успешно, $skippedCount пропущено)")
    }
    
    /**
     * Показывает групповой Toast для нескольких файлов
     */
    private fun showBatchToast(context: Context, successfulResults: List<CompressionResult>, skippedCount: Int) {
        // Проверяем настройку перед показом Toast
        if (!settingsManager.shouldShowCompressionToast()) {
            LogUtil.debug("CompressionBatchTracker", "Toast о батче сжатия отключен в настройках")
            return
        }

        val message = if (successfulResults.isNotEmpty()) {
            // Считаем общую статистику для Toast
            val totalOriginalSize = successfulResults.sumOf { it.originalSize }
            val totalCompressedSize = successfulResults.sumOf { it.compressedSize }
            val totalReduction = FileOperationsUtil.computeSizeReductionPercent(totalOriginalSize, totalCompressedSize)

            val originalSizeStr = FileOperationsUtil.formatFileSize(totalOriginalSize)
            val compressedSizeStr = FileOperationsUtil.formatFileSize(totalCompressedSize)
            val reductionStr = String.format("%.1f", totalReduction)

            val baseMessage = "Сжато ${successfulResults.size} фото: $originalSizeStr → $compressedSizeStr (-$reductionStr%)"

            if (skippedCount > 0) {
                "$baseMessage\nПропущено: $skippedCount фото"
            } else {
                baseMessage
            }
        } else {
            // Только пропущенные файлы
            "Пропущено: $skippedCount фото (уже сжаты или малый размер)"
        }

        NotificationUtil.showToast(context, message, android.widget.Toast.LENGTH_LONG)
    }
    
    /**
     * Устанавливает таймаут для автоматического завершения батча
     */
    private fun scheduleTimeout(batchId: String, timeoutMs: Long) {
        val batch = batches[batchId] ?: return

        synchronized(batch) {
            // Отменяем предыдущий таймаут
            batch.timeoutJob?.cancel()

            val timeoutJob = appScope.launch {
                delay(timeoutMs)
                LogUtil.processDebug("Истек таймаут для батча: $batchId")
                processBatch(batchId)
            }

            batch.timeoutJob = timeoutJob
        }
    }

    /**
     * Очищает старые батчи для предотвращения утечек памяти
     */
    private fun cleanupOldBatches() {
        if (batches.size <= MAX_BATCHES) return

        val now = System.currentTimeMillis()
        val oldBatches = batches.entries.filter { (_, batch) ->
            val age = now - batch.createdAt
            age > 300000L // 5 минут
        }

        oldBatches.forEach { (batchId, batch) ->
            batch.timeoutJob?.cancel()
            batches.remove(batchId)
        }
        updateProgress()

        if (oldBatches.isNotEmpty()) {
            LogUtil.processDebug("Очищено старых батчей: ${oldBatches.size}")
        }
    }

    /**
     * Возвращает количество активных батчей (для отладки)
     */
    fun getActiveBatchCount(): Int = batches.size
    
    /**
     * Очищает все батчи (для тестирования)
     */
    fun clearAllBatches() {
        batches.values.forEach { batch ->
            batch.timeoutJob?.cancel()
        }
        batches.clear()
        updateProgress()
        LogUtil.processDebug("Все батчи очищены")
    }
}

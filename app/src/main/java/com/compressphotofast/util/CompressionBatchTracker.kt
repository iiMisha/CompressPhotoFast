package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Утилита для группировки результатов сжатия изображений
 * Определяет когда показать индивидуальный Toast (1 файл) или групповой (несколько файлов)
 */
object CompressionBatchTracker {

    private val batches = ConcurrentHashMap<String, CompressionBatch>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val batchIdCounter = AtomicInteger(1)

    // Константы для автобатчей
    private const val AUTO_BATCH_TIMEOUT_MS = 8000L // 8 секунд для завершения автобатча
    private const val MAX_BATCHES = 50 // Максимальное количество отслеживаемых батчей

    /**
     * Данные одного результата сжатия
     */
    data class CompressionResult(
        val fileName: String,
        val originalSize: Long,
        val compressedSize: Long,
        val sizeReduction: Float,
        val skipped: Boolean,
        val skipReason: String? = null
    )

    /**
     * Информация о батче сжатия
     */
    private data class CompressionBatch(
        val batchId: String,
        val context: Context,
        val expectedCount: Int?, // Если null - автобатч, количество неизвестно
        val isIntentBatch: Boolean,
        var results: MutableList<CompressionResult> = mutableListOf(),
        var timeoutRunnable: Runnable? = null
    ) {
        fun isComplete(): Boolean {
            return if (expectedCount != null) {
                results.size >= expectedCount
            } else {
                false // Автобатчи завершаются только по таймауту
            }
        }
    }

    /**
     * Создает новый батч для Intent-сжатия с известным количеством файлов
     */
    fun createIntentBatch(context: Context, expectedCount: Int): String {
        val batchId = "intent_batch_${batchIdCounter.getAndIncrement()}_${System.currentTimeMillis()}"
        val batch = CompressionBatch(
            batchId = batchId,
            context = context,
            expectedCount = expectedCount,
            isIntentBatch = true
        )
        
        batches[batchId] = batch
        LogUtil.processDebug("Создан Intent батч: $batchId, ожидается файлов: $expectedCount")
        
        // Устанавливаем таймаут безопасности для Intent батчей (30 секунд)
        scheduleTimeout(batchId, 30000L)
        
        cleanupOldBatches()
        return batchId
    }

    /**
     * Получает или создает автобатч для автоматического сжатия
     */
    fun getOrCreateAutoBatch(context: Context): String {
        // Ищем активный автобатч
        val activeBatch = batches.values.find { 
            !it.isIntentBatch && System.currentTimeMillis() - getBatchTimestamp(it.batchId) < AUTO_BATCH_TIMEOUT_MS 
        }
        
        if (activeBatch != null) {
            // Продлеваем таймаут существующего автобатча
            extendAutoBatchTimeout(activeBatch.batchId)
            LogUtil.processDebug("Используется существующий автобатч: ${activeBatch.batchId}")
            return activeBatch.batchId
        }
        
        // Создаем новый автобатч
        val batchId = "auto_batch_${batchIdCounter.getAndIncrement()}_${System.currentTimeMillis()}"
        val batch = CompressionBatch(
            batchId = batchId,
            context = context,
            expectedCount = null,
            isIntentBatch = false
        )
        
        batches[batchId] = batch
        LogUtil.processDebug("Создан автобатч: $batchId")
        
        scheduleTimeout(batchId, AUTO_BATCH_TIMEOUT_MS)
        cleanupOldBatches()
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
        skipReason: String? = null
    ) {
        val batch = batches[batchId] ?: return
        
        val result = CompressionResult(
            fileName = fileName,
            originalSize = originalSize,
            compressedSize = compressedSize,
            sizeReduction = sizeReduction,
            skipped = skipped,
            skipReason = skipReason
        )
        
        batch.results.add(result)
        LogUtil.processDebug("Добавлен результат в батч $batchId: $fileName (${batch.results.size}${if (batch.expectedCount != null) "/${batch.expectedCount}" else ""})")
        
        // Проверяем, завершен ли батч
        if (batch.isComplete()) {
            processBatch(batchId)
        } else if (!batch.isIntentBatch) {
            // Для автобатчей продлеваем таймаут
            extendAutoBatchTimeout(batchId)
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
        batch.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        
        val results = batch.results
        if (results.isEmpty()) {
            LogUtil.processDebug("Пустой батч $batchId, результат не показывается")
            return
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            if (results.size == 1) {
                // Показываем индивидуальный результат
                showIndividualResult(batch.context, results[0])
            } else {
                // Показываем групповой результат
                showBatchResult(batch.context, results, batch.isIntentBatch)
            }
        }
        
        LogUtil.processDebug("Обработан батч $batchId: ${results.size} результатов")
    }

    /**
     * Показывает индивидуальный результат (как было раньше)
     */
    private fun showIndividualResult(context: Context, result: CompressionResult) {
        if (result.skipped) {
            // Для пропущенных файлов не показываем Toast в индивидуальном режиме
            LogUtil.processDebug("Индивидуальный результат пропущен (файл был пропущен): ${result.fileName}")
            return
        }
        
        NotificationUtil.showCompressionResultToast(
            context = context,
            fileName = FileOperationsUtil.truncateFileName(result.fileName),
            originalSize = result.originalSize,
            compressedSize = result.compressedSize,
            reduction = result.sizeReduction
        )
    }

    /**
     * Показывает групповой результат для нескольких файлов
     */
    private fun showBatchResult(context: Context, results: List<CompressionResult>, isIntentBatch: Boolean) {
        val successfulResults = results.filter { !it.skipped }
        val skippedCount = results.count { it.skipped }
        
        if (successfulResults.isEmpty() && skippedCount == 0) {
            return // Нет результатов для показа
        }
        
        val message = if (successfulResults.isNotEmpty()) {
            // Считаем общую статистику
            val totalOriginalSize = successfulResults.sumOf { it.originalSize }
            val totalCompressedSize = successfulResults.sumOf { it.compressedSize }
            val totalReduction = if (totalOriginalSize > 0) {
                ((totalOriginalSize - totalCompressedSize).toFloat() / totalOriginalSize) * 100
            } else 0f
            
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
        LogUtil.processDebug("Показан групповой результат: ${results.size} файлов (${successfulResults.size} успешно, $skippedCount пропущено)")
    }

    /**
     * Устанавливает таймаут для автоматического завершения батча
     */
    private fun scheduleTimeout(batchId: String, timeoutMs: Long) {
        val batch = batches[batchId] ?: return
        
        // Отменяем предыдущий таймаут
        batch.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        
        val timeoutRunnable = Runnable {
            LogUtil.processDebug("Истек таймаут для батча: $batchId")
            processBatch(batchId)
        }
        
        batch.timeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)
    }

    /**
     * Продлевает таймаут автобатча
     */
    private fun extendAutoBatchTimeout(batchId: String) {
        scheduleTimeout(batchId, AUTO_BATCH_TIMEOUT_MS)
    }

    /**
     * Извлекает временную метку из ID батча
     */
    private fun getBatchTimestamp(batchId: String): Long {
        return try {
            batchId.substringAfterLast("_").toLong()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Очищает старые батчи для предотвращения утечек памяти
     */
    private fun cleanupOldBatches() {
        if (batches.size <= MAX_BATCHES) return
        
        val now = System.currentTimeMillis()
        val oldBatches = batches.entries.filter { (batchId, batch) ->
            val age = now - getBatchTimestamp(batchId)
            age > 300000L // 5 минут
        }
        
        oldBatches.forEach { (batchId, batch) ->
            batch.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            batches.remove(batchId)
        }
        
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
            batch.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        }
        batches.clear()
        LogUtil.processDebug("Все батчи очищены")
    }
}
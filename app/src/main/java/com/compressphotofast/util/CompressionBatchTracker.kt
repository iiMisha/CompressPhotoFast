package com.compressphotofast.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Утилита для группировки результатов сжатия изображений
 * Определяет когда показать индивидуальный Toast (1 файл) или групповой (несколько файлов)
 *
 * Использует Application Context для предотвращения утечек памяти
 */
@Singleton
class CompressionBatchTracker @Inject constructor(
    @ApplicationContext private val appContext: Context
) {

    init {
        // Инициализируем статический экземпляр при создании DI-синглтона
        // Это гарантирует, что staticInstance будет доступен после ребута устройства
        staticInstance = this
    }

    companion object {
        // Shared Handler для всех экземпляров
        private val sharedMainHandler = Handler(Looper.getMainLooper())

        /**
         * Статический экземпляр для обратной совместимости
         * Используется в ImageProcessingUtil (object) который не может инжектировать зависимости
         *
         * IMPORTANT: staticInstance инициализируется при создании DI-экземпляра через init-блок
         *
         * TODO: Удалить после рефакторинга ImageProcessingUtil в injectable class
         */
        @Volatile
        private var staticInstance: CompressionBatchTracker? = null

        /**
         * Получает экземпляр CompressionBatchTracker
         * Для использования в object классах которые не поддерживают DI
         */
        @JvmStatic
        fun getInstance(context: Context): CompressionBatchTracker {
            return staticInstance ?: synchronized(this) {
                staticInstance ?: CompressionBatchTracker(context.applicationContext).also {
                    staticInstance = it
                }
            }
        }

        /**
         * Создает Intent батч (статический метод для обратной совместимости)
         * @deprecated Используйте инжектируемый экземпляр через Hilt
         */
        @Deprecated("Use injected instance via Hilt instead")
        fun createIntentBatchCompat(context: Context, expectedCount: Int): String {
            return getInstance(context).createIntentBatch(expectedCount)
        }

        /**
         * Создает или получает автобатч (статический метод для обратной совместимости)
         * @deprecated Используйте инжектируемый экземпляр через Hilt
         */
        @Deprecated("Use injected instance via Hilt instead")
        fun getOrCreateAutoBatchCompat(context: Context): String {
            return getInstance(context).getOrCreateAutoBatch()
        }

        /**
         * Добавляет результат в батч (статический метод для обратной совместимости)
         * @deprecated Используйте инжектируемый экземпляр через Hilt
         */
        @Deprecated("Use injected instance via Hilt instead")
        fun addResultCompat(
            batchId: String,
            fileName: String,
            originalSize: Long,
            compressedSize: Long,
            sizeReduction: Float,
            skipped: Boolean,
            skipReason: String? = null
        ) {
            // Находим любой существующий экземпляр
            staticInstance?.addResult(batchId, fileName, originalSize, compressedSize, sizeReduction, skipped, skipReason)
        }

        /**
         * Завершает батч (статический метод для обратной совместимости)
         * @deprecated Используйте инжектируемый экземпляр через Hilt
         */
        @Deprecated("Use injected instance via Hilt instead")
        fun finalizeBatchCompat(batchId: String) {
            staticInstance?.finalizeBatch(batchId)
        }

        /**
         * Возвращает количество активных батчей (для отладки)
         * @deprecated Используйте инжектируемый экземпляр через Hilt
         */
        @Deprecated("Use injected instance via Hilt instead")
        fun getActiveBatchCountCompat(): Int {
            return staticInstance?.getActiveBatchCount() ?: 0
        }

        /**
         * Очищает все батчи (для тестирования)
         * @deprecated Используйте инжектируемый экземпляр через Hilt
         */
        @Deprecated("Use injected instance via Hilt instead")
        fun clearAllBatchesCompat() {
            staticInstance?.clearAllBatches()
        }
    }

    private val batches = ConcurrentHashMap<String, CompressionBatch>()
    private val mainHandler = sharedMainHandler
    private val batchIdCounter = AtomicInteger(1)

    // Singleton coroutine scope для UI обновлений (требует Main thread)
    private val batchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Константы для автобатчей
    private val AUTO_BATCH_TIMEOUT_MS = 60000L // 60 секунд для завершения автобатча
    private val MAX_BATCHES = 50 // Максимальное количество отслеживаемых батчей

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
    fun createIntentBatch(expectedCount: Int): String {
        val batchId = "intent_batch_${batchIdCounter.getAndIncrement()}_${System.currentTimeMillis()}"
        // Application Context инжектируется через конструктор
        val batch = CompressionBatch(
            batchId = batchId,
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
    fun getOrCreateAutoBatch(): String {
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
        // Application Context инжектируется через конструктор
        val batch = CompressionBatch(
            batchId = batchId,
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
        }
        // Для автобатчей НЕ продлеваем таймаут при добавлении результатов
        // Автобатчи завершаются только по исходному таймауту (60 секунд)
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

        // Используем Application Context из конструктора
        batchScope.launch {
            if (results.size == 1) {
                // Показываем индивидуальный результат
                showIndividualResult(appContext, results[0])
            } else {
                // Показываем групповой результат
                showBatchResult(appContext, results, batch.isIntentBatch)
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
                fileName = FileOperationsUtil.truncateFileName(result.fileName),
                originalSize = result.originalSize,
                compressedSize = result.compressedSize,
                reduction = result.sizeReduction
            )
        }
        
        // Показываем индивидуальное уведомление для 1 файла
        showIndividualNotification(context, result)
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
        
        // Показываем групповой Toast
        showBatchToast(context, successfulResults, skippedCount)
        
        // Показываем групповые уведомления
        showBatchNotifications(context, results, successfulResults, skippedCount)
        
        LogUtil.processDebug("Показан групповой результат: ${results.size} файлов (${successfulResults.size} успешно, $skippedCount пропущено)")
    }
    
    /**
     * Показывает групповой Toast для нескольких файлов
     */
    private fun showBatchToast(context: Context, successfulResults: List<CompressionResult>, skippedCount: Int) {
        // Проверяем настройку перед показом Toast
        val settingsManager = SettingsManager.getInstance(context)
        if (!settingsManager.shouldShowCompressionToast()) {
            LogUtil.debug("CompressionBatchTracker", "Toast о батче сжатия отключен в настройках")
            return
        }

        val message = if (successfulResults.isNotEmpty()) {
            // Считаем общую статистику для Toast
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
    }
    
    /**
     * Показывает групповые уведомления для нескольких файлов
     */
    private fun showBatchNotifications(context: Context, allResults: List<CompressionResult>, successfulResults: List<CompressionResult>, skippedCount: Int) {
        // Считаем общую статистику для уведомлений
        val totalOriginalSize = successfulResults.sumOf { it.originalSize }
        val totalCompressedSize = successfulResults.sumOf { it.compressedSize }
        val totalReduction = if (totalOriginalSize > 0) {
            ((totalOriginalSize - totalCompressedSize).toFloat() / totalOriginalSize) * 100
        } else 0f
        
        // Конвертируем результаты в формат для уведомлений
        val notificationItems = allResults.map { result ->
            NotificationUtil.BatchNotificationItem(
                fileName = FileOperationsUtil.truncateFileName(result.fileName),
                originalSize = result.originalSize,
                compressedSize = result.compressedSize,
                sizeReduction = result.sizeReduction,
                skipped = result.skipped,
                skipReason = result.skipReason
            )
        }
        
        // Показываем групповое уведомление
        NotificationUtil.showBatchCompressionNotification(
            context = context,
            successfulCount = successfulResults.size,
            skippedCount = skippedCount,
            totalOriginalSize = totalOriginalSize,
            totalCompressedSize = totalCompressedSize,
            totalSizeReduction = totalReduction,
            individualResults = notificationItems
        )
    }
    
    /**
     * Показывает индивидуальное уведомление для одного файла
     */
    private fun showIndividualNotification(context: Context, result: CompressionResult) {
        NotificationUtil.showCompressionResultNotification(
            context = context,
            fileName = result.fileName,
            originalSize = result.originalSize,
            compressedSize = result.compressedSize,
            sizeReduction = result.sizeReduction,
            skipped = result.skipped
        )
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

    /**
     * Очищает coroutine scope (должен вызываться при уничтожении приложения)
     */
    fun destroy() {
        batchScope.cancel()
        clearAllBatches()
    }
}
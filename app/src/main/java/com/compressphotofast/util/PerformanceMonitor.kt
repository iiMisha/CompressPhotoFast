package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

/**
 * Монитор производительности для отслеживания эффективности оптимизаций
 * Собирает статистику по времени выполнения различных операций
 */
object PerformanceMonitor {

    // Счетчики производительности
    private val batchMetadataRequests = AtomicInteger(0)
    private val individualMetadataRequests = AtomicInteger(0)
    private val batchMetadataTime = AtomicLong(0)
    private val individualMetadataTime = AtomicLong(0)
    
    private val cacheHits = AtomicInteger(0)
    private val cacheMisses = AtomicInteger(0)
    
    private val processingTimesBySize = ConcurrentHashMap<String, MutableList<Long>>()
    
    // Статистика кэшей
    private val directoryCheckTime = AtomicLong(0)
    private val exifCheckTime = AtomicLong(0)
    private val mimeTypeCheckTime = AtomicLong(0)
    
    // Счетчики оптимизированных операций
    private val optimizedBatchProcessing = AtomicInteger(0)
    private val legacyProcessing = AtomicInteger(0)
    
    private val debouncedBatches = AtomicInteger(0)
    private val immediateProcessing = AtomicInteger(0)
    
    /**
     * Измеряет время выполнения пакетного получения метаданных
     */
    suspend fun <T> measureBatchMetadata(operation: suspend () -> T): T {
        val result: T
        val timeMs = measureTimeMillis {
            result = operation()
        }
        
        batchMetadataRequests.incrementAndGet()
        batchMetadataTime.addAndGet(timeMs)
        
        return result
    }
    
    /**
     * Измеряет время выполнения индивидуального получения метаданных
     */
    suspend fun <T> measureIndividualMetadata(operation: suspend () -> T): T {
        val result: T
        val timeMs = measureTimeMillis {
            result = operation()
        }
        
        individualMetadataRequests.incrementAndGet()
        individualMetadataTime.addAndGet(timeMs)
        
        return result
    }
    
    /**
     * Фиксирует попадание в кэш
     */
    fun recordCacheHit(cacheType: String) {
        cacheHits.incrementAndGet()
        LogUtil.processDebug("PerformanceMonitor: Cache hit - $cacheType")
    }
    
    /**
     * Фиксирует промах кэша
     */
    fun recordCacheMiss(cacheType: String) {
        cacheMisses.incrementAndGet()
        LogUtil.processDebug("PerformanceMonitor: Cache miss - $cacheType")
    }
    
    /**
     * Измеряет время проверки директорий
     */
    suspend fun <T> measureDirectoryCheck(operation: suspend () -> T): T {
        val result: T
        val timeMs = measureTimeMillis {
            result = operation()
        }
        
        directoryCheckTime.addAndGet(timeMs)
        return result
    }
    
    /**
     * Измеряет время проверки EXIF-данных
     */
    suspend fun <T> measureExifCheck(operation: suspend () -> T): T {
        val result: T
        val timeMs = measureTimeMillis {
            result = operation()
        }
        
        exifCheckTime.addAndGet(timeMs)
        return result
    }
    
    /**
     * Измеряет время проверки MIME-типа
     */
    fun <T> measureMimeTypeCheck(operation: () -> T): T {
        val result: T
        val timeMs = measureTimeMillis {
            result = operation()
        }
        
        mimeTypeCheckTime.addAndGet(timeMs)
        return result
    }
    
    /**
     * Записывает время обработки изображения в зависимости от размера
     */
    fun recordProcessingTime(fileSize: Long, processingTimeMs: Long) {
        val sizeCategory = when {
            fileSize < 1024 * 1024 -> "small" // < 1MB
            fileSize < 5 * 1024 * 1024 -> "medium" // 1-5MB
            fileSize < 20 * 1024 * 1024 -> "large" // 5-20MB
            else -> "xlarge" // > 20MB
        }
        
        processingTimesBySize.getOrPut(sizeCategory) { mutableListOf() }.add(processingTimeMs)
    }
    
    /**
     * Увеличивает счетчик оптимизированной обработки
     */
    fun recordOptimizedBatchProcessing() {
        optimizedBatchProcessing.incrementAndGet()
    }
    
    /**
     * Увеличивает счетчик устаревшей обработки
     */
    fun recordLegacyProcessing() {
        legacyProcessing.incrementAndGet()
    }
    
    /**
     * Увеличивает счетчик дебаунс-батчей
     */
    fun recordDebouncedBatch(batchSize: Int) {
        debouncedBatches.incrementAndGet()
        LogUtil.processDebug("PerformanceMonitor: Debounced batch processed with $batchSize items")
    }
    
    /**
     * Увеличивает счетчик немедленной обработки
     */
    fun recordImmediateProcessing() {
        immediateProcessing.incrementAndGet()
    }
    
    /**
     * Получает подробную статистику производительности
     */
    fun getDetailedStats(): String {
        val batchRequests = batchMetadataRequests.get()
        val individualRequests = individualMetadataRequests.get()
        val totalBatchTime = batchMetadataTime.get()
        val totalIndividualTime = individualMetadataTime.get()
        
        val avgBatchTime = if (batchRequests > 0) totalBatchTime / batchRequests else 0
        val avgIndividualTime = if (individualRequests > 0) totalIndividualTime / individualRequests else 0
        
        val hits = cacheHits.get()
        val misses = cacheMisses.get()
        val totalCacheRequests = hits + misses
        val cacheHitRate = if (totalCacheRequests > 0) (hits * 100.0 / totalCacheRequests) else 0.0
        
        val optimizedBatch = optimizedBatchProcessing.get()
        val legacy = legacyProcessing.get()
        val totalProcessing = optimizedBatch + legacy
        val optimizedRate = if (totalProcessing > 0) (optimizedBatch * 100.0 / totalProcessing) else 0.0
        
        val debounced = debouncedBatches.get()
        val immediate = immediateProcessing.get()
        val totalEvents = debounced + immediate
        val debounceEfficiency = if (totalEvents > 0) (debounced * 100.0 / totalEvents) else 0.0
        
        return """
            |=== СТАТИСТИКА ПРОИЗВОДИТЕЛЬНОСТИ ===
            |
            |Получение метаданных:
            |  Пакетные запросы: $batchRequests (среднее время: ${avgBatchTime}ms)
            |  Индивидуальные запросы: $individualRequests (среднее время: ${avgIndividualTime}ms)
            |  Эффективность пакетной обработки: ${if (avgIndividualTime > 0) String.format("%.1f", avgIndividualTime.toDouble() / avgBatchTime * 100) else "N/A"}%
            |
            |Кэширование:
            |  Попадания: $hits
            |  Промахи: $misses
            |  Коэффициент попаданий: ${String.format("%.1f", cacheHitRate)}%
            |  
            |Время проверок:
            |  Директории: ${directoryCheckTime.get()}ms
            |  EXIF: ${exifCheckTime.get()}ms
            |  MIME-типы: ${mimeTypeCheckTime.get()}ms
            |
            |Обработка:
            |  Оптимизированная: $optimizedBatch (${String.format("%.1f", optimizedRate)}%)
            |  Устаревшая: $legacy
            |  
            |Дебаунсинг:
            |  Дебаунснутых батчей: $debounced
            |  Немедленных обработок: $immediate
            |  Эффективность дебаунсинга: ${String.format("%.1f", debounceEfficiency)}%
            |
            |${getProcessingTimesBySize()}
            |
            |${OptimizedCacheUtil.getCacheStats()}
            |${UriProcessingTracker.getCacheStats()}
            |${BatchMediaStoreUtil.getCacheStats()}
        """.trimMargin()
    }
    
    /**
     * Получает статистику времени обработки по размерам файлов
     */
    private fun getProcessingTimesBySize(): String {
        val stats = StringBuilder("Время обработки по размерам:\n")
        
        processingTimesBySize.forEach { (sizeCategory, times) ->
            if (times.isNotEmpty()) {
                val avgTime = times.average()
                val minTime = times.minOrNull() ?: 0
                val maxTime = times.maxOrNull() ?: 0
                val count = times.size
                
                stats.append("  $sizeCategory файлы ($count шт.): среднее ${String.format("%.0f", avgTime)}ms, мин ${minTime}ms, макс ${maxTime}ms\n")
            }
        }
        
        return stats.toString().trimEnd()
    }
    
    /**
     * Получает краткую статистику производительности
     */
    fun getQuickStats(): String {
        val batchRequests = batchMetadataRequests.get()
        val individualRequests = individualMetadataRequests.get()
        val hits = cacheHits.get()
        val misses = cacheMisses.get()
        val totalCacheRequests = hits + misses
        val cacheHitRate = if (totalCacheRequests > 0) (hits * 100.0 / totalCacheRequests) else 0.0
        val optimizedRate = optimizedBatchProcessing.get()
        val debouncedRate = debouncedBatches.get()
        
        return "PerformanceMonitor: пакетные/индивид=$batchRequests/$individualRequests, кэш=${String.format("%.0f", cacheHitRate)}%, оптимизированных=$optimizedRate, дебаунсов=$debouncedRate"
    }
    
    /**
     * Сбрасывает все счетчики статистики
     */
    fun resetStats() {
        batchMetadataRequests.set(0)
        individualMetadataRequests.set(0)
        batchMetadataTime.set(0)
        individualMetadataTime.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
        directoryCheckTime.set(0)
        exifCheckTime.set(0)
        mimeTypeCheckTime.set(0)
        optimizedBatchProcessing.set(0)
        legacyProcessing.set(0)
        debouncedBatches.set(0)
        immediateProcessing.set(0)
        processingTimesBySize.clear()
        
        LogUtil.processDebug("PerformanceMonitor: статистика сброшена")
    }
    
    /**
     * Вычисляет экономию времени от оптимизаций
     */
    fun calculateOptimizationSavings(): String {
        val batchRequests = batchMetadataRequests.get()
        val individualRequests = individualMetadataRequests.get()
        val totalBatchTime = batchMetadataTime.get()
        val totalIndividualTime = individualMetadataTime.get()
        
        if (batchRequests == 0 || individualRequests == 0) {
            return "Недостаточно данных для расчета экономии"
        }
        
        val avgBatchTime = totalBatchTime.toDouble() / batchRequests
        val avgIndividualTime = totalIndividualTime.toDouble() / individualRequests
        
        // Сколько времени заняли бы пакетные запросы если бы они делались индивидуально
        val estimatedIndividualTime = batchRequests * avgIndividualTime
        val actualBatchTime = totalBatchTime
        val timeSaved = estimatedIndividualTime - actualBatchTime
        val percentSaved = (timeSaved / estimatedIndividualTime) * 100
        
        return """
            |Экономия от пакетной обработки:
            |  Фактическое время пакетных запросов: ${totalBatchTime}ms
            |  Оценочное время для индивидуальных запросов: ${estimatedIndividualTime.toLong()}ms
            |  Сэкономлено времени: ${timeSaved.toLong()}ms (${String.format("%.1f", percentSaved)}%)
        """.trimMargin()
    }
    
    /**
     * Создает отчет о производительности в формате, удобном для логирования
     */
    fun generatePerformanceReport(context: Context): String {
        return """
            |
            |╔══════════════════════════════════════════════════════════════════
            |║                     ОТЧЕТ О ПРОИЗВОДИТЕЛЬНОСТИ
            |╠══════════════════════════════════════════════════════════════════
            |║ ${getQuickStats()}
            |║
            |║ ${calculateOptimizationSavings().replace("\n", "\n║ ")}
            |║
            |║ Кэши:
            |║ ${OptimizedCacheUtil.getCacheStats()}
            |║ ${UriProcessingTracker.getCacheStats()}
            |║
            |║ Память: ${getMemoryInfo()}
            |╚══════════════════════════════════════════════════════════════════
        """.trimMargin()
    }
    
    /**
     * Получает информацию об использовании памяти
     */
    private fun getMemoryInfo(): String {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory() / 1024 / 1024
        val freeMemory = runtime.freeMemory() / 1024 / 1024
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        
        return "используется ${usedMemory}MB/${maxMemory}MB (свободно: ${freeMemory}MB)"
    }
    
    /**
     * Автоматически выводит отчет о производительности в лог каждые N операций
     */
    fun autoReportIfNeeded(context: Context) {
        val totalOperations = batchMetadataRequests.get() + individualMetadataRequests.get()
        
        // Выводим отчет каждые 100 операций
        if (totalOperations > 0 && totalOperations % 100 == 0) {
            LogUtil.processDebug(generatePerformanceReport(context))
        }
        
        // Выводим краткую статистику каждые 50 операций
        if (totalOperations > 0 && totalOperations % 50 == 0) {
            LogUtil.processDebug(getQuickStats())
        }
    }
}
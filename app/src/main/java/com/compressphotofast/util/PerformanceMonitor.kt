package com.compressphotofast.util

import android.content.Context
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
    private val batchMetadataTime = AtomicLong(0)

    private val cacheHits = AtomicInteger(0)
    private val cacheMisses = AtomicInteger(0)

    private val exifCheckTime = AtomicLong(0)

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
     * Фиксирует попадание в кэш
     */
    fun recordCacheHit(cacheType: String) {
        cacheHits.incrementAndGet()
    }

    /**
     * Фиксирует промах кэша
     */
    fun recordCacheMiss(cacheType: String) {
        cacheMisses.incrementAndGet()
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
     * Получает подробную статистику производительности
     */
    fun getDetailedStats(context: Context): String {
        val batchRequests = batchMetadataRequests.get()
        val avgBatchTime = if (batchRequests > 0) batchMetadataTime.get() / batchRequests else 0

        val hits = cacheHits.get()
        val misses = cacheMisses.get()
        val totalCacheRequests = hits + misses
        val cacheHitRate = if (totalCacheRequests > 0) (hits * 100.0 / totalCacheRequests) else 0.0

        val uriTrackerStats = UriProcessingTracker.getInstance(context).getCacheStats()

        return """
            |=== СТАТИСТИКА ПРОИЗВОДИТЕЛЬНОСТИ ===
            |
            |Получение метаданных:
            |  Пакетные запросы: $batchRequests (среднее время: ${avgBatchTime}ms)
            |
            |Кэширование:
            |  Попадания: $hits
            |  Промахи: $misses
            |  Коэффициент попаданий: ${String.format("%.1f", cacheHitRate)}%
            |
            |Время проверок:
            |  EXIF: ${exifCheckTime.get()}ms
            |
            |${OptimizedCacheUtil.getCacheStats()}
            |$uriTrackerStats
            |${BatchMediaStoreUtil.getCacheStats()}
        """.trimMargin()
    }

    /**
     * Получает краткую статистику производительности
     */
    fun getQuickStats(): String {
        val hits = cacheHits.get()
        val misses = cacheMisses.get()
        val totalCacheRequests = hits + misses
        val cacheHitRate = if (totalCacheRequests > 0) (hits * 100.0 / totalCacheRequests) else 0.0

        return "PerformanceMonitor: пакетные=${batchMetadataRequests.get()}, кэш=${String.format("%.0f", cacheHitRate)}%"
    }

    /**
     * Сбрасывает все счетчики статистики (для изоляции тестов)
     */
    fun resetStats() {
        batchMetadataRequests.set(0)
        batchMetadataTime.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
        exifCheckTime.set(0)
    }

    /**
     * Создает отчет о производительности в формате, удобном для логирования
     */
    fun generatePerformanceReport(context: Context): String {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory() / 1024 / 1024
        val freeMemory = runtime.freeMemory() / 1024 / 1024
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory() / 1024 / 1024

        return """
            |╔═════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════
            |║
            |║  ОТЧЕТ О ПРОИЗВОДИТЕЛЬНОСТИ
            |║
            |║${getDetailedStats(context)}
            |║
            |║  Использование памяти: ${usedMemory}MB/${maxMemory}MB (свободно: ${freeMemory}MB)
            |║
            |╚═══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════
        """.trimMargin()
    }

    /**
     * Автоматически выводит отчет о производительности в лог каждые N операций
     */
    fun autoReportIfNeeded(context: Context) {
        val totalOperations = batchMetadataRequests.get()

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

package com.compressphotofast.util

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * Класс для централизованного отслеживания обрабатываемых URI изображений
 */
object UriProcessingTracker {
    // Статический набор URI для синхронизации между сервисами
    private val processingUris = Collections.synchronizedSet(HashSet<String>())
    
    // Игнорировать изменения в MediaStore на указанное время после удачного сжатия (мс)
    private val ignoreMediaStoreChangesAfterCompression = 5000L // 5 секунд
    private val ignoreChangesUntil = ConcurrentHashMap<String, Long>()

    /**
     * Проверяет, обрабатывается ли изображение в данный момент
     */
    fun isImageBeingProcessed(uri: String): Boolean {
        return processingUris.contains(uri)
    }

    /**
     * Добавляет URI в список обрабатываемых
     */
    fun addProcessingUri(uri: String) {
        processingUris.add(uri)
        Timber.d("URI добавлен в список обрабатываемых: $uri (всего ${processingUris.size})")
    }
    
    /**
     * Удаляет URI из списка обрабатываемых и возвращает true, если URI был найден и удален
     */
    fun removeProcessingUri(uri: String): Boolean {
        val removed = processingUris.remove(uri)
        Timber.d("URI был ${if (removed) "успешно удалён" else "не найден"} в списке обрабатываемых: $uri (всего ${processingUris.size})")
        return removed
    }
    
    /**
     * Устанавливает период игнорирования изменений для URI
     */
    fun setIgnorePeriod(uri: String, durationMs: Long = ignoreMediaStoreChangesAfterCompression) {
        ignoreChangesUntil[uri] = System.currentTimeMillis() + durationMs
        Timber.d("URI $uri будет игнорироваться в течение ${durationMs}мс")
    }
    
    /**
     * Проверяет, должен ли URI игнорироваться в настоящее время
     */
    fun shouldIgnoreUri(uri: String): Boolean {
        val ignoreUntil = ignoreChangesUntil[uri]
        return ignoreUntil != null && System.currentTimeMillis() < ignoreUntil
    }
    
    /**
     * Очищает период игнорирования для URI
     */
    fun clearIgnorePeriod(uri: String) {
        ignoreChangesUntil.remove(uri)
    }
    
    /**
     * Получает количество обрабатываемых URI
     */
    fun getProcessingCount(): Int {
        return processingUris.size
    }
} 
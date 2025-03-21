package com.compressphotofast.util

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * Утилитарный класс для отслеживания обрабатываемых URI
 * Предотвращает дублирование обработки и отслеживает состояние URI
 */
object UriProcessingTracker {

    // Множество URI, которые в данный момент обрабатываются
    private val processingUris = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    
    // Множество URI, которые недавно были обработаны (для предотвращения повторной обработки)
    private val recentlyProcessedUris = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    
    // Карта для отслеживания времени игнорирования URI
    private val ignoreUrisUntil = ConcurrentHashMap<String, Long>()
    
    // Время последней очистки recentlyProcessedUris
    private var lastCleanupTime = System.currentTimeMillis()
    
    // Интервал очистки (1 час)
    private const val CLEANUP_INTERVAL = 3600000L
    
    // Максимальное количество URI в recentlyProcessedUris
    private const val MAX_RECENT_URIS = 1000
    
    // Время истечения для недавно обработанных URI (10 минут)
    private val RECENTLY_PROCESSED_EXPIRATION = 10 * 60 * 1000L
    
    // Время игнорирования URI после обработки (5 секунд)
    private const val IGNORE_PERIOD = 5000L
    
    /**
     * Добавляет URI в список обрабатываемых
     * @param uriString URI для добавления
     */
    fun addProcessingUri(uriString: String) {
        processingUris.add(uriString)
        Timber.d("URI добавлен в список обрабатываемых: $uriString (всего: ${processingUris.size})")
    }
    
    /**
     * Удаляет URI из списка обрабатываемых и добавляет его в список недавно обработанных
     * @param uriString URI для удаления
     * @return true если URI был удален, false если его не было в списке
     */
    fun removeProcessingUri(uriString: String): Boolean {
        val removed = processingUris.remove(uriString)
        // Добавляем в список недавно обработанных
        if (removed) {
            addRecentlyProcessedUri(uriString)
        }
        Timber.d("URI удален из списка обрабатываемых: $uriString (осталось: ${processingUris.size})")
        return removed
    }
    
    /**
     * Добавляет URI в список недавно обработанных
     * @param uriString URI для добавления
     */
    fun addRecentlyProcessedUri(uriString: String) {
        // Проверяем, не нужно ли очистить список
        checkAndCleanRecentList()
        
        // Добавляем в список с временной меткой
        recentlyProcessedUris.add("$uriString:${System.currentTimeMillis()}")
        Timber.d("URI добавлен в список недавно обработанных: $uriString")
    }
    
    /**
     * Проверяет, не обрабатывается ли уже изображение с заданным URI
     * @param uriString URI для проверки
     * @return true если URI уже обрабатывается, false в противном случае
     */
    fun isImageBeingProcessed(uriString: String): Boolean {
        return processingUris.contains(uriString)
    }
    
    /**
     * Возвращает количество URI, которые в настоящее время обрабатываются
     * @return Количество обрабатываемых URI
     */
    fun getProcessingCount(): Int {
        return processingUris.size
    }
    
    /**
     * Устанавливает период игнорирования для URI
     * @param uriString URI для игнорирования
     * @param timeMs Время в миллисекундах (если не указано, используется IGNORE_PERIOD)
     */
    fun setIgnorePeriod(uriString: String, timeMs: Long = IGNORE_PERIOD) {
        val ignoreUntil = System.currentTimeMillis() + timeMs
        ignoreUrisUntil[uriString] = ignoreUntil
        Timber.d("Установлен период игнорирования для URI: $uriString до ${java.util.Date(ignoreUntil)}")
    }
    
    /**
     * Проверяет, не следует ли игнорировать заданный URI (был недавно обработан)
     * @param uriString URI для проверки
     * @return true если URI следует игнорировать, false в противном случае
     */
    fun shouldIgnoreUri(uriString: String): Boolean {
        // Проверяем период игнорирования
        val ignoreUntil = ignoreUrisUntil[uriString]
        if (ignoreUntil != null && System.currentTimeMillis() < ignoreUntil) {
            Timber.d("URI в периоде игнорирования: $uriString")
            return true
        }
        
        // Очистка устаревших записей
        checkAndCleanRecentList()
        
        // Проверяем по префиксу (исключая временную метку)
        val currentTime = System.currentTimeMillis()
        val isRecent = recentlyProcessedUris.any { 
            val entry = it.split(":")
            if (entry.size >= 2) {
                val uri = entry[0]
                val timestamp = entry[1].toLongOrNull() ?: 0L
                val isMatch = uri == uriString
                val isNotExpired = (currentTime - timestamp) < RECENTLY_PROCESSED_EXPIRATION
                isMatch && isNotExpired
            } else {
                false
            }
        }
        
        return isRecent
    }
    
    /**
     * Проверяет, нужно ли очистить список недавно обработанных URI
     */
    private fun checkAndCleanRecentList() {
        val currentTime = System.currentTimeMillis()
        
        // Если прошло достаточно времени с последней очистки или список слишком большой
        if (currentTime - lastCleanupTime > CLEANUP_INTERVAL || recentlyProcessedUris.size > MAX_RECENT_URIS) {
            cleanupRecentlyProcessedUris()
            lastCleanupTime = currentTime
        }
    }
    
    /**
     * Очищает устаревшие записи из списка недавно обработанных URI
     */
    private fun cleanupRecentlyProcessedUris() {
        val currentTime = System.currentTimeMillis()
        val expiredUris = recentlyProcessedUris.filter { 
            val entry = it.split(":")
            if (entry.size >= 2) {
                val timestamp = entry[1].toLongOrNull() ?: 0L
                (currentTime - timestamp) > RECENTLY_PROCESSED_EXPIRATION
            } else {
                true // Удаляем записи неправильного формата
            }
        }
        
        recentlyProcessedUris.removeAll(expiredUris)
        
        // Также очищаем просроченные периоды игнорирования
        val expiredIgnorePeriods = ignoreUrisUntil.entries.filter {
            it.value < currentTime
        }.map { it.key }
        
        expiredIgnorePeriods.forEach { ignoreUrisUntil.remove(it) }
        
        Timber.d("Очищены устаревшие URI из списка недавно обработанных (удалено: ${expiredUris.size}, осталось: ${recentlyProcessedUris.size})")
        Timber.d("Очищены истекшие периоды игнорирования (удалено: ${expiredIgnorePeriods.size}, осталось: ${ignoreUrisUntil.size})")
    }
    
    /**
     * Очищает все списки (для тестирования или сброса)
     */
    fun clearAll() {
        processingUris.clear()
        recentlyProcessedUris.clear()
        ignoreUrisUntil.clear()
        Timber.d("Все списки отслеживания URI очищены")
    }
} 
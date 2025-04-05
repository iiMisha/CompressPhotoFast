package com.compressphotofast.util

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import androidx.annotation.VisibleForTesting
import android.net.Uri
import com.compressphotofast.util.LogUtil

/**
 * Утилитарный класс для отслеживания обрабатываемых URI
 * Предотвращает дублирование обработки и отслеживает состояние URI
 */
object UriProcessingTracker {

    // Множество URI, которые в данный момент обрабатываются
    private val processingUris = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    
    // Карта URI с временными метками, которые недавно были обработаны
    private val recentlyProcessedUris = ConcurrentHashMap<String, Long>()
    
    // Карта для отслеживания времени игнорирования URI
    private val ignoreUrisUntil = ConcurrentHashMap<String, Long>()
    
    // Время последней очистки recentlyProcessedUris
    private var lastCleanupTime = System.currentTimeMillis()
    
    // Интервал очистки (1 час)
    private const val CLEANUP_INTERVAL = 3600000L
    
    // Максимальное количество URI в recentlyProcessedUris
    private const val MAX_RECENT_URIS = 1000
    
    // Время истечения для недавно обработанных URI (10 минут)
    private const val RECENTLY_PROCESSED_EXPIRATION = 10 * 60 * 1000L
    
    // Время игнорирования URI после обработки (5 секунд)
    private const val IGNORE_PERIOD = 5000L
    
    /**
     * Добавляет URI в список обрабатываемых
     */
    fun addProcessingUri(uri: Uri) {
        val uriString = uri.toString()
        processingUris.add(uriString)
        LogUtil.processDebug("URI добавлен в список обрабатываемых: $uriString (всего: ${processingUris.size})")
    }
    
    /**
     * Проверяет, находится ли URI в списке обрабатываемых
     */
    fun isProcessing(uri: Uri): Boolean {
        return processingUris.contains(uri.toString())
    }
    
    /**
     * Удаляет URI из списка обрабатываемых
     */
    fun removeProcessingUri(uri: Uri) {
        val uriString = uri.toString()
        processingUris.remove(uriString)
        LogUtil.processDebug("URI удален из списка обрабатываемых: $uriString (осталось: ${processingUris.size})")
    }
    
    /**
     * Добавляет URI в список недавно обработанных
     */
    fun addRecentlyProcessedUri(uri: Uri) {
        val uriString = uri.toString()
        val timestamp = System.currentTimeMillis()
        recentlyProcessedUris[uriString] = timestamp
        LogUtil.processDebug("URI добавлен в список недавно обработанных: $uriString")
    }
    
    /**
     * Проверяет, был ли URI недавно обработан
     */
    fun wasRecentlyProcessed(uri: Uri): Boolean {
        val uriString = uri.toString()
        val timestamp = recentlyProcessedUris[uriString] ?: return false
        
        val now = System.currentTimeMillis()
        val elapsedTime = now - timestamp
        
        // Если прошло меньше времени, чем период кэширования, считаем URI недавно обработанным
        return elapsedTime < RECENTLY_PROCESSED_EXPIRATION
    }
    
    /**
     * Устанавливает период игнорирования для URI до указанного времени
     */
    fun setIgnoreUntil(uri: Uri, ignoreUntil: Long) {
        val uriString = uri.toString()
        ignoreUrisUntil[uriString] = ignoreUntil
        LogUtil.processDebug("Установлен период игнорирования для URI: $uriString до ${java.util.Date(ignoreUntil)}")
    }
    
    /**
     * Устанавливает стандартный период игнорирования для URI
     */
    fun setIgnorePeriod(uri: Uri) {
        val ignoreUntil = System.currentTimeMillis() + IGNORE_PERIOD
        setIgnoreUntil(uri, ignoreUntil)
    }
    
    /**
     * Проверяет, должен ли URI игнорироваться в данный момент
     */
    fun shouldIgnore(uri: Uri): Boolean {
        val uriString = uri.toString()
        val ignoreUntil = ignoreUrisUntil[uriString] ?: return false
        
        // Если текущее время меньше, чем время окончания периода игнорирования, URI игнорируется
        val now = System.currentTimeMillis()
        val shouldIgnore = now < ignoreUntil
        
        if (shouldIgnore) {
            LogUtil.processDebug("URI в периоде игнорирования: $uriString")
        }
        
        return shouldIgnore
    }
    
    /**
     * Возвращает количество URI, которые в настоящее время обрабатываются
     * @return Количество обрабатываемых URI
     */
    fun getProcessingCount(): Int {
        return processingUris.size
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
            LogUtil.processDebug("URI в периоде игнорирования: $uriString")
            return true
        }
        
        // Очистка устаревших записей
        checkAndCleanRecentList()
        
        // Проверяем, есть ли URI в списке недавно обработанных
        val timestamp = recentlyProcessedUris[uriString]
        if (timestamp != null) {
            val currentTime = System.currentTimeMillis()
            val isNotExpired = (currentTime - timestamp) < RECENTLY_PROCESSED_EXPIRATION
            if (isNotExpired) {
                return true
            }
        }
        
        return false
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
     * Очищает устаревшие URI из списка недавно обработанных
     */
    private fun cleanupRecentlyProcessedUris() {
        val now = System.currentTimeMillis()
        val expiredUris = mutableListOf<String>()
        
        // Находим устаревшие URI
        for ((uri, timestamp) in recentlyProcessedUris) {
            if (now - timestamp > RECENTLY_PROCESSED_EXPIRATION) {
                expiredUris.add(uri)
            }
        }
        
        // Удаляем их из списка
        for (uri in expiredUris) {
            recentlyProcessedUris.remove(uri)
        }
        
        LogUtil.processDebug("Очищены устаревшие URI: удалено ${expiredUris.size}, осталось ${recentlyProcessedUris.size}")
    }
    
    /**
     * Очищает устаревшие URI из списка недавно обработанных и истекшие периоды игнорирования
     */
    fun cleanupStaleEntries() {
        val now = System.currentTimeMillis()
        
        // Очищаем устаревшие URI из списка недавно обработанных
        val expiredUris = mutableListOf<String>()
        for (entry in recentlyProcessedUris.entries) {
            val uri = entry.key
            val timestamp = entry.value
            if (now - timestamp > RECENTLY_PROCESSED_EXPIRATION) {
                expiredUris.add(uri)
            }
        }
        
        for (uri in expiredUris) {
            recentlyProcessedUris.remove(uri)
        }
        
        // Очищаем истекшие периоды игнорирования
        val expiredIgnorePeriods = mutableListOf<String>()
        for (entry in ignoreUrisUntil.entries) {
            val uri = entry.key
            val ignoreUntil = entry.value
            if (now >= ignoreUntil) {
                expiredIgnorePeriods.add(uri)
            }
        }
        
        for (uri in expiredIgnorePeriods) {
            ignoreUrisUntil.remove(uri)
        }
        
        LogUtil.processDebug("Очищены устаревшие URI из списка недавно обработанных (удалено: ${expiredUris.size}, осталось: ${recentlyProcessedUris.size})")
        LogUtil.processDebug("Очищены истекшие периоды игнорирования (удалено: ${expiredIgnorePeriods.size}, осталось: ${ignoreUrisUntil.size})")
    }
    
    /**
     * Сбрасывает все списки отслеживания URI
     */
    fun resetAll() {
        processingUris.clear()
        recentlyProcessedUris.clear()
        ignoreUrisUntil.clear()
        LogUtil.processDebug("Все списки отслеживания URI очищены")
    }
    
    /**
     * Проверяет, обрабатывается ли в данный момент изображение с указанным URI
     */
    fun isImageBeingProcessed(uri: Uri): Boolean {
        return isProcessing(uri) || shouldIgnore(uri) || wasRecentlyProcessed(uri)
    }
} 
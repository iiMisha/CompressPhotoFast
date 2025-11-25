package com.compressphotofast.util

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import android.content.Context
import android.net.Uri
import com.compressphotofast.util.LogUtil

/**
 * Утилитарный класс для отслеживания обрабатываемых URI
 * Предотвращает дублирование обработки и отслеживает состояние URI
 */
object UriProcessingTracker {

    // Множество URI, которые в данный момент обрабатываются
    private val processingUris = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    
    // Контекст для доступа к UriUtil (инжектится через инициализацию)
    private var context: Context? = null
    
    // Множество URI, которые недоступны (не существуют)
    private val unavailableUris = ConcurrentHashMap<String, Long>()
    
    // Время истечения для недоступных URI (10 минут)
    private const val UNAVAILABLE_URI_EXPIRATION = 10 * 60 * 1000L
    
    fun initialize(context: Context) {
        this.context = context
    }
    
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
        return isUriRecentlyProcessed(uri.toString())
    }
    
    /**
     * Проверяет, был ли URI недавно обработан по строковому представлению
     */
    private fun isUriRecentlyProcessed(uriString: String): Boolean {
        val timestamp = recentlyProcessedUris[uriString] ?: return false
        val now = System.currentTimeMillis()
        return (now - timestamp) < RECENTLY_PROCESSED_EXPIRATION
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
        return shouldIgnoreUri(uri.toString())
    }
    
    /**
     * Возвращает количество URI, которые в настоящее время обрабатываются
     * @return Количество обрабатываемых URI
     */
    fun getProcessingCount(): Int {
        return processingUris.size
    }
    
    /**
     * Проверяет, не следует ли игнорировать заданный URI (был недавно обработан или в периоде игнорирования)
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
        return isUriRecentlyProcessed(uriString)
    }
    
    /**
     * Проверяет, нужно ли очистить список недавно обработанных URI
     */
    private fun checkAndCleanRecentList() {
        val currentTime = System.currentTimeMillis()
        
        // Если прошло достаточно времени с последней очистки или список слишком большой
        if (currentTime - lastCleanupTime > CLEANUP_INTERVAL || recentlyProcessedUris.size > MAX_RECENT_URIS) {
            cleanupStaleEntries()
            lastCleanupTime = currentTime
        }
    }
    
    /**
     * Очищает устаревшие URI из списка недавно обработанных и истекшие периоды игнорирования
     */
    fun cleanupStaleEntries() {
        val now = System.currentTimeMillis()
        
        // Очищаем устаревшие URI из списка недавно обработанных
        val expiredUris = mutableListOf<String>()
        for ((uri, timestamp) in recentlyProcessedUris) {
            if (now - timestamp > RECENTLY_PROCESSED_EXPIRATION) {
                expiredUris.add(uri)
            }
        }
        
        for (uri in expiredUris) {
            recentlyProcessedUris.remove(uri)
        }
        
        // Очищаем истекшие периоды игнорирования
        val expiredIgnorePeriods = mutableListOf<String>()
        for ((uri, ignoreUntil) in ignoreUrisUntil) {
            if (now >= ignoreUntil) {
                expiredIgnorePeriods.add(uri)
            }
        }
        
        for (uri in expiredIgnorePeriods) {
            ignoreUrisUntil.remove(uri)
        }
        
        // Очищаем устаревшие недоступные URI
        val expiredUnavailableUris = mutableListOf<String>()
        for ((uri, timestamp) in unavailableUris) {
            if (now - timestamp > UNAVAILABLE_URI_EXPIRATION) {
                expiredUnavailableUris.add(uri)
            }
        }
        
        for (uri in expiredUnavailableUris) {
            unavailableUris.remove(uri)
            LogUtil.processDebug("Устаревший URI удален из списка недоступных: $uri")
        }
        
        LogUtil.processDebug("Очищены устаревшие URI из списка недавно обработанных (удалено: ${expiredUris.size}, осталось: ${recentlyProcessedUris.size})")
        LogUtil.processDebug("Очищены истекшие периоды игнорирования (удалено: ${expiredIgnorePeriods.size}, осталось: ${ignoreUrisUntil.size})")
        LogUtil.processDebug("Очищены устаревшие недоступные URI (удалено: ${expiredUnavailableUris.size}, осталось: ${unavailableUris.size})")
    }
    
    /**
     * Сбрасывает все списки отслеживания URI
     */
    fun resetAll() {
        processingUris.clear()
        recentlyProcessedUris.clear()
        ignoreUrisUntil.clear()
        unavailableUris.clear()
        LogUtil.processDebug("Все списки отслеживания URI очищены")
    }
    
    /**
     * Проверяет, обрабатывается ли в данный момент изображение с указанным URI
     * Расширенная проверка с учетом всех состояний
     */
    fun isImageBeingProcessed(uri: Uri): Boolean {
        val uriString = uri.toString()
        val isProcessing = processingUris.contains(uriString)
        val isIgnored = shouldIgnoreUri(uriString)
        val isRecentlyProcessed = isUriRecentlyProcessed(uriString)
        
        // Дополнительная проверка: если файл был обработан совсем недавно, но уже вышел из кэша,
        // все равно временно игнорируем его
        if (!isRecentlyProcessed && !isIgnored && !isProcessing) {
            // Проверяем время модификации файла - если он был изменен совсем недавно,
            // возможно он все еще находится в процессе обработки другим процессом
            try {
                val appContext = context ?: return isProcessing || isIgnored || isRecentlyProcessed
                val lastModified = kotlinx.coroutines.runBlocking {
                    UriUtil.getFileLastModified(appContext, uri)
                }
                val currentTime = System.currentTimeMillis()
                if (lastModified > 0 && (currentTime - lastModified < 5000)) { // 5 секунд
                    return true
                }
            } catch (e: Exception) {
                // Если не удается получить время модификации, продолжаем стандартную проверку
            }
        }
        
        return isProcessing || isIgnored || isRecentlyProcessed
    }
    
    /**
     * Добавляет URI в список обрабатываемых с дополнительной информацией
     */
    fun addProcessingUriWithDetails(uri: Uri, source: String = "unknown") {
        val uriString = uri.toString()
        processingUris.add(uriString)
        LogUtil.processDebug("URI добавлен в список обрабатываемых из $source: $uriString (всего: ${processingUris.size})")
    }
    
    /**
     * Проверяет, обрабатывается ли в данный момент изображение с указанным URI
     * Расширенная проверка с учетом времени модификации файла
     */
    fun isImageBeingProcessed(uri: Uri, fileModifiedDate: Long = 0): Boolean {
        val uriString = uri.toString()
        val isProcessing = processingUris.contains(uriString)
        val isIgnored = shouldIgnoreUri(uriString)
        val isRecentlyProcessed = isUriRecentlyProcessed(uriString)
        
        // Если передано время модификации файла, используем его для дополнительной проверки
        if (fileModifiedDate > 0) {
            val currentTime = System.currentTimeMillis()
            // Если файл был изменен совсем недавно (в течение 5 секунд),
            // возможно он все еще находится в процессе обработки другим процессом
            if (currentTime - fileModifiedDate < 5000L) {
                return true
            }
        } else {
            // Дополнительная проверка: если файл был обработан совсем недавно, но уже вышел из кэша,
            // все равно временно игнорируем его
            if (!isRecentlyProcessed && !isIgnored && !isProcessing) {
                // Проверяем время модификации файла - если он был изменен совсем недавно,
                // возможно он все еще находится в процессе обработки другим процессом
                try {
                    val appContext = context ?: return isProcessing || isIgnored || isRecentlyProcessed
                    val lastModified = kotlinx.coroutines.runBlocking {
                        UriUtil.getFileLastModified(appContext, uri)
                    }
                    val currentTime = System.currentTimeMillis()
                    if (lastModified > 0 && (currentTime - lastModified < 5000L)) { // 5 секунд
                        return true
                    }
                } catch (e: Exception) {
                    // Если не удается получить время модификации, продолжаем стандартную проверку
                }
            }
        }
        
        return isProcessing || isIgnored || isRecentlyProcessed
    }
    
    /**
     * Получает статистику кэшей для мониторинга производительности
     */
    fun getCacheStats(): String {
        return "UriTracker: обрабатывается ${processingUris.size}, недавно обработано ${recentlyProcessedUris.size}, игнорируется ${ignoreUrisUntil.size}, недоступно ${unavailableUris.size}"
    }
    
    /**
     * Помечает URI как недоступный
     */
    fun markUriUnavailable(uri: Uri) {
        val uriString = uri.toString()
        unavailableUris[uriString] = System.currentTimeMillis()
        LogUtil.processDebug("URI помечен как недоступный: $uriString")
    }
    
    /**
     * Проверяет, является ли URI недоступным
     */
    fun isUriUnavailable(uri: Uri): Boolean {
        val uriString = uri.toString()
        
        // Очищаем устаревшие записи перед проверкой
        cleanupStaleUnavailableEntries()
        
        return unavailableUris.containsKey(uriString)
    }
    
    /**
     * Очищает устаревшие записи недоступных URI
     */
    private fun cleanupStaleUnavailableEntries() {
        val now = System.currentTimeMillis()
        val expiredUris = mutableListOf<String>()

        for ((uri, timestamp) in unavailableUris) {
            if (now - timestamp > UNAVAILABLE_URI_EXPIRATION) {
                expiredUris.add(uri)
            }
        }

        for (uri in expiredUris) {
            unavailableUris.remove(uri)
            LogUtil.processDebug("Устаревший URI удален из списка недоступных: $uri")
        }
    }

    /**
     * Атомарно удаляет URI из обработки, выполняет действие и добавляет в недавно обработанные
     * Безопасная обертка для предотвращения гонок состояний
     */
    suspend fun <T> safelyProcessAfterRemoval(
        uri: Uri,
        action: suspend () -> T
    ): T {
        removeProcessingUri(uri)
        return try {
            action()
        } finally {
            addRecentlyProcessedUri(uri)
        }
    }
}
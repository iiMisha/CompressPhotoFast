package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Централизованный менеджер для отслеживания URI и их обработки.
 * Объединяет функциональность из UriProcessingTracker, StatsTracker и ImageProcessingTracker.
 */
object UriTrackingManager {
    // Константы статусов обработки
    const val PROCESSING_STATUS_NONE = "none"
    const val PROCESSING_STATUS_PROCESSING = "processing"
    const val PROCESSING_STATUS_COMPLETED = "completed"
    const val PROCESSING_STATUS_FAILED = "failed"
    const val PROCESSING_STATUS_SKIPPED = "skipped"
    
    // Время игнорирования URI после обработки (5 секунд)
    private const val DEFAULT_IGNORE_PERIOD = 5000L
    
    // Время истечения для недавно обработанных URI (10 минут)
    private const val RECENTLY_PROCESSED_EXPIRATION = 10 * 60 * 1000L
    
    // Интервал очистки (1 час)
    private const val CLEANUP_INTERVAL = 3600000L
    
    // Максимальное количество URI в списке недавно обработанных
    private const val MAX_RECENT_URIS = 1000
    
    // Множество URI, которые в данный момент обрабатываются
    private val processingUris = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    
    // Множество URI, которые в данный момент обрабатываются в MainActivity
    private val mainActivityProcessingUris = Collections.synchronizedSet(HashSet<String>())
    
    // Множество URI, которые недавно были обработаны
    private val recentlyProcessedUris = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    
    // Карта для отслеживания времени игнорирования URI
    private val ignoreUrisUntil = ConcurrentHashMap<String, Long>()
    
    // Map для хранения статусов обработки
    private val processingStatus = Collections.synchronizedMap(HashMap<String, String>())
    
    // Время последней очистки
    private var lastCleanupTime = System.currentTimeMillis()
    
    /**
     * Добавляет URI в список обрабатываемых
     * @param uri URI для добавления
     */
    fun addProcessingUri(uri: Uri) {
        val uriString = uri.toString()
        processingUris.add(uriString)
        updateStatus(uri, PROCESSING_STATUS_PROCESSING)
        Timber.d("URI добавлен в список обрабатываемых: $uriString (всего: ${processingUris.size})")
    }
    
    /**
     * Удаляет URI из списка обрабатываемых
     * @param uri URI для удаления
     * @param status Новый статус обработки (COMPLETED, FAILED, SKIPPED)
     * @return true если URI был удален, false если его не было в списке
     */
    fun removeProcessingUri(uri: Uri, status: String = PROCESSING_STATUS_COMPLETED): Boolean {
        val uriString = uri.toString()
        val removed = processingUris.remove(uriString)
        
        if (removed) {
            // Добавляем в список недавно обработанных
            addRecentlyProcessedUri(uri)
            // Обновляем статус
            updateStatus(uri, status)
        }
        
        Timber.d("URI удален из списка обрабатываемых со статусом $status: $uriString (осталось: ${processingUris.size})")
        return removed
    }
    
    /**
     * Добавляет URI в список обрабатываемых в MainActivity
     * @param uri URI для добавления
     */
    fun addUriToMainActivityProcessing(uri: Uri) {
        val uriString = uri.toString()
        mainActivityProcessingUris.add(uriString)
        Timber.d("URI добавлен в список обрабатываемых в MainActivity: $uriString")
    }
    
    /**
     * Удаляет URI из списка обрабатываемых в MainActivity
     * @param uri URI для удаления
     */
    fun removeUriFromMainActivityProcessing(uri: Uri) {
        val uriString = uri.toString()
        mainActivityProcessingUris.remove(uriString)
        Timber.d("URI удален из списка обрабатываемых в MainActivity: $uriString")
    }
    
    /**
     * Проверяет, обрабатывается ли URI в MainActivity
     * @param uri URI для проверки
     * @return true если URI обрабатывается в MainActivity
     */
    fun isUriBeingProcessedByMainActivity(uri: Uri): Boolean {
        val uriString = uri.toString()
        return mainActivityProcessingUris.contains(uriString)
    }
    
    /**
     * Добавляет URI в список недавно обработанных
     * @param uri URI для добавления
     */
    fun addRecentlyProcessedUri(uri: Uri) {
        val uriString = uri.toString()
        
        // Проверяем, не нужно ли очистить список
        checkAndCleanRecentList()
        
        // Добавляем в список с временной меткой
        recentlyProcessedUris.add("$uriString:${System.currentTimeMillis()}")
        Timber.d("URI добавлен в список недавно обработанных: $uriString")
    }
    
    /**
     * Проверяет, обрабатывается ли уже URI
     * @param uri URI для проверки
     * @return true если URI уже обрабатывается
     */
    fun isUriBeingProcessed(uri: Uri): Boolean {
        val uriString = uri.toString()
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
     * @param uri URI для игнорирования
     * @param timeMs Время в миллисекундах
     */
    fun setIgnorePeriod(uri: Uri, timeMs: Long = DEFAULT_IGNORE_PERIOD) {
        val uriString = uri.toString()
        val ignoreUntil = System.currentTimeMillis() + timeMs
        ignoreUrisUntil[uriString] = ignoreUntil
        Timber.d("Установлен период игнорирования для URI: $uriString до ${java.util.Date(ignoreUntil)}")
    }
    
    /**
     * Проверяет, не следует ли игнорировать заданный URI
     * @param uri URI для проверки
     * @return true если URI следует игнорировать
     */
    fun shouldIgnoreUri(uri: Uri): Boolean {
        val uriString = uri.toString()
        
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
     * Обновляет статус обработки URI
     * @param uri URI для обновления
     * @param status Новый статус
     */
    fun updateStatus(uri: Uri, status: String) {
        val uriString = uri.toString()
        processingStatus[uriString] = status
        Timber.d("Обновлен статус для URI $uriString: $status")
    }
    
    /**
     * Получает текущий статус обработки URI
     * @param uri URI для проверки
     * @return Текущий статус или NONE, если статус не установлен
     */
    fun getStatus(uri: Uri): String {
        val uriString = uri.toString()
        return processingStatus[uriString] ?: PROCESSING_STATUS_NONE
    }
    
    /**
     * Проверяет, было ли изображение обработано ранее по EXIF метаданным
     * @param context Контекст приложения
     * @param uri URI изображения
     * @return true если изображение уже обработано
     */
    suspend fun isImageProcessed(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Используем централизованную логику из ImageProcessingChecker
            val result = ImageProcessingChecker.isProcessingRequired(context, uri, false)
            val isProcessed = !result.processingRequired
            
            if (isProcessed) {
                Timber.d("Изображение уже обработано (причина: ${result.reason}): $uri")
            } else {
                Timber.d("Изображение требует обработки: $uri")
            }
            
            return@withContext isProcessed
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса обработки файла: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Проверяет, нужно ли обрабатывать изображение
     * Делегирует к централизованной логике в ImageProcessingChecker
     */
    suspend fun shouldProcessImage(context: Context, uri: Uri): Boolean {
        return ImageProcessingChecker.shouldProcessImage(context, uri)
    }
    
    /**
     * Отмечает изображение как обработанное (добавляет EXIF маркер)
     */
    suspend fun markImageAsProcessed(context: Context, uri: Uri, quality: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext ExifUtil.markCompressedImage(context, uri, quality)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при маркировке изображения как обработанного: ${e.message}")
            return@withContext false
        }
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
        mainActivityProcessingUris.clear()
        recentlyProcessedUris.clear()
        ignoreUrisUntil.clear()
        processingStatus.clear()
        Timber.d("Все списки отслеживания URI очищены")
    }
} 
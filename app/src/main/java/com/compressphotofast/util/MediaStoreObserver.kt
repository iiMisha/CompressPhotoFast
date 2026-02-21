package com.compressphotofast.util

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.compressphotofast.util.LogUtil
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import com.compressphotofast.util.UriUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/**
 * Класс для централизованной работы с ContentObserver для отслеживания изменений в MediaStore
 */
import javax.inject.Inject

class MediaStoreObserver @Inject constructor(
    private val context: Context,
    private val optimizedCacheUtil: OptimizedCacheUtil,
    private val uriProcessingTracker: UriProcessingTracker,
    private var imageChangeListener: ((Uri) -> Unit)? = null
) {
    // Shared CoroutineScope для всех экземпляров
    companion object {
        private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    private val handlerScope = mainScope
    // CoroutineScope для асинхронных операций
    private val observerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Система для предотвращения дублирования событий от ContentObserver
    private val recentlyObservedUris = ConcurrentHashMap<String, Long>()
    private val contentObserverDebounceTime = 5000L // 5000мс (5 секунд) для дедупликации событий - увеличиваем для надежности

    // Очередь отложенных задач для ContentObserver
    private val pendingTasks = ConcurrentHashMap<String, Job>()
    
    // Счетчик попыток для каждого URI, чтобы избежать бесконечных циклов
    private val retryCounts = ConcurrentHashMap<String, Int>()
    private val maxRetries = 4 // Уменьшено с 6 до 4 для оптимизации производительности
    private val baseRetryDelayMs = 1000L // Базовая задержка (1 секунда вместо 7) для экспоненциального backoff
    
    // ContentObserver для отслеживания изменений в MediaStore
    // Handler все еще нужен для ContentObserver, но используется минимально
    private val contentObserverHandler = Handler(Looper.getMainLooper())
    private val contentObserver: ContentObserver = object : ContentObserver(contentObserverHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)

            uri?.let {
                // Игнорируем URI, если он был недавно оптимизирован
                if (uriProcessingTracker.shouldIgnore(it)) {
                    LogUtil.processDebug("MediaStoreObserver: URI $it пропущен, так как недавно обработан.")
                    return
                }

                // Проверяем, что это новое изображение с базовой фильтрацией
                if (it.toString().contains("media") && it.toString().contains("image")) {
                    // Проверяем, не является ли файл переименованным оригиналом (с суффиксом _original)
                    val fileName = UriUtil.getFileNameFromUri(context, it) ?: ""
                    if (fileName.contains("_original.")) {
                        // Это переименованный оригинал, пропускаем его
                        LogUtil.processDebug("MediaStoreObserver: пропускаем обработку переименованного оригинала: $fileName")
                        return
                    }
                    
                    // Предотвращаем дублирование событий для одного URI за короткий период времени
                    val uriString = it.toString()
                    val currentTime = System.currentTimeMillis()
                    val lastObservedTime = recentlyObservedUris[uriString]
                    
                    if (lastObservedTime != null && (currentTime - lastObservedTime < contentObserverDebounceTime)) {
                        // Если URI был недавно обработан, пропускаем его
                        return
                    }
                    
                    // Обновляем время последнего наблюдения
                    recentlyObservedUris[uriString] = currentTime
                    
                    // Удаляем старые записи (старше 15 секунд, чтобы соответствовать новому debounce времени)
                    val urisToRemove = recentlyObservedUris.entries
                        .filter { (currentTime - it.value) > 15000L }
                        .map { it.key }
                    
                    urisToRemove.forEach { key -> recentlyObservedUris.remove(key) }
                    
                    // Логируем событие
                    LogUtil.processDebug("MediaStoreObserver: обнаружено изменение в MediaStore: $uri, обработка через ${Constants.CONTENT_OBSERVER_DELAY_SECONDS} сек")

                    // Отменяем предыдущую отложенную задачу для этого URI, если она существует
                    pendingTasks[uriString]?.cancel()
                    LogUtil.processDebug("MediaStoreObserver: предыдущая задача для $uriString отменена")

                    // Создаем новую задачу с задержкой
                    val delayJob = handlerScope.launch {
                        delay(Constants.CONTENT_OBSERVER_DELAY_SECONDS * 1000L)
                        processUriWithRetry(it, uriString)
                    }

                    // Сохраняем задачу
                    pendingTasks[uriString] = delayJob
                }
            }
        }
    }

    /**
     * Обрабатывает URI с механизмами повтора при is_pending и ошибках
     */
    private fun processUriWithRetry(uri: Uri, uriString: String) {
        LogUtil.processDebug("MediaStoreObserver: начинаем обработку URI $uriString после задержки")

        // Проверяем, является ли URI недоступным перед обработкой
        if (uriProcessingTracker.isUriUnavailable(uri)) {
            LogUtil.processDebug("MediaStoreObserver: URI помечен как недоступный, пропускаем обработку: $uriString")
            pendingTasks.remove(uriString)
            return
        }

        // Проверяем is_pending перед проверкой существования
        val isPending = UriUtil.isFilePending(context, uri)
        if (isPending) {
            val currentRetries = retryCounts.getOrDefault(uriString, 0)
            if (currentRetries < maxRetries) {
                val nextRetry = currentRetries + 1
                retryCounts[uriString] = nextRetry
                // Экспоненциальный backoff: 1с, 2с, 4с, 8с
                val delayMs = baseRetryDelayMs * (1 shl nextRetry) // 2^nextRetry
                LogUtil.processDebug("MediaStoreObserver: файл имеет is_pending=1, планируем повтор #$nextRetry через ${delayMs/1000} сек (эксп. backoff): $uriString")

                // Перепланируем задачу с экспоненциальной задержкой
                val retryJob = handlerScope.launch {
                    delay(delayMs)
                    processUriWithRetry(uri, uriString)
                }
                pendingTasks[uriString] = retryJob
            } else {
                LogUtil.processDebug("MediaStoreObserver: файл все еще is_pending=1 после $maxRetries попыток, пропускаем: $uriString")
                retryCounts.remove(uriString)
                pendingTasks.remove(uriString)
            }
            return
        }

        // Если дошли сюда, значит файл больше не pending по флагу MediaStore
        retryCounts.remove(uriString)

        // Проверяем существование URI перед передачей в обработчик
        observerScope.launch {
            try {
                val exists = UriUtil.isUriExistsSuspend(context, uri)
                if (exists) {
                    imageChangeListener?.invoke(uri)
                } else {
                    LogUtil.processDebug("MediaStoreObserver: URI не существует, помечаем как недоступный: $uriString")
                    uriProcessingTracker.markUriUnavailable(uri)
                }
            } catch (e: PendingItemException) {
                // Файл физически есть, но доступен только владельцу (is_pending=1 в другом смысле)
                val currentRetries = retryCounts.getOrDefault(uriString, 0)
                if (currentRetries < maxRetries) {
                    val nextRetry = currentRetries + 1
                    retryCounts[uriString] = nextRetry
                    // Экспоненциальный backoff: 1с, 2с, 4с, 8с
                    val delayMs = baseRetryDelayMs * (1 shl nextRetry) // 2^nextRetry
                    LogUtil.processDebug("MediaStoreObserver: обнаружен PendingItemException (Only owner), планируем повтор #$nextRetry через ${delayMs/1000} сек (эксп. backoff): $uriString")

                    val retryJob = handlerScope.launch {
                        delay(delayMs)
                        processUriWithRetry(uri, uriString)
                    }
                    pendingTasks[uriString] = retryJob
                } else {
                    LogUtil.processDebug("MediaStoreObserver: файл все еще PendingItem после $maxRetries попыток, пропускаем: $uriString")
                    retryCounts.remove(uriString)
                    uriProcessingTracker.markUriUnavailable(uri)
                }
            } catch (e: Exception) {
                LogUtil.error(uri, "MediaStoreObserver", "Ошибка при первичной проверке существования", e)
            }
            pendingTasks.remove(uriString)
        }
    }
    
    fun setImageChangeListener(listener: (Uri) -> Unit) {
        this.imageChangeListener = listener
    }

    /**
     * Регистрирует ContentObserver для отслеживания изменений в MediaStore
     */
    fun register() {
        if (imageChangeListener == null) {
            LogUtil.error(null, "MediaStoreObserver", "imageChangeListener не установлен. Вызовите setImageChangeListener перед регистрацией.")
            return
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
        LogUtil.processDebug("MediaStoreObserver: ContentObserver зарегистрирован для MediaStore.Images.Media.EXTERNAL_CONTENT_URI")
    }
    
    /**
     * Отменяет регистрацию ContentObserver
     */
    fun unregister() {
        context.contentResolver.unregisterContentObserver(contentObserver)

        // Очищаем все отложенные задачи
        pendingTasks.forEach { (uri, job) ->
            job.cancel()
            LogUtil.processDebug("MediaStoreObserver: отменена отложенная задача для $uri при остановке")
        }
        pendingTasks.clear()

        // Отменяем все корутины
        observerScope.cancel()

        LogUtil.processDebug("MediaStoreObserver: ContentObserver отменен")
    }
    
    /**
     * Очищает все задачи
     */
    fun clearTasks() {
        pendingTasks.forEach { (uri, job) ->
            job.cancel()
            LogUtil.processDebug("MediaStoreObserver: отменена отложенная задача для $uri при очистке")
        }
        pendingTasks.clear()
    }

    /**
     * Очищает coroutine scopes (должен вызываться при уничтожении)
     */
    fun destroy() {
        unregister()
        handlerScope.cancel()
    }
} 
package com.compressphotofast.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.compressphotofast.util.LogUtil
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

/**
 * Класс для централизованной работы с ContentObserver для отслеживания изменений в MediaStore
 */
import javax.inject.Inject
import com.compressphotofast.util.Constants

class MediaStoreObserver @Inject constructor(
    private val context: Context,
    private val uriProcessingTracker: UriProcessingTracker,
    private var imageChangeListener: ((Uri) -> Unit)? = null
) {
    // Все задачи принадлежат конкретному observer и отменяются в unregister().
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
            // ContentObserver обычно вызывает onChange на main. Здесь только
            // захватываем URI и передаём весь provider/EXIF I/O в owned IO scope.
            uri?.let { capturedUri -> observerScope.launch { handleChange(capturedUri) } }
        }
    }

    private suspend fun handleChange(uri: Uri) {
        if (uriProcessingTracker.shouldIgnore(uri) || uriProcessingTracker.isProcessing(uri)) return
        if (!uri.toString().contains("media") || !uri.toString().contains("image")) return

        // Debounce до любого IPC: серия onChange по одному URI не должна
        // порождать серию запросов к MediaStore
        val uriString = uri.toString()
        val currentTime = System.currentTimeMillis()
        val lastObservedTime = recentlyObservedUris[uriString]
        if (lastObservedTime != null && currentTime - lastObservedTime < contentObserverDebounceTime) return
        recentlyObservedUris[uriString] = currentTime
        recentlyObservedUris.entries.removeIf { currentTime - it.value > 15000L }

        LogUtil.processDebug("MediaStoreObserver: обнаружено изменение в MediaStore: $uri, обработка через ${Constants.CONTENT_OBSERVER_DELAY_SECONDS} сек")
        pendingTasks[uriString]?.cancel()
        val delayJob = observerScope.launch {
            delay(Constants.CONTENT_OBSERVER_DELAY_SECONDS * 1000L)
            if (uriProcessingTracker.shouldIgnore(uri) || uriProcessingTracker.isProcessing(uri)) {
                pendingTasks.remove(uriString)
                return@launch
            }
            processUri(uri, uriString)
        }
        pendingTasks[uriString] = delayJob
    }

    /**
     * Проверяет URI одним запросом [MediaItemSnapshot]: существование, `_original.`,
     * файлы директории приложения (собственные сжатые копии), pending с повтором.
     * Собственные изменения вне директории приложения отсекает ignore-период
     * [UriProcessingTracker] (ключ по MediaStore ID, независимо от тома).
     */
    internal suspend fun processUri(uri: Uri, uriString: String = uri.toString()) {
        LogUtil.processDebug("MediaStoreObserver: начинаем обработку URI $uriString после задержки")

        if (uriProcessingTracker.isUriUnavailable(uri)) {
            LogUtil.processDebug("MediaStoreObserver: URI помечен как недоступный, пропускаем обработку: $uriString")
            finishTask(uriString)
            return
        }

        val snapshot = try {
            MediaItemSnapshot.query(context, uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.error(uri, "MediaStoreObserver", "Ошибка при запросе метаданных", e)
            scheduleRetry(uri, uriString)
            return
        }
        if (snapshot == null) {
            LogUtil.processDebug("MediaStoreObserver: URI не существует, помечаем как недоступный: $uriString")
            uriProcessingTracker.markUriUnavailable(uri)
            finishTask(uriString)
            return
        }

        val fileName = snapshot.displayName ?: ""
        val isInAppDirectory = OptimizedCacheUtil.checkDirectoryStatus(snapshot.filePath ?: "", Constants.APP_DIRECTORY)
        if (fileName.contains("_original.") || isInAppDirectory) {
            finishTask(uriString)
            return
        }

        if (UriUtil.isPendingEffective(context, snapshot)) {
            val nextRetry = retryCounts.compute(uriString) { _, current -> (current ?: 0) + 1 } ?: 1
            if (nextRetry <= maxRetries) {
                // Экспоненциальный backoff: 2с, 4с, 8с, 16с
                val delayMs = baseRetryDelayMs * (1 shl nextRetry)
                LogUtil.processDebug("MediaStoreObserver: файл имеет is_pending=1, планируем повтор #$nextRetry через ${delayMs/1000} сек (эксп. backoff): $uriString")
                pendingTasks[uriString] = observerScope.launch {
                    delay(delayMs)
                    processUri(uri, uriString)
                }
            } else {
                LogUtil.processDebug("MediaStoreObserver: файл все еще is_pending=1 после $maxRetries попыток, пропускаем: $uriString")
                retryCounts.remove(uriString)
                pendingTasks.remove(uriString)
            }
            return
        }

        retryCounts.remove(uriString)
        imageChangeListener?.invoke(uri)
        finishTask(uriString)
    }

    /** Удаляет задачу, только если она не была перезаписана новым onChange. */
    private suspend fun finishTask(uriString: String) {
        val ownJob = currentCoroutineContext()[Job]
        val job = pendingTasks[uriString] ?: return
        if (job === ownJob || job.isCompleted) {
            pendingTasks.remove(uriString, job)
        }
    }

    private fun scheduleRetry(uri: Uri, uriString: String) {
        val nextRetry = retryCounts.compute(uriString) { _, current -> (current ?: 0) + 1 } ?: 1
        if (nextRetry > maxRetries) {
            retryCounts.remove(uriString)
            pendingTasks.remove(uriString)
            LogUtil.processDebug("MediaStoreObserver: временная ошибка для $uriString, retry исчерпан")
            return
        }
        val delayMs = baseRetryDelayMs * (1 shl nextRetry)
        val retryJob = observerScope.launch {
            delay(delayMs)
            processUri(uri, uriString)
        }
        pendingTasks[uriString] = retryJob
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
        try {
            context.contentResolver.unregisterContentObserver(contentObserver)
        } catch (_: Exception) {
            // Observer мог не успеть зарегистрироваться.
        }

        // Очищаем все отложенные задачи
        pendingTasks.forEach { (uri, job) ->
            job.cancel()
            LogUtil.processDebug("MediaStoreObserver: отменена отложенная задача для $uri при остановке")
        }
        pendingTasks.clear()

        recentlyObservedUris.clear()
        retryCounts.clear()

        // Отменяем все корутины
        observerScope.cancel()

        LogUtil.processDebug("MediaStoreObserver: ContentObserver отменен")
    }
}

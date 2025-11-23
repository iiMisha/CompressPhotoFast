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

/**
 * Класс для централизованной работы с ContentObserver для отслеживания изменений в MediaStore
 */
import javax.inject.Inject

class MediaStoreObserver @Inject constructor(
    private val context: Context,
    private val optimizedCacheUtil: OptimizedCacheUtil,
    private val uriProcessingTracker: UriProcessingTracker,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private var imageChangeListener: ((Uri) -> Unit)? = null
) {
    // Система для предотвращения дублирования событий от ContentObserver
    private val recentlyObservedUris = Collections.synchronizedMap(HashMap<String, Long>())
    private val contentObserverDebounceTime = 2000L // 2000мс (2 секунды) для дедупликации событий
    
    // Очередь отложенных задач для ContentObserver
    private val pendingTasks = ConcurrentHashMap<String, Runnable>()
    
    // ContentObserver для отслеживания изменений в MediaStore
    private val contentObserver: ContentObserver = object : ContentObserver(handler) {
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
                    
                    // Удаляем старые записи (старше 10 секунд)
                    val urisToRemove = recentlyObservedUris.entries
                        .filter { (currentTime - it.value) > 10000 }
                        .map { it.key }
                    
                    urisToRemove.forEach { key -> recentlyObservedUris.remove(key) }
                    
                    // Логируем событие
                    LogUtil.processDebug("MediaStoreObserver: обнаружено изменение в MediaStore: $uri, обработка через ${Constants.CONTENT_OBSERVER_DELAY_SECONDS} сек")
                    
                    // Отменяем предыдущую отложенную задачу для этого URI, если она существует
                    pendingTasks[uriString]?.let { runnable ->
                        handler.removeCallbacks(runnable)
                        LogUtil.processDebug("MediaStoreObserver: предыдущая задача для $uriString отменена")
                    }
                    
                    // Создаем новую задачу с задержкой
                    val delayTask = Runnable {
                        LogUtil.processDebug("MediaStoreObserver: начинаем обработку URI $uriString после задержки")
                        // Проверяем существование URI перед передачей в обработчик
                        val exists = kotlinx.coroutines.runBlocking {
                            UriUtil.isUriExistsSuspend(context, it)
                        }
                        if (exists) {
                            imageChangeListener?.invoke(it)
                        } else {
                            LogUtil.processDebug("MediaStoreObserver: URI не существует, пропускаем обработку: $uriString")
                        }
                        pendingTasks.remove(uriString)
                    }
                    
                    // Сохраняем задачу и планируем ее выполнение с задержкой
                    pendingTasks[uriString] = delayTask
                    handler.postDelayed(delayTask, Constants.CONTENT_OBSERVER_DELAY_SECONDS * 1000)
                }
            }
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
        pendingTasks.forEach { (uri, runnable) ->
            handler.removeCallbacks(runnable)
            LogUtil.processDebug("MediaStoreObserver: отменена отложенная задача для $uri при остановке")
        }
        pendingTasks.clear()
        
        LogUtil.processDebug("MediaStoreObserver: ContentObserver отменен")
    }
    
    /**
     * Очищает все задачи
     */
    fun clearTasks() {
        pendingTasks.forEach { (uri, runnable) ->
            handler.removeCallbacks(runnable)
            LogUtil.processDebug("MediaStoreObserver: отменена отложенная задача для $uri при очистке")
        }
        pendingTasks.clear()
    }
} 
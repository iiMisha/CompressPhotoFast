package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Утилитарный класс для отслеживания статуса сжатия изображений
 */
object ImageTrackingUtil {
    private const val PREF_COMPRESSED_IMAGES = "compressed_images"
    private const val MAX_TRACKED_IMAGES = 1000
    private const val PROCESSING_TIMEOUT = 5 * 60 * 1000L // 5 минут
    
    // Кэш для отслеживания файлов в процессе обработки
    private val processingFiles = ConcurrentHashMap<String, Long>()
    
    // Кэш для отслеживания обработанных URI и имен файлов
    private val processedUris = mutableSetOf<String>()
    private val processedFileNames = mutableMapOf<String, Uri>()
    
    // Mutex для синхронизации доступа
    private val mutex = Mutex()
    
    // Реестр URI, которые обрабатываются через MainActivity (через Intent)
    private val urisBeingProcessedByMainActivity = Collections.synchronizedSet(HashSet<String>())
    
    /**
     * Регистрирует URI, который обрабатывается через MainActivity
     */
    fun registerUriBeingProcessedByMainActivity(uri: Uri) {
        val uriString = uri.toString()
        urisBeingProcessedByMainActivity.add(uriString)
        Timber.d("registerUriBeingProcessedByMainActivity: зарегистрирован URI: $uriString")
    }
    
    /**
     * Проверяет, обрабатывается ли URI через MainActivity
     */
    fun isUriBeingProcessedByMainActivity(uri: Uri): Boolean {
        val uriString = uri.toString()
        val result = urisBeingProcessedByMainActivity.contains(uriString)
        Timber.d("isUriBeingProcessedByMainActivity: URI $uriString ${if (result) "обрабатывается" else "не обрабатывается"} через MainActivity")
        return result
    }
    
    /**
     * Удаляет URI из списка обрабатываемых через MainActivity
     */
    fun unregisterUriBeingProcessedByMainActivity(uri: Uri) {
        val uriString = uri.toString()
        val removed = urisBeingProcessedByMainActivity.remove(uriString)
        Timber.d("unregisterUriBeingProcessedByMainActivity: URI $uriString ${if (removed) "удален" else "не найден"} в списке")
    }

    /**
     * Проверяет, было ли изображение уже обработано
     */
    fun isImageProcessed(context: Context, uri: Uri): Boolean {
        try {
            // Проверяем, находится ли файл в директории приложения
            val path = FileUtil.getFilePathFromUri(context, uri)
            
            // Проверяем, содержит ли путь или URI название директории приложения
            val isInAppDir = !path.isNullOrEmpty() && 
                (path.contains("/${Constants.APP_DIRECTORY}/") || 
                 path.contains("content://media/external/images/media") && 
                 path.contains(Constants.APP_DIRECTORY))
            
            Timber.d("isImageProcessed: файл находится в директории приложения: $isInAppDir, путь: $path")
            
            if (isInAppDir) {
                return true
            }
            
            // Проверяем, есть ли URI в списке обработанных
            val uriString = uri.toString()
            if (processedUris.contains(uriString)) {
                Timber.d("isImageProcessed: URI найден в списке обработанных: $uriString")
                return true
            }
            
            // Проверяем имя файла
            val fileName = FileUtil.getFileNameFromUri(context, uri)
            if (fileName != null) {
                // Проверяем, содержит ли имя файла маркеры сжатия
                if (fileName.contains("_compressed") || 
                    fileName.contains("_сжатое") || 
                    fileName.contains("_small")) {
                    Timber.d("isImageProcessed: имя файла содержит маркер сжатия: $fileName")
                    return true
                }
                
                // Проверяем, есть ли имя файла в списке обработанных
                if (processedFileNames.containsKey(fileName)) {
                    Timber.d("isImageProcessed: имя файла найдено в списке обработанных: $fileName")
                    return true
                }
            }
            
            // Файл прошел все проверки и не был обработан
            return false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса файла: $uri")
            return false
        }
    }

    /**
     * Добавляет URI в список обработанных
     */
    private fun addToProcessedList(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences(PREF_COMPRESSED_IMAGES, Context.MODE_PRIVATE)
        val processedImages = prefs.getStringSet("uris", setOf()) ?: setOf()
        val uriString = uri.toString()
        
        // Создаем новый набор, так как SharedPreferences возвращает неизменяемый набор
        val newSet = HashSet(processedImages)
        newSet.add(uriString)
        
        // Ограничиваем размер списка
        if (newSet.size > MAX_TRACKED_IMAGES) {
            val toRemove = newSet.size - MAX_TRACKED_IMAGES
            val iterator = newSet.iterator()
            var removed = 0
            while (iterator.hasNext() && removed < toRemove) {
                iterator.next()
                iterator.remove()
                removed++
            }
        }
        
        prefs.edit().putStringSet("uris", newSet).apply()
        Timber.d("addToProcessedList: URI '$uriString' добавлен в список обработанных (всего: ${newSet.size})")
    }

    /**
     * Проверяет, находится ли файл в процессе обработки
     */
    private fun isFileProcessing(uriString: String): Boolean {
        val timestamp = processingFiles[uriString]
        
        if (timestamp == null) {
            Timber.d("isFileProcessing: URI '$uriString' не найден в списке обрабатываемых")
            return false
        }
        
        val now = System.currentTimeMillis()
        val elapsed = now - timestamp
        
        // Если прошло больше времени чем PROCESSING_TIMEOUT, считаем что обработка прервалась
        if (elapsed > PROCESSING_TIMEOUT) {
            processingFiles.remove(uriString)
            Timber.d("isFileProcessing: обработка URI '$uriString' истекла по таймауту (${elapsed}ms > ${PROCESSING_TIMEOUT}ms)")
            return false
        }
        
        Timber.d("isFileProcessing: URI '$uriString' находится в процессе обработки (прошло ${elapsed}ms)")
        return true
    }

    /**
     * Помечает файл как находящийся в процессе обработки
     */
    private fun markFileAsProcessing(uriString: String) {
        processingFiles[uriString] = System.currentTimeMillis()
        Timber.d("markFileAsProcessing: URI '$uriString' добавлен в список обрабатываемых")
    }

    /**
     * Проверяет наличие URI в списке обработанных
     */
    private fun isUriInProcessedList(context: Context, uri: Uri): Boolean {
        val prefs = context.getSharedPreferences(PREF_COMPRESSED_IMAGES, Context.MODE_PRIVATE)
        val processedImages = prefs.getStringSet("uris", setOf()) ?: setOf()
        val uriString = uri.toString()
        val contains = processedImages.contains(uriString)
        
        Timber.d("isUriInProcessedList: URI '$uriString' ${if (contains) "найден" else "не найден"} в списке обработанных")
        return contains
    }

    /**
     * Получает имя файла из URI
     */
    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        return FileUtil.getFileNameFromUri(context, uri)
    }

    /**
     * Получает описание изображения
     */
    private fun getImageDescription(context: Context, uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DESCRIPTION)
        return context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DESCRIPTION))
            } else null
        }
    }

    /**
     * Получает путь к файлу
     */
    private fun getImagePath(context: Context, uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        return context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
            } else null
        }
    }

    /**
     * Помечает изображение как обработанное
     */
    suspend fun markImageAsProcessed(context: Context, uri: Uri) = mutex.withLock {
        val uriString = uri.toString()
        
        // Добавляем URI в список обработанных
        addToProcessedList(context, uri)
        
        // Удаляем из списка обрабатываемых
        processingFiles.remove(uriString)
        
        Timber.d("URI добавлен в список обработанных: $uri")
    }

    /**
     * Добавляет изображение в список обработанных
     * Алиас для markImageAsProcessed для обратной совместимости
     */
    fun addProcessedImage(context: Context, uri: Uri) {
        Timber.d("addProcessedImage: Добавление URI в список обработанных: $uri")
        // Запускаем функцию без ожидания, так как это публичный метод без suspend
        // и вызывается из FileUtil.saveCompressedImageToGallery
        GlobalScope.launch {
            markImageAsProcessed(context, uri)
        }
    }

    /**
     * Очищает статус обработки для URI без добавления в список обработанных
     */
    suspend fun clearProcessingStatus(uri: Uri) = mutex.withLock {
        val uriString = uri.toString()
        processingFiles.remove(uriString)
        Timber.d("Очищен статус обработки для URI: $uri")
    }

    private suspend fun checkImageStatus(context: Context, uri: Uri): Boolean = mutex.withLock {
        try {
            // Проверяем путь файла
            val path = getImagePath(context, uri)
            Timber.d("checkImageStatus: путь к файлу: $path")
            
            val isInAppDir = !path.isNullOrEmpty() && path.contains("/${Constants.APP_DIRECTORY}/")
            Timber.d("checkImageStatus: файл находится в директории приложения: $isInAppDir")
            
            if (isInAppDir) {
                return@withLock true
            }

            // Проверяем по сохраненным URI
            val isInList = isUriInProcessedList(context, uri)
            Timber.d("checkImageStatus: URI найден в списке обработанных: $isInList")
            
            if (isInList) {
                return@withLock true
            }

            // Если файл не обработан, помечаем его как находящийся в процессе обработки
            markFileAsProcessing(uri.toString())
            Timber.d("checkImageStatus: помечаем файл как находящийся в процессе обработки: $uri")
            
            // Добавляем URI в список обработанных сразу, чтобы избежать параллельной обработки
            addToProcessedList(context, uri)
            Timber.d("checkImageStatus: добавляем URI в список обработанных: $uri")
            
            return@withLock false
        } catch (e: Exception) {
            Timber.e(e, "checkImageStatus: ошибка при проверке статуса файла")
            return@withLock false
        }
    }
} 
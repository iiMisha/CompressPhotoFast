package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/**
 * Утилитарный класс для отслеживания статуса сжатия изображений
 */
object ImageTrackingUtil {
    private const val PREF_COMPRESSED_IMAGES = "compressed_images"
    private const val MAX_TRACKED_IMAGES = 1000
    private const val PROCESSING_TIMEOUT = 5 * 60 * 1000L // 5 минут
    
    // Кэш для отслеживания файлов в процессе обработки
    private val processingFiles = ConcurrentHashMap<String, Long>()
    
    // Mutex для синхронизации доступа
    private val mutex = Mutex()
    
    // Реестр URI, которые обрабатываются через MainActivity (через Intent)
    private val urisBeingProcessedByMainActivity = Collections.synchronizedSet(HashSet<String>())
    
    /**
     * Маркеры сжатых изображений
     */
    val COMPRESSION_MARKERS = listOf(
        "_compressed",
        "_сжатое",
        "_small",
        "_reduced"
    )

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
     * Проверяет, было ли изображение уже обработано или находится в процессе обработки
     */
    suspend fun isImageProcessed(context: Context, uri: Uri): Boolean = mutex.withLock {
        try {
            val uriString = uri.toString()
            Timber.d("isImageProcessed: проверяем URI: $uriString")
            
            // Проверяем, не обрабатывается ли URI через MainActivity
            if (isUriBeingProcessedByMainActivity(uri)) {
                Timber.d("isImageProcessed: URI обрабатывается через MainActivity: $uriString")
                return@withLock true
            }
            
            // Проверяем, не находится ли файл в процессе обработки
            if (isFileProcessing(uriString)) {
                Timber.d("isImageProcessed: файл уже в процессе обработки: $uri")
                return@withLock true
            }

            // Получаем имя файла
            val fileName = getFileNameFromUri(context, uri)
            Timber.d("isImageProcessed: имя файла: $fileName")
            
            if (fileName == null) {
                Timber.e("isImageProcessed: не удалось получить имя файла для URI: $uri")
                return@withLock false
            }

            // Проверяем наличие маркеров сжатия в имени файла
            val hasMarker = hasCompressionMarker(fileName)
            Timber.d("isImageProcessed: файл имеет маркер сжатия в имени: $hasMarker (имя: $fileName)")
            
            if (hasMarker) {
                return@withLock true
            }

            // Проверяем путь файла
            val path = getImagePath(context, uri)
            Timber.d("isImageProcessed: путь к файлу: $path")
            
            val isInAppDir = !path.isNullOrEmpty() && path.contains("/${Constants.APP_DIRECTORY}/")
            Timber.d("isImageProcessed: файл находится в директории приложения: $isInAppDir")
            
            if (isInAppDir) {
                return@withLock true
            }

            // Проверяем наличие сжатой версии
            val hasCompressed = hasCompressedVersion(context, fileName)
            Timber.d("isImageProcessed: найдена существующая сжатая версия: $hasCompressed (для $fileName)")
            
            if (hasCompressed) {
                return@withLock true
            }

            // Проверяем по сохраненным URI
            val isInList = isUriInProcessedList(context, uri)
            Timber.d("isImageProcessed: URI найден в списке обработанных: $isInList")
            
            if (isInList) {
                return@withLock true
            }

            // Если файл не обработан, помечаем его как находящийся в процессе обработки
            markFileAsProcessing(uriString)
            Timber.d("isImageProcessed: помечаем файл как находящийся в процессе обработки: $uri")
            
            // Добавляем URI в список обработанных сразу, чтобы избежать параллельной обработки
            addToProcessedList(context, uri)
            Timber.d("isImageProcessed: добавляем URI в список обработанных: $uri")
            
            return@withLock false
        } catch (e: Exception) {
            Timber.e(e, "isImageProcessed: ошибка при проверке статуса файла")
            return@withLock false
        }
    }

    /**
     * Добавляет URI в список обработанных
     */
    private fun addToProcessedList(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences(PREF_COMPRESSED_IMAGES, Context.MODE_PRIVATE)
        val processedImages = prefs.getStringSet("uris", mutableSetOf()) ?: mutableSetOf()
        
        // Создаем новый набор, так как SharedPreferences возвращает неизменяемый набор
        val updatedImages = HashSet(processedImages)
        
        // Проверяем, не содержится ли уже URI в списке
        val uriString = uri.toString()
        val alreadyExists = updatedImages.contains(uriString)
        if (alreadyExists) {
            Timber.d("addToProcessedList: URI '$uriString' уже присутствует в списке обработанных")
            return
        }
        
        // Добавляем новый URI
        updatedImages.add(uriString)
        Timber.d("addToProcessedList: добавлен URI '$uriString'. Текущий размер списка: ${updatedImages.size}")
        
        // Если список слишком большой, удаляем старые записи
        var removedCount = 0
        while (updatedImages.size > MAX_TRACKED_IMAGES) {
            updatedImages.iterator().next()?.let { 
                updatedImages.remove(it)
                removedCount++
            }
        }
        
        if (removedCount > 0) {
            Timber.d("addToProcessedList: удалено $removedCount старых записей для поддержания ограничения размера")
        }
        
        // Сохраняем обновленный список
        prefs.edit().putStringSet("uris", updatedImages).apply()
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
     * Проверяет наличие маркеров сжатия в имени файла
     */
    private fun hasCompressionMarker(fileName: String): Boolean {
        val lowerFileName = fileName.lowercase()
        
        for (marker in COMPRESSION_MARKERS) {
            if (lowerFileName.contains(marker.lowercase())) {
                Timber.d("hasCompressionMarker: найден маркер '$marker' в имени файла '$fileName'")
                return true
            }
        }
        
        Timber.d("hasCompressionMarker: маркеры сжатия не найдены в имени файла '$fileName'")
        return false
    }

    /**
     * Проверяет существование сжатой версии файла
     */
    private suspend fun hasCompressedVersion(context: Context, fileName: String): Boolean {
        val baseFileName = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")
        
        Timber.d("hasCompressedVersion: проверка наличия сжатой версии для '$fileName' (базовое имя: '$baseFileName', расширение: '$extension')")
        
        // Проверяем все возможные варианты имен сжатых файлов
        for (marker in COMPRESSION_MARKERS) {
            val compressedFileName = "${baseFileName}$marker.$extension"
            
            Timber.d("hasCompressedVersion: поиск возможного сжатого файла '$compressedFileName'")
            
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(compressedFileName)
            
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val found = cursor.count > 0
                if (found) {
                    Timber.d("hasCompressedVersion: найден сжатый файл '$compressedFileName'")
                    return true
                }
            }
        }
        
        Timber.d("hasCompressedVersion: сжатые версии не найдены для '$fileName'")
        return false
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
     * Очищает статус обработки для URI без добавления в список обработанных
     */
    suspend fun clearProcessingStatus(uri: Uri) = mutex.withLock {
        val uriString = uri.toString()
        processingFiles.remove(uriString)
        Timber.d("Очищен статус обработки для URI: $uri")
    }

    private suspend fun checkImageStatus(context: Context, uri: Uri): Boolean = mutex.withLock {
        try {
            // Получаем имя файла
            val fileName = getFileNameFromUri(context, uri)
            if (fileName == null) {
                Timber.d("checkImageStatus: не удалось получить имя файла для URI: $uri")
                return@withLock false
            }
            
            // Проверяем наличие маркеров сжатия в имени файла
            if (hasCompressionMarker(fileName)) {
                Timber.d("checkImageStatus: найден маркер сжатия в имени файла: $fileName")
                markImageAsProcessed(context, uri)
                return@withLock true
            }

            // Проверяем путь файла
            val path = getImagePath(context, uri)
            Timber.d("checkImageStatus: путь к файлу: $path")
            
            val isInAppDir = !path.isNullOrEmpty() && path.contains("/${Constants.APP_DIRECTORY}/")
            Timber.d("checkImageStatus: файл находится в директории приложения: $isInAppDir")
            
            if (isInAppDir) {
                return@withLock true
            }

            // Проверяем наличие сжатой версии
            val hasCompressed = hasCompressedVersion(context, fileName)
            Timber.d("checkImageStatus: найдена существующая сжатая версия: $hasCompressed (для $fileName)")
            
            if (hasCompressed) {
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
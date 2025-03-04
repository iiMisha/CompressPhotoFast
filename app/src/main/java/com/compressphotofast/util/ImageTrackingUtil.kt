package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    
    /**
     * Маркеры сжатых изображений
     */
    private val COMPRESSION_MARKERS = listOf(
        "_compressed",
        "_сжатое",
        "_small",
        "_reduced"
    )

    /**
     * Проверяет, было ли изображение уже обработано или находится в процессе обработки
     */
    suspend fun isImageProcessed(context: Context, uri: Uri): Boolean = mutex.withLock {
        try {
            val uriString = uri.toString()
            
            // Проверяем, не находится ли файл в процессе обработки
            if (isFileProcessing(uriString)) {
                Timber.d("Файл уже в процессе обработки: $uri")
                return@withLock true
            }

            // Получаем имя файла
            val fileName = getFileNameFromUri(context, uri)
            if (fileName == null) {
                Timber.e("Не удалось получить имя файла для URI: $uri")
                return@withLock false
            }

            // Проверяем наличие маркеров сжатия в имени файла
            if (hasCompressionMarker(fileName)) {
                Timber.d("Файл содержит маркер сжатия: $fileName")
                return@withLock true
            }

            // Проверяем путь файла
            val path = getImagePath(context, uri)
            if (!path.isNullOrEmpty() && path.contains("/${Constants.APP_DIRECTORY}/")) {
                Timber.d("Файл находится в директории приложения")
                return@withLock true
            }

            // Проверяем наличие сжатой версии
            if (hasCompressedVersion(context, fileName)) {
                Timber.d("Найдена существующая сжатая версия для $fileName")
                return@withLock true
            }

            // Проверяем по сохраненным URI
            if (isUriInProcessedList(context, uri)) {
                Timber.d("URI найден в списке обработанных: $uri")
                return@withLock true
            }

            // Если файл не обработан, помечаем его как находящийся в процессе обработки
            markFileAsProcessing(uriString)
            
            // Добавляем URI в список обработанных сразу, чтобы избежать параллельной обработки
            addToProcessedList(context, uri)
            
            return@withLock false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса файла")
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
        
        // Добавляем новый URI
        updatedImages.add(uri.toString())
        
        // Если список слишком большой, удаляем старые записи
        while (updatedImages.size > MAX_TRACKED_IMAGES) {
            updatedImages.iterator().next()?.let { updatedImages.remove(it) }
        }
        
        // Сохраняем обновленный список
        prefs.edit().putStringSet("uris", updatedImages).apply()
    }

    /**
     * Проверяет, находится ли файл в процессе обработки
     */
    private fun isFileProcessing(uriString: String): Boolean {
        val timestamp = processingFiles[uriString] ?: return false
        val now = System.currentTimeMillis()
        
        // Если прошло больше времени чем PROCESSING_TIMEOUT, считаем что обработка прервалась
        if (now - timestamp > PROCESSING_TIMEOUT) {
            processingFiles.remove(uriString)
            return false
        }
        
        return true
    }

    /**
     * Помечает файл как находящийся в процессе обработки
     */
    private fun markFileAsProcessing(uriString: String) {
        processingFiles[uriString] = System.currentTimeMillis()
    }

    /**
     * Проверяет наличие URI в списке обработанных
     */
    private fun isUriInProcessedList(context: Context, uri: Uri): Boolean {
        val prefs = context.getSharedPreferences(PREF_COMPRESSED_IMAGES, Context.MODE_PRIVATE)
        val processedImages = prefs.getStringSet("uris", setOf()) ?: setOf()
        return processedImages.contains(uri.toString())
    }

    /**
     * Проверяет наличие маркеров сжатия в имени файла
     */
    private fun hasCompressionMarker(fileName: String): Boolean {
        val lowerFileName = fileName.lowercase()
        return COMPRESSION_MARKERS.any { marker ->
            lowerFileName.contains(marker.lowercase())
        }
    }

    /**
     * Проверяет существование сжатой версии файла
     */
    private suspend fun hasCompressedVersion(context: Context, fileName: String): Boolean {
        val baseFileName = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")
        
        // Проверяем все возможные варианты имен сжатых файлов
        for (marker in COMPRESSION_MARKERS) {
            val compressedFileName = "${baseFileName}$marker.$extension"
            
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
                if (cursor.count > 0) {
                    return true
                }
            }
        }
        
        return false
    }

    /**
     * Получает имя файла из URI
     */
    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
        return context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
            } else null
        }
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
} 
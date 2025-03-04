package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import timber.log.Timber

/**
 * Утилитарный класс для отслеживания статуса сжатия изображений
 */
object ImageTrackingUtil {
    private const val PREF_COMPRESSED_IMAGES = "compressed_images"
    private const val MAX_TRACKED_IMAGES = 1000
    
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
     * Проверяет, было ли изображение уже обработано
     */
    suspend fun isImageProcessed(context: Context, uri: Uri): Boolean {
        try {
            // 1. Проверка по сохраненным URI
            if (isUriInProcessedList(context, uri)) {
                Timber.d("URI найден в списке обработанных: $uri")
                return true
            }

            // 2. Проверка по имени файла
            val fileName = getFileNameFromUri(context, uri)
            if (fileName != null) {
                // Проверка на наличие маркеров сжатия в имени
                if (hasCompressionMarker(fileName)) {
                    Timber.d("Файл содержит маркер сжатия: $fileName")
                    return true
                }

                // Проверка существования сжатой версии
                if (hasCompressedVersion(context, fileName)) {
                    Timber.d("Найдена существующая сжатая версия для $fileName")
                    return true
                }
            }

            // 3. Проверка по метаданным
            val description = getImageDescription(context, uri)
            if (!description.isNullOrEmpty() && description.contains("Compressed")) {
                Timber.d("Файл помечен как сжатый в метаданных")
                return true
            }

            // 4. Проверка пути файла
            val path = getImagePath(context, uri)
            if (!path.isNullOrEmpty() && path.contains("/${Constants.APP_DIRECTORY}/")) {
                Timber.d("Файл находится в директории приложения")
                return true
            }

            return false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса файла")
            return false
        }
    }

    /**
     * Добавляет URI в список обработанных
     */
    fun markImageAsProcessed(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences(PREF_COMPRESSED_IMAGES, Context.MODE_PRIVATE)
        val processedImages = prefs.getStringSet("uris", mutableSetOf()) ?: mutableSetOf()
        
        // Ограничиваем размер списка
        val updatedImages = processedImages.toMutableSet()
        if (updatedImages.size >= MAX_TRACKED_IMAGES) {
            // Удаляем 20% старых записей
            val removeCount = (MAX_TRACKED_IMAGES * 0.2).toInt()
            updatedImages.take(removeCount).forEach { updatedImages.remove(it) }
        }
        
        updatedImages.add(uri.toString())
        
        prefs.edit()
            .putStringSet("uris", updatedImages)
            .apply()
            
        Timber.d("URI добавлен в список обработанных: $uri")
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
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("$compressedFileName%")
            
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
} 
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
    fun isImageProcessed(context: Context, uri: Uri): Boolean {
        // 1. Проверка по сохраненным URI
        if (isUriInProcessedList(context, uri)) {
            Timber.d("URI найден в списке обработанных: $uri")
            return true
        }

        // 2. Проверка по метаданным и имени файла
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DESCRIPTION,
                MediaStore.Images.Media.DATA
            ),
            null,
            null,
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                // Проверка имени файла
                val nameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (nameIndex != -1) {
                    val fileName = it.getString(nameIndex)
                    if (hasCompressionMarker(fileName)) {
                        Timber.d("Файл содержит маркер сжатия: $fileName")
                        return true
                    }
                }

                // Проверка описания (метаданных)
                val descIndex = it.getColumnIndex(MediaStore.Images.Media.DESCRIPTION)
                if (descIndex != -1) {
                    val description = it.getString(descIndex)
                    if (!description.isNullOrEmpty() && description.contains("Compressed")) {
                        Timber.d("Файл помечен как сжатый в метаданных")
                        return true
                    }
                }

                // Проверка пути файла
                val pathIndex = it.getColumnIndex(MediaStore.Images.Media.DATA)
                if (pathIndex != -1) {
                    val path = it.getString(pathIndex)
                    if (path.contains("/${Constants.APP_DIRECTORY}/")) {
                        Timber.d("Файл находится в директории приложения")
                        return true
                    }
                }
            }
        }

        return false
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
} 
package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.HashSet

/**
 * Класс для управления статусом обработки изображений
 */
object ImageProcessingTracker {
    // Константы для статусов обработки
    const val COMPRESSION_STATUS_PROCESSING = "processing"
    const val COMPRESSION_STATUS_COMPLETED = "completed"
    const val COMPRESSION_STATUS_FAILED = "failed"

    // Множество URI, которые в данный момент обрабатываются в MainActivity
    private val mainActivityProcessingUris = Collections.synchronizedSet(HashSet<String>())
    
    // Множество URI, которые в данный момент отслеживаются
    private val trackingUris = Collections.synchronizedSet(HashSet<String>())
    
    // Map для хранения статусов обработки
    private val processingStatus = Collections.synchronizedMap(HashMap<String, String>())

    /**
     * Проверяет, обработано ли изображение по EXIF метаданным
     */
    suspend fun isImageProcessed(context: Context, uri: Uri): Boolean {
        return ImageProcessingChecker.isAlreadyProcessed(context, uri)
    }
    
    /**
     * Маркирует изображение как обработанное
     */
    suspend fun markAsProcessed(context: Context, uri: Uri, quality: Int): Boolean {
        return ExifUtil.markCompressedImage(context, uri, quality)
    }

    /**
     * Начинает отслеживание URI
     */
    fun startTracking(uri: Uri) {
        trackingUris.add(uri.toString())
        Timber.d("Начато отслеживание URI: $uri")
    }

    /**
     * Обновляет статус обработки URI
     */
    fun updateStatus(context: Context, uri: Uri, status: String) {
        processingStatus[uri.toString()] = status
        Timber.d("Обновлен статус для URI $uri: $status")
    }

    /**
     * Проверяет, обрабатывается ли URI в MainActivity
     */
    fun isUriBeingProcessedByMainActivity(uri: Uri): Boolean {
        return mainActivityProcessingUris.contains(uri.toString())
    }

    /**
     * Добавляет URI в список обрабатываемых в MainActivity
     */
    fun addUriToMainActivityProcessing(uri: Uri) {
        mainActivityProcessingUris.add(uri.toString())
        Timber.d("URI добавлен в список обрабатываемых в MainActivity: $uri")
    }

    /**
     * Удаляет URI из списка обрабатываемых в MainActivity
     */
    fun removeUriFromMainActivityProcessing(uri: Uri) {
        mainActivityProcessingUris.remove(uri.toString())
        Timber.d("URI удален из списка обрабатываемых в MainActivity: $uri")
    }
} 
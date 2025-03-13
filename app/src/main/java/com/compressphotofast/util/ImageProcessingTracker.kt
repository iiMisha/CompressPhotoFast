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
     * Проверяет, является ли изображение сжатым
     * Использует только систему EXIF маркировки
     */
    suspend fun isImageProcessed(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверяем EXIF маркер - единственный надежный способ
            val isCompressed = FileUtil.isCompressedByExif(context, uri)
            if (isCompressed) {
                Timber.d("Изображение по URI $uri уже сжато (обнаружен EXIF маркер)")
                return@withContext true
            }
            
            return@withContext false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса изображения: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Добавляет EXIF-маркер сжатия в изображение
     */
    suspend fun addProcessedImage(context: Context, uri: Uri, quality: Int) = withContext(Dispatchers.IO) {
        try {
            // Добавляем EXIF метку
            FileUtil.markCompressedImage(context, uri, quality)
            Timber.d("Добавлен EXIF маркер сжатия для URI: $uri")
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при добавлении маркера сжатия: ${e.message}")
        }
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
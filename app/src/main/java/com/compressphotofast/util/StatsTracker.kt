package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.HashSet

/**
 * Утилитарный класс для отслеживания статистики и статуса сжатия изображений
 */
object StatsTracker {
    // Константы статусов сжатия
    const val COMPRESSION_STATUS_NONE = 0
    const val COMPRESSION_STATUS_PROCESSING = 1
    const val COMPRESSION_STATUS_COMPLETED = 2
    const val COMPRESSION_STATUS_FAILED = 3

    // Множество для отслеживания URI в процессе обработки
    private val processingUris = mutableSetOf<String>()
    
    // Реестр URI, которые обрабатываются через MainActivity (через Intent)
    private val urisBeingProcessedByMainActivity = Collections.synchronizedSet(HashSet<String>())

    /**
     * Начинает отслеживание URI
     */
    fun startTracking(uri: Uri) {
        processingUris.add(uri.toString())
        Timber.d("Начато отслеживание URI: $uri")
    }

    /**
     * Обновляет статус сжатия для указанного URI
     */
    fun updateStatus(context: Context, uri: Uri, status: Int) {
        try {
            // Если статус завершающий (COMPLETED или FAILED), убираем URI из отслеживаемых
            if (status == COMPRESSION_STATUS_COMPLETED || status == COMPRESSION_STATUS_FAILED) {
                processingUris.remove(uri.toString())
                Timber.d("URI удален из отслеживаемых: $uri")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при обновлении статуса для $uri")
        }
    }

    /**
     * Проверяет, является ли изображение сжатым
     * Использует новую систему EXIF маркировки
     */
    suspend fun isImageProcessed(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверяем EXIF маркер
            val isCompressed = FileUtil.isCompressedByExif(context, uri)
            if (isCompressed) {
                Timber.d("Изображение по URI $uri уже сжато (обнаружен EXIF маркер)")
                return@withContext true
            }
            
            // Проверяем, находится ли файл в директории приложения (для обратной совместимости)
            val path = FileUtil.getFilePathFromUri(context, uri)
            val isInAppDir = !path.isNullOrEmpty() && 
                (path.contains("/${Constants.APP_DIRECTORY}/") || 
                 path.contains("content://media/external/images/media") && 
                 path.contains(Constants.APP_DIRECTORY))
            
            if (isInAppDir) {
                Timber.d("Изображение по URI $uri находится в директории приложения")
                return@withContext true
            }
            
            return@withContext false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса изображения: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Добавляет изображение в список обработанных
     * Устаревший метод, оставлен для обратной совместимости
     */
    fun addProcessedImage(context: Context, uri: Uri) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Вместо добавления в старый список просто добавляем EXIF метку
                val quality = 85 // Значение по умолчанию
                FileUtil.markCompressedImage(context, uri, quality)
                Timber.d("Добавлен EXIF маркер сжатия для URI: $uri")
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при добавлении URI в список обработанных: ${e.message}")
            }
        }
    }

    /**
     * Регистрирует URI, который обрабатывается через MainActivity
     * Метод для обратной совместимости
     */
    fun registerUriBeingProcessedByMainActivity(uri: Uri) {
        val uriString = uri.toString()
        urisBeingProcessedByMainActivity.add(uriString)
        Timber.d("Зарегистрирован URI для обработки через MainActivity: $uriString")
    }
    
    /**
     * Проверяет, обрабатывается ли URI через MainActivity
     * Метод для обратной совместимости
     */
    fun isUriBeingProcessedByMainActivity(uri: Uri): Boolean {
        val uriString = uri.toString()
        val isProcessing = urisBeingProcessedByMainActivity.contains(uriString)
        if (isProcessing) {
            Timber.d("URI обрабатывается через MainActivity: $uriString")
        }
        return isProcessing
    }
    
    /**
     * Снимает регистрацию URI, который больше не обрабатывается через MainActivity
     * Метод для обратной совместимости
     */
    fun unregisterUriBeingProcessedByMainActivity(uri: Uri) {
        val uriString = uri.toString()
        urisBeingProcessedByMainActivity.remove(uriString)
        Timber.d("Снята регистрация URI для обработки через MainActivity: $uriString")
    }
    
    /**
     * Отмечает изображение как обработанное
     * Метод для обратной совместимости
     */
    fun markImageAsProcessed(context: Context, uri: Uri) {
        addProcessedImage(context, uri)
    }
} 
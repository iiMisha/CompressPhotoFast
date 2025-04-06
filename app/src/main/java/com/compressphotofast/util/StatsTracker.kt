package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import com.compressphotofast.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Утилитарный класс для отслеживания статистики и статуса сжатия изображений
 */
object StatsTracker {
    // Константы статусов сжатия
    const val COMPRESSION_STATUS_NONE = 0
    const val COMPRESSION_STATUS_PROCESSING = 1
    const val COMPRESSION_STATUS_COMPLETED = 2
    const val COMPRESSION_STATUS_FAILED = 3
    const val COMPRESSION_STATUS_SKIPPED = 4

    // Множество для отслеживания URI в процессе обработки
    private val processingUris = mutableSetOf<String>()
    
    /**
     * Начинает отслеживание URI
     */
    fun startTracking(uri: Uri) {
        processingUris.add(uri.toString())
        LogUtil.processDebug("Начато отслеживание URI: $uri")
    }

    /**
     * Обновляет статус сжатия для указанного URI
     */
    fun updateStatus(uri: Uri, status: Int) {
        try {
            // Если статус завершающий (COMPLETED или FAILED), убираем URI из отслеживаемых
            if (status == COMPRESSION_STATUS_COMPLETED || status == COMPRESSION_STATUS_FAILED || status == COMPRESSION_STATUS_SKIPPED) {
                processingUris.remove(uri.toString())
                LogUtil.processDebug("URI удален из отслеживаемых: $uri")
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "UPDATE_STATUS", "Ошибка при обновлении статуса", e)
        }
    }

    /**
     * Проверяет, нужно ли обрабатывать изображение
     * Делегирует к централизованной логике в ImageProcessingChecker
     */
    suspend fun shouldProcessImage(context: Context, uri: Uri): Boolean {
        return ImageProcessingChecker.shouldProcessImage(context, uri)
    }

    /**
     * Проверяет, обрабатывается ли URI через MainActivity
     * Метод для обратной совместимости
     * @deprecated Больше не используется, всегда возвращает false
     */
    @Deprecated("Метод больше не используется, всегда возвращает false")
    fun isUriBeingProcessedByMainActivity(): Boolean {
        return false // Всегда возвращает false, так как механизм регистрации больше не используется
    }
} 
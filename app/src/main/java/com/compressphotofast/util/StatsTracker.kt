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

    /**
     * Начинает отслеживание URI (для логирования)
     */
    fun startTracking(uri: Uri) {
        LogUtil.processDebug("Начато отслеживание URI: $uri")
    }

    /**
     * Обновляет статус сжатия для указанного URI (для логирования)
     */
    fun updateStatus(uri: Uri, status: Int) {
        try {
            // Если статус завершающий (COMPLETED или FAILED), логируем
            if (status == COMPRESSION_STATUS_COMPLETED || status == COMPRESSION_STATUS_FAILED || status == COMPRESSION_STATUS_SKIPPED) {
                LogUtil.processDebug("URI завершил обработку со статусом $status: $uri")
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
     * Регистрирует ошибку удаления файла для метрик
     */
    fun recordDeleteFailure(uri: Uri?) {
        LogUtil.warning(uri, "StatsTracker", "Зафиксирована ошибка удаления файла")
    }
} 
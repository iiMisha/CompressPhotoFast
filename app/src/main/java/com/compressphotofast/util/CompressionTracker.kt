package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import timber.log.Timber

/**
 * Утилитарный класс для отслеживания статуса сжатия изображений
 */
object CompressionTracker {
    // Константы статусов сжатия
    const val COMPRESSION_STATUS_NONE = 0
    const val COMPRESSION_STATUS_PROCESSING = 1
    const val COMPRESSION_STATUS_COMPLETED = 2
    const val COMPRESSION_STATUS_FAILED = 3

    private const val PREF_NAME = "compression_tracker"
    private const val MAX_TRACKED_ITEMS = 1000

    // Множество для отслеживания URI в процессе обработки
    private val processingUris = mutableSetOf<String>()

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
            updateCompressionStatus(context, uri, status)
            
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
     * Проверяет, отслеживается ли URI в данный момент
     */
    fun isBeingTracked(uri: Uri): Boolean {
        return processingUris.contains(uri.toString())
    }

    /**
     * Обновляет статус сжатия для указанного URI
     */
    private fun updateCompressionStatus(context: Context, uri: Uri, status: Int) {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
                putInt(uri.toString(), status)
                apply()
            }
            Timber.d("Обновлен статус сжатия для $uri: $status")
            
            // Очищаем старые записи, если их слишком много
            cleanupOldEntries(context)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при обновлении статуса сжатия для $uri")
        }
    }

    /**
     * Получает текущий статус сжатия для указанного URI
     */
    fun getCompressionStatus(context: Context, uri: Uri): Int {
        return try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(uri.toString(), COMPRESSION_STATUS_NONE)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении статуса сжатия для $uri")
            COMPRESSION_STATUS_NONE
        }
    }

    /**
     * Очищает статус сжатия для указанного URI
     */
    fun clearCompressionStatus(context: Context, uri: Uri) {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
                remove(uri.toString())
                apply()
            }
            processingUris.remove(uri.toString())
            Timber.d("Очищен статус сжатия для $uri")
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при очистке статуса сжатия для $uri")
        }
    }

    /**
     * Очищает все статусы сжатия
     */
    fun clearAllCompressionStatuses(context: Context) {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
                clear()
                apply()
            }
            processingUris.clear()
            Timber.d("Очищены все статусы сжатия")
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при очистке всех статусов сжатия")
        }
    }

    /**
     * Очищает старые записи, если их количество превышает лимит
     */
    private fun cleanupOldEntries(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            if (prefs.all.size > MAX_TRACKED_ITEMS) {
                val entries = prefs.all.entries.toList()
                val entriesToRemove = entries.take(entries.size - MAX_TRACKED_ITEMS)
                
                prefs.edit {
                    entriesToRemove.forEach { 
                        remove(it.key)
                        processingUris.remove(it.key)
                    }
                    apply()
                }
                
                Timber.d("Очищено ${entriesToRemove.size} старых записей статусов сжатия")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при очистке старых записей статусов сжатия")
        }
    }
} 
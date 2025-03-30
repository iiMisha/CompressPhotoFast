package com.compressphotofast.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Менеджер для работы с Toast сообщениями с поддержкой дедупликации
 */
object ToastManager {
    private const val TAG = "ToastManager"
    private val lastMessages = ConcurrentHashMap<String, Long>()
    private const val DUPLICATE_TIMEOUT_MS = 1500L
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Показывает Toast с проверкой дублирования
     */
    fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        val currentTime = System.currentTimeMillis()
        val lastShown = lastMessages[message] ?: 0L

        if (currentTime - lastShown > DUPLICATE_TIMEOUT_MS) {
            lastMessages[message] = currentTime
            Log.d(TAG, "Showing toast: $message")
            mainHandler.post {
                try {
                    Toast.makeText(context, message, duration).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка отображения Toast: ${e.message}")
                }
            }
        } else {
            Log.d(TAG, "Skipping duplicate toast: $message")
        }
    }

    /**
     * Показывает Toast с результатами сжатия изображения
     */
    fun showCompressionResultToast(context: Context, fileName: String, originalSize: Long, compressedSize: Long, duration: Int = Toast.LENGTH_LONG) {
        val originalSizeStr = FileUtil.formatFileSize(originalSize)
        val compressedSizeStr = FileUtil.formatFileSize(compressedSize)
        
        val reductionPercent = if (originalSize > 0) {
            ((originalSize - compressedSize) * 100.0 / originalSize).roundToInt()
        } else {
            0
        }
        
        val message = "$fileName: $originalSizeStr → $compressedSizeStr (-$reductionPercent%)"
        showToast(context, message, duration)
    }
} 
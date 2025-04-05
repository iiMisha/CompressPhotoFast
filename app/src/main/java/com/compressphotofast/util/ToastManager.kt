package com.compressphotofast.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.compressphotofast.util.LogUtil

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
        try {
            // Проверяем, не показывали ли мы это сообщение недавно
            if (shouldSkipDuplicateToast(message)) {
                LogUtil.debug("ToastManager", "Skipping duplicate toast: $message")
                return
            }

            // Показываем Toast
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, message, duration).show()
                LogUtil.debug("ToastManager", "Showing toast: $message")
            }
        } catch (e: Exception) {
            LogUtil.errorWithException("ToastManager", e)
        }
    }

    private fun shouldSkipDuplicateToast(message: String): Boolean {
        val now = System.currentTimeMillis()
        val lastShown = lastMessages[message]
        
        // Если сообщение недавно показывалось (в пределах DUPLICATE_TIMEOUT_MS), пропускаем
        if (lastShown != null && now - lastShown < DUPLICATE_TIMEOUT_MS) {
            LogUtil.debug("ToastManager", "Skipping duplicate toast: $message")
            return true
        }
        
        // Обновляем время последнего показа для этого сообщения
        lastMessages[message] = now
        return false
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
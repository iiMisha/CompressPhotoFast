package com.compressphotofast.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Централизованный класс для отображения Toast-сообщений
 * Предотвращает дублирование кода и содержит логику для предотвращения
 * одновременного отображения нескольких Toast-сообщений
 */
object ToastManager {
    // Хранит последнее показанное сообщение и время его отображения
    private var lastToastInfo: Pair<String, Long>? = null
    
    // Минимальный интервал между одинаковыми сообщениями (в миллисекундах)
    private const val DEDUPE_WINDOW_MS = 3000L
    
    // Обработчик для отображения Toast на основном потоке
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * Показывает Toast-сообщение
     * @param context Контекст приложения
     * @param message Текст сообщения
     * @param duration Длительность отображения (Toast.LENGTH_SHORT или Toast.LENGTH_LONG)
     */
    fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        // Предотвращаем переполнение экрана одинаковыми сообщениями
        val currentTime = System.currentTimeMillis()
        val lastInfo = lastToastInfo
        
        if (lastInfo != null && 
            lastInfo.first == message && 
            currentTime - lastInfo.second < DEDUPE_WINDOW_MS) {
            // Пропускаем дублирующееся сообщение
            Timber.d("Toast отфильтрован (дубликат): $message")
            return
        }
        
        // Сохраняем информацию о последнем сообщении
        lastToastInfo = Pair(message, currentTime)
        
        // Отображаем Toast в основном потоке
        mainHandler.post {
            try {
                Toast.makeText(context.applicationContext, message, duration).show()
                Timber.d("Toast показан: $message")
            } catch (e: Exception) {
                Timber.e(e, "Ошибка отображения Toast: ${e.message}")
            }
        }
    }
    
    /**
     * Показывает Toast-сообщение с результатами сжатия
     * @param context Контекст приложения
     * @param fileName Имя файла
     * @param originalSize Исходный размер в байтах
     * @param compressedSize Размер после сжатия в байтах
     */
    fun showCompressionResultToast(
        context: Context,
        fileName: String,
        originalSize: Long,
        compressedSize: Long
    ) {
        // Вычисляем процент сжатия
        val reduction = if (originalSize > 0) {
            ((originalSize - compressedSize).toFloat() / originalSize) * 100
        } else 0f
        
        // Форматируем размеры
        val originalSizeStr = formatFileSize(originalSize)
        val compressedSizeStr = formatFileSize(compressedSize)
        val reductionStr = reduction.toInt().toString()
        
        // Обрезаем слишком длинное имя файла
        val maxFileNameLength = 25
        val truncatedFileName = if (fileName.length > maxFileNameLength) {
            "..." + fileName.takeLast(maxFileNameLength - 3)
        } else {
            fileName
        }
        
        // Формируем сообщение
        val message = "$truncatedFileName: $originalSizeStr → $compressedSizeStr (-$reductionStr%)"
        
        // Отображаем toast
        showToast(context, message, Toast.LENGTH_LONG)
    }
    
    /**
     * Форматирует размер файла в читаемый вид (KB, MB)
     * @param size Размер в байтах
     * @return Отформатированная строка
     */
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        }
    }
} 
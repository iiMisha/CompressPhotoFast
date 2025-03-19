package com.compressphotofast.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import android.widget.Toast
import com.compressphotofast.R
import com.compressphotofast.ui.MainActivity
import com.compressphotofast.service.BackgroundMonitoringService
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Утилитарный класс для работы с уведомлениями и Toast
 */
object NotificationUtil {
    // Карта для отслеживания времени последнего показа конкретного сообщения
    private val lastMessageTime = ConcurrentHashMap<String, Long>()
    
    // Минимальный интервал между показом Toast (мс)
    private const val MIN_TOAST_INTERVAL = 3000L
    
    // Блокировка для синхронизации показа Toast
    private val toastLock = Any()
    
    // Флаг, указывающий что Toast в процессе отображения
    private var isToastShowing = false
    
    /**
     * Создание уведомления для фонового сервиса
     */
    fun createBackgroundServiceNotification(context: Context): Notification {
        val notificationIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Создаем действие для остановки сервиса
        val stopIntent = Intent(context, BackgroundMonitoringService::class.java).apply {
            action = Constants.ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(context, context.getString(R.string.notification_channel_id))
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_background_service))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_launcher_foreground,
                context.getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notification_background_service_description)))
            .build()
    }
    
    /**
     * Настраивает канал уведомлений для Android 8.0+
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                context.getString(R.string.notification_channel_id),
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = context.getString(R.string.notification_channel_description)
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Показывает Toast с результатом сжатия
     */
    fun showCompressionResultToast(context: Context, fileName: String, originalSize: Long, compressedSize: Long, reduction: Float) {
        // Запускаем на главном потоке
        Handler(Looper.getMainLooper()).post {
            try {
                // Сокращаем длинное имя файла
                val truncatedFileName = FileUtil.truncateFileName(fileName)
                
                // Форматируем размеры
                val originalSizeStr = FileUtil.formatFileSize(originalSize)
                val compressedSizeStr = FileUtil.formatFileSize(compressedSize)
                val reductionStr = String.format("%.1f", reduction)
                
                // Создаем текст уведомления
                val message = "$truncatedFileName: $originalSizeStr → $compressedSizeStr (-$reductionStr%)"
                
                // Показываем Toast
                showToast(context, message, Toast.LENGTH_LONG)
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при показе Toast о результате сжатия")
            }
        }
    }
    
    /**
     * Показывает Toast с дедупликацией сообщений
     * Централизованный метод для всего приложения
     */
    fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        Handler(Looper.getMainLooper()).post {
            synchronized(toastLock) {
                try {
                    // Проверяем, не показывали ли мы недавно это сообщение
                    val lastTime = lastMessageTime[message] ?: 0L
                    val currentTime = System.currentTimeMillis()
                    val timePassed = currentTime - lastTime
                    
                    if (timePassed <= MIN_TOAST_INTERVAL) {
                        Timber.d("Пропуск Toast - сообщение '$message' уже было показано ${timePassed}мс назад")
                        return@synchronized
                    }
                    
                    // Если Toast уже отображается, пропускаем
                    if (isToastShowing) {
                        Timber.d("Пропуск Toast - другое сообщение уже отображается")
                        return@synchronized
                    }
                    
                    // Обновляем время последнего показа
                    lastMessageTime[message] = currentTime
                    
                    // Устанавливаем флаг
                    isToastShowing = true
                    
                    // Показываем Toast
                    val toast = Toast.makeText(context, message, duration)
                    
                    // Добавляем callback для сброса флага
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        toast.addCallback(object : Toast.Callback() {
                            override fun onToastHidden() {
                                super.onToastHidden()
                                isToastShowing = false
                            }
                        })
                    } else {
                        // Для API < 30 сбрасываем флаг через примерное время отображения
                        Handler(Looper.getMainLooper()).postDelayed({
                            isToastShowing = false
                        }, if (duration == Toast.LENGTH_LONG) 3500 else 2000)
                    }
                    
                    toast.show()
                    
                    // Очищаем старые записи через двойное время интервала
                    Handler(Looper.getMainLooper()).postDelayed({
                        lastMessageTime.remove(message)
                    }, MIN_TOAST_INTERVAL * 2)
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при показе Toast: ${e.message}")
                    isToastShowing = false
                }
            }
        }
    }

    /**
     * Показывает уведомление о завершении операции
     */
    fun showCompletionNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, context.getString(R.string.notification_channel_id))
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        notificationManager.notify(notificationId, notification)
    }
} 
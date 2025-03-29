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
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo

/**
 * Утилитарный класс для работы с уведомлениями и Toast
 * Централизованная точка для всех операций с уведомлениями
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
     * Общий метод для создания уведомления с различными настройками
     * Централизованный метод для создания всех уведомлений в приложении
     */
    fun createNotification(
        context: Context,
        channelId: String,
        title: String,
        content: String,
        smallIcon: Int = R.drawable.ic_launcher_foreground,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT,
        ongoing: Boolean = false,
        contentIntent: PendingIntent? = null,
        autoCancel: Boolean = !ongoing,
        actions: List<NotificationAction> = emptyList(),
        progress: ProgressInfo? = null
    ): Notification {
        // Убеждаемся, что канал уведомлений создан
        createDefaultNotificationChannel(context)
        
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(smallIcon)
            .setPriority(priority)
            .setOngoing(ongoing)
            .setAutoCancel(autoCancel)
        
        // Добавляем интент для открытия приложения при нажатии на уведомление
        contentIntent?.let { builder.setContentIntent(it) }
        
        // Добавляем информацию о прогрессе, если она предоставлена
        progress?.let { 
            builder.setProgress(it.max, it.current, it.indeterminate)
        }
        
        // Добавляем действия, если они предоставлены
        actions.forEach { action ->
            builder.addAction(
                action.iconRes,
                action.title,
                action.pendingIntent
            )
        }
        
        return builder.build()
    }
    
    /**
     * Модель действия в уведомлении
     */
    data class NotificationAction(
        val iconRes: Int,
        val title: String,
        val pendingIntent: PendingIntent
    )
    
    /**
     * Модель информации о прогрессе
     */
    data class ProgressInfo(
        val max: Int,
        val current: Int,
        val indeterminate: Boolean = false
    )
    
    /**
     * Создание уведомления для фонового сервиса
     */
    fun createForegroundNotification(
        context: Context, 
        title: String, 
        content: String
    ): Notification {
        // Создаем Intent для открытия приложения при нажатии на уведомление
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Создаем Intent для остановки сервиса
        val stopIntent = Intent(context, BackgroundMonitoringService::class.java).apply {
            action = Constants.ACTION_STOP_SERVICE
        }
        
        val stopPendingIntent = PendingIntent.getService(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Создаем и возвращаем уведомление
        return createNotification(
            context = context,
            channelId = context.getString(R.string.notification_channel_id),
            title = title,
            content = content,
            priority = NotificationCompat.PRIORITY_LOW,
            ongoing = true,
            contentIntent = pendingIntent,
            actions = listOf(
                NotificationAction(
                    iconRes = android.R.drawable.ic_menu_close_clear_cancel,
                    title = context.getString(R.string.notification_stop),
                    pendingIntent = stopPendingIntent
                )
            )
        )
    }
    
    /**
     * Создание ForegroundInfo для WorkManager
     */
    fun createForegroundInfo(
        context: Context, 
        notificationTitle: String, 
        notificationId: Int,
        content: String = context.getString(R.string.notification_processing)
    ): ForegroundInfo {
        // Создаем уведомление
        val notification = createNotification(
            context = context,
            channelId = context.getString(R.string.notification_channel_id),
            title = notificationTitle,
            content = content,
            priority = NotificationCompat.PRIORITY_LOW,
            ongoing = true
        )
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
    
    /**
     * Создает основной канал уведомлений с настройками по умолчанию
     * Централизованный метод, используемый во всем приложении
     */
    fun createDefaultNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notification_channel_name)
            val description = context.getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            
            createNotificationChannel(
                context,
                context.getString(R.string.notification_channel_id),
                name,
                description,
                importance,
                showBadge = false,
                enableLights = false,
                enableVibration = false
            )
            
            // Создаем дополнительный канал для уведомлений о завершении сжатия
            createNotificationChannel(
                context,
                "compression_completion_channel",
                context.getString(R.string.notification_completion_channel_name),
                context.getString(R.string.notification_completion_channel_description),
                NotificationManager.IMPORTANCE_DEFAULT,
                showBadge = true,
                enableLights = true,
                enableVibration = true
            )
            
            Timber.d("Уведомления: каналы уведомлений созданы")
        }
    }
    
    /**
     * Создает канал уведомлений с заданными параметрами
     * Универсальный метод для создания любых каналов уведомлений в приложении
     */
    fun createNotificationChannel(
        context: Context,
        channelId: String,
        channelName: String,
        description: String,
        importance: Int,
        showBadge: Boolean = true,
        enableLights: Boolean = true,
        enableVibration: Boolean = true
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                importance
            ).apply {
                this.description = description
                setShowBadge(showBadge)
                enableLights(enableLights)
                enableVibration(enableVibration)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Timber.d("Уведомления: создан канал $channelName (id=$channelId)")
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
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = createNotification(
            context = context,
            channelId = context.getString(R.string.notification_channel_id),
            title = title,
            content = message,
            autoCancel = true,
            contentIntent = pendingIntent
        )
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Создание уведомления для фонового сервиса мониторинга
     */
    fun createBackgroundServiceNotification(context: Context): Notification {
        return createForegroundNotification(
            context,
            context.getString(R.string.background_service_notification_title),
            context.getString(R.string.background_service_notification_text)
        )
    }

    /**
     * Показывает уведомление о начале обработки нескольких изображений
     */
    fun showBatchProcessingStartedNotification(context: Context, count: Int) {
        // Создаем Intent для открытия приложения при нажатии на уведомление
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = createNotification(
            context = context,
            channelId = context.getString(R.string.notification_channel_id),
            title = context.getString(R.string.notification_batch_processing_title),
            content = context.getString(R.string.notification_batch_processing_start, count),
            ongoing = true,
            contentIntent = pendingIntent,
            progress = ProgressInfo(count, 0, false)
        )
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(Constants.NOTIFICATION_ID_BATCH_PROCESSING, notification)
    }
    
    /**
     * Обновляет уведомление о прогрессе обработки нескольких изображений
     */
    fun updateBatchProcessingNotification(context: Context, progress: com.compressphotofast.ui.MultipleImagesProgress) {
        // Создаем Intent для остановки обработки
        val stopIntent = Intent(context, MainActivity::class.java).apply {
            action = Constants.ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getActivity(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = createNotification(
            context = context,
            channelId = context.getString(R.string.notification_channel_id),
            title = context.getString(R.string.notification_batch_processing_title),
            content = context.getString(
                R.string.notification_batch_processing_progress,
                progress.processed,
                progress.total
            ),
            ongoing = true,
            actions = listOf(
                NotificationAction(
                    iconRes = R.drawable.ic_launcher_foreground,
                    title = context.getString(R.string.notification_action_stop),
                    pendingIntent = stopPendingIntent
                )
            ),
            progress = ProgressInfo(progress.total, progress.processed, false)
        )
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(Constants.NOTIFICATION_ID_BATCH_PROCESSING, notification)
    }
    
    /**
     * Показывает уведомление о завершении обработки нескольких изображений
     */
    fun showBatchProcessingCompletedNotification(context: Context, progress: com.compressphotofast.ui.MultipleImagesProgress) {
        // Создаем Intent для открытия приложения
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Создаем текст уведомления с учетом пропущенных файлов
        val contentText = if (progress.skipped > 0) {
            context.getString(
                R.string.notification_batch_processing_completed_with_skipped,
                progress.successful,
                progress.skipped,
                progress.failed,
                progress.total
            )
        } else {
            context.getString(
                R.string.notification_batch_processing_completed,
                progress.successful,
                progress.failed,
                progress.total
            )
        }
        
        val notification = createNotification(
            context = context,
            channelId = context.getString(R.string.notification_channel_id),
            title = context.getString(R.string.notification_batch_processing_completed_title),
            content = contentText,
            contentIntent = pendingIntent,
            autoCancel = true
        )
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(Constants.NOTIFICATION_ID_BATCH_PROCESSING, notification)
    }
    
    /**
     * Удаляет уведомление о прогрессе обработки
     */
    fun cancelBatchProcessingNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(Constants.NOTIFICATION_ID_BATCH_PROCESSING)
    }
    
    /**
     * Показывает уведомление о результате сжатия изображения (успешное или пропущенное)
     */
    fun showCompressionResultNotification(
        context: Context,
        fileName: String,
        originalSize: Long,
        compressedSize: Long,
        sizeReduction: Float,
        skipped: Boolean,
        notificationId: Int = Constants.NOTIFICATION_ID_COMPRESSION_RESULT
    ) {
        // Форматируем информацию о размерах файла
        val originalSizeStr = FileUtil.formatFileSize(originalSize)
        val compressedSizeStr = FileUtil.formatFileSize(compressedSize)
        val reductionStr = String.format("%.1f", sizeReduction)
        
        // Определяем заголовок и текст уведомления
        val title = if (skipped) {
            context.getString(R.string.notification_compression_skipped_title)
        } else {
            context.getString(R.string.notification_compression_completed_title)
        }
        
        val message = if (skipped) {
            context.getString(
                R.string.notification_compression_skipped_text,
                fileName,
                reductionStr
            )
        } else {
            context.getString(
                R.string.notification_compression_completed_text,
                fileName,
                originalSizeStr,
                compressedSizeStr,
                reductionStr
            )
        }
        
        // Показываем уведомление
        showCompletionNotification(context, title, message, notificationId)
    }
    
    /**
     * Проверяет, разрешены ли уведомления
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
} 
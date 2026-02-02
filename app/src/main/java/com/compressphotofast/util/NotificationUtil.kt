package com.compressphotofast.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import android.widget.Toast
import com.compressphotofast.R
import com.compressphotofast.ui.MainActivity
import com.compressphotofast.service.BackgroundMonitoringService
import com.compressphotofast.util.LogUtil
import java.util.concurrent.ConcurrentHashMap
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import com.compressphotofast.util.FileOperationsUtil

/**
 * Утилитарный класс для работы с уведомлениями и Toast
 * Централизованная точка для всех операций с уведомлениями
 */
object NotificationUtil {
    // Singleton coroutine scope для Toast и UI обновлений (требует Main thread)
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Карта для отслеживания времени последнего показа конкретного сообщения
    private val lastMessageTime = ConcurrentHashMap<String, Long>()
    
    // Минимальный интервал между показом Toast (мс)
    private const val MIN_TOAST_INTERVAL = 2000L
    
    // Блокировка для синхронизации показа Toast
    private val toastLock = Any()
    
    // Флаг, указывающий что Toast в процессе отображения
    private var isToastShowing = false
    
    /**
     * Получает NotificationManager из контекста
     */
    private fun getNotificationManager(context: Context): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    
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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        
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
        // Для foreground сервисов проверяем разрешения, но всё равно создаем уведомление
        // (иначе сервис не может работать)
        if (!canShowNotifications(context)) {
            LogUtil.debug("NotificationUtil", "Foreground service notification создан без разрешений - сервис требует уведомления: '$title'")
        }
        
        // Создаем Intent для открытия приложения при нажатии на уведомление
        val pendingIntent = createMainActivityPendingIntent(context)
        
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
        
        // Создаем и возвращаем уведомление (обязательно для foreground сервисов)
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
        // Для WorkManager foreground уведомлений проверяем разрешения, но всё равно создаем уведомление
        // (иначе worker не может работать в foreground режиме)
        if (!canShowNotifications(context)) {
            LogUtil.debug("NotificationUtil", "WorkManager ForegroundInfo создан без разрешений - worker требует уведомления: '$notificationTitle'")
        }
        
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
            // Изменено с IMPORTANCE_LOW на IMPORTANCE_DEFAULT для лучшей совместимости с Android 13+
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            
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
                NotificationManager.IMPORTANCE_HIGH,
                showBadge = true,
                enableLights = true,
                enableVibration = true
            )
            
            // LogUtil.notification("Уведомления: каналы уведомлений созданы")
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
            
            getNotificationManager(context).createNotificationChannel(channel)
            // LogUtil.notification("Уведомления: создан канал $channelName (id=$channelId)")
        }
    }
    
    /** 
     * Показывает Toast с результатом сжатия 
     */
    fun showCompressionResultToast(context: Context, fileName: String, originalSize: Long, compressedSize: Long, reduction: Float) {
        // Проверяем настройку перед показом Toast
        val settingsManager = SettingsManager.getInstance(context)
        if (!settingsManager.shouldShowCompressionToast()) {
            LogUtil.debug("NotificationUtil", "Toast о сжатии отключен в настройках")
            return
        }

        val truncatedFileName = FileOperationsUtil.truncateFileName(fileName)
        val originalSizeStr = FileOperationsUtil.formatFileSize(originalSize)
        val compressedSizeStr = FileOperationsUtil.formatFileSize(compressedSize)
        val reductionStr = String.format("%.1f", reduction)

        val message = "🖼️ $truncatedFileName: $originalSizeStr → $compressedSizeStr (-$reductionStr%)"
        showToast(context, message, Toast.LENGTH_LONG)
    }
    
    /**
     * Показывает Toast с результатом сжатия (вариант с вычислением процента сокращения)
     */
    fun showCompressionResultToast(context: Context, fileName: String, originalSize: Long, compressedSize: Long, duration: Int = Toast.LENGTH_LONG) {
        // Проверяем настройку перед показом Toast
        val settingsManager = SettingsManager.getInstance(context)
        if (!settingsManager.shouldShowCompressionToast()) {
            LogUtil.debug("NotificationUtil", "Toast о сжатии отключен в настройках")
            return
        }

        val originalSizeStr = FileOperationsUtil.formatFileSize(originalSize)
        val compressedSizeStr = FileOperationsUtil.formatFileSize(compressedSize)

        val reductionPercent = if (originalSize > 0) {
            ((originalSize - compressedSize) * 100.0 / originalSize).roundToInt()
        } else {
            0
        }

        val message = "$fileName: $originalSizeStr → $compressedSizeStr (-$reductionPercent%)"
        showToast(context, message, duration)
    }
    
    /**
     * Показывает Toast с дедупликацией сообщений
     * Улучшенная версия с использованием корутин и проверкой разрешений
     * Централизованный метод для всего приложения
     */
    fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        // Запускаем на главном потоке через singleton scope
        notificationScope.launch {
            synchronized(toastLock) {
                try {
                    // Проверяем разрешения на показ уведомлений (включая Toast на Android 13+)
                    if (!canShowNotifications(context)) {
                        LogUtil.debug("NotificationUtil", "Toast пропущен - отсутствуют разрешения на уведомления: '$message'")
                        return@synchronized
                    }
                    
                    // Проверяем, не показывали ли мы недавно это сообщение
                    val lastTime = lastMessageTime[message] ?: 0L
                    val currentTime = System.currentTimeMillis()
                    val timePassed = currentTime - lastTime
                    
                    if (timePassed <= MIN_TOAST_INTERVAL) {
                        LogUtil.debug("NotificationUtil", "Пропуск Toast - сообщение '$message' уже было показано ${timePassed}мс назад")
                        return@synchronized
                    }
                    
                    // Если Toast уже отображается, пропускаем
                    if (isToastShowing) {
                        LogUtil.debug("NotificationUtil", "Пропуск Toast - другое сообщение уже отображается")
                        return@synchronized
                    }
                    
                    // Обновляем время последнего показа
                    lastMessageTime[message] = currentTime
                    
                    // Устанавливаем флаг
                    isToastShowing = true
                    
                    // Показываем Toast с обработкой SecurityException для Android 13+
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
                    LogUtil.debug("NotificationUtil", "Показан Toast: $message")
                    
                    // Очищаем старые записи через двойное время интервала
                    Handler(Looper.getMainLooper()).postDelayed({
                        lastMessageTime.remove(message)
                    }, MIN_TOAST_INTERVAL * 2)
                } catch (e: SecurityException) {
                    // Обработка ошибки безопасности на Android 13+
                    LogUtil.error(android.net.Uri.EMPTY, "Toast", "SecurityException при показе Toast - отсутствует разрешение POST_NOTIFICATIONS: '$message'", e)
                    isToastShowing = false
                } catch (e: Exception) {
                    LogUtil.errorWithException("NotificationUtil", e) 
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
        // Проверяем разрешения перед показом уведомления
        if (!canShowNotifications(context)) {
            LogUtil.debug("NotificationUtil", "Completion notification пропущен - отсутствуют разрешения: '$title'")
            return
        }
        
        try {
            val pendingIntent = createMainActivityPendingIntent(context)
            
            val notification = createNotification(
                context = context,
                channelId = "compression_completion_channel",
                title = title,
                content = message,
                autoCancel = true,
                contentIntent = pendingIntent
            )
            
            getNotificationManager(context).notify(notificationId, notification)
            LogUtil.debug("NotificationUtil", "Показано completion notification: $title")
        } catch (e: SecurityException) {
            LogUtil.error(android.net.Uri.EMPTY, "Notification", "SecurityException при показе completion notification - отсутствует разрешение POST_NOTIFICATIONS: '$title'", e)
        } catch (e: Exception) {
            LogUtil.errorWithException("NotificationUtil", e)
        }
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
        val originalSizeStr = FileOperationsUtil.formatFileSize(originalSize)
        val compressedSizeStr = FileOperationsUtil.formatFileSize(compressedSize)
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
    
    /**
     * Централизованная проверка возможности показа уведомлений
     * Проверяет разрешение POST_NOTIFICATIONS для Android 13+ и общее состояние уведомлений
     */
    fun canShowNotifications(context: Context): Boolean {
        // Проверяем общее состояние уведомлений
        if (!areNotificationsEnabled(context)) {
            LogUtil.debug("NotificationUtil", "Уведомления отключены в настройках системы")
            return false
        }
        
        // Для Android 13+ проверяем разрешение POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context, 
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (!hasPermission) {
                LogUtil.debug("NotificationUtil", "Отсутствует разрешение POST_NOTIFICATIONS для Android 13+")
                return false
            }
        }
        
        return true
    }
    
    /**
     * Создает PendingIntent для открытия главного активити
     */
    fun createMainActivityPendingIntent(context: Context, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    /**
     * Создает и/или обновляет уведомление о прогрессе с возможностью отмены
     * @param context Контекст приложения
     * @param notificationId ID уведомления
     * @param title Заголовок уведомления
     * @param content Текст уведомления
     * @param progress Прогресс выполнения (0-100)
     * @param max Максимальное значение прогресса
     * @param indeterminate Показывать ли неопределенный прогресс
     * @param cancelAction Action для кнопки отмены (если null, кнопка не отображается)
     */
    fun showProgressNotification(
        context: Context,
        notificationId: Int,
        title: String,
        content: String,
        progress: Int,
        max: Int = 100,
        indeterminate: Boolean = false,
        cancelAction: String? = null
    ) {
        // Проверяем разрешения перед показом уведомления
        if (!canShowNotifications(context)) {
            LogUtil.debug("NotificationUtil", "Progress notification пропущен - отсутствуют разрешения: '$title'")
            return
        }
        
        try {
            // Создаем Intent для открытия приложения при нажатии на уведомление
            val contentIntent = createMainActivityPendingIntent(context)
            
            // Список действий для уведомления
            val actions = mutableListOf<NotificationAction>()
            
            // Добавляем кнопку отмены, если указан action
            if (cancelAction != null) {
                val cancelIntent = Intent(cancelAction)
                val cancelPendingIntent = PendingIntent.getBroadcast(
                    context,
                    notificationId,
                    cancelIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
                
                actions.add(
                    NotificationAction(
                        iconRes = android.R.drawable.ic_menu_close_clear_cancel,
                        title = context.getString(R.string.notification_action_stop),
                        pendingIntent = cancelPendingIntent
                    )
                )
            }
            
            // Создаем уведомление с прогрессом
            val notification = createNotification(
                context = context,
                channelId = context.getString(R.string.notification_channel_id),
                title = title,
                content = content,
                priority = NotificationCompat.PRIORITY_LOW,
                ongoing = true,
                contentIntent = contentIntent,
                actions = actions,
                progress = ProgressInfo(max, progress, indeterminate)
            )
            
            // Показываем уведомление
            getNotificationManager(context).notify(notificationId, notification)
            LogUtil.debug("NotificationUtil", "Показано progress notification: $title ($progress/$max)")
        } catch (e: SecurityException) {
            LogUtil.error(android.net.Uri.EMPTY, "Notification", "SecurityException при показе progress notification - отсутствует разрешение POST_NOTIFICATIONS: '$title'", e)
        } catch (e: Exception) {
            LogUtil.errorWithException("NotificationUtil", e)
        }
    }
    
    /**
     * Обновляет уведомление о прогрессе
     * @deprecated Используйте showProgressNotification вместо этого метода
     */
    @Deprecated("Используйте showProgressNotification вместо этого метода", 
                ReplaceWith("showProgressNotification(context, notificationId, title, content, progress, max, indeterminate)"))
    fun updateProgressNotification(
        context: Context,
        notificationId: Int,
        title: String,
        content: String,
        progress: Int,
        max: Int = 100,
        indeterminate: Boolean = false
    ) {
        showProgressNotification(context, notificationId, title, content, progress, max, indeterminate)
    }
    
    /**
     * Отменяет уведомление
     */
    fun cancelNotification(context: Context, notificationId: Int) {
        getNotificationManager(context).cancel(notificationId)
    }
    
    /**
     * Показывает групповое уведомление для нескольких результатов сжатия
     */
    fun showBatchCompressionNotification(
        context: Context,
        successfulCount: Int,
        skippedCount: Int,
        totalOriginalSize: Long,
        totalCompressedSize: Long,
        totalSizeReduction: Float,
        individualResults: List<BatchNotificationItem>
    ) {
        // Показываем только итоговое уведомление (без индивидуальных)
        showSummaryNotification(
            context = context,
            successfulCount = successfulCount,
            skippedCount = skippedCount,
            totalOriginalSize = totalOriginalSize,
            totalCompressedSize = totalCompressedSize,
            totalSizeReduction = totalSizeReduction,
            totalCount = individualResults.size
        )
    }
    
    /**
     * Показывает summary уведомление для группы
     */
    private fun showSummaryNotification(
        context: Context,
        successfulCount: Int,
        skippedCount: Int,
        totalOriginalSize: Long,
        totalCompressedSize: Long,
        totalSizeReduction: Float,
        totalCount: Int
    ) {
        // Проверяем разрешения перед показом уведомления
        if (!canShowNotifications(context)) {
            LogUtil.debug("NotificationUtil", "Summary notification пропущен - отсутствуют разрешения")
            return
        }
        
        try {
            val pendingIntent = createMainActivityPendingIntent(context)
            
            // Формируем заголовок
            val title = if (successfulCount > 0) {
                "📦 Сжато $successfulCount фото"
            } else {
                "⏭️ Обработано $totalCount фото"
            }
            
            // Формируем текст
            val message = buildString {
                if (successfulCount > 0) {
                    val originalSizeStr = FileOperationsUtil.formatFileSize(totalOriginalSize)
                    val compressedSizeStr = FileOperationsUtil.formatFileSize(totalCompressedSize)
                    val reductionStr = String.format("%.1f", totalSizeReduction)
                    append("$originalSizeStr → $compressedSizeStr (-$reductionStr%)")
                    
                    if (skippedCount > 0) {
                        append("\nПропущено: $skippedCount фото")
                    }
                } else {
                    append("Все файлы пропущены (уже сжаты или малый размер)")
                }
            }
            
            // Создаем обычное уведомление без группировки
            val builder = NotificationCompat.Builder(context, "compression_completion_channel")
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
            
            getNotificationManager(context).notify(Constants.NOTIFICATION_ID_COMPRESSION_SUMMARY, builder.build())
            LogUtil.debug("NotificationUtil", "Показано summary notification: $title")
        } catch (e: SecurityException) {
            LogUtil.error(android.net.Uri.EMPTY, "Notification", "SecurityException при показе summary notification - отсутствует разрешение POST_NOTIFICATIONS", e)
        } catch (e: Exception) {
            LogUtil.errorWithException("NotificationUtil", e)
        }
    }
    
    /**
     * Показывает индивидуальное уведомление внутри группы
     */
    private fun showIndividualNotificationInGroup(
        context: Context,
        result: BatchNotificationItem,
        index: Int
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // Для старых версий Android не показываем индивидуальные уведомления в группе
            return
        }
        
        // Проверяем разрешения перед показом уведомления
        if (!canShowNotifications(context)) {
            LogUtil.debug("NotificationUtil", "Individual notification пропущен - отсутствуют разрешения: '${result.fileName}'")
            return
        }
        
        try {
            val title = if (result.skipped) "⏭️ ${result.fileName}" else "✅ ${result.fileName}"
            
            val message = if (result.skipped) {
                result.skipReason ?: "Пропущен"
            } else {
                val originalSizeStr = FileOperationsUtil.formatFileSize(result.originalSize)
                val compressedSizeStr = FileOperationsUtil.formatFileSize(result.compressedSize)
                val reductionStr = String.format("%.1f", result.sizeReduction)
                "$originalSizeStr → $compressedSizeStr (-$reductionStr%)"
            }
            
            val builder = NotificationCompat.Builder(context, "compression_completion_channel")
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(if (result.skipped) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_save)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setGroup(Constants.NOTIFICATION_GROUP_COMPRESSION)
                .setGroupSummary(false)
            
            val notificationId = Constants.NOTIFICATION_ID_COMPRESSION_INDIVIDUAL_BASE + index
            getNotificationManager(context).notify(notificationId, builder.build())
            LogUtil.debug("NotificationUtil", "Показано individual notification: ${result.fileName}")
        } catch (e: SecurityException) {
            LogUtil.error(android.net.Uri.EMPTY, "Notification", "SecurityException при показе individual notification - отсутствует разрешение POST_NOTIFICATIONS: '${result.fileName}'", e)
        } catch (e: Exception) {
            LogUtil.errorWithException("NotificationUtil", e)
        }
    }
    
    /**
     * Очищает все уведомления группы сжатия
     */
    fun clearCompressionNotificationGroup(context: Context) {
        // Отменяем summary уведомление
        cancelNotification(context, Constants.NOTIFICATION_ID_COMPRESSION_SUMMARY)
        
        // Отменяем индивидуальные уведомления (максимум 50)
        for (i in 0..50) {
            cancelNotification(context, Constants.NOTIFICATION_ID_COMPRESSION_INDIVIDUAL_BASE + i)
        }
    }
    
    /**
     * Данные для индивидуального уведомления в батче
     */
    data class BatchNotificationItem(
        val fileName: String,
        val originalSize: Long,
        val compressedSize: Long,
        val sizeReduction: Float,
        val skipped: Boolean,
        val skipReason: String? = null
    )
    
    /**
     * Отправляет Broadcast о результате сжатия
     */
    fun sendCompressionResultBroadcast(
        context: Context,
        uriString: String,
        fileName: String,
        originalSize: Long,
        compressedSize: Long,
        sizeReduction: Float,
        skipped: Boolean,
        skipReason: String? = null,
        batchId: String? = null
    ) {
        try {
            // Определяем тип уведомления: о завершении или о пропуске
            val action = if (skipped) 
                Constants.ACTION_COMPRESSION_SKIPPED 
            else 
                Constants.ACTION_COMPRESSION_COMPLETED
                
            // Отправляем информацию через broadcast
            val intent = Intent(action).apply {
                putExtra(Constants.EXTRA_FILE_NAME, fileName)
                putExtra(Constants.EXTRA_URI, uriString)
                putExtra(Constants.EXTRA_ORIGINAL_SIZE, originalSize)
                putExtra(Constants.EXTRA_COMPRESSED_SIZE, compressedSize)
                putExtra(Constants.EXTRA_REDUCTION_PERCENT, sizeReduction)
                if (skipReason != null) {
                    putExtra(Constants.EXTRA_SKIP_REASON, skipReason)
                }
                if (batchId != null) {
                    putExtra(Constants.EXTRA_BATCH_ID, batchId)
                }
                flags = Intent.FLAG_RECEIVER_FOREGROUND
            }
            context.sendBroadcast(intent)
            
            // Показываем уведомление о результате сжатия с помощью централизованного метода
            showCompressionResultNotification(
                context = context,
                fileName = fileName,
                originalSize = originalSize,
                compressedSize = compressedSize,
                sizeReduction = sizeReduction,
                skipped = skipped
            )
            
            // Логируем информацию о результате
            val message = if (skipped) {
                "Уведомление о пропуске сжатия отправлено: Файл=$fileName, экономия=${String.format("%.1f", sizeReduction)}%"
            } else {
                "Уведомление о завершении сжатия отправлено: Файл=$fileName"
            }
            LogUtil.processInfo(message)
        } catch (e: Exception) {
            LogUtil.error(Uri.EMPTY, "Отправка уведомления", "Ошибка при отправке уведомления о ${if (skipped) "пропуске" else "завершении"} сжатия", e)
        }
    }

    /**
     * Очищает coroutine scope (должен вызываться при уничтожении приложения)
     */
    fun destroy() {
        notificationScope.cancel()
    }
} 
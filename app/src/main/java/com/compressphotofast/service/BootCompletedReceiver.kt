package com.compressphotofast.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.compressphotofast.util.Constants
import timber.log.Timber

/**
 * BroadcastReceiver для запуска сервиса при загрузке системы
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Timber.d("Получен BOOT_COMPLETED, запускаем сервисы")
            
            // Проверяем, включено ли автоматическое сжатие
            val prefs = context.getSharedPreferences(
                Constants.PREF_FILE_NAME,
                Context.MODE_PRIVATE
            )
            val isAutoCompressionEnabled = prefs.getBoolean(Constants.PREF_AUTO_COMPRESSION, false)
            
            if (isAutoCompressionEnabled) {
                // Запускаем JobService
                ImageDetectionJobService.scheduleJob(context)
                
                // Запускаем фоновый сервис
                val serviceIntent = Intent(context, BackgroundMonitoringService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                
                Timber.d("Фоновые сервисы запущены после загрузки системы")
            }
        }
    }
} 
package com.compressphotofast.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import com.compressphotofast.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * BroadcastReceiver для запуска сервиса при загрузке системы
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Timber.d("Получен сигнал о загрузке системы")
            
            // Проверяем, включено ли автоматическое сжатие
            val isAutoCompressionEnabled = sharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, false)
            
            if (isAutoCompressionEnabled) {
                Timber.d("Запуск фонового сервиса после загрузки системы")
                val serviceIntent = Intent(context, BackgroundMonitoringService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
} 
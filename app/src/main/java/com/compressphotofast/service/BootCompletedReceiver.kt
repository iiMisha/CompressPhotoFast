package com.compressphotofast.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * BroadcastReceiver для запуска сервиса при загрузке системы
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Timber.d("Получен BOOT_COMPLETED, запускаем JobService")
            ImageDetectionJobService.scheduleJob(context)
        }
    }
} 
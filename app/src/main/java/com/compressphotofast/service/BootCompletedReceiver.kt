package com.compressphotofast.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.compressphotofast.util.LogUtil

/**
 * BroadcastReceiver для автоматического восстановления мониторинга новых фото
 * после загрузки системы ([Intent.ACTION_BOOT_COMPLETED]) и после обновления пакета
 * ([Intent.ACTION_MY_PACKAGE_REPLACED]).
 *
 * Восстановление происходит только при включенном автосжатии — запуск и проверки
 * делегируются в [MonitoringController.startMonitoring], который атомарно проверяет
 * настройку, планирует резервный JobScheduler-триггер и поднимает постоянную
 * foreground-службу.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        LogUtil.processDebug("BootCompletedReceiver: получен $action, восстанавливаем мониторинг")

        com.compressphotofast.worker.GalleryReconciliationWorker.schedule(context, catchUp = true)

        // Единая точка запуска: проверяет флаг автосжатия и поднимает оба механизма.
        // Если автосжатие выключено — controller ничего не запустит.
        val result = MonitoringController.startMonitoring(context)
        LogUtil.processDebug("BootCompletedReceiver: результат восстановления мониторинга: $result")
    }
}

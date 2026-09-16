package com.compressphotofast.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Помощник для запроса исключения приложения из системной оптимизации батареи (Doze).
 *
 * Фоновая служба обнаружения фото работает надёжнее, если система не применяет к ней
 * ограничения Doze. Программно включить исключение без согласия пользователя нельзя,
 * поэтому помощник запускает системный диалог [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS].
 *
 * Запрос показывается однократно (флаг [SettingsManager.isBatteryExemptionRequested]),
 * чтобы не создавать навязчивый цикл системных диалогов при отказе. Отказ не отключает
 * автосжатие — приложение продолжает работу в режиме best-effort.
 */
object BatteryOptimizationHelper {

    /**
     * Проверяет, исключено ли приложение из оптимизации батареи.
     *
     * @return `true`, если приложение уже в списке исключений (или оптимизация неприменима).
     */
    fun isExempted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Запускает системный диалог запроса исключения из оптимизации батареи.
     *
     * @return `true`, если системный экран успешно запущен; `false`, если intent недоступен
     *   (OEM-сборки) — в этом случае вызывающая сторона может использовать [openBatterySettings].
     */
    fun requestExemption(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            LogUtil.warning(
                null,
                "BatteryOptimizationHelper",
                "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS недоступен: ${e.message}"
            )
            openBatterySettings(context)
        }
    }

    /**
     * Открывает экран оптимизации батареи (fallback, если прямой запрос недоступен).
     *
     * @return `true` при успешном запуске любого подходящего системного экрана.
     */
    fun openBatterySettings(context: Context): Boolean {
        // Пробуем системный экран оптимизации батареи
        val tried = listOf(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        )
        for (action in tried) {
            try {
                val intent = Intent(action).apply {
                    if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // пробуем следующий вариант
            }
        }
        // Последняя попытка — общий список приложений
        return try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            LogUtil.warning(null, "BatteryOptimizationHelper", "Не удалось открыть настройки батареи: ${e.message}")
            false
        }
    }
}

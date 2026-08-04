package com.compressphotofast.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.SettingsManager

/**
 * Результат попытки запуска постоянной службы мониторинга.
 */
enum class MonitoringStartResult {
    /** Foreground-служба успешно запущена (или уже запускается). */
    STARTED,

    /** Автосжатие выключено — мониторинг не требуется. */
    DISABLED,

    /** Запуск не удался (например, системные ограничения Android 12+); остаётся резервный Job. */
    FAILED
}

/**
 * Единая точка управления жизненным циклом постоянной службы обнаружения новых фото.
 *
 * Координирует запуск/остановку двух взаимодополняющих механизмов:
 *  - постоянной foreground-службы [BackgroundMonitoringService] (тип `specialUse`,
 *    real-time `ContentObserver`, без лимита времени на Android 14+);
 *  - резервного content-trigger задания [ImageDetectionJobService] (JobScheduler,
 *    подхватывает фото, если служба была убита системой).
 *
 * Используется из UI-переключателя, [com.compressphotofast.service.BootCompletedReceiver]
 * и [com.compressphotofast.ui.MainViewModel], чтобы не дублировать логику запуска/остановки.
 *
 * Абсолютная «неубиваемость» невозможна на Android: force stop, отзыв разрешений и
 * ручное ограничение батареи пользователем не обходятся программно. После обычного
 * завершения процесса и перезагрузки мониторинг восстанавливается автоматически.
 */
object MonitoringController {

    /**
     * Запускает мониторинг, если включено автосжатие.
     *
     * @return [MonitoringStartResult] — результат попытки.
     */
    fun startMonitoring(context: Context): MonitoringStartResult {
        val settingsManager = SettingsManager.getInstance(context)
        if (!settingsManager.isAutoCompressionEnabled()) {
            LogUtil.processDebug("MonitoringController: автосжатие выключено, мониторинг не запускается")
            return MonitoringStartResult.DISABLED
        }

        // Резервный механизм обеспечивается первым и независимо от результата
        // попытки foreground-start (Android 12+ может его запретить).
        ensureDetectionJob(context)

        // Постоянная foreground-служба — real-time обнаружение через ContentObserver.
        return startForegroundService(context)
    }

    /** Идемпотентно оставляет один content-trigger Job в JobScheduler. */
    fun ensureDetectionJob(context: Context) {
        try {
            ImageDetectionJobService.scheduleJob(context)
        } catch (e: Exception) {
            LogUtil.error(null, "MonitoringController", "Не удалось обеспечить recovery Job", e)
        }
    }

    /**
     * Запускает постоянную foreground-службу мониторинга.
     *
     * Не проверяет флаг автосжатия: используется как для запуска при включенной настройке,
     * так и для разовой задачи обработки конкретного фото (служба сама завершится в `onCreate`,
     * если автосжатие выключено). Ошибки старта (например,
     * `ForegroundServiceStartNotAllowedException` на Android 12+ при запуске из фона)
     * подавляются, чтобы не ронять приложение — остаётся резервный JobScheduler.
     */
    fun startForegroundService(context: Context): MonitoringStartResult {
        val serviceIntent = Intent(context, BackgroundMonitoringService::class.java)
        return try {
            ContextCompat.startForegroundService(context, serviceIntent)
            LogUtil.processDebug("MonitoringController: foreground-служба мониторинга запущена")
            MonitoringStartResult.STARTED
        } catch (e: Exception) {
            LogUtil.error(
                null,
                "MonitoringController",
                "Не удалось запустить foreground-службу: ${e.javaClass.simpleName}: ${e.message}",
                e
            )
            MonitoringStartResult.FAILED
        }
    }

    /**
     * Останавливает мониторинг.
     *
     * @param disableAutoCompression если `true` — отключает автосжатие в настройках,
     *   чтобы служба не была автоматически перезапущена системой (`START_STICKY`),
     *   boot-receiver'ом или резервным Job'ом.
     */
    fun stopMonitoring(context: Context, disableAutoCompression: Boolean = true) {
        if (disableAutoCompression) {
            SettingsManager.getInstance(context).setAutoCompression(false)
        }

        // Отменяем резервный content-trigger Job.
        ImageDetectionJobService.cancelJob(context)

        // Явно останавливаем постоянную службу. stopService безопасен, если служба не запущена.
        // Намеренно НЕ используем startForegroundService(ACTION_STOP_SERVICE), чтобы не поднимать
        // foreground-службу только ради её остановки (это может быть запрещено из фона).
        // Служба также обработает ACTION_STOP_SERVICE из кнопки уведомления отдельно.
        try {
            context.stopService(Intent(context, BackgroundMonitoringService::class.java))
        } catch (e: Exception) {
            LogUtil.warning(null, "MonitoringController", "Ошибка при остановке службы: ${e.message}")
        }

        LogUtil.processDebug("MonitoringController: мониторинг остановлен (disableAuto=$disableAutoCompression)")
    }
}

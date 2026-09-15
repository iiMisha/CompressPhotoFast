package com.compressphotofast

import android.app.Application
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.CompressionBatchTracker
import com.compressphotofast.util.TempFilesCleaner
import com.compressphotofast.util.MediaStoreUtil
import com.compressphotofast.util.BackupRecoveryHelper
import com.compressphotofast.util.OptimizedCacheUtil
import com.compressphotofast.util.SettingsManager
import com.compressphotofast.service.MonitoringController
import com.compressphotofast.worker.GalleryReconciliationWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import java.util.concurrent.Executors

/**
 * Основной класс приложения, отвечающий за инициализацию компонентов.
 */
@HiltAndroidApp
class CompressPhotoApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * Application-scoped корутинный скоуп для фоновых задач инициализации/очистки,
     * запускаемых в onCreate. SupervisorJob гарантирует, что одна упавшая задача
     * не отменяет остальные.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Настройка логирования
        if (BuildConfig.DEBUG) {
            // В режиме отладки показываем все логи
            Timber.plant(Timber.DebugTree())
        } else {
            // В релизной версии полностью отключаем логирование
            Timber.plant(ReleaseTree())
        }

        // Создание канала уведомлений (для Android 8.0+)
        // Используем централизованный метод из NotificationUtil
        NotificationUtil.createDefaultNotificationChannel(this)

        // Инициализация WorkManager с конфигурацией
        // Проверяем, что WorkManager еще не инициализирован (например, в тестах)
        try {
            WorkManager.initialize(
                applicationContext,
                workManagerConfiguration
            )
        } catch (e: IllegalStateException) {
            // WorkManager уже инициализирован (например, в тестах с WorkManagerTestInitHelper)
            // Игнорируем ошибку и продолжаем
            Timber.w("WorkManager already initialized, skipping initialization")
        }

        logPreviousExitForDebug()

        // Холодный старт может быть вызван WorkManager/JobScheduler после LMK.
        // Сначала обеспечиваем Job, затем best-effort пробуем вернуть FGS.
        if (SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()) {
            GalleryReconciliationWorker.schedule(applicationContext, catchUp = true)
            MonitoringController.startMonitoring(applicationContext)
        }

        // Очистка и восстановление после непредвиденного закрытия приложения
        // (kill, OOM, crash, перезагрузка). Запускается при каждом холодном старте,
        // независимо от того, включено ли автосжатие (foreground-сервис может не работать).
        performPostCrashCleanup()
    }

    /**
     * Запускает фоновую очистку orphan temp-файлов, stale IS_PENDING записей MediaStore
     * и восстановление повреждённых файлов из orphan backup'ов.
     *
     * Ранее эти операции выполнялись только из [BackgroundMonitoringService], что
     * оставляло окно уязвимости при выключенном автосжатии: orphan `exif_backup_*` /
     * `replace_backup_*` файлы копились в cacheDir бесконечно, а stale pending-записи
     * засоряли MediaStore. Теперь очистка гарантированно выполняется при холодном старте.
     */
    private fun performPostCrashCleanup() {
        // TempFilesCleaner.cleanupTempFiles — синхронная, запускаем в одном потоке
        Executors.newSingleThreadExecutor().execute {
            try {
                TempFilesCleaner.cleanupTempFiles(applicationContext)
            } catch (e: Exception) {
                LogUtil.errorWithException("APP_POST_CRASH_CLEANUP", e)
            }
        }
        // cleanupStalePendingEntries и recoverPendingBackups — suspend, нужен скоуп
        appScope.launch {
            try {
                MediaStoreUtil.cleanupStalePendingEntries(applicationContext)
            } catch (e: Exception) {
                LogUtil.errorWithException("APP_STALE_PENDING_CLEANUP", e)
            }
            try {
                BackupRecoveryHelper.recoverPendingBackups(applicationContext)
            } catch (e: Exception) {
                LogUtil.errorWithException("APP_BACKUP_RECOVERY", e)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        // Очищаем статические ресурсы CompressionBatchTracker
        CompressionBatchTracker.destroyStatic()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            OptimizedCacheUtil.evictAll()
        }
    }

    /** Диагностика последнего завершения процесса только в debug-сборке. */
    private fun logPreviousExitForDebug() {
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val activityManager = getSystemService(ActivityManager::class.java)
            val exit = activityManager.getHistoricalProcessExitReasons(packageName, 0, 1)
                .firstOrNull() ?: return
            LogUtil.processDebug(
                "ApplicationExitInfo: reason=${exit.reason}(${exitReasonName(exit.reason)}), timestamp=${exit.timestamp}, " +
                    "pss=${exit.pss}KB, rss=${exit.rss}KB"
            )
        } catch (e: Exception) {
            LogUtil.warning(null, "CompressPhotoApp", "Не удалось прочитать ApplicationExitInfo: ${e.message}")
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        else -> "OTHER"
    }

    /**
     * Конфигурация для WorkManager с поддержкой Hilt
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .setDefaultProcessName("com.compressphotofast")
            .setExecutor(Executors.newFixedThreadPool(2))
            .setTaskExecutor(Executors.newFixedThreadPool(2))
            .build()
            
    /**
     * Дерево логирования для релизной версии, полностью отключает все логи
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Пустая реализация - не логируем ничего в релизе
        }
    }
}

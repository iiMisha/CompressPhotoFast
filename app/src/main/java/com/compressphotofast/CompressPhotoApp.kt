package com.compressphotofast

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.compressphotofast.R
import com.compressphotofast.util.FileInfoUtil
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.LogUtil
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Основной класс приложения, отвечающий за инициализацию компонентов.
 */
@HiltAndroidApp
class CompressPhotoApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        
        // Настройка логирования
        if (BuildConfig.DEBUG) {
            // В режиме отладки показываем все логи
            Timber.plant(Timber.DebugTree())
        } else {
            // В релизной версии фильтруем и показываем только важные логи
            Timber.plant(ReleaseTree())
        }
        
        // Создание канала уведомлений (для Android 8.0+)
        // Используем централизованный метод из NotificationUtil
        NotificationUtil.createDefaultNotificationChannel(this)

        // Очистка устаревших записей в кэше
        FileInfoUtil.cleanupCache()

        // Инициализация WorkManager с конфигурацией
        WorkManager.initialize(
            applicationContext,
            workManagerConfiguration
        )
    }

    /**
     * Конфигурация для WorkManager с поддержкой Hilt
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .setDefaultProcessName("com.compressphotofast")
            .build()
            
    /**
     * Дерево логирования для релизной версии, фильтрует логи по приоритету
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // В релизе логируем только ошибки и информационные сообщения
            if (priority == Log.ERROR || priority == Log.INFO) {
                // Записываем лог, возможно в файл или отправляем на сервер
                if (priority == Log.ERROR && t != null) {
                    LogUtil.errorWithMessageAndException("APP", message, t)
                } else if (priority == Log.ERROR) {
                    LogUtil.errorSimple("APP", message)
                } else if (priority == Log.INFO) {
                    LogUtil.processInfo(message)
                }
            }
        }
    }
} 
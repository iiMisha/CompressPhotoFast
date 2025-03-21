package com.compressphotofast

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.compressphotofast.R
import com.compressphotofast.util.NotificationUtil
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
            Timber.plant(Timber.DebugTree())
        }
        
        // Создание канала уведомлений (для Android 8.0+)
        // Используем централизованный метод из NotificationUtil
        NotificationUtil.createDefaultNotificationChannel(this)

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
} 
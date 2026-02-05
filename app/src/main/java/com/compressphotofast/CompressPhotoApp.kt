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
            // В релизной версии полностью отключаем логирование
            Timber.plant(ReleaseTree())
        }

        // Создание канала уведомлений (для Android 8.0+)
        // Используем централизованный метод из NotificationUtil
        NotificationUtil.createDefaultNotificationChannel(this)

        // Очистка устаревших записей в кэше
        FileInfoUtil.cleanupCache()

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
            Log.w("CompressPhotoApp", "WorkManager already initialized, skipping initialization")
        }
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
     * Дерево логирования для релизной версии, полностью отключает все логи
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Пустая реализация - не логируем ничего в релизе
        }
    }
} 
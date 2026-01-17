package com.compressphotofast.test

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * Тестовое приложение для unit тестов, которое инициализирует WorkManager тестовой инициализацией.
 */
class TestApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Инициализируем WorkManager для тестов до использования
        val config = Configuration.Builder()
            .setExecutor(Executors.newSingleThreadExecutor())
            .build()
            
        // Инициализируем WorkManager с тестовой конфигурацией
        WorkManagerTestInitHelper.initializeTestWorkManager(this, config)
        
        // Инициализируем Timber для тестов
        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
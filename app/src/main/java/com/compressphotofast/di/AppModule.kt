package com.compressphotofast.di

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.work.WorkManager
import com.compressphotofast.util.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt модуль для предоставления зависимостей на уровне приложения
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Предоставляет экземпляр SharedPreferences
     */
    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Предоставляет экземпляр DataStore для хранения настроек
     */
    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(Constants.PREF_FILE_NAME) }
        )
    }

    /**
     * Предоставляет экземпляр WorkManager
     */
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    /**
     * Предоставляет синглтон экземпляр OptimizedCacheUtil
     */
    @Provides
    @Singleton
    fun provideOptimizedCacheUtil(): com.compressphotofast.util.OptimizedCacheUtil {
        return com.compressphotofast.util.OptimizedCacheUtil
    }

    /**
     * Предоставляет синглтон экземпляр CompressionBatchTracker
     * Использует Application Context для предотвращения утечек памяти
     */
    @Provides
    @Singleton
    fun provideCompressionBatchTracker(@ApplicationContext context: Context): com.compressphotofast.util.CompressionBatchTracker {
        return com.compressphotofast.util.CompressionBatchTracker(context)
    }
}
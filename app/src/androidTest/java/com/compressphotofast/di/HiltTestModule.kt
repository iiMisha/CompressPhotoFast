package com.compressphotofast.di

import android.content.Context
import android.content.SharedPreferences
import com.compressphotofast.util.SettingsManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt модуль для instrumentation тестов.
 *
 * Предоставляет зависимости для тестов, которые используют Hilt.
 * Это базовая инфраструктура для будущего использования @Inject в тестах.
 */
@Module
@InstallIn(SingletonComponent::class)
object HiltTestModule {
    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("com.compressphotofast_preferences", Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideSettingsManager(sharedPreferences: SharedPreferences): SettingsManager {
        return SettingsManager(sharedPreferences)
    }
}

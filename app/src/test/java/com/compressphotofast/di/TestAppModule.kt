package com.compressphotofast.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Named

/**
 * Test Hilt модуль для предоставления зависимостей в тестах
 * Заменяет основные модули приложения на тестовые реализации
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AppModule::class])
object TestAppModule {

    /**
     * Предоставляет контекст приложения для тестов
     */
    @Provides
    @Named("test_context")
    fun provideTestContext(@ApplicationContext context: Context): Context {
        return context
    }
}

package com.compressphotofast.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/**
 * Test Hilt модуль для предоставления дополнительных зависимостей в тестах.
 *
 * Примечание: WorkManager предоставляется через WorkManagerTestModule,
 * который заменяет AppModule.
 */
@Module
@InstallIn(SingletonComponent::class)
object TestAppModule {

    /**
     * Предоставляет контекст приложения для тестов.
     * Используется для получения ApplicationContext в тестах.
     *
     * @param context Контекст приложения (предоставляется Robolectric/Hilt)
     * @return ApplicationContext
     */
    @Provides
    @Named("test_context")
    fun provideTestContext(@ApplicationContext context: Context): Context {
        return context
    }
}

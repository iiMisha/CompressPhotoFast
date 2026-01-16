package com.compressphotofast.di

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.util.concurrent.Executors
import javax.inject.Singleton

/**
 * Test Hilt модуль для предоставления WorkManager в unit тестах.
 *
 * Решает проблему IllegalStateException при попытке инициализации WorkManager в Robolectric тестах.
 * Использует WorkManagerTestInitHelper для proper initialization с SynchronousExecutor.
 *
 * **Важно:** Этот модуль заменяет только provideWorkManager из AppModule,
 * оставляя остальные зависимости (DataStore, SharedPreferences и т.д.) без изменений.
 *
 * Использование:
 * ```kotlin
 * @HiltAndroidTest
 * @RunWith(RobolectricTestRunner::class)
 * @Config(sdk = [29])
 * class MyTest : BaseUnitTest() {
 *     @Inject
 *     lateinit var workManager: WorkManager
 *
 *     @Test
 *     fun testSomething() = runTest {
 *         // WorkManager будет правильно инициализирован
 *     }
 * }
 * ```
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class]
)
object WorkManagerTestModule {

    /**
     * Предоставляет экземпляр WorkManager для тестов.
     *
     * Использует SynchronousExecutor для немедленного выполнения задач в тестах,
     * что делает тесты детерминированными и быстрыми.
     *
     * @param context Контекст приложения (предоставляется Robolectric)
     * @return Инициализированный экземпляр WorkManager
     */
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        // Создаем конфигурацию с SynchronousExecutor для немедленного выполнения задач
        val config = Configuration.Builder()
            .setExecutor(Executors.newSingleThreadExecutor())
            .build()

        // Инициализируем WorkManager для тестов
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        // Возвращаем экземпляр WorkManager
        return WorkManager.getInstance(context)
    }
}

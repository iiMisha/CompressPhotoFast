package com.compressphotofast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit Rule для тестирования корутин.
 * 
 * Предоставляет TestDispatcher для тестирования suspend функций.
 * Автоматически настраивает и сбрасывает MainDispatcher.
 * 
 * Использование:
 * ```kotlin
 * class MyTest {
 *     @get:Rule
 *     val coroutinesTestRule = CoroutinesTestRule()
 *     
 *     @Test
 *     fun testSomething() = coroutinesTestRule.testDispatcher.runTest {
 *         // Тестовый код с корутинами
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutinesTestRule(
    /**
     * TestDispatcher для тестирования корутин.
     * По умолчанию использует UnconfinedTestDispatcher.
     */
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    
    /**
     * Настройка перед каждым тестом.
     * Устанавливает MainDispatcher.
     */
    override fun starting(description: Description) {
        super.starting(description)
        Dispatchers.setMain(testDispatcher)
    }
    
    /**
     * Очистка после каждого теста.
     * Сбрасывает MainDispatcher.
     */
    override fun finished(description: Description) {
        super.finished(description)
        Dispatchers.resetMain()
    }
}

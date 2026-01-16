package com.compressphotofast

import io.mockk.MockKAnnotations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import timber.log.Timber

/**
 * Базовый класс для всех unit тестов.
 * 
 * Предоставляет общую настройку для:
 * - Инициализации MockK для мокинга зависимостей
 * - Настройки TestDispatcher для тестирования корутин
 * - Автоматической очистки после тестов
 * 
 * Использование:
 * ```kotlin
 * class MyTest : BaseUnitTest() {
 *     private lateinit var myClass: MyClass
 *     
 *     @Before
 *     override fun setUp() {
 *         super.setUp()
 *         myClass = MyClass()
 *     }
 *     
 *     @Test
 *     fun testSomething() = runTest {
 *         // Тестовый код
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseUnitTest {
    
    /**
     * TestDispatcher для тестирования корутин.
     * Использует UnconfinedTestDispatcher для немедленного выполнения корутин.
     */
    protected val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
    
    /**
     * Настройка перед каждым тестом.
     * Инициализирует MockK, Timber и настраивает TestDispatcher.
     */
    @Before
    open fun setUp() {
        // Инициализируем Timber для тестов
        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(testDispatcher)
    }
    
    /**
     * Очистка после каждого теста.
     * Сбрасывает MainDispatcher.
     */
    @After
    open fun tearDown() {
        Dispatchers.resetMain()
    }
}

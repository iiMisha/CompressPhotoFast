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
 * - Инициализации MockK для мокирования зависимостей
 * - Настройки TestDispatcher для тестирования корутин
 * - Автоматической очистки после тестов
 * - Инициализации Timber для логирования
 *
 * ## Особенности реализации
 *
 * ### TestDispatcher
 * Использует `UnconfinedTestDispatcher` для немедленного выполнения корутин в тестах.
 * Это обеспечивает:
 * - Детерминированное выполнение тестов
 * - Быстрое выполнение без задержек
 * - Предсказуемое поведение корутин
 *
 * ### WorkManager Integration
 * Работает в связке с `WorkManagerTestModule` для proper initialization WorkManager в тестах.
 * Это решает проблему IllegalStateException при попытке использовать WorkManager в unit тестах.
 *
 * ## Использование
 *
 * ### Базовый пример
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
 *         val result = myClass.doSomething()
 *         assertEquals(expected, result)
 *     }
 * }
 * ```
 *
 * ### Тестирование корутин
 * ```kotlin
 * @Test
 * fun testAsyncOperation() = runTest {
 *     // Корутины выполняются немедленно на testDispatcher
 *     viewModel.loadData()
 *
 *     // Ожидание завершения всех корутин (если нужно)
 *     advanceUntilIdle()
 *
 *     // Проверки
 *     assertEquals(expected, viewModel.data.value)
 * }
 * ```
 *
 * ### Использование с MockK
 * ```kotlin
 * class MyTest : BaseUnitTest() {
 *     @MockK
 *     lateinit var repository: MyRepository
 *
 *     @Before
 *     override fun setUp() {
 *         super.setUp() // Важно: вызвать super.setUp() для инициализации MockK
 *         every { repository.getData() } returns flowOf("data")
 *     }
 * }
 * ```
 *
 * ### Тестирование Flow
 * ```kotlin
 * @Test
 * fun testFlow() = runTest {
 *     val flow = repository.getData()
 *
 *     // Собираем значения из Flow
 *     val values = mutableListOf<String>()
 *     val job = launch(testDispatcher) {
 *         flow.toList(values)
 *     }
 *
 *     // Ожидаем завершения
 *     advanceUntilIdle()
 *
 *     // Проверяем значения
 *     assertEquals(listOf("data"), values)
 * }
 * ```
 *
 * ## Лучшие практики
 *
 * 1. **Всегда вызывайте super.setUp()** в методе setUp тестового класса
 * 2. **Используйте runTest {}** для тестов с корутинами
 * 3. **Не используйте Thread.sleep()** - вместо этого используйте advanceUntilIdle()
 * 4. **Очищайте ресурсы** в методе tearDown
 * 5. **Используйте try-finally** для очистки временных файлов
 *
 * ## Устранение неполадок
 *
 * ### Проблема: WorkManager не инициализируется
 * **Решение:** Убедитесь, что `WorkManagerTestModule` добавлен в di package и заменяет `AppModule`
 *
 * ### Проблема: Корутины не выполняются
 * **Решение:** Используйте `runTest {}` и `testDispatcher` для запуска корутин
 *
 * ### Проблема: Тесты зависают
 * **Решение:** Проверьте, что не используете блокирующие операции. Используйте `advanceUntilIdle()`
 * для ожидания завершения асинхронных операций.
 *
 * @see WorkManagerTestModule
 * @see kotlinx.coroutines.test.runTest
 * @see kotlinx.coroutines.test.TestDispatcher
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseUnitTest {

    /**
     * TestDispatcher для тестирования корутин.
     *
     * Использует UnconfinedTestDispatcher для:
     * - Немедленного выполнения корутин без переключения потоков
     * - Детерминированного поведения тестов
     * - Упрощения отладки тестов
     *
     * Для более реалистичного тестирования переключений контекста можно использовать
     * StandardTestDispatcher вместо UnconfinedTestDispatcher.
     */
    protected val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()

    /**
     * Настройка перед каждым тестом.
     *
     * Выполняет следующие действия:
     * 1. Инициализирует Timber для логирования в тестах
     * 2. Инициализирует MockK для мокирования зависимостей
     * 3. Устанавливает TestDispatcher в качестве MainDispatcher
     *
     * **Важно:** При переопределении в подклассах обязательно вызывайте super.setUp()
     * для правильной инициализации.
     */
    @Before
    open fun setUp() {
        // Инициализируем Timber для тестов, если еще не инициализирован
        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }

        // Инициализируем MockK для мокирования зависимостей
        // relaxUnitFun = true позволяет мокать функции, возвращающие Unit, без явного every
        MockKAnnotations.init(this, relaxUnitFun = true)

        // Устанавливаем TestDispatcher в качестве MainDispatcher для тестов
        // Это позволяет тестировать корутины без реального переключения потоков
        Dispatchers.setMain(testDispatcher)
    }

    /**
     * Очистка после каждого теста.
     *
     * Сбрасывает MainDispatcher в исходное состояние.
     *
     * **Важно:** При переопределении в подклассах обязательно вызывайте super.tearDown()
     * для правильной очистки.
     */
    @After
    open fun tearDown() {
        // Сбрасываем MainDispatcher в исходное состояние
        // Это необходимо для избежания влияния тестов друг на друга
        Dispatchers.resetMain()
    }
}

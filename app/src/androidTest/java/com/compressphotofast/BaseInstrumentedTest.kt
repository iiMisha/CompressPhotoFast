package com.compressphotofast

import androidx.activity.ComponentActivity
import androidx.annotation.IdRes
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import org.hamcrest.Matchers.not
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before

/**
 * Базовый класс для всех instrumentation тестов.
 *
 * Предоставляет общую настройку для:
 * - Запуска Activity для UI тестов
 * - Утилитных методов для Espresso
 *
 * Использование:
 * ```kotlin
 * class MainActivityTest : BaseInstrumentedTest() {
 *
 *     @Before
 *     override fun setUp() {
 *         super.setUp()
 *         // Инициализируем nullable activityScenario
 *         activityScenario = ActivityScenario.launch(MainActivity::class.java)
 *     }
 *
 *     @Test
 *     fun testButtonDisplayed() {
 *         // Используем безопасный вызов через ?.
 *         activityScenario?.onActivity { activity ->
 *             // Код с activity
 *         }
 *         assertViewDisplayed(R.id.myButton)
 *     }
 *
 *     @After
 *     override fun tearDown() {
 *         // Базовый класс автоматически закроет activityScenario
 *         super.tearDown()
 *     }
 * }
 * ```
 */
abstract class BaseInstrumentedTest {

    /**
     * ActivityScenario для управления жизненным циклом Activity.
     * Сделан nullable для безопасности в tearDown().
     */
    protected var activityScenario: ActivityScenario<out ComponentActivity>? = null

    /**
     * Настройка перед каждым тестом.
     */
    @Before
    open fun setUp() {
        // Пустая реализация для переопределения в дочерних классах
    }

    /**
     * Очистка после каждого теста.
     * Закрывает ActivityScenario безопасно.
     */
    @After
    open fun tearDown() {
        activityScenario?.close()
        activityScenario = null
    }
    
    /**
     * Проверяет, что View с указанным ID отображается на экране.
     * 
     * @param viewId ID View для проверки
     */
    protected fun assertViewDisplayed(@IdRes viewId: Int) {
        Espresso.onView(ViewMatchers.withId(viewId))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }
    
    /**
     * Проверяет, что View с указанным ID имеет определенный текст.
     * 
     * @param viewId ID View для проверки
     * @param text Ожидаемый текст
     */
    protected fun assertViewHasText(@IdRes viewId: Int, text: String) {
        Espresso.onView(ViewMatchers.withId(viewId))
            .check(ViewAssertions.matches(ViewMatchers.withText(text)))
    }
    
    /**
     * Проверяет, что View с указанным ID включена (enabled).
     * 
     * @param viewId ID View для проверки
     */
    protected fun assertViewEnabled(@IdRes viewId: Int) {
        Espresso.onView(ViewMatchers.withId(viewId))
            .check(ViewAssertions.matches(ViewMatchers.isEnabled()))
    }
    
    /**
     * Проверяет, что View с указанным ID отключена (disabled).
     * 
     * @param viewId ID View для проверки
     */
    protected fun assertViewDisabled(@IdRes viewId: Int) {
        Espresso.onView(ViewMatchers.withId(viewId))
            .check(ViewAssertions.matches(not(ViewMatchers.isEnabled())))
    }
}

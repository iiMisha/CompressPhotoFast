# Базовые классы тестов

## Содержание

1. [Введение](#введение)
2. [Инструкция по обновлению .kilocodeignore](#инструкция-по-обновлению-kilocodeignore)
3. [BaseUnitTest.kt](#baseunittestkt)
4. [CoroutinesTestRule.kt](#coroutinestestrulekt)
5. [BaseInstrumentedTest.kt](#baseinstrumentedtestkt)
6. [Примеры использования](#примеры-использования)
7. [Создание файлов](#создание-файлов)
8. [Проверка](#проверка)

---

## Введение

### Зачем нужны базовые классы тестов

Базовые классы тестов предоставляют общую инфраструктуру для всех тестов в проекте, что позволяет:

- **Избежать дублирования кода**: Общая настройка и очистка выполняются в одном месте
- **Обеспечить согласованность**: Все тесты используют одинаковые подходы к настройке
- **Упростить поддержку**: Изменения в настройке тестов вносятся в одном месте
- **Повысить читаемость**: Тесты фокусируются на логике, а не на настройке

### Преимущества использования базовых классов

1. **Единая настройка MockK**: Автоматическая инициализация моков для всех тестов
2. **Поддержка корутин**: TestDispatcher для тестирования suspend функций
3. **Автоматическая очистка**: Гарантированный сброс состояния после каждого теста
4. **Утилитные методы**: Готовые методы для Espresso в instrumentation тестах
5. **Hilt интеграция**: Автоматическое внедрение зависимостей в тесты

### Обзор создаваемых классов

| Класс | Тип тестов | Назначение |
|-------|-----------|-----------|
| [`BaseUnitTest.kt`](#baseunittestkt) | Unit тесты | Базовый класс для локальных тестов с поддержкой корутин |
| [`CoroutinesTestRule.kt`](#coroutinestestrulekt) | Unit тесты | JUnit Rule для тестирования корутин |
| [`BaseInstrumentedTest.kt`](#baseinstrumentedtestkt) | Instrumentation тесты | Базовый класс для UI тестов с Hilt и Espresso |

---

## Инструкция по обновлению .kilocodeignore

Перед созданием базовых классов тестов необходимо разрешить доступ к тестовым файлам в `.kilocodeignore`.

### Пошаговая инструкция

1. **Открыть файл `.kilocodeignore` в редакторе**

2. **Найти строки:**
   ```ignore
   # Тесты
   app/src/androidTest/
   app/src/test/
   ```

3. **Закомментировать или удалить эти строки:**
   ```ignore
   # Тесты
   # app/src/androidTest/
   # app/src/test/
   ```

4. **Сохранить файл**

После этого Kilo Code сможет создавать и редактировать файлы в директориях `app/src/test/` и `app/src/androidTest/`.

---

## BaseUnitTest.kt

Базовый класс для всех unit тестов. Предоставляет общую настройку для инициализации MockK и тестирования корутин.

### Полный код класса

```kotlin
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
    protected lateinit var testDispatcher: TestDispatcher
    
    /**
     * Настройка перед каждым тестом.
     * Инициализирует MockK и настраивает TestDispatcher.
     */
    @Before
    open fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        testDispatcher = UnconfinedTestDispatcher()
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
```

### Ключевые особенности

- **MockKAnnotations.init()**: Автоматически инициализирует все поля с аннотацией `@MockK`
- **relaxUnitFun = true**: Позволяет вызывать методы, возвращающие `Unit`, без явного `every { ... } just Runs`
- **UnconfinedTestDispatcher**: Корутины выполняются немедленно в текущем потоке
- **Dispatchers.setMain()**: Подменяет MainDispatcher для тестов

### Когда использовать

- Для всех unit тестов, которые используют корутины
- Для тестов, требующих мокирования зависимостей
- Для тестов ViewModel, UseCase, Repository и других компонентов

---

## CoroutinesTestRule.kt

JUnit Rule для тестирования корутин. Предоставляет альтернативный способ настройки TestDispatcher.

### Полный код класса

```kotlin
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
```

### Ключевые особенности

- **Наследует от TestWatcher**: Интегрируется с JUnit lifecycle
- **starting()**: Вызывается перед каждым тестом
- **finished()**: Вызывается после каждого теста
- **Гибкая настройка**: Можно передать любой TestDispatcher

### Когда использовать

- Когда не нужен BaseUnitTest (например, для простых тестов без моков)
- Когда нужно использовать несколько TestDispatcher в одном тесте
- Для тестов, где требуется более гибкая настройка корутин

---

## BaseInstrumentedTest.kt

Базовый класс для всех instrumentation тестов. Предоставляет общую настройку для Hilt, Activity и Espresso.

### Полный код класса

```kotlin
package com.compressphotofast

import androidx.activity.ComponentActivity
import androidx.annotation.IdRes
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * Базовый класс для всех instrumentation тестов.
 * 
 * Предоставляет общую настройку для:
 * - Внедрения зависимостей через Hilt
 * - Запуска Activity для UI тестов
 * - Утилитных методов для Espresso
 * 
 * Использование:
 * ```kotlin
 * @HiltAndroidTest
 * class MainActivityTest : BaseInstrumentedTest() {
 *     
 *     @Before
 *     override fun setUp() {
 *         super.setUp()
 *         activityScenario = ActivityScenario.launch(MainActivity::class.java)
 *     }
 *     
 *     @Test
 *     fun testButtonDisplayed() {
 *         assertViewDisplayed(R.id.myButton)
 *     }
 * }
 * ```
 */
@HiltAndroidTest
abstract class BaseInstrumentedTest {
    
    /**
     * Hilt Rule для внедрения зависимостей в тесты.
     */
    @get:Rule
    val hiltRule = HiltAndroidRule(this)
    
    /**
     * ActivityScenario для управления жизненным циклом Activity.
     */
    protected lateinit var activityScenario: ActivityScenario<ComponentActivity>
    
    /**
     * Настройка перед каждым тестом.
     * Инициализирует Hilt.
     */
    @Before
    open fun setUp() {
        hiltRule.inject()
    }
    
    /**
     * Очистка после каждого теста.
     * Закрывает ActivityScenario.
     */
    @After
    open fun tearDown() {
        if (::activityScenario.isInitialized) {
            activityScenario.close()
        }
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
            .check(ViewAssertions.matches(ViewMatchers.not(ViewMatchers.isEnabled())))
    }
}
```

### Ключевые особенности

- **@HiltAndroidTest**: Включает Hilt для instrumentation тестов
- **HiltAndroidRule**: Автоматическое внедрение зависимостей
- **ActivityScenario**: Безопасное управление жизненным циклом Activity
- **Утилитные методы Espresso**: Готовые методы для распространенных проверок

### Утилитные методы

| Метод | Описание |
|-------|----------|
| `assertViewDisplayed(viewId)` | Проверяет, что View отображается |
| `assertViewHasText(viewId, text)` | Проверяет текст View |
| `assertViewEnabled(viewId)` | Проверяет, что View включена |
| `assertViewDisabled(viewId)` | Проверяет, что View отключена |

### Когда использовать

- Для всех UI тестов с Espresso
- Для тестов, требующих внедрения зависимостей через Hilt
- Для тестов Activity, Fragment и других UI компонентов

---

## Примеры использования

### Пример 1: Unit тест с BaseUnitTest

```kotlin
package com.compressphotofast

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.MockK
import org.junit.Test

class SettingsManagerTest : BaseUnitTest() {
    
    private lateinit var settingsManager: SettingsManager
    private lateinit var context: Context
    
    @MockK
    private lateinit var mockDataStore: DataStore<Preferences>
    
    @Before
    override fun setUp() {
        super.setUp()
        context = ApplicationProvider.getApplicationContext()
        settingsManager = SettingsManager(context, mockDataStore)
    }
    
    @Test
    fun `getCompressionQuality returns default value when not set`() = runTest {
        // Arrange
        val expectedQuality = CompressionQuality.MEDIUM
        
        // Act
        val actualQuality = settingsManager.getCompressionQuality()
        
        // Assert
        assertThat(actualQuality).isEqualTo(expectedQuality)
    }
    
    @Test
    fun `setCompressionQuality saves value`() = runTest {
        // Arrange
        val quality = CompressionQuality.HIGH
        
        // Act
        settingsManager.setCompressionQuality(quality)
        
        // Assert
        // Проверка сохранения значения
    }
}
```

### Пример 2: Instrumentation тест с BaseInstrumentedTest

```kotlin
package com.compressphotofast

import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.compressphotofast.ui.MainActivity
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Test

@HiltAndroidTest
class MainActivityTest : BaseInstrumentedTest() {
    
    @Before
    override fun setUp() {
        super.setUp()
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
    }
    
    @Test
    fun `compressButton is displayed`() {
        assertViewDisplayed(R.id.compressButton)
    }
    
    @Test
    fun `compressButton has correct text`() {
        assertViewHasText(R.id.compressButton, "Сжать")
    }
    
    @Test
    fun `compressButton is enabled when image selected`() {
        // Arrange
        // Выбрать изображение
        
        // Assert
        assertViewEnabled(R.id.compressButton)
    }
}
```

### Пример 3: Использование CoroutinesTestRule

```kotlin
package com.compressphotofast

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ImageCompressionUtilTest {
    
    @get:Rule
    val coroutinesTestRule = CoroutinesTestRule()
    
    @Test
    fun `compressImage returns compressed file`() = coroutinesTestRule.testDispatcher.runTest {
        // Arrange
        val originalFile = createTestImageFile()
        
        // Act
        val result = ImageCompressionUtil.compressImage(
            originalFile,
            quality = 70
        )
        
        // Assert
        assertThat(result).isNotNull()
        assertThat(result.length()).isLessThan(originalFile.length())
    }
}
```

---

## Создание файлов

### Шаг 1: Создать BaseUnitTest.kt

**Путь:** `app/src/test/java/com/compressphotofast/BaseUnitTest.kt`

Скопируйте полный код класса из раздела [BaseUnitTest.kt](#baseunittestkt).

### Шаг 2: Создать CoroutinesTestRule.kt

**Путь:** `app/src/test/java/com/compressphotofast/CoroutinesTestRule.kt`

Скопируйте полный код класса из раздела [CoroutinesTestRule.kt](#coroutinestestrulekt).

### Шаг 3: Создать BaseInstrumentedTest.kt

**Путь:** `app/src/androidTest/java/com/compressphotofast/BaseInstrumentedTest.kt`

Скопируйте полный код класса из раздела [BaseInstrumentedTest.kt](#baseinstrumentedtestkt).

### Структура директорий

```
app/src/
├── test/
│   └── java/
│       └── com/
│           └── compressphotofast/
│               ├── BaseUnitTest.kt
│               └── CoroutinesTestRule.kt
└── androidTest/
    └── java/
        └── com/
            └── compressphotofast/
                └── BaseInstrumentedTest.kt
```

---

## Проверка

После создания файлов выполните сборку для проверки:

```bash
./gradlew assembleDebug
```

Если сборка прошла успешно, базовые классы готовы к использованию.

### Дополнительная проверка

Можно также запустить существующие тесты для проверки совместимости:

```bash
# Запуск unit тестов
./gradlew testDebugUnitTest

# Запуск instrumentation тестов (требуется подключенное устройство или эмулятор)
./gradlew connectedDebugAndroidTest
```

---

## Связанные документы

- [`TESTING.md`](TESTING.md) - Общая документация по тестированию
- [`TESTING_PLAN.md`](TESTING_PLAN.md) - План развития системы тестирования
- [Memory Bank: Architecture](../.kilocode/rules/memory-bank/architecture.md) - Архитектура приложения

---

## Полезные ресурсы

- [Testing Coroutines](https://developer.android.com/kotlin/coroutines/test)
- [Hilt Testing](https://dagger.dev/hilt/testing.html)
- [Espresso](https://developer.android.com/training/testing/espresso)
- [MockK](https://mockk.io/)

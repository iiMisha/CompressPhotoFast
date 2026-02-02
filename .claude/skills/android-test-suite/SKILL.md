---
name: android-test-suite
description: Комплексное тестирование Android приложения CompressPhotoFast (unit, instrumentation, E2E, performance тесты)
user-invocable: true
arguments:
  - name: test_type
    description: Тип тестов (unit, instrumentation, e2e, performance, all)
    required: false
    default: all
  - name: scope
    description: Область тестирования (whole_project, specific_module, specific_class)
    required: false
    default: whole_project
  - name: coverage
    description: Генерировать coverage отчет (true, false)
    required: false
    default: true
  - name: focus_module
    description: Конкретный модуль для тестирования (например, compression, util, ui)
    required: false
    default: null
  - name: continuous
    description: Непрерывный режим тестирования (true, false)
    required: false
    default: false
  - name: start_emulator
    description: Автоматически запускать эмулятор если нужен (true, false)
    required: false
    default: false
  - name: verbose
    description: Подробный вывод (true, false)
    required: false
    default: false
---

# Android Test Suite

Комплексный скилл для тестирования Android приложения CompressPhotoFast. Управляет всеми типами тестов: unit, instrumentation, E2E, performance.

## Типы тестов

### 1. Unit Тесты
**Расположение:** `app/src/test/`

**Что тестируют:**
- Логику утилит (`FileOperationsUtil`, `ImageCompressionUtil`, etc.)
- ViewModel (без Android фреймворка)
- Репозитории и Use Cases
- Обработку событий
- Форматирование данных

**Фреймворки:**
- JUnit 5
- MockK (моки)
- Truth (assertions)
- CoroutinesTestRule (для корутин)

**Запуск:**
```bash
./gradlew testDebugUnitTest
```

**Время выполнения:** 30-60 секунд

### 2. Instrumentation Тесты
**Расположение:** `app/src/androidTest/`

**Что тестируют:**
- Интеграцию с Android framework
- Работу с MediaStore
- Обработку HEIC изображений
- Настройки (SharedPreferences/DataStore)
- Интеграционные сценарии

**Фреймворки:**
- AndroidX Test
- Espresso (UI)
- UI Automator
- MockK (для instrumented)

**Запуск:**
```bash
./gradlew connectedDebugAndroidTest
```

**Время выполнения:** 3-5 минут

**Требования:** Нужно устройство или эмулятор

### 3. E2E Тесты
**Расположение:** `app/src/androidTest/java/com/compressphotofast/e2e/`

**Что тестируют:**
- Полные пользовательские сценарии:
  - Автоматическая компрессия
  - Ручная компрессия
  - Пакетная компрессия
  - Share Intent
  - Настройки приложения

**Фреймворки:**
- Espresso
- UI Automator
- ActivityScenario

**Запуск:**
```bash
./scripts/run_e2e_tests.sh
```

**Время выполнения:** 5-10 минут

### 4. Performance Тесты
**Расположение:**
- `app/src/test/performance/`
- `app/src/androidTest/performance/`

**Что тестируют:**
- Скорость компрессии
- Memory usage
- Обработка больших файлов
- Нагрузочное тестирование

**Метрики:**
- Время выполнения операций
- Потребление памяти
- Throughput (файлов/секунду)

**Запуск:**
```bash
./scripts/run_performance_tests.sh
```

## Скрипты для тестирования

### Основные скрипты

**`./scripts/run_unit_tests.sh`** - Unit тесты
```bash
./scripts/run_unit_tests.sh [опции]
Опции:
  -c, --continuous   Непрерывный режим
  -v, --verbose      Подробный вывод
```

**`./scripts/run_instrumentation_tests.sh`** - Instrumentation тесты
```bash
./scripts/run_instrumentation_tests.sh [опции]
Опции:
  --start-emulator   Запустить эмулятор если нет устройства
```

**`./scripts/run_all_tests.sh`** - Все тесты
```bash
./scripts/run_all_tests.sh [опции]
Опции:
  --start-emulator        Автоматически запустить эмулятор
  --skip-unit             Пропустить unit тесты
  --skip-instrumentation  Пропустить instrumentation тесты
```

**`./scripts/run_e2e_tests.sh`** - E2E тесты
```bash
./scripts/run_e2e_tests.sh [опции]
Опции:
  --start-emulator   Запустить эмулятор если нет устройства
  --specific-test    Запустить конкретный тест
```

**`./scripts/run_performance_tests.sh`** - Performance тесты
```bash
./scripts/run_performance_tests.sh [опции]
Опции:
  --iterations      Количество итераций (default: 10)
  --file-size       Размер тестовых файлов
```

**`./scripts/quick_test.sh`** - Быстрая проверка
```bash
./scripts/quick_test.sh
```
Запускает быструю проверку кода и базовые тесты.

### Вспомогательные скрипты

**`./scripts/check_device.sh`** - Проверка устройства
```bash
./scripts/check_device.sh [--start-emulator]
```
Проверяет наличие подключенного устройства/эмулятора.

**`./scripts/start_emulator.sh`** - Запуск эмулятора
```bash
./scripts/start_emulator.sh [avd_name]
```
Запускает эмулятор Small_Phone по умолчанию.

**`./scripts/generate_test_images.sh`** - Генерация тестовых изображений
```bash
./scripts/generate_test_images.sh [опции]
Опции:
  --count         Количество изображений
  --formats       Форматы через запятую (jpg,png,heic)
  --sizes         Размеры через запятую (small,medium,large)
```

## Как работает скилл

### Шаг 1: Анализ запроса
- Определение типа тестов из `test_type`
- Проверка области тестирования (`scope`, `focus_module`)
- Анализ необходимости эмулятора

### Шаг 2: Подготовка окружения
1. Проверка устройства (если нужен эмулятор)
2. Генерация тестовых данных (если нужно)
3. Очистка предыдущих результатов

### Шаг 3: Запуск тестов
Используется подходящий скрипт или Gradle задача:
- Unit: `./gradlew testDebugUnitTest`
- Instrumentation: `./gradlew connectedDebugAndroidTest`
- E2E: `./scripts/run_e2e_tests.sh`
- Performance: `./scripts/run_performance_tests.sh`

### Шаг 4: Анализ результатов
- Парсинг вывода тестов
- Сбор статистики (passed, failed, skipped)
- Анализ failures

### Шаг 5: Генерация отчета
- Coverage отчет (если `coverage=true`)
- HTML отчеты
- Рекомендации по исправлению

## Coverage отчеты

### Unit тесты
```bash
./gradlew jacocoTestReport
```
Отчет: `app/build/reports/jacoco/jacocoTestReport/html/index.html`

### Instrumentation тесты
```bash
./gradlew jacocoAndroidTestReport
```
Отчет: `app/build/reports/jacoco/jacocoAndroidTestReport/html/index.html`

### Объединенный отчет
```bash
./gradlew jacocoCombinedTestReport
```
Отчет: `app/build/reports/jacoco/jacocoCombinedTestReport/html/index.html`

### Открытие отчета
```bash
xdg-open app/build/reports/jacoco/jacocoCombinedTestReport/html/index.html
```

## Написание тестов

### Unit тест - шаблон

```kotlin
class MyUtilTest : BaseUnitTest() {

    private lateinit var util: MyUtil

    @Before
    override fun setUp() {
        super.setUp()
        util = MyUtil()
    }

    @Test
    fun `should do something when condition met`() = runTest {
        // Given
        val input = "test"

        // When
        val result = util.process(input)

        // Then
        assertThat(result).isTrue()
    }

    @Test
    fun `should throw exception when invalid input`() = runTest {
        // Given
        val input = ""

        // When & Then
        assertThrows<IllegalArgumentException> {
            util.process(input)
        }
    }
}
```

### Instrumentation тест - шаблон

```kotlin
class MyIntegrationTest : BaseInstrumentedTest() {

    @Test
    fun testMediaStoreIntegration() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testFile = createTestImage(context)

        // When
        val result = MediaStoreUtil.saveImage(context, testFile)

        // Then
        assertThat(result).isNotNull()
        assertThat(result.uri).isNotNull()
    }
}
```

### E2E тест - шаблон

```kotlin
class MyFeatureE2ETest : BaseE2ETest() {

    @Test
    fun testCompleteUserFlow() {
        // Given
        launchMainActivity()

        // When - пользователь выполняет действия
        onView(withId(R.id.settings_button))
            .perform(click())

        onView(withId(R.id.enable_auto_compression))
            .perform(click())

        pressBack()

        // Then - проверяем результат
        onView(withId(R.id.status_text))
            .check(matches(withText("Автоматическая компрессия включена")))
    }
}
```

## Использование скилла

### Базовое использование
```
Запусти android-test-suite
```
Запустит все тесты (unit + instrumentation) с coverage.

### Только unit тесты
```
Запусти android-test-suite с test_type=unit
```

### E2E тесты с эмулятором
```
Запусти android-test-suite с test_type=e2e и start_emulator=true
```

### Тестирование конкретного модуля
```
Запусти android-test-suite с test_type=unit и focus_module=compression
Протестируй класс ImageCompressionUtil
```

### Performance тесты
```
Запусти android-test-suite с test_type=performance и coverage=false
```

### Непрерывный режим
```
Запусти android-test-suite с test_type=unit и continuous=true
```
Тесты будут перезапускаться при изменениях кода.

## Анализ результатов тестов

### Формат отчета

```markdown
# Android Test Results

## Summary
- Test Type: Unit
- Total: 156 tests
- Passed: 154 ✅
- Failed: 2 ❌
- Skipped: 0 ⏭️
- Duration: 45.3s
- Coverage: 78.5%

## Failed Tests

### 1. ImageCompressionUtilTest.testCompressJPEG_InvalidInput
**Location:** `app/src/test/.../ImageCompressionUtilTest.kt:45`

**Error:**
```
Expected exception IllegalArgumentException but code completed successfully
```

**Stack Trace:**
```
at ImageCompressionUtilTest.testCompressJPEG_InvalidInput(ImageCompressionUtilTest.kt:45)
...
```

**Fix:**
```kotlin
// Before
@Test
fun testCompressJPEG_InvalidInput() {
    util.compressJPEG(null) // не выбрасывает исключение
}

// After
@Test
fun testCompressJPEG_InvalidInput() {
    val exception = assertThrows<IllegalArgumentException> {
        util.compressJPEG(null)
    }
    assertThat(exception).hasMessageThat().contains("Input cannot be null")
}
```

---

### 2. SettingsManagerTest.testSaveSettings_InvalidValue
...

## Recommendations

1. Добавить проверки на null в ImageCompressionUtil
2. Увеличить coverage модуля compression (текущий: 65%)
3. Добавить тесты для edge cases в SettingsManager

## Next Steps
1. Исправить failing тесты
2. Добавить недостающие тестовые случаи
3. Увеличить покрытие кода до 80%+
```

## Best Practices

### 1. Написание тестов
- **Изолированность**: Каждый тест должен быть независимым
- **Читаемость**: Используйте Given-When-Then паттерн
- **Скорость**: Unit тесты должны быть быстрыми (< 1сек каждый)
- **覆盖**: Тестируйте не только happy path, но и error cases

### 2. Тестирование асинхронного кода
```kotlin
@Test
fun testAsyncOperation() = runTest {
    // Given
    val deferred = async { util.doAsyncWork() }

    // When
    val result = deferred.await()

    // Then
    assertThat(result).isNotNull()
}
```

### 3. Моки в тестах
```kotlin
@Test
fun testWithMock() = runTest {
    // Given
    val mockRepository = mockk<Repository>()
    every { mockRepository.getData() } returns Result.success("data")

    val util = MyUtil(mockRepository)

    // When
    val result = util.process()

    // Then
    verify { mockRepository.getData() }
    assertThat(result).isTrue()
}
```

### 4. Тестирование корутин
```kotlin
@Test
fun testCoroutine() = runTest {
    // Given
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)

    // When
    viewModel.loadData()

    // Then
    advanceUntilIdle()
    assertThat(viewModel.state.value).isEqualTo(State.Loaded)
}
```

## Troubleshooting

### Проблема: Нет устройства для instrumentation тестов
**Решение:**
```bash
./scripts/start_emulator.sh
# или
./scripts/check_device.sh --start-emulator
```

### Проблема: Тесты падают с OutOfMemoryError
**Решение:** Увеличить heap size для тестов в `app/build.gradle.kts`:
```kotlin
testOptions {
    unitTests {
        isIncludeAndroidResources = true
        maxHeapSize = "4g"
    }
}
```

### Проблема: Медленные тесты
**Решения:**
1. Используйте `@Ignore` для медленных тестов
2. Параллельный запуск: `./gradlew test --parallel`
3. Используйте Robolectric вместо instrumented тестов где возможно

### Проблема: Flaky тесты (нестабильные)
**Решения:**
1. Добавить явные ожидания: `advanceUntilIdle()`
2. Использовать IdlingResources для Espresso
3. Добавить retry логику
4. Изолировать тесты друг от друга

## CI/CD Интеграция

### GitHub Actions пример

```yaml
name: Android Tests

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Run unit tests
        run: ./gradlew testDebugUnitTest
      - name: Generate coverage
        run: ./gradlew jacocoTestReport
      - name: Upload coverage
        uses: codecov/codecov-action@v3

  instrumentation-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Run instrumentation tests
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 29
          script: ./gradlew connectedDebugAndroidTest
```

## Интеграция с проектом CompressPhotoFast

Для CompressPhotoFast скилл учитывает:

1. **Специфику домена**:
   - Работа с изображениями (JPEG, PNG, HEIC)
   - MediaStore API
   - File operations
   - Compression algorithms

2. **Критичные компоненты**:
   - `ImageCompressionUtil` - core compression logic
   - `FileOperationsUtil` - file handling
   - `MediaStoreUtil` - Android integration
   - `SettingsManager` - persistence

3. **User flows**:
   - Auto compression (background)
   - Manual compression (UI)
   - Batch compression
   - Share intent handling

## Примеры использования

### Пример 1: Запуск всех тестов перед коммитом
```
Запусти android-test-suite с test_type=all и start_emulator=true
```

### Пример 2: Быстрая проверка после изменений
```
Запусти android-test-suite с test_type=unit и coverage=false
```

### Пример 3: Полный тестовый цикл для релиза
```
Запусти android-test-suite с test_type=all, coverage=true и verbose=true
Сгенерируй полный отчет покрытия кода
```

### Пример 4: Тестирование конкретной фичи
```
Запусти android-test-suite с focus_module=compression и test_type=unit
Протестируй все классы в пакете compression
```

### Пример 5: Performance тестирование
```
Запусти android-test-suite с test_type=performance
Измерь производительность сжатия JPEG изображений
Сравни с baseline показателями
```

## Метрики качества

### Целевые показатели
- **Unit Test Coverage**: минимум 70%, цель 80%+
- **Test Pass Rate**: 100% (все тесты должны проходить)
- **Test Duration**:
  - Unit: < 2 минут
  - Instrumentation: < 5 минут
  - E2E: < 10 минут
- **Flaky Test Rate**: 0%

### Monitoring
```bash
# Показать статистику тестов
./gradlew testDebugUnitTest --info | grep -E "tests? completed"

# Coverage по пакетам
./gradlew jacocoTestReport
cat app/build/reports/jacoco/jacocoTestReport/html/index.html | grep "Total"
```

## Related Tools

- **JUnit 5** - фреймворк для unit тестов
- **MockK** - моки в Kotlin
- **Espresso** - UI тесты
- **UI Automator** - E2E тесты
- **Jacoco** - покрытие кода
- **Robolectric** - Android framework для unit тестов
- **AndroidX Test** - utilities для тестирования

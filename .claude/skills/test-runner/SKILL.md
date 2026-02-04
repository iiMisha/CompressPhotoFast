---
name: test-runner
description: Умный запуск тестов с определением изменённых модулей и оптимизацией
user-invocable: true
arguments:
  - name: mode
    description: Режим запуска (smart, changed_modules, all, unit_only, instrumentation_only)
    required: false
    default: smart
  - name: scope
    description: Область тестирования (whole_project, specific_package, specific_class)
    required: false
    default: whole_project
  - name: target
    description: Конкретный пакет/класс для тестирования (например, com.compressphotofast.compression)
    required: false
    default: null
  - name: coverage
    description: Генерировать coverage отчет (true, false)
    required: false
    default: true
  - name: parallel
    description: Параллельный запуск тестов (true, false)
    required: false
    default: false
  - name: verbose
    description: Подробный вывод (true, false)
    required: false
    default: false
  - name: continuous
    description: Непрерывный режим с перезапуском при изменениях (true, false)
    required: false
    default: false
  - name: filter
    description: Фильтр тестов (например, "*CompressionTest")
    required: false
    default: null
---

# Test Runner

Умный скилл для запуска тестов с оптимизацией на основе изменённых файлов и модулей.

## Режимы запуска

### 1. Smart Mode (`mode=smart`) - **РЕКОМЕНДУЕТСЯ**
Автоматическое определение изменённых файлов и запуск только связанных тестов.

**Алгоритм:**
1. Определение изменённых файлов через `git diff`
2. Определение затронутых модулей/пакетов
3. Запуск только связанных unit тестов
4. Если изменения в UI/Android коде - запуск instrumentation тестов

**Пример:**
```bash
# Изменён: ImageCompressionUtil.kt
# Запустятся: все тесты для ImageCompressionUtil + связанные compression тесты
./gradlew test --tests "*ImageCompressionUtil*"
```

### 2. Changed Modules (`mode=changed_modules`)
Запуск всех тестов для изменённых модулей.

**Что запускается:**
- Все unit тесты модуля
- Все instrumentation тесты модуля
- Связанные интеграционные тесты

**Пример:**
```bash
# Изменён: compression/*.kt
# Запустятся: все тесты модуля compression
./gradlew :app:test --tests "com.compressphotofast.compression.**"
```

### 3. All (`mode=all`)
Полный прогон всех тестов.

**Включает:**
- Все unit тесты
- Все instrumentation тесты
- Все E2E тесты
- Coverage отчет

**Время:** 15-20 минут

### 4. Unit Only (`mode=unit_only`)
Только unit тесты (без эмулятора).

**Время:** 5-10 минут

### 5. Instrumentation Only (`mode=instrumentation_only`)
Только instrumentation тесты (требует эмулятор/устройство).

**Время:** 10-15 минут

## Как работает скилл

### Шаг 1: Определение режима (для smart/changed_modules)
Использование Bash для анализа git:
```bash
# Определение изменённых файлов
git diff --name-only HEAD~1

# Определение изменённых пакетов
git diff --name-only HEAD~1 | grep "app/src/main/java" | cut -d/ -f5-

# Определение типа изменений
git diff --name-only HEAD~1 | grep -E "(Test|spec)"
```

### Шаг 2: Определение тестов для запуска
На основе изменений формируется список тестов:

| Изменённый файл | Запускаемые тесты |
|-----------------|-------------------|
| `*Util.kt` | `*UtilTest`, связанные интеграционные тесты |
| `*ViewModel.kt` | `*ViewModelTest`, instrumentation UI тесты |
| `*Worker.kt` | `*WorkerTest`, instrumentation тесты |
| `AndroidManifest.xml` | Все instrumentation тесты |
| `build.gradle.kts` | Все тесты (full run) |

### Шаг 3: Запуск тестов
Используется Bash tool с `timeout=600000` (10 минут):

**Unit тесты:**
```bash
# Все unit тесты
./gradlew testDebugUnitTest

# Конкретный пакет
./gradlew test --tests "com.compressphotofast.compression.**"

# Конкретный класс
./gradlew test --tests "*ImageCompressionUtilTest"

# С параллельным запуском
GRADLE_MODE=fast ./gradlew testDebugUnitTest

# Подробный вывод
./gradlew testDebugUnitTest --info --stacktrace
```

**Instrumentation тесты:**
```bash
# Все instrumentation тесты
./gradlew connectedDebugAndroidTest

# Конкретный класс
./gradlew connectedDebugAndroidTest --tests "*CompressionInstrumentedTest"
```

### Шаг 4: Анализ результатов
- Парсинг вывода Gradle
- Определение passed/failed/skipped
- Сбор stack trace для failed тестов

### Шаг 5: Генерация отчета (если coverage=true)
```bash
./gradlew jacocoTestReport
```

### Шаг 6: Непрерывный режим (если continuous=true)
Запуск с файловым вотчером:
```bash
# Требуется установленный fdwatch/inotifywait
while true; do
    ./gradlew testDebugUnitTest
    inotifywait -r -e modify,create,delete app/src/
done
```

## Формат отчета

```markdown
# Test Results

## Summary
- Mode: Smart (changed modules only)
- Total Tests: 47
- Passed: 45 ✅
- Failed: 2 ❌
- Skipped: 0 ⏭️
- Duration: 2m 34s
- Coverage: 72.3%

## Changed Files
1. `app/src/main/java/com/compressphotofast/util/ImageCompressionUtil.kt`
2. `app/src/main/java/com/compressphotofast/util/FileOperationsUtil.kt`

## Tests Executed

### Unit Tests (45)
- ✅ ImageCompressionUtilTest (12/12 passed)
- ✅ FileOperationsUtilTest (15/15 passed)
- ✅ CompressionBatchTrackerTest (8/8 passed)
- ❌ MediaStoreUtilTest (2/10 failed)

### Failed Tests

### 1. MediaStoreUtilTest.testSaveImage_InvalidUri
**Location:** `app/src/test/.../MediaStoreUtilTest.kt:78`

**Error:**
```
java.lang.IllegalArgumentException: Invalid URI: invalid://uri
    at MediaStoreUtil.saveImage(MediaStoreUtil.kt:145)
    at MediaStoreUtilTest.testSaveImage_InvalidUri(MediaStoreUtilTest.kt:78)
```

**Fix:**
```kotlin
// Before
fun saveImage(context: Context, uri: Uri): Result<File> {
    // 直接使用 uri без проверки
}

// After
fun saveImage(context: Context, uri: Uri): Result<File> {
    require(uri.scheme == "file" || uri.scheme == "content") {
        "Invalid URI scheme: ${uri.scheme}"
    }
    // ...
}
```

---

### 2. MediaStoreUtilTest.testSaveImage_PermissionDenied
**Location:** `app/src/test/.../MediaStoreUtilTest.kt:92`

**Error:**
```
SecurityException: Permission Denial: writing com.android.providers.media.MediaProvider uri
    at android.database.DatabaseUtils.readExceptionWithFileNotFoundExceptionFromParcel
```

**Fix:**
```kotlin
// Test needs proper context mock or instrumentation test
@Ignore("Requires real context with permissions")
fun testSaveImage_PermissionDenied() {
    // Move to instrumentation tests
}
```

---

## Coverage Report

### Module Coverage
| Module | Coverage | Target | Status |
|--------|----------|--------|--------|
| compression | 78.5% | 70% | ✅ |
| util | 65.2% | 70% | ⚠️ |
| ui | 45.0% | 60% | ❌ |
| di | 85.0% | 70% | ✅ |

### Files with Low Coverage (< 50%)
- `MainActivity.kt` - 35%
- `SettingsManager.kt` - 48%
- `BackgroundMonitoringService.kt` - 42%

## Recommendations
1. Исправить failing тесты в MediaStoreUtilTest
2. Переместить тесты с PermissionDenied в instrumentation
3. Увеличить coverage для модуля ui
4. Добавить тесты для MainActivity

## Next Steps
1. Исправить failing тесты
2. Запустить снова для проверки
3. Добавить недостающие тесты для low coverage файлов
```

## Использование скилла

### Базовое использование
```
Запусти test-runner
```
Smart mode - автоматически определит изменённые модули.

### Полный прогон
```
Запусти test-runner с mode=all
```

### Только unit тесты
```
Запусти test-runner с mode=unit_only
```

### Конкретный пакет
```
Запусти test-runner с scope=specific_package и target=com.compressphotofast.compression
```

### Конкретный класс
```
Запусти test-runner с scope=specific_class и target=ImageCompressionUtilTest
```

### С параллельным запуском
```
Запусти test-runner с mode=unit_only и parallel=true
```

### С фильтром
```
Запусти test-runner с filter=*CompressionTest
```

### Непрерывный режим
```
Запусти test-runner с mode=unit_only и continuous=true
```

## Примеры работы

### Пример 1: После изменений в compression модуле
```
Запусти test-runner
```
Автоматически запустит тесты только для compression модуля.

### Пример 2: Полный тестовый цикл перед релизом
```
Запусти test-runner с mode=all, coverage=true и verbose=true
```

### Пример 3: Быстрая проверка во время разработки
```
Запусти test-runner с mode=unit_only и parallel=true
```

### Пример 4: Тестирование конкретной функции
```
Запусти test-runner с filter=*compressJPEG* и verbose=true
```

### Пример 5: После рефакторинга
```
Запусти test-runner с mode=all и coverage=true
Сравни покрытие с предыдущим запуском
```

## Mapping файлов к тестам

### Kotlin файлы → Unit тесты
```
ImageCompressionUtil.kt → ImageCompressionUtilTest.kt
FileOperationsUtil.kt → FileOperationsUtilTest.kt
MediaStoreUtil.kt → MediaStoreUtilTest.kt
SettingsManager.kt → SettingsManagerTest.kt
```

### ViewModels → Интеграционные тесты
```
MainViewModel.kt → MainViewModelTest.kt (unit) + *InstrumentedTest.kt
```

### Workers → Instrumentation тесты
```
ImageCompressionWorker.kt → ImageCompressionWorkerTest.kt (instrumented)
```

### UI/Activities → Instrumentation/E2E тесты
```
MainActivity.kt → MainActivityTest.kt (instrumented) + *E2ETest.kt
```

## Настройка параллельного запуска

Параллельный запуск управляется через `GRADLE_MODE`:

```bash
# Eco режим (по умолчанию) - последовательное выполнение
GRADLE_MODE=eco ./gradlew test

# Fast режим - параллельное выполнение
GRADLE_MODE=fast ./gradlew test
```

Настройка в `app/build.gradle.kts`:
```kotlin
tasks.withType<Test> {
    val gradleMode = System.getenv("GRADLE_MODE") ?: "eco"
    maxParallelForks = when (gradleMode) {
        "fast" -> (Runtime.getRuntime().availableProcessors() / 2).coerceAtMost(4)
        else -> 1
    }
}
```

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
xdg-open app/build/reports/jacoco/jacocoTestReport/html/index.html
```

## Оптимизации

### 1. Только изменённые классы
```bash
# Изменён только ImageCompressionUtil
./gradlew test --tests "*ImageCompressionUtilTest"
```

### 2. Инкрементальные тесты
```bash
# Только для изменённых классов
./gradlew test --tests "*compress*" --continuous
```

### 3. Кэширование результатов
```bash
# Использование build cache
./gradlew test --build-cache
```

## CI/CD Интеграция

### GitHub Actions пример
```yaml
name: Smart Tests

on: [push, pull_request]

jobs:
  smart-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
        with:
          fetch-depth: 2  # Нужно для git diff

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'

      - name: Detect changed modules
        id: detect
        run: |
          CHANGED=$(git diff --name-only HEAD~1 | grep "app/src/main/java" | cut -d/ -f5- | sort -u)
          echo "modules=$CHANGED" >> $GITHUB_OUTPUT

      - name: Run affected tests
        run: |
          for module in ${{ steps.detect.outputs.modules }}; do
            ./gradlew test --tests "com.compressphotofast.$module.**"
          done

      - name: Generate coverage
        run: ./gradlew jacocoTestReport

      - name: Upload coverage
        uses: codecov/codecov-action@v3
```

## Troubleshooting

### Проблема: Тесты не находятся для изменённого файла
**Решение:** Проверьте маппинг имён:
```bash
# Найти тестовый файл
find app/src/test -name "*ImageCompression*Test.kt"
```

### Проблема: Медленный запуск
**Решения:**
1. Используйте `parallel=true`
2. Уменьшите область через `target`
3. Используйте `mode=unit_only`

### Проблема: OutOfMemory при тестах
**Решение:** Увеличьте heap:
```bash
./gradlew test -DmaxParallelForks=1 -Dorg.gradle.jvmargs="-Xmx4g"
```

### Проблема: Flaky тесты
**Решения:**
1. Добавьте retry логику
2. Используйте `@RepeatTest`
3. Изолируйте тесты друг от друга

## Интеграция с проектом CompressPhotoFast

Для CompressPhotoFast скилл учитывает:
- **Компрессию** - приоритетное тестирование ImageCompressionUtil
- **File operations** - FileOperationsUtil, MediaStoreUtil
- **Background processing** - Worker, Service тесты
- **Settings** - SettingsManager, DataStore тесты
- **DI** - Hilt модули тесты

## Best Practices

### 1. Изоляция тестов
```kotlin
@Test
fun `should compress image when quality is 70`() = runTest {
    // Given
    val util = ImageCompressionUtil()

    // When
    val result = util.compress(testFile, 70)

    // Then
    assertThat(result).isNotNull()
}
```

### 2. Использование Test fixtures
```kotlin
abstract class BaseCompressionTest {
    protected lateinit var testContext: Context
    protected lateinit var testFile: File

    @Before
    fun setUp() {
        testContext = ApplicationProvider.getApplicationContext()
        testFile = createTestImage()
    }

    @After
    fun tearDown() {
        testFile.delete()
    }
}
```

### 3. Параметризованные тесты
```kotlin
@Test
fun `should compress with different qualities`() {
    val qualities = listOf(60, 70, 85)
    qualities.forEach { quality ->
        val result = util.compress(testFile, quality)
        assertThat(result).isNotNull()
    }
}
```

## Related Tools

- **JUnit 5** - фреймворк для тестов
- **MockK** - моки в Kotlin
- **Robolectric** - Android framework для unit тестов
- **Espresso** - UI тесты
- **Jacoco** - покрытие кода
- **AndroidX Test** - utilities для тестирования

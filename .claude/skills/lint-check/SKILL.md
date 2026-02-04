---
name: lint-check
description: Запускает Android Lint и статический анализ кода с детальным разбором результатов
user-invocable: true
arguments:
  - name: tools
    description: Какие инструменты запустить (android_lint, detekt, ktlint, all)
    required: false
    default: all
  - name: scope
    description: Область проверки (whole_project, specific_module, specific_files)
    required: false
    default: whole_project
  - name: focus_path
    description: Конкретный путь для проверки (например, app/src/main/java/com/compressphotofast/compression)
    required: false
    default: null
  - name: severity
    description: Минимальный уровень проблем для показа (error, warning, info)
    required: false
    default: warning
  - name: auto_fix
    description: Автоматически исправлять проблемы (true, false)
    required: false
    default: false
  - name: generate_report
    description: Генерировать HTML отчет (true, false)
    required: false
    default: true
  - name: fail_on_errors
    description: Прерывать выполнение при ошибках (true, false)
    required: false
    default: false
---

# Lint Check

Этот скилл запускает статический анализ кода Android приложения и предоставляет детальный разбор найденных проблем.

## Поддерживаемые инструменты

### 1. Android Lint
**Статус:** Встроен в Android Gradle Plugin ✅

**Что проверяет:**
- Проблемы с XML layouts
- Правильность использования Android API
- Потенциальные crash scenarios
- Internationalization проблемы
- Accessibility проблемы
- Security issues
- Performance проблемы

**Gradle команды:**
```bash
./gradlew lint                    # Все варианты сборки
./gradlew lintDebug              # Только Debug
./gradlew lintRelease            # Только Release
```

**Отчеты:**
- HTML: `app/build/reports/lint-results-debug.html`
- XML: `app/build/reports/lint-results-debug.xml`
- TXT: `app/build/reports/lint-results-debug.txt`

### 2. Detekt (опционально)
**Статус:** Требуется настройка ⚙️

**Что проверяет:**
- Kotlin code smells
- Complexity метрики
- Code style нарушения
- Потенциальные баги
- Contract violations

**Настройка:**
```kotlin
// build.gradle.kts (project level)
plugins {
    id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
}

// app/build.gradle.kts
plugins {
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    config.setFrom(files("$rootDir/.detekt/config.yml"))
    buildUponDefaultConfig = true
}
```

**Gradle команды:**
```bash
./gradlew detekt                  # Все проверки
./gradlew detektMain             # Только main source
./gradlew detektTest             # Только тесты
```

**Отчеты:**
- HTML: `app/build/reports/detekt/detekt.html`
- XML: `app/build/reports/detekt/detekt.xml`
- TXT: `app/build/reports/detekt/detekt.txt`

### 3. ktlint (опционально)
**Статус:** Требуется настройка ⚙️

**Что проверяет:**
- Форматирование Kotlin кода
- Отступы и пробелы
- Импорты
- Комментарии

**Настройка:**
```kotlin
// build.gradle.kts (project level)
plugins {
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0" apply false
}

// app/build.gradle.kts
plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    android = true
    ignoreFailures = false
}
```

**Gradle команды:**
```bash
./gradlew ktlintCheck            # Проверка
./gradlew ktlintFormat           # Форматирование
```

## Как работает скилл

### Шаг 1: Определение инструментов
Проверка доступности инструментов через Gradle:
```bash
./gradlew tasks --group="verification"
```

### Шаг 2: Запуск анализа
Используется Bash tool с `timeout=300000` (5 минут):

**Android Lint:**
```bash
./gradlew lintDebug
```

**Detekt (если настроен):**
```bash
./gradlew detekt
```

**ktlint (если настроен):**
```bash
./gradlew ktlintCheck
```

### Шаг 3: Парсинг результатов
- Чтение отчетов через Read tool
- Извлечение проблем по уровням (error, warning, info)
- Группировка по типам и файлам

### Шаг 4: Генерация сводки
Создание структурированного отчета с:
- Статистикой проблем
- Списком критичных проблем
- Рекомендациями по исправлению
- Примерами кода (before/after)

### Шаг 5: Автофикс (опционально)
Если `auto_fix=true`:
```bash
./gradlew ktlintFormat           # Форматирование
./gradlew detektAutoCorrect      # Автоисправление Detekt
```

## Формат отчета

```markdown
# Lint Analysis Report

## Summary
- Tools: Android Lint, Detekt, ktlint
- Total Issues: X
- Errors: X | Warnings: X | Info: X
- Duration: Xs

## Critical Errors

### 1. [Lint] MissingPermission
**Location:** `app/src/main/AndroidManifest.xml:15`

**Problem:**
App uses `CAMERA` permission but doesn't declare it in manifest.

**Impact:**
Runtime crash when accessing camera on Android M+.

**Fix:**
```xml
<!-- Add to AndroidManifest.xml -->
<uses-permission android:name="android.permission.CAMERA" />
```

---

### 2. [Detekt] TooManyFunctions
**Location:** `app/src/main/java/com/compressphotofast/util/FileUtil.kt:45`

**Problem:**
File contains 15 functions (max allowed: 10)

**Impact:**
Reduced code readability and maintainability.

**Fix:**
Split file into multiple focused classes:
- `FileUtil.kt` - core operations
- `FileMetadataUtil.kt` - metadata operations
- `FileValidationUtil.kt` - validation

---

## High Priority Warnings
...

## Medium Priority Issues
...

## Low Priority Issues
...

## Recommendations
1. Добавить недостающие permissions
2. Рефакторинг больших классов
3. Улучшить error handling
4. Оптимизировать работу с памятью
```

## Использование скилла

### Базовое использование
```
Запусти lint-check
```
Запустит все доступные инструменты (Android Lint + настроенные Detekt/ktlint).

### Только Android Lint
```
Запусти lint-check с tools=android_lint
```

### Проверка конкретного модуля
```
Запусти lint-check с scope=specific_module и focus_path=app/src/main/java/com/compressphotofast/compression
```

### С автоисправлением
```
Запусти lint-check с auto_fix=true
```
Запустит форматирование и автоисправление для ktlint/detekt.

### Только критичные проблемы
```
Запусти lint-check с severity=error
```

### Без генерации отчета
```
Запусти lint-check с generate_report=false
```

## Примеры работы

### Пример 1: Полная проверка перед коммитом
```
Запусти lint-check с auto_fix=true и fail_on_errors=true
```

### Пример 2: Быстрая проверка
```
Запусти lint-check с tools=android_lint и severity=error
```

### Пример 3: Проверка конкретной фичи
```
Запусти lint-check с scope=specific_module
Проверь папку compression на наличие проблем
```

## Настройка Detekt (если нужно)

### Шаг 1: Добавить плагин
В `build.gradle.kts` (project level):
```kotlin
plugins {
    id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
}
```

### Шаг 2: Применить к модулю
В `app/build.gradle.kts`:
```kotlin
plugins {
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    config.setFrom(files("$rootDir/.detekt/config.yml"))
    buildUponDefaultConfig = true
    allRules = false
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")
}
```

### Шаг 3: Создать конфиг
`.detekt/config.yml`:
```yaml
build:
  maxIssues: 0
  excludeCorrectable: false
  weights:
    complexity: 2
    LongParameterList: 1
    style: 1
    comments: 1

complexity:
  TooManyFunctions:
    active: true
    thresholdInClasses: 10
    thresholdInInterfaces: 10
    thresholdInObjects: 10
    thresholdInEnums: 5

style:
  MagicNumber:
    active: false
  MaxLineLength:
    active: true
    maxLineLength: 120
```

## Настройка ktlint (если нужно)

### Шаг 1: Добавить плагин
В `build.gradle.kts` (project level):
```kotlin
plugins {
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0" apply false
}
```

### Шаг 2: Применить к модулю
В `app/build.gradle.kts`:
```kotlin
plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    android = true
    ignoreFailures = false
    verbose = true
}
```

## Troubleshooting

### Проблема: Lint не находит проблемы
**Решение:** Проверьте что lint базовая конфигурация включена:
```kotlin
android {
    lint {
        abortOnError false
        checkReleaseBuilds true
        disable "MissingTranslation"
    }
}
```

### Проблема: Detekt не работает после добавления
**Решение:** Синхронизируйте Gradle:
```bash
./gradlew --refresh-dependencies build
```

### Проблема: ktlint форматирование ломает код
**Решение:** Отключите problematic rules:
```kotlin
ktlint {
    disabledRules.addAll("import-ordering", "parameter-list-wrapping")
}
```

## CI/CD Интеграция

### GitHub Actions пример
```yaml
name: Lint Check

on: [push, pull_request]

jobs:
  lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Run Lint
        run: ./gradlew lintDebug
      - name: Upload Lint Report
        uses: actions/upload-artifact@v3
        with:
          name: lint-report
          path: app/build/reports/lint-results-debug.html
```

## Интеграция с проектом CompressPhotoFast

Для CompressPhotoFast скилл учитывает:
- **Работу с изображениями** - проверки для bitmap operations
- **File I/O** - проверки для работы с файлами
- **Memory management** - детект potential leaks
- **Coroutines** - проверки для правильного использования
- **Hilt DI** - проверки для DI

## Related Tools

- **Android Lint** - встроенный статический анализ
- **Detekt** - Kotlin static analysis
- **ktlint** - Kotlin formatter
- **SonarQube** - комплексный анализ качества кода
- **Codacy** - cloud-based code analysis

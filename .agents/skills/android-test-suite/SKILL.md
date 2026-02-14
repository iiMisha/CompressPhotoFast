---
name: android-test-suite
description: Комплексное тестирование Android приложения CompressPhotoFast (unit, instrumentation, E2E, performance тесты). Use when: запуск тестов, анализ coverage, troubleshooting, написание новых тестов.
---

# Android Test Suite

Комплексный скилл для тестирования Android приложения CompressPhotoFast.

## Типы тестов

### Unit Тесты
**Расположение:** `app/src/test/`

**Что тестируют:** Логику утилит, ViewModel, репозитории, обработку событий.

**Запуск:**
```bash
./gradlew testDebugUnitTest
```
**Время:** 30-60 секунд

### Instrumentation Тесты
**Расположение:** `app/src/androidTest/`

**Что тестируют:** Интеграцию с Android framework, MediaStore, HEIC, настройки.

**Запуск:**
```bash
./gradlew connectedDebugAndroidTest
```
**Время:** 3-5 минут
**Требования:** Устройство или эмулятор

### E2E Тесты
**Расположение:** `app/src/androidTest/e2e/`

**Что тестируют:** Полные пользовательские сценарии (авто/ручная/пакетная компрессия, Share Intent).

**Запуск:**
```bash
./scripts/run_e2e_tests.sh
```
**Время:** 5-10 минут

### Performance Тесты
**Расположение:** `app/src/test/performance/`, `app/src/androidTest/performance/`

**Метрики:** Время выполнения, потребление памяти, throughput.

**Запуск:**
```bash
./scripts/run_performance_tests.sh
```

## Скрипты

| Скрипт | Назначение |
|--------|------------|
| `./scripts/run_unit_tests.sh` | Unit тесты |
| `./scripts/run_instrumentation_tests.sh` | Instrumentation тесты |
| `./scripts/run_all_tests.sh` | Все тесты |
| `./scripts/run_e2e_tests.sh` | E2E тесты |
| `./scripts/run_performance_tests.sh` | Performance тесты |
| `./scripts/quick_test.sh` | Быстрая проверка |
| `./scripts/check_device.sh` | Проверка устройства |
| `./scripts/start_emulator.sh` | Запуск эмулятора |

## Workflow

### КРИТИЧЕСКОЕ ПРАВИЛО
**НИКОГДА не запускай тесты через Bash в основном контексте!** Используй субагент `general-purpose`.

### Процесс выполнения

1. **Определи тип тестов** из аргументов
2. **Запусти через `general-purpose`:**
   ```yaml
   Task(tool: Task, subagent_type: "general-purpose",
     prompt: "Запусти unit тесты: ./gradlew testDebugUnitTest
              Таймаут: 10 минут.
              Верни summary с passed/failed/skipped.")
   ```
3. **Если coverage=true** - запусти `android-test-analyzer`
4. **Собери результаты** и сгенерируй отчет

## Coverage

### Unit
```bash
./gradlew jacocoTestReport
```
Отчет: `app/build/reports/jacoco/jacocoTestReport/html/index.html`

### Instrumentation
```bash
./gradlew jacocoAndroidTestReport
```

### Объединенный
```bash
./gradlew jacocoCombinedTestReport
xdg-open app/build/reports/jacoco/jacocoCombinedTestReport/html/index.html
```

## Ресурсы

- **[Шаблоны тестов](references/test-templates.md)** - Примеры unit, instrumentation, E2E тестов
- **[Troubleshooting](references/troubleshooting.md)** - Решение проблем (OOM, flaky tests, etc.)
- **[CI/CD примеры](references/cicd-examples.md)** - GitHub Actions, GitLab CI, Jenkins
- **[Примеры использования](references/usage-examples.md)** - Конкретные сценарии запуска

## Best Practices

1. **Изолированность** - Каждый тест независим
2. **Читаемость** - Given-When-Then паттерн
3. **Скорость** - Unit тесты < 1сек каждый
4. **Полнота** - Тестируйте happy path и error cases

## Интеграция с проектом

### Критичные компоненты
- `ImageCompressionUtil` - core compression logic
- `FileOperationsUtil` - file handling
- `MediaStoreUtil` - Android integration
- `SettingsManager` - persistence

### User flows
- Auto compression (background)
- Manual compression (UI)
- Batch compression
- Share intent handling

## Метрики качества

| Метрика | Минимум | Цель |
|---------|---------|------|
| Unit Coverage | 70% | 80%+ |
| Pass Rate | 100% | 100% |
| Test Duration (Unit) | < 2 мин | < 1 мин |
| Flaky Rate | 0% | 0% |

## Related Tools

- **JUnit 5** - unit тесты
- **MockK** - моки
- **Espresso** - UI тесты
- **UI Automator** - E2E тесты
- **Jacoco** - coverage
- **Robolectric** - Android framework для unit тестов

# Система автоматического тестирования CompressPhotoFast

## Обзор

Создана локальная система автоматического тестирования для Android-приложения CompressPhotoFast. Система работает на эмуляторе или реальном устройстве через ADB и обеспечивает обязательное тестирование кода при изменениях.

## Быстрый старт

```bash
# Проверка устройства
./scripts/check_device.sh

# Запуск unit тестов
./scripts/run_unit_tests.sh

# Запуск всех тестов
./scripts/run_all_tests.sh
```

## Структура

### Unit тесты
- `app/src/test/java/com/compressphotofast/util/ImageCompressionUtilTest.kt`

### Instrumentation тесты
- `app/src/androidTest/java/com/compressphotofast/` (будут добавлены)

## Скрипты

1. **check_device.sh** - проверка подключения устройства
2. **run_unit_tests.sh** - запуск только unit тестов
3. **run_all_tests.sh** - полный цикл тестирования

## Gradle команды

```bash
./gradlew testDebugUnitTest              # Unit тесты
./gradlew connectedDebugAndroidTest      # Instrumentation тесты
./gradlew jacocoTestReport               # Coverage отчет
./gradlew checkAllTests                  # Все тесты + coverage
```

## Coverage

- HTML отчет: `app/build/reports/jacoco/jacocoTestReport/html/index.html`
- Целевой coverage: 50-70%
- Текущий: ~5%

## Текущее состояние

### ✅ Реализовано
- Базовая инфраструктура тестирования
- JaCoCo для coverage
- Параллельный запуск тестов
- Скрипты автоматизации
- Генератор тестовых изображений
- Первый unit тест

### ⏳ В планах
- Дополнительные unit тесты
- Instrumentation тесты
- Увеличение coverage до 50-70%

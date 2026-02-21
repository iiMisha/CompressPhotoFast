# Примеры использования android-test-suite

## Запуск unit тестов
```
User: Запусти unit тесты
Claude: Использую Task(general-purpose) для запуска ./gradlew testDebugUnitTest
```

## Запуск instrumentation тестов
```
User: Запусти instrumentation тесты
Claude: Использую Task(general-purpose) для запуска ./scripts/run_instrumentation_tests.sh
```

## Важно
1. Всегда использовать Task tool
2. Не вызывать android-test-analyzer (рекурсия)
3. Проверять эмулятор для instrumentation тестов

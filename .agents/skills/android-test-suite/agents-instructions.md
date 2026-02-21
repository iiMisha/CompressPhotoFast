# Инструкции для агентов android-test-suite

## Критические правила
1. ВСЕГДА использовать Task tool
2. НЕ вызывать android-test-analyzer
3. Проверять эмулятор для instrumentation тестов

## Шаблон команды
```
Task(
  subagent_type = "general-purpose",
  prompt = "Запусти тесты: ./gradlew testDebugUnitTest"
)
```

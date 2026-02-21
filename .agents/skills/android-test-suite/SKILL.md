---
name: android-test-suite
description: |
  Запуск Android тестов в изолированном субагенте с возвратом результатов в основной контекст.
  Использовать ОБЯЗАТЕЛЬНО для любого запуска тестов в проекте CompressPhotoFast.

  **Когда использовать:**
  - Запуск unit тестов (./gradlew testDebugUnitTest)
  - Запуск instrumentation тестов (./scripts/run_instrumentation_tests.sh)
  - Запуск всех тестов (./scripts/run_all_tests.sh)
  - Любые другие команды тестирования Android проекта

  **ВАЖНО:** Этот скилл ВСЕГДА запускает тесты в отдельном субагенте через Task tool,
  чтобы избежать блокировки основного контекста и корректно вернуть результаты.

  **НЕ вызывает android-test-analyzer** (во избежание рекурсии). Для анализа покрытия
  вызывай android-test-analyzer отдельно ПОСЛЕ запуска тестов.
---

# Android Test Suite Runner

Запускает Android тесты в изолированном субагенте и возвращает результаты в основной контекст.

## Quick Start

Для запуска тестов выберите нужный тип:
- Unit тесты: `./gradlew testDebugUnitTest`
- Instrumentation тесты: `./scripts/run_instrumentation_tests.sh`
- Все тесты: `./scripts/run_all_tests.sh`

## Результаты

Скилл возвращает количество тестов, статус прохождения, время выполнения и покрытие.

# Контекст

## Последние изменения (февраль 2026)
*   **Локальные агенты**: 6 агентов в `.claude/agents/` (kotlin-specialist, java-architect, deployment-engineer, devops-engineer, platform-engineer, database-administrator)
*   **Новые скиллы**: lint-check (Android Lint + Detekt), test-runner (умный запуск тестов)
*   **Android Test Orchestrator**: добавлен для стабильности instrumentation тестов (#30fc343)
*   **Инструкции по памяти**: обновлены в rules.md с лимитами размера файлов
*   **Memory Bank оптимизация**: ограничения (brief: 5, context: 50, tasks: 100, architecture: 80, tech: 30)
*   **Консолидация правил**: единые правила в rules.md, удалены дубликаты workflow
*   **Депрекация Task(Explore)**: замена на Glob/Grep/Read
*   **Hilt DI**: UriProcessingTracker → @Inject singleton
*   **Корутины**: Handler → CoroutineScope (BackgroundMonitoringService, NotificationUtil)

## Текущие проблемы
*   🔴 Дубликаты при массовой обработке (50+ файлов)
*   🔴 Двойные расширения (HEIC.jpg)

## Недавние исправления
*   ✅ **Уведомления о сжатии**: Полностью исправлена система уведомлений (февраль 2026, e45d9e7)
    *   Проблема 1: `staticInstance` был null после перезагрузки → init блок для инициализации
    *   Проблема 2: Автобатчи имели бесконечный таймаут → убрано продление таймаута
    *   Оптимизации: toInputStream() вместо toByteArray(), destroy() → suspend, атомарные add()
    *   Файлы: `CompressionBatchTracker.kt`, `ImageCompressionUtil.kt`, `SequentialImageProcessor.kt`, `UriProcessingTracker.kt`
    *   Тесты: 320/320 passed (2 новых теста для staticInstance)

## Метрики
*   **Исходный код**: 36 Kotlin файлов
*   **Тесты**: 320 unit + 232 instrumentation (100% pass rate)
*   **Скиллы**: 5 (android-test-suite, android-optimization-analyzer, memory-bank-updater, lint-check, test-runner)
*   **Локальные агенты**: 6 в `.claude/agents/` (kotlin-specialist, java-architect, deployment-engineer, devops-engineer, platform-engineer, database-administrator)

## Дальнейшие шаги
*   Исправить дубликаты/расширения
*   Настроить Detekt/ktlint в проекте

# Контекст

## Последние изменения (февраль 2026)
*   **WIP: Исправление двойных расширений**: В процессе доработки
    *   `FileOperationsUtil.kt`: добавлено сохранение последнего расширения в режиме замены
    *   `MediaStoreUtil.kt`: добавлен режим "wt" (write+truncate) для корректной перезаписи файлов
    *   `ImageCompressionWorker.kt`: добавлена проверка `savedUri != imageUri` перед удалением оригинала
*   **Локальные агенты**: 14 агентов в `.claude/agents/` (kotlin-specialist, java-architect, python-pro, deployment-engineer, devops-engineer, platform-engineer, database-administrator, sre-engineer, security-engineer, incident-responder, sql-pro, android-test-analyzer, android-silent-failure-hunter, android-code-reviewer)
*   **Review агенты**: 3 локальных агента (android-test-analyzer, android-silent-failure-hunter, android-code-reviewer)
*   **Новые скиллы**: lint-check, test-runner, android-test-suite, android-optimization-analyzer, memory-bank-updater
*   **Android Test Orchestrator**: добавлен для стабильности instrumentation тестов (#30fc343)
*   **Hilt DI**: UriProcessingTracker → @Inject singleton
*   **Корутины**: Handler → CoroutineScope (BackgroundMonitoringService, NotificationUtil)

## Текущие проблемы
*   🔴 Двойные расширения (HEIC.jpg) - в процессе исправления
*   🔴 Дубликаты при массовой обработке (50+ файлов)

## Недавние исправления
*   ✅ **Phase 1+2 Performance Optimizations**: Исправлено 8 проблем (февраль 2026)
    *   **Phase 1**: SequentialImageProcessor (+30-40%), shared Handlers (3 leaks), Job tracking
    *   **Phase 2**: HEIC single-pass decode (2x), DataStore миграция (0 ANR), MediaStore batch queries (-99%), LeakCanary
    *   Общий эффект: 60-80% ускорение, -7 leaks, -99% queries
*   ✅ **Уведомления о сжатие**: Исправлена система уведомлений (e45d9e7)

## Метрики
*   **Исходный код**: 36 Kotlin файлов + Python CLI (4 файла)
*   **Тесты**: 320 unit + 232 instrumentation (100% pass rate)
*   **Скиллы**: 5 (android-test-suite, android-optimization-analyzer, memory-bank-updater, lint-check, test-runner)
*   **Локальные агенты**: 14

## Дальнейшие шаги
*   Завершить исправление двойных расширений
*   Исправить дубликаты при массовой обработке
*   Настроить Detekt/ktlint

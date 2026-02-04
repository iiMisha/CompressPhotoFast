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

## Метрики
*   **Исходный код**: 36 Kotlin файлов
*   **Тесты**: 24 unit + 232 instrumentation (100% pass rate)
*   **Скиллы**: 5 (android-test-suite, android-optimization-analyzer, memory-bank-updater, lint-check, test-runner)
*   **Локальные агенты**: 6 в `.claude/agents/` (kotlin-specialist, java-architect, deployment-engineer, devops-engineer, platform-engineer, database-administrator)

## Дальнейшие шаги
*   Исправить дубликаты/расширения
*   Настроить Detekt/ktlint в проекте

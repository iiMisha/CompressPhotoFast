# Контекст

## Последние изменения (февраль 2026)
*   **Локальные агенты**: 9 агентов в `.claude/agents/` (kotlin-specialist, java-architect, deployment-engineer, devops-engineer, platform-engineer, database-administrator, android-test-analyzer, android-silent-failure-hunter, android-code-reviewer)
*   **Review агенты**: Добавлены 3 локальных агента для code review и тестирования (android-test-analyzer, android-silent-failure-hunter, android-code-reviewer)
*   **Новые скиллы**: lint-check (Android Lint + Detekt), test-runner (умный запуск тестов)
*   **Android Test Orchestrator**: добавлен для стабильности instrumentation тестов (#30fc343)
*   **Инструкции по памяти**: обновлены в rules.md с лимитами размера файлов
*   **Memory Bank оптимизация**: ограничения (brief: 5, context: 50, tasks: 100, architecture: 80, tech: 30)
*   **Консолидация правил**: единые правила в rules.md, удалены дубликаты workflow
*   **Депрекация Task(Explore)**: замена на Glob/Grep/Read
*   **Hilt DI**: UriProcessingTracker → @Inject singleton
*   **Корутины**: Handler → CoroutineScope (BackgroundMonitoringService, NotificationUtil)
*   **Интеграция агентов в скиллы**: Добавлена секция "Автоматизация через агентов" во все скиллы
    *   test-runner → general-purpose (запуск тестов)
    *   android-test-suite → general-purpose + android-test-analyzer (анализ покрытия)
    *   lint-check → general-purpose + kotlin-specialist + android-code-reviewer (lint + исправление)
    *   android-optimization-analyzer → kotlin-specialist + android-silent-failure-hunter (анализ кода)
    *   memory-bank-updater → прямые инструменты Glob/Grep/Read (без агентов)

## Текущие проблемы
*   🔴 Дубликаты при массовой обработке (50+ файлов)
*   🔴 Двойные расширения (HEIC.jpg)

## Недавние исправления
*   ✅ **Phase 1+2 Performance Optimizations**: Исправлено 8 проблем (февраль 2026)
    *   **Phase 1**: SequentialImageProcessor (+30-40%), shared Handlers (3 leaks), Job tracking
    *   **Phase 2**: HEIC single-pass decode (2x), DataStore миграция (0 ANR), MediaStore batch queries (-99%), LeakCanary
    *   Тесты: 320/320 passed, обе фазы успешны
    *   Общий эффект: 60-80% ускорение, -7 leaks, -99% queries
*   ✅ **Review агенты**: 3 агента из pr-review-toolkit (февраль 2026)
    *   android-test-analyzer, android-silent-failure-hunter, android-code-reviewer
*   ✅ **Уведомления о сжатие**: Исправлена система уведомлений (e45d9e7)

## Метрики
*   **Исходный код**: 36 Kotlin файлов
*   **Тесты**: 320 unit + 232 instrumentation (100% pass rate)
*   **Скиллы**: 5 (android-test-suite, android-optimization-analyzer, memory-bank-updater, lint-check, test-runner)
*   **Локальные агенты**: 9 в `.claude/agents/` (kotlin-specialist, java-architect, deployment-engineer, devops-engineer, platform-engineer, database-administrator, android-test-analyzer, android-silent-failure-hunter, android-code-reviewer)

## Дальнейшие шаги
*   Исправить дубликаты/расширения
*   Настроить Detekt/ktlint в проекте

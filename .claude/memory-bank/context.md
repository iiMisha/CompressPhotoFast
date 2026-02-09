# Контекст

## Последние изменения (февраль 2026)
*   **✅ Двойные расширения ИСПРАВЛЕНЫ** (99636da, c86c711)
    *   `FileOperationsUtil.kt`: сохранение последнего расширения в режиме замены
    *   `MediaStoreUtil.kt`: режим "wt" (write+truncate) для корректной перезаписи
    *   `ImageCompressionWorker.kt`: проверка `savedUri != imageUri` перед удалением оригинала
*   **Агенты/Скиллы**: 14 локальных агентов + 5 скиллов (lint-check, test-runner, android-test-suite, android-optimization-analyzer, memory-bank-updater)
*   **Android Test Orchestrator**: добавлен для стабильности instrumentation тестов (#30fc343)
*   **LeakCanary**: добавлен для детектирования memory leaks (debug builds)
*   **DataStore**: миграция настроек в `SettingsDataStore` для предотвращения ANR

## Текущие проблемы
*   🔴 Дубликаты при массовой обработке (50+ файлов)

## Недавние исправления
*   ✅ **Phase 1+2 Performance Optimizations**: 60-80% ускорение, -7 leaks, -99% MediaStore queries
    *   SequentialImageProcessor (+30-40%), HEIC single-pass decode (2x)
    *   Пакетные MediaStore операции, CoroutineScope вместо Handler
    *   Job tracking, LeakCanary
*   ✅ **Уведомления о сжатие**: исправлена система (e45d9e7)

## Метрики
*   **Исходный код**: 40 Kotlin файлов (31 util + UI/worker/service)
*   **Тесты**: Unit + Instrumentation (JaCoCo coverage, мин 30%)
*   **Скрипты**: 8 (run_all_tests.sh, run_instrumentation_tests.sh, performance_tests.sh)
*   **Версия**: 2.2.10 (versionCode: 2)

## Дальнейшие шаги
*   Исправить дубликаты при массовой обработке
*   Настроить Detekt/ktlint

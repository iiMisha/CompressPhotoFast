# Контекст

## Последние изменения

*   **Переход на экономичный режим (22.01.2026)**: Проект настроен на использование экономичного режима Gradle по умолчанию для снижения нагрузки на CPU. Все тесты и сборка теперь выполняются с низкой нагрузкой на процессор, что позволяет комфортно работать на компьютере во время выполнения задач. Документация: [`docs/GRADLE_ECO_MODE.md`](docs/GRADLE_ECO_MODE.md).
*   **Обновление Memory Bank (20.01.2026)**: Обновление документации с учетом улучшений в системе тестирования.
*   **Расширенная система тестирования (январь 2026)**: Значительное улучшение системы автоматического тестирования:
    *   **Новые скрипты тестирования**:
        *   [`quick_test.sh`](scripts/quick_test.sh) - быстрый запуск тестов с опциями (unit|instrumentation|all)
        *   [`run_e2e_tests.sh`](scripts/run_e2e_tests.sh) - E2E тестирование с категориями (manualcompression, batchcompression, autocompression, shareintent, settings)
        *   [`run_performance_tests.sh`](scripts/run_performance_tests.sh) - тестирование производительности (compression, memory, throughput)
        *   [`start_emulator.sh`](scripts/start_emulator.sh) - автоматический запуск эмулятора Android
    *   **Расширенная документация**:
        *   [`docs/TESTING.md`](docs/TESTING.md) - полная документация системы тестирования с актуальной статистикой
        *   [`docs/TEST_BASE_CLASSES.md`](docs/TEST_BASE_CLASSES.md) - документация базовых классов для тестов
        *   [`docs/TESTING_PLAN.md`](docs/TESTING_PLAN.md) - план развития системы тестирования
    *   **Базовые классы для тестов**:
        *   [`BaseUnitTest.kt`](app/src/test/java/com/compressphotofast/BaseUnitTest.kt) - базовый класс для unit тестов с поддержкой корутин
        *   [`BaseInstrumentedTest.kt`](app/src/androidTest/java/com/compressphotofast/BaseInstrumentedTest.kt) - базовый класс для instrumentation тестов с Hilt и Espresso
        *   [`CoroutinesTestRule.kt`](app/src/test/java/com/compressphotofast/CoroutinesTestRule.kt) - JUnit Rule для тестирования корутин
    *   **Инфраструктура тестирования**:
        *   [`TestImageGenerator.kt`](app/src/test/java/com/compressphotofast/util/TestImageGenerator.kt) - генератор тестовых изображений
        *   [`TestUtilities.kt`](app/src/test/java/com/compressphotofast/util/TestUtilities.kt) - вспомогательные функции для тестов
        *   [`WorkManagerTestModule.kt`](app/src/test/java/com/compressphotofast/di/WorkManagerTestModule.kt) - модуль для тестирования WorkManager
        *   [`robolectric.properties`](app/src/test/resources/robolectric.properties) - конфигурация Robolectric
*   **Скрипты установки CLI**: Добавлены автоматические установщики для Linux/macOS (`install.sh`) и Windows (`install.ps1`) с детекцией Python и зависимостей.
*   **Проблема двойных расширений**: Выявлена и документирована проблема с созданием файлов с двойными расширениями (например, `image.HEIC.jpg`).
*   **Тестирование HEIC форматов**: Созданы специальные тесты для проверки обработки HEIC/HEIF форматов (20 тестов).

## Текущее состояние проекта

### Android-версия
*   Версия: 2.2.8 (31.08.2025), versionCode 2
*   Kotlin: 2.2.10, Android Gradle Plugin: 8.13.2
*   Использует DataStore для хранения настроек (вместо SharedPreferences)
*   Поддерживает автоматическое сжатие в фоновом режиме
*   Включает функции игнорирования фото из мессенджеров и скриншотов
*   Система тестирования с JaCoCo coverage (текущий ~8-10%, целевой 50-70%)
*   Полный набор утилит в пакете `util` (24 файла)
*   **Статистика тестирования (январь 2026)**:
    *   Всего unit тестов: 251
    *   Проходят успешно: 238 (94.8%)
    *   Instrumentation тестов: 96
    *   Всего тестов: 347 (251 unit + 96 instrumentation)
    *   Общее покрытие: ~8-10%
*   **Скрипты тестирования**:
    *   [`quick_test.sh`](scripts/quick_test.sh) - быстрый запуск тестов (unit|instrumentation|all)
    *   [`run_all_tests.sh`](scripts/run_all_tests.sh) - полный цикл тестирования (Unit + Instrumentation + Coverage)
    *   [`run_unit_tests.sh`](scripts/run_unit_tests.sh) - запуск только unit тестов
    *   [`run_instrumentation_tests.sh`](scripts/run_instrumentation_tests.sh) - запуск только instrumentation тестов
    *   [`run_e2e_tests.sh`](scripts/run_e2e_tests.sh) - E2E тестирование с категориями
    *   [`run_performance_tests.sh`](scripts/run_performance_tests.sh) - тестирование производительности
    *   [`start_emulator.sh`](scripts/start_emulator.sh) - автоматический запуск эмулятора
    *   [`check_device.sh`](scripts/check_device.sh) - проверка подключения устройства
    *   [`generate_test_images.sh`](scripts/generate_test_images.sh) - генерация тестовых изображений

### CLI-версия
*   Версия: 1.0.0
*   Python: 3.10+
*   Полностью поддерживает многопроцессорную обработку изображений с использованием ProcessPoolExecutor
*   Использует multiprocessing.Manager().Lock() для process-safe доступа к статистике
*   Поддерживает HEIC/HEIF форматы (опционально через pillow-heif)
*   Имеет dry-run режим для предварительного анализа с многопроцессорной обработкой
*   Автоматические установщики для Linux/macOS и Windows
*   Модульная структура с 7 основными файлами

## Дальнейшие шаги

*   Увеличить coverage тестов до 50-70%
*   Добавить instrumentation тесты для оставшихся компонентов
*   Исправить проблему двойных расширений файлов
*   Документировать повторяющиеся задачи в tasks.md
*   Реализовать E2E тесты для всех категорий (manualcompression, batchcompression, autocompression, shareintent, settings)
*   Добавить performance тесты для измерения времени выполнения, использования памяти и пропускной способности
*   Интегрировать автоматический запуск эмулятора в CI/CD пайплайн
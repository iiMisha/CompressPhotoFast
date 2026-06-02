# CompressPhotoFast - Проектная документация

**Язык:** Русский | **ОС:** Linux Mint | **Версия:** 2.2.10

---

## Project Overview

Кроссплатформенное приложение для сжатия фотографий. Android (API 29+) + CLI (Python 3.10+). Идентичная логика сжатия с сохранением EXIF-маркеров.

**Проблема**: Пользователям нужно уменьшать размер фотографий для экономии места и отправки в мессенджеры. Стандартные инструменты неудобны.

**Решение**: Android-приложение + CLI с быстрой обработкой, автоматическим режимом и настройками качества.

### Agent Symlinks

Символические ссылки `.agents/` → `.claude/`, `.gemini/`, `.opencode/`, `.qwen/` для синхронизации агентов и скиллов между AI-платформами. Оригиналы в `.agents/`, платформы используют symlinks.

### Обязательное чтение

Перед началом работы обязательно прочитайте `.claude/rules/rules.md` — правила разработки проекта.

---

## Tech Stack

### Android
- Kotlin 2.2.10, Java 17, AGP 9.0.1, KSP 2.3.2
- MVVM + Hilt 2.57.1 (DI)
- Compressor 3.0.1, Coil 3.3.0, DataStore 1.1.7, ExifInterface 1.4.1
- Coroutines 1.10.2, WorkManager 2.10.3
- JUnit, MockK, Espresso, JaCoCo
- Android Test Orchestrator 1.5.0
- minSdk 29, targetSdk 36

### CLI (Python 3.10+)
- Pillow, pillow-heif, piexif
- Click, Rich, tqdm
- ProcessPoolExecutor

---

## Architecture

### Android Layers
- **UI**: MainActivity.kt, MainViewModel.kt
- **Domain**: ImageCompressionUtil.kt, ImageCompressionWorker.kt, SettingsManager.kt
- **Data**: DataStore (SettingsDataStore), MediaStore
- **Utils**: 35 файлов (MediaStore, EXIF, файлы, статистика, TempFilesCleaner)

### Background Processing
- WorkManager (ImageCompressionWorker.kt) - основная обработка
- BackgroundMonitoringService.kt - отслеживание новых изображений
- ImageDetectionJobService.kt - периодическая проверка
- BootCompletedReceiver.kt - автозапуск

### DI (Hilt 2.57.1)
- Модули: AppModule.kt (основной), тестовые модули
- Singleton: UriProcessingTracker, PerformanceMonitor, CompressionBatchTracker

---

## Key Features

- Ручное сжатие: выбор одного или нескольких изображений
- Автоматическое сжатие: фоновое обнаружение новых фото (30 секунд)
- Настройки качества: 60/70/80
- Режимы сохранения: замена оригинала или отдельная папка
- Обработка через "Поделиться" (Share Intent)
- Сохранение EXIF (GPS требует отдельного разрешения)
- Игнорирование фото из мессенджеров и скриншотов
- Пакетная обработка с сводкой результатов

---

## Compression Rules

**Бизнес-логика сжатия:**

- Минимальный размер: 100 КБ
- Минимальная экономия: 30% + 10 КБ
- Маркер: `CompressPhotoFast_Compressed:quality:timestamp`

---

## Code Style

### Kotlin/Android
- MVVM + Hilt DI
- Coroutines вместо GlobalScope/Handler
- Методы destroy() для cleanup
- inSampleSize, RGB_565 для декодирования
- Пакетные MediaStore операции

### Python CLI
- Многопроцессорная обработка (ProcessPoolExecutor)
- Сохранение идентичной логики с Android частью

---

## Current Focus (Июнь 2026)

### Последние изменения
- ✅ **Race condition в CompressionBatchTracker** (6f1d59f) — убран `extendAutoBatchTimeout()` из `getOrCreateAutoBatch()`. Сканирование и workers конкурентно вызывали `scheduleTimeout()` на `var timeoutJob` без синхронизации → старый таймаут не отменялся → батч финализировался преждевременно (7 из 49 результатов). Теперь только `addResult()` продлевает таймаут
- ✅ **APPEND → APPEND_OR_REPLACE** (955bd87) — `ImageProcessingUtil.kt`: застрявшая цепочка WorkManager блокировала новые work'ы. `APPEND_OR_REPLACE` не прерывает running workers, только заменяет завершённые цепочки
- ✅ **Idle timeout для автобатчей** (955bd87) — `CompressionBatchTracker.kt`: idle 20с после последнего `addResult()`, max lifetime 10 мин, `createdAt` вместо парсинга batchId
- ✅ **cleanupStuckWorkManagerChain** (955bd87) — `MainActivity.kt`: очистка >50 застрявших works при запуске
- ✅ **Исправлен уровень логирования "Файл уже сжат"** — `SequentialImageProcessor.kt:300`: `LogUtil.error()` → `LogUtil.skipImage()` + `onCompressionSkipped()`
- ✅ **Handler → Coroutines** — CompressionBatchTracker, MediaStoreObserver

### Метрики проекта
- Исходный код: 38 Kotlin файлов
- Unit тесты: 35 файлов
- Instrumentation тесты: 25 файлов
- Скрипты: 11
- Skills: 5 (agents-updater, android-optimization-analyzer, android-test-suite, code-analyzer, skill-creator)
- Агенты: 14 файлов
- Версия: 2.2.10

---

## Known Issues

### ✅ Дубликаты при массовой обработке (решено)

**Результат:** Тест 100 фото (burst-режим) — 0 дубликатов обработки, 0 битых файлов, 99 верификаций пройдено.

**Что помогло:** Последовательная обработка + дедупликация через WorkInfo + IGNORE_PERIOD 60с.

### ✅ Burst разбивается на несколько батчей (решено)

**Результат:** Тест 49 фото — батч финализировался с 7 результатами вместо 49 из-за race condition.

**Что помогло:** Убран `extendAutoBatchTimeout()` из `getOrCreateAutoBatch()` — только `addResult()` продлевает таймаут, устраняя конкуренцию между scanning thread и worker thread.

---

## Testing

### Команды тестирования (по запросу)
```bash
# Unit тесты
./gradlew testDebugUnitTest

# Instrumentation тесты
./scripts/run_instrumentation_tests.sh

# Все тесты
./scripts/run_all_tests.sh
```

**Примечание:** Эмулятор `Small_Phone` должен быть запущен для instrumentation тестов.

---

## Version Update

**Файлы:** gradle.properties, app/build.gradle.kts

**Шаги:**
1. Обновить `VERSION_NAME_BASE`
2. Увеличить `versionCode`
3. `./gradlew assembleDebug`, `./gradlew assembleRelease`

---

## Development Workflows

### Рефакторинг кода (code-analyzer)

**Когда**: Нужно устранить дубликаты, мёртвый код, проблемы качества
**Файлы**: Любые `.kt` файлы

**Шаги:**
1. Запустить `/code-analyzer` для анализа
2. Использовать `voltagent-lang:kotlin-specialist` для рефакторинга
3. Выполнить `./gradlew assembleDebug`
4. Commit и `/agents-updater`

---

**См. также:** `.agents/rules/rules.md` - инструкции для AI-агентов по работе с проектом

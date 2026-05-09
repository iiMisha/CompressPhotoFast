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

## Current Focus (Май 2026)

### Последние изменения
- ✅ **Исправление битых файлов при burst-режиме** (c81dd50) — 5 исправлений в 5 файлах:
  - `UriProcessingTracker.kt`: IGNORE_PERIOD 5с → 60с для предотвращения Petri-цикла уведомлений
  - `ImageCompressionWorker.kt`: верификация ВСЕГДА (не только при разных URI), ignore period на оба URI
  - `ImageDetectionJobService.kt`: убран TOCTOU race condition, экспоненциальный backoff для isPending (3с/6с/12с)
  - `MediaStoreObserver.kt`: проверка свежего маркера сжатия (< 60с) перед обработкой
  - `ExifUtil.kt`: верификация целостности после saveAttributes() с восстановлением из backup
- ✅ **Оптимизация двойного управления URI** - устранено дублирование логики URI в ImageDetectionJobService
- ✅ **Безопасная проверка целостности** - динамический контроль баллов сжатия и верификация
- ✅ **Улучшен debouncing в ImageDetectionJobService** - trailing debounce через ConcurrentHashMap
- ✅ **Handler → Coroutines** - CompressionBatchTracker, MediaStoreObserver

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

### 🟡 Дубликаты при массовой обработке (в процессе исправления)

**Проблема:** При обработке 50+ файлов с автосжатием создаются дубликаты в отдельной папке.

**Прогресс:** Значительно улучшено (c81dd50) — устранены Petri-цикл уведомлений, TOCTOU race condition, пропуски верификации.

**Файлы:** UriProcessingTracker.kt, ImageCompressionWorker.kt, ImageDetectionJobService.kt, MediaStoreObserver.kt, ExifUtil.kt

**Шаги:**
1. Протестировать на 50+ файлах (burst-режим камеры)
2. Если проблема сохраняется — добавить content-based дедупликацию

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

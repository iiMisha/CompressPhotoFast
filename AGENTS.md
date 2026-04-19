# CompressPhotoFast - Проектная документация

**Язык:** Русский | **ОС:** Linux Mint | **Версия:** 2.2.10

---

## Project Overview

Кроссплатформенное приложение для сжатия фотографий. Android (API 29+) + CLI (Python 3.10+). Идентичная логика сжатия с сохранением EXIF-маркеров.

**Проблема**: Пользователям нужно уменьшать размер фотографий для экономии места и отправки в мессенджеры. Стандартные инструменты неудобны.

**Решение**: Android-приложение + CLI с быстрой обработкой, автоматическим режимом и настройками качества.

### Agent Symlinks

Символические ссылки `.agents/` → `.claude/`, `.gemini/`, `.opencode/`, `.qwen/` для синхронизации агентов и скиллов между AI-платформами. Оригиналы в `.agents/`, платформы используют symlinks.

---

## Tech Stack

### Android
- Kotlin 2.2.10, Java 17, AGP 9.0.1, KSP 2.3.2
- MVVM + Hilt 2.57.1 (DI)
- Compressor 3.0.1, Coil 3.3.0, DataStore 1.1.7, ExifInterface 1.4.1
- Coroutines 1.10.2, WorkManager 2.10.3
- JUnit, MockK, Espresso, JaCoCo (мин 30% coverage)
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

## Current Focus (Апрель 2026)

### Известные проблемы
- 🔴 **Дубликаты при массовой обработке (50+ файлов)** - проблема с URI

### Последние изменения
- ✅ **Оптимизация двойного управления URI** - устранено дублирование логики URI в ImageDetectionJobService, улучшена обработка параллельных задач
- ✅ **Безопасная проверка целостности** - динамический контроль баллов сжатия и верификация для устранения повреждений файлов (8 файлов)
- ✅ **Улучшен debouncing в ImageDetectionJobService** - trailing debounce через ConcurrentHashMap
- ✅ **Расширено окно сканирования галереи** - 24ч → 48ч
- ✅ **Обновлены настройки качества** - HIGH: 85→80
- ✅ **Android Optimization Analysis** - 9/14 оптимизаций
- ✅ **Рефакторинг качества кода** - ~200 строк дубликатов устранено
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

### 🔴 Дубликаты при массовой обработке

**Проблема:** При обработке 50+ файлов с автоматическим сжатием создаются дубликаты в отдельной папке. Проблема с URI.

**Прогресс:** Частично исправлено (d6ec39f, 5e7edbd) — устранено дублирование логики URI, добавлена проверка целостности.

**Файлы:** MediaStoreUtil.kt, FileOperationsUtil.kt, ImageCompressionWorker.kt, ImageDetectionJobService.kt

**Шаги:**
1. Протестировать на 50+ файлах
2. Проверить логирование путей
3. Если проблема сохраняется — добавить content-based дедупликацию

---

## Testing Coverage

**Минимум:** 30% (JaCoCo)

### Команды тестирования
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
3. Обновить тесты (если нужно)
4. Выполнить `./gradlew assembleDebug`
5. Запустить все тесты через `Task(general-purpose)`
6. Commit и `/agents-updater`

**Важно**: Всегда запускать тесты в отдельном субагенте через Task tool

---

**См. также:** `.agents/rules/rules.md` - инструкции для AI-агентов по работе с проектом

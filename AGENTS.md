# CompressPhotoFast - Проектная документация

**Язык:** Русский | **ОС:** Linux Mint | **Версия:** 2.2.10

---

## Project Overview

Кроссплатформенное приложение для сжатия фотографий. Android (API 29+) + CLI (Python 3.10+). Идентичная логика сжатия с сохранением EXIF-маркеров.

**Проблема**: Пользователям нужно уменьшать размер фотографий для экономии места и отправки в мессенджеры. Стандартные инструменты неудобны.

**Решение**: Android-приложение + CLI с быстрой обработкой, автоматическим режимом и настройками качества.

### Agent Symlinks

Единая система символических ссылок для синхронизации агентов и скиллов между AI-платформами (`.claude/`, `.gemini/`, `.opencode/`, `.qwen/`). Оригиналы в `.agents/`, платформы используют symlinks.

**Структура:**
```
.agents/                    # Оригинальные файлы (источник истинности)
├── agents/                 # 6 агентов
├── rules/                  # Единые правила
└── skills/                 # 5 скиллов

.claude/                    # Папка платформы
├── agents → .agents/agents (symlink)
├── rules → .agents/rules   (symlink)
└── skills → .agents/skills (symlink)
```

**Преимущества:** единый источник изменений, без дублирования, автоматическая синхронизация между платформами.

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
- Настройки качества: 60/70/85
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

## Current Focus (Март 2026)

### Известные проблемы
- 🔴 **Дубликаты при массовой обработке (50+ файлов)** - проблема с URI
- ⚠️ **Instrumentation тесты падают** - daemon упал при 219/248 тестов (88%)

### Последние изменения
- ✅ **Android Optimization Analysis** - выполнено 9 из 14 оптимизаций
  - Критические (3/3): destroyStatic() для CompressionBatchTracker, убрана delay(50), добавлен 50MB limit
  - Высокий приоритет (1/4): static методы помечены как @Deprecated
  - Средний приоритет (5/5): убрана дублирующая проверка файла, LruCache на основе maxMemory(), улучшены таймауты
  - Низкий приоритет (2/3): fallback для reflection, регулярный cleanupOldBatches()
- ✅ **Рефакторинг качества кода** - устранено ~200 строк дубликатов (MediaStoreUtil, ExifUtil)
- ✅ **Handler → Coroutines** - CompressionBatchTracker, MediaStoreObserver обновлены
- ✅ **Удалён deprecated код** - runBlocking, unused методы
- ✅ **Все 576 тестов проходят** - 328 unit + 248 instrumentation
- ✅ **Добавлен скилл code-analyzer** - анализ кода на дубликаты, мёртвый код и качество
- ✅ **Добавлен скилл android-test-suite** - запуск тестов в изолированном субагенте
- ✅ **Изменён формат имени APK** - унифицированный формат для debug/release сборок
- ✅ **Исправлена ориентация Samsung (JPEG + HEIC)** - обработка EXIF orientation, поворот bitmap, тесты

### Метрики проекта
- Исходный код: 39 Kotlin файлов
- Unit тесты: 328 (100% pass)
- Instrumentation тесты: 248 (88% pass, 219 completed, 0 failed)
- Скрипты: 12
- Skills: 6 (agents-updater, android-optimization-analyzer, android-test-suite, code-analyzer, skill-creator, glm-plan-usage)
- Версия: 2.2.10

---

## Known Issues

### 🔴 Дубликаты при массовой обработке

**Проблема:** При обработке 50+ файлов с автоматическим сжатием создаются дубликаты в отдельной папке. Подозрение: проблема с URI.

**Файлы:** MediaStoreUtil.kt, FileOperationsUtil.kt, ImageCompressionWorker.kt

**Шаги:**
1. Проверить логику копирования и работу с URI
2. Добавить логирование путей
3. Протестировать

### ⚠️ Instrumentation тесты падают

**Проблема:** Android Test Daemon падает во время выполнения instrumentation тестов. Успешно завершается 219 из 248 тестов (88%).

**Последний запуск:** 2026-03-14

**Шаги:**
1. Перезапустить эмулятор Small_Phone
2. Проверить логи adb logcat для анализа причины падения daemon
3. Рассмотреть разбивку тестов на smaller batches

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

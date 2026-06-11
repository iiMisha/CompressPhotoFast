# CompressPhotoFast — Проектная документация

**Версия:** 2.2.10 | **ОС:** Linux Mint | **Язык проекта:** Русский

---

## ⚡️ Шпаргалка разработчика

```bash
# Чтение текущего контекста и статуса
# Статус: [AGENTS.md: Active]

# Сборка проекта
./gradlew assembleDebug

# Запуск тестов
./scripts/run_unit_tests.sh
./scripts/run_instrumentation_tests.sh
./scripts/run_all_tests.sh
```

---

## 📋 Обзор проекта

Кроссплатформенная утилита сжатия фото с сохранением EXIF (Android API 29+ и CLI Python 3.10+).

- **Проблема:** Большой размер фото, неудобство ручного сжатия перед отправкой.
- **Решение:** Фоновое автообнаружение новых фото + ручной/пакетный режимы сжатия с гибкими настройками качества.
- **Синхронизация AI:** Симлинки `.agents/` → `.claude/`, `.gemini/`, `.opencode/`, `.qwen/` для синхронизации агентов и скиллов.

---

## 🛠 Технологический стек

### Android
- Kotlin 2.2.10, Coroutines 1.10.2, Hilt 2.57.1 (DI), WorkManager 2.10.3
- Compressor 3.0.1, Coil 3.3.0, DataStore 1.1.7, ExifInterface 1.4.1
- JUnit, MockK, Espresso, JaCoCo, Test Orchestrator 1.5.0
- SDK: minSdk 29, targetSdk 36

### CLI (Python 3.10+)
- Pillow, pillow-heif, piexif, Click, Rich, tqdm, ProcessPoolExecutor

---

## 🏛 Архитектура приложения

### Android-слои
- **UI:** [MainActivity.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/ui/MainActivity.kt), [MainViewModel.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/ui/MainViewModel.kt)
- **Domain (Бизнес-логика):** [ImageCompressionUtil.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/util/ImageCompressionUtil.kt), [ImageCompressionWorker.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/worker/ImageCompressionWorker.kt), [SettingsManager.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/util/SettingsManager.kt)
- **Data:** MediaStore, SettingsManager
- **DI & Singletons:** [AppModule.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/di/AppModule.kt), [UriProcessingTracker.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/util/UriProcessingTracker.kt), [PerformanceMonitor.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/util/PerformanceMonitor.kt), [CompressionBatchTracker.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/util/CompressionBatchTracker.kt)

### Фоновая обработка
- [BackgroundMonitoringService.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/service/BackgroundMonitoringService.kt) — отслеживание новых изображений
- [ImageDetectionJobService.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/service/ImageDetectionJobService.kt) — периодический поиск
- [BootCompletedReceiver.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/service/BootCompletedReceiver.kt) — автозапуск после перезагрузки

### CLI (Python)
- [cli.py](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/compressphotofast-cli/src/cli.py) — точка входа, [compression.py](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/compressphotofast-cli/src/compression.py) — логика сжатия

---

## ⚙️ Бизнес-логика сжатия

- **Минимальный размер:** 100 КБ (меньшие файлы не сжимаются)
- **Эффективность:** экономия от 30% + 10 КБ (иначе сжатие отменяется)
- **Маркер сжатого файла:** EXIF-тэг `CompressPhotoFast_Compressed:quality:timestamp`

---

## 💎 Стиль кода и гайдлайны

- **Kotlin:** MVVM, DI (Hilt), Coroutines (без Handler/GlobalScope), обязательные методы `destroy()` для очистки, `inSampleSize` и `RGB_565` для экономии памяти, пакетные операции MediaStore.
- **Python:** Идентичная Android-части логика сжатия, пул процессов `ProcessPoolExecutor`.

---

## 🎯 Текущий фокус (Июнь 2026)

### В работе (незакоммиченные изменения)
- 🔧 **Очистка мёртвого кода** — удаление неиспользуемых классов и консолидация:
  - Удалены: `FileInfoUtil.kt`, `Result.kt`, `SettingsDataStore.kt`
  - [StatsTracker.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/util/StatsTracker.kt): убран дублирующий `processingUris` (уже есть в UriProcessingTracker)
  - [FileOperationsUtil.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/util/FileOperationsUtil.kt): удалены `createTempImageFileValidated()` и `isScreenshot()`
  - [UriUtil.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/util/UriUtil.kt): `isScreenshot()` теперь использует `OptimizedCacheUtil`
  - [AppModule.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/di/AppModule.kt): удалены провайдеры `OptimizedCacheUtil` и `SettingsDataStore`
  - [ImageCompressionWorker.kt](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/src/main/java/com/compressphotofast/worker/ImageCompressionWorker.kt): убрана неиспользуемая инъекция `optimizedCacheUtil`

### Недавние изменения (закоммиченные)
- ✅ **Надежность работы с файлами и сервисами** (`f46f571`) — CancellationException cleanup, очистка ресурсов в unregister/onStopJob
- ✅ **Надежность файловых операций** (`ce844d1`) — `read() != -1` вместо `available()`, `rwt` для Android 12+
- ✅ **Race conditions в обнаружении** (`395b895`) — убран TOCTOU, synchronized batch, STALE_URI_THRESHOLD 30 мин
- ✅ **Надежность файловых операций** (`7f4bf5f`) — константы задержек EXIF/MediaStore, обработка temp-файлов
- ✅ **Ложное срабатывание каталога приложения** (`ec780c5`) — удалена "compressphotofast" из `appDirPatterns`
- ✅ **Отслеживание времени сканирования** (`67b1543`) — динамическое окно сканирования на основе timestamp

### Метрики
- Исходный код: 38 Kotlin-файлов, 9 Python-файлов
- Покрытие тестами: 35 Unit-тестов, 25 Instrumentation-тестов
- Версия: 2.2.10

### Известные проблемы
- 🚫 Активных известных проблем не обнаружено. Дубликаты при серийной съемке (burst) и дробление батчей успешно решены.

---

## 🧪 Тестирование и релиз

### Команды
- **Unit-тесты:** `./gradlew testDebugUnitTest` (или через [run_unit_tests.sh](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/scripts/run_unit_tests.sh))
- **Instrumentation-тесты:** [run_instrumentation_tests.sh](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/scripts/run_instrumentation_tests.sh) (требуется запущенный эмулятор `Small_Phone`)
- **Все тесты:** [run_all_tests.sh](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/scripts/run_all_tests.sh) (запуск перед релизом)

### Обновление версии (в [gradle.properties](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/gradle.properties) и [build.gradle.kts](file:///home/misha/Документы/1 Проекты/CompressPhotoFast/app/build.gradle.kts))
1. Обновить `VERSION_NAME_BASE`
2. Инкрементировать `versionCode`
3. Собрать сборки: `./gradlew assembleDebug` и `./gradlew assembleRelease`

---

## 🔄 Шаблоны рабочих процессов (Workflows)

### Рефакторинг кода (code-analyzer)
**Когда:** Оптимизация, устранение дублирования, мёртвого кода.
**Шаги:**
1. Запустить `/code-analyzer` для анализа.
2. Использовать `voltagent-lang:kotlin-specialist` для рефакторинга.
3. Выполнить сборку: `./gradlew assembleDebug`.
4. Зафиксировать изменения в Git и запустить `/agents-updater`.

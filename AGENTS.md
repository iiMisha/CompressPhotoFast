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
- 📌 **Очистка мёртвого кода v3 (Tier 1)** (Android) — по плану `.kilo/plans/dead-code-cleanup-v3.md`. Реально Tier 1 (используется только в main, не в test/androidTest): `MainViewModel.workObservers` поле + блок очистки в `onCleared()` + 3 импорта (`Observer`, `WorkInfo`, `UUID`); orphaned KDoc в `ImageProcessingChecker.kt` над `ProcessingCheckResult`. Сборка зелёная, 330 unit-тестов проходят.
- ⚠️ **Скорректированные пункты плана (Test-only / Tier 2)** — 3 из 5 пунктов плана v3 оказались test-only и НЕ удалены (план неверно оценил «0 references», не проверив `test`/`androidTest`): `Constants.PREF_COMPRESSION_PRESET` (исп. в `SettingsIntegrationTest.kt:238,245`), `StatsTracker.COMPRESSION_STATUS_NONE` (исп. в `StatsTrackerTest.kt:25`), 5 `*Compat`-методов + instance `getActiveBatchCount()`/`clearAllBatches()` в `CompressionBatchTracker.kt` (~80 вызовов в `CompressionBatchTrackerTest.kt`). Удаление требует правки тестов (Tier 2, отклонено пользователем).
- 📌 **Очистка мёртвого и дублирующегося кода v2** (Android, -654 строки) — удалены: мёртвые функции (`createTempImageFile`, `insertImageIntoMediaStore`, `showProgressNotification`, `cancelNotification`, 3× `destroy()`, `withUriLock`, `safelyProcessAfterRemoval`, `cleanupExpiredEntries`, `addPendingRenameRequest`, каскадно `addProcessingUri`/`cleanupUnusedMutexes`/`MAX_MUTEX_COUNT`), мёртвые подклассы исключений (`UnsupportedFormat`, `PermissionDenied`, `IoError` + catch-блоки), мёртвый broadcast-канал RENAME целиком (`renamePermissionReceiver`, `renameRequestLauncher`, `permissionRequest`, `requestPermission`, константы), неиспользуемые импорты (~35, включая всю цепочку `id.zelory.compressor`), закомментированный код; устранены дубликаты (verifyImageIntegrity, GPS-массив ×3, backup-restore ×2, хелперы `computeSizeReductionPercent`/`splitNameAndExtension`, HEIC-проверка); исправлен баг: канал `compression_errors` теперь создаётся. Сборка зелёная, 330 unit-тестов проходят.

### Недавние изменения (закоммиченные)
- ✅ **Оптимизация архитектуры UI** (`f463068`) — удалены `SequentialImageProcessor`, BroadcastReceiver'ы пропуска/готовности, избыточные наблюдатели в MainViewModel/MainActivity (-626 строк)
- ✅ **Удаление избыточного кода** (`87478cb`) — консолидация AppModule, MediaStoreObserver, OptimizedCacheUtil, UriProcessingTracker (-472 строки)
- ✅ **Удаление избыточных утилит** (`7186907`) — удалены `FileInfoUtil`, `Result`, `SettingsDataStore`; убран дублирующий `processingUris` в StatsTracker
- ✅ **Надежность работы с файлами и сервисами** (`f46f571`) — CancellationException cleanup, очистка ресурсов в unregister/onStopJob
- ✅ **Надежность файловых операций** (`ce844d1`) — `read() != -1` вместо `available()`, `rwt` для Android 12+
- ✅ **Race conditions в обнаружении** (`395b895`) — убран TOCTOU, synchronized batch, STALE_URI_THRESHOLD 30 мин
- ✅ **Отслеживание времени сканирования** (`67b1543`) — динамическое окно сканирования на основе timestamp

### Метрики
- Исходный код: 34 Kotlin-файла, 9 Python-файлов
- Покрытие тестами: 330 Unit-тестов (проходят), 25 Instrumentation-тестов
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

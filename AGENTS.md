# CompressPhotoFast

Кроссплатформенная утилита сжатия фото с сохранением EXIF: Android (API 29+) и Python CLI (3.10+). Язык проекта: русский. Версия: `2.2.10`.

## Быстрые правила

- По умолчанию работаем с Android-приложением; CLI-версией — только по явному запросу.
- Для сборки или передачи APK обязательно использовать навык `apk`; после изменения runtime-кода `app/src/main/**` и успешной сборки публиковать APK автоматически (не для docs/тестов/CLI/ресурсов).
- Android-тесты: по умолчанию запускать только unit-тесты (~10 мин); instrumentation (~20 мин) — только перед PR в main, при изменении кода, работающего с Android API, или при проблемах с UI/фоновыми сервисами; полный прогон — только перед релизом.
- Не обходить ограничения Android для force stop, отозванных разрешений и ручных ограничений батареи.
- Не удалять код, используемый в `test` или `androidTest`, без одновременного обновления тестов.

## Стек

- Android: Kotlin 2.2.10, Coroutines, Hilt, WorkManager, Coil, ExifInterface; minSdk 29, targetSdk 36.
- Тесты: JUnit, MockK, Robolectric, Espresso, JaCoCo.
- CLI: Pillow, pillow-heif, piexif, Click, Rich, tqdm, `ProcessPoolExecutor`.

## Архитектура Android

- Пакеты: `domain/` (правила и оркестрация) → `data/` (MediaStore, EXIF, backup, настройки) → `util/` (leaf: `Constants`, `LogUtil`, `FileIoUtil`); `platform/` — уведомления, разрешения, батарея. `data/` и `util/` не импортируют `domain/`; `domain/`, `data/`, `service/`, `worker/` не импортируют `ui`.
- UI: `ui/MainActivity.kt`, `ui/MainViewModel.kt` (валидация и постановка share/Photo Picker URI — `compressSharedImages`; события воркера — `compressionEvents`).
- Сжатие: `domain/CompressImageUseCase.kt` (EXIF → тест → сохранение → верификация → удаление оригинала; возвращает `Outcome`), `worker/ImageCompressionWorker.kt` (тонкий адаптер: lock URI, gate, foreground, retry, показ результата; после gate — свежий snapshot + `readSourceExif` → checker и use case; TOCTOU-проверки делают свои запросы), `worker/ImageSettleWorker.kt` (только дренаж legacy settle-работ), `worker/GalleryReconciliationWorker.kt`, `domain/CompressionWorkScheduler.kt`, `domain/CompressionExecutionGate.kt`, `domain/ImageCompressionUtil.kt`, `domain/ImageProcessingChecker.kt`.
- Данные: `data/SettingsManager.kt` (SharedPreferences), `data/MediaStoreUtil.kt` (`saveCompressedImageFromFile(File artifact)` → `SaveResult`, без UI), `data/ExifUtil.kt` (`readSourceExif` — теги + маркер одним разбором; `getExifInterface` через FD, только чтение), `data/MediaItemSnapshot.kt` (метаданные URI одним запросом; `PROJECTION`/`fromCursor` в скане и `MainViewModel`; pending — `UriUtil.isPendingEffective`), `data/CompressionMarker.kt` (формат маркера, чистые функции), `data/UriProcessingTracker.kt` (ключи по MediaStore ID, `external`/`external_primary` — один ключ), `data/MediaStoreObserver.kt` (один `MediaItemSnapshot` на URI, без EXIF), `data/StatsTracker.kt` (`apply`).
- Инфраструктура: `data/BackupRegistry.kt`, `data/BackupRecoveryHelper.kt`, `util/FileIoUtil.kt` (fstat/fsync), `di/AppModule.kt`, `di/CoroutineScopeModule.kt` (`@ApplicationScope`), `domain/CompressionBatchTracker.kt`, `domain/CompressionEvents.kt` (SharedFlow воркер → UI/сервис вместо broadcast'ов).
- Мониторинг: `service/BackgroundMonitoringService.kt`, `service/ImageDetectionJobService.kt`, `service/MonitoringController.kt`, `service/BootCompletedReceiver.kt`.
- CLI: `compressphotofast-cli/src/cli.py`, `compressphotofast-cli/src/compression.py`.

## Инварианты сжатия

- Не сжимать файлы меньше 100 КБ.
- Сохранять результат, только если экономия не меньше 30% и 10 КБ.
- Маркировать сжатые файлы EXIF-тегом `CompressPhotoFast_Compressed:quality:timestamp:size:origSize` (size — сжатый размер, origSize — исходный до сжатия; оба фиксированной ширины): для сжатой копии — двухфазно в локальный artifact до публикации (`ExifUtil.writeExifToArtifact`, size = точная длина, в MediaStore одна durable-запись); для оригинала (маркер пропуска) — `ExifUtil.writeSkipMarker`: одна запись size до записи (fstat) через `guardedExifWrite`, теги не переписываются, корректирующая — только при дрейфе сверх допуска, провал → откат без маркера; дрейф `saveAttributes()` до ~1 КБ покрывается допуском). Эффективность сжатия восстанавливается из маркера сторонним EXIF-приложением.
- Повторная обработка файла с маркером — только если текущий размер файла расходится с size в маркере сверх допуска `Constants.MARKER_SIZE_TOLERANCE_BYTES` (4 КБ, `ImageProcessingChecker.isMarkerSizeMismatch`; в CLI — `ExifHandler.should_recompress`); mtime не используется; маркер без размера (старый формат/HEIC/заглушка) — пропуск.
- Сохранять исходное разрешение; память контролировать admission-проверкой, software/low-RAM decode и `RGB_565` для JPEG, пакетно работать с MediaStore.
- Python-логика должна сохранять семантическое соответствие Android-реализации.

## Актуальный контекст

- Мониторинг фото работает как `specialUse` foreground service на API 34+; `MonitoringController` централизует запуск и остановку.
- Сервис использует `START_STICKY`, корректно отменяет резервный Job при ручной остановке и восстанавливается после перезагрузки или обновления приложения.
- При первом включении автосжатия однократно запрашивается исключение из оптимизации батареи; флаг хранится в `SettingsManager`.
- Игнорирование фото из мессенджеров удалено: защита от повторного сжатия основана на проверке эффективности.
- Backup перед любой перезаписью на месте (`rwt`, `saveAttributes`): `BackupRegistry.createBackup` (оригинал через `setRequireOriginal`, fsync, сверка длины, реестр `path→uri`: регистрация — commit, снятие — apply); `releaseBackup` только при успехе или успешном `rollback` (вместе с вложенными `exif_backup_` того же URI — `releaseBackupsCreatedSince`), иначе backup остаётся до recovery. Файл пользователя никогда не удаляется при сбое.
- Recovery при старте (`BackupRecoveryHelper`, до `TempFilesCleaner`, который не трогает зарегистрированные backup): безусловный restore самого раннего backup URI, иначе копия в `Pictures/CompressPhotoFast/Recovered`; backup'ы текущего процесса пропускаются.
- Replace-режим: перезапись на месте только если найденный по имени файл — сам оригинал (ID MediaStore; воркер тоже сравнивает через `isSameMediaItem`, не строки URI); размер оригинала сверяется до перезаписи/удаления (TOCTOU); провал EXIF — откат/удаление новой копии. `cleanDoubleExtensions` срезает только расширения изображений (как CLI). `Orientation=NORMAL` — только при `pixelsTransformed`.
- UI/E2E instrumentation-тесты удалены как неактуальные; сохранены интеграционные тесты утилит и сервисов.
- Recovery после LMK/OEM kill: cold-start и JobScheduler best-effort восстанавливают FGS, reconciliation независимо от FGS восстанавливает MediaStore URI, content-trigger использует два чередующихся job ID (update delay 3 с, URI в ignore-периоде `UriProcessingTracker` отсекаются); periodic reconciliation — 60 мин (`RECONCILIATION_INTERVAL_MINUTES`, policy UPDATE).
- Новые URI ставятся в per-URI unique WorkManager works через SHA-256 identity: одна работа на URI: auto — `setInitialDelay` 30 с + KEEP, manual — expedited без delay, заменяет неначатую auto-работу (REPLACE, если не RUNNING); legacy `sequential_image_compression` не отменяется и дренируется bounded Worker.
- Тяжёлая Bitmap/MediaStore-фаза сериализуется `CompressionExecutionGate`; transient retry ограничен пятью попытками с линейным backoff (`OutOfMemory` — одна повторная попытка), проблемный URI не блокирует соседние; уведомление о результате показывает только воркер.
- Автосжатие задерживается на 30 секунд, ручное сжатие запускается без задержки; JPEG test artifacts пишутся в `cacheDir` и удаляются после любого исхода.
- Ручной батч (Picker/Share): Snackbar «принято N», прогресс в подписи FAB (`CompressionBatchTracker.progress`), итог — `CompressionEvents.Event.BatchCompleted` → Snackbar (не зависит от уведомлений/`PREF_SHOW_COMPRESSION_TOAST`). Воркер отчитывается в батч на каждом финальном исходе (COMPRESSED/SKIPPED/FAILED); `setExpectedCount` после enqueue; таймаут скользящий 120 с.
- Разрешения запрашиваются все при первом запуске: один runtime-диалог (медиа/уведомления/гео EXIF) через `PermissionsManager.requestStartupPermissions`, затем All-Files-Access (`requestAllFilesAccessIfNeeded` в `MainActivity`), затем battery exemption; счётчик попыток запросов убран.
- DI: `SettingsManager` внедряется Hilt во все Hilt-компоненты (воркеры, сервисы, `MainActivity`, `CompressPhotoApp`, `CompressionWorkScheduler`); `SettingsManager.getInstance` — только в `object`/companion. Pending-delete URI — через `SettingsManager`. `ImageProcessingChecker`, `GalleryScanUtil` — инъецируемые `@Singleton`; граф `object` ациклический (новые зависимости не должны создавать циклов). Корутины синглтонов — в `@ApplicationScope`, не в самодельных scope.
- Скан галереи (FGS, Job, reconciliation) — только через `domain/GalleryScanCoordinator` (окно от watermark с overlap или HISTORY); watermark = время начала скана, продвигается только при `completedSuccessfully` и durable enqueue всех URI; triggered URI из Job watermark не двигают; HISTORY — только catch-up worker при cold start/`MainActivity` (не при пересоздании), FGS стартует со скана от watermark; в separate-режиме сжатые копии ищутся одним запросом имён app-директории на скан (`FileOperationsUtil.hasCompressedVersionName`); debug `ApplicationExitInfo` логирует человекочитаемую причину.

## Проверка и релиз

- Сборка: `./gradlew assembleDebug`.
- Unit-тесты: `./gradlew testDebugUnitTest`.
- Instrumentation-тесты: `./scripts/run_instrumentation_tests.sh`; нужен эмулятор `Small_Phone`.
- Перед релизом: `./scripts/run_all_tests.sh`, затем `./gradlew assembleDebug` и `./gradlew assembleRelease`.
- Версию обновлять в `gradle.properties` (`VERSION_NAME_BASE`) и `app/build.gradle.kts` (`versionCode`).
- Release: R8 без широких `-keep`, `shrinkResources`, `localeFilters=ru`; `LogUtil` вырезается `-assumenosideeffects`, Timber-дерево в release не сажается.
- `versionName` и имя APK содержат короткий хеш git-коммита (+ `-dirty` при несохранённых изменениях); хеш берётся через `GitHashValueSource` в `app/build.gradle.kts`, совместим с configuration cache.

## Рабочий процесс

1. Внести изменения в код.
2. Проверить сборку Android командой `./gradlew assembleDebug`.
3. Запустить unit-тесты `./gradlew testDebugUnitTest`.
4. Обновить этот файл навыком `agents-updater`, сохраняя его кратким и актуальным.
5. Собрать и расшарить debug-APK через навык `apk`.

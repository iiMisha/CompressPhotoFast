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
- Данные: `data/SettingsManager.kt` (SharedPreferences), `data/MediaStoreUtil.kt` (`saveCompressedImageFromFile(File artifact)` → `SaveResult` (`Saved.size` — точная длина), без UI; путь цели считается раз, в replace — из снимка; поиск по имени — только replace), `data/ExifUtil.kt` (`readSourceExif` — теги + маркер одним разбором; `getExifInterface` через FD, только чтение), `data/MediaItemSnapshot.kt` (метаданные URI одним запросом; `PROJECTION`/`fromCursor` в скане и `MainViewModel`; pending — `UriUtil.isPendingEffective`), `data/CompressionMarker.kt` (формат маркера, чистые функции), `data/UriProcessingTracker.kt` (ключи по MediaStore ID, `external`/`external_primary` — один ключ), `data/MediaStoreObserver.kt` (один `MediaItemSnapshot` на URI, без EXIF), `data/StatsTracker.kt` (`apply`).
- Инфраструктура: `data/BackupRegistry.kt`, `data/BackupRecoveryHelper.kt`, `util/FileIoUtil.kt` (fstat/fsync, буфер копий `COPY_BUFFER_SIZE` 256 КБ), `di/AppModule.kt`, `di/CoroutineScopeModule.kt` (`@ApplicationScope`), `domain/CompressionBatchTracker.kt`, `domain/CompressionEvents.kt` (SharedFlow воркер → UI/сервис вместо broadcast'ов).
- Мониторинг: `service/BackgroundMonitoringService.kt`, `service/ImageDetectionJobService.kt`, `service/MonitoringController.kt`, `service/BootCompletedReceiver.kt`.
- Debug-only: `app/src/debug/` (пакет `com.compressphotofast.debug`) — `FileLog` (журнал, Timber-дерево, crash-handler), `LogServer` (отправка), `DebugLogTools` (меню тулбара `MainActivity.setupUI`). `app/src/release/` — пустая заглушка `DebugLogTools`.
- CLI: `compressphotofast-cli/src/cli.py`, `compressphotofast-cli/src/compression.py`.

## Инварианты сжатия

- Не сжимать файлы меньше 100 КБ.
- Сохранять результат, только если экономия не меньше 30% и 10 КБ.
- Маркировать сжатые файлы EXIF-тегом `CompressPhotoFast_Compressed:quality:timestamp:size:origSize` (size — сжатый размер, origSize — исходный до сжатия; оба фиксированной ширины): для сжатой копии — в локальный artifact до публикации (`ExifUtil.writeExifToArtifact`: заглушка, затем патч байтов size на месте, fallback — повторный `saveAttributes()`; size = точная длина, в MediaStore одна durable-запись); для оригинала (маркер пропуска) — `ExifUtil.writeSkipMarker`: одна запись size до записи (fstat) через `guardedExifWrite`, теги не переписываются, корректирующая — только при дрейфе сверх допуска, провал → откат без маркера; дрейф `saveAttributes()` до ~1 КБ покрывается допуском; HEIC — только переименование суффиксом `_compressed`). Эффективность сжатия восстанавливается из маркера сторонним EXIF-приложением.
- Повторная обработка файла с маркером — только если текущий размер файла расходится с size в маркере сверх допуска `Constants.MARKER_SIZE_TOLERANCE_BYTES` (4 КБ, `ImageProcessingChecker.isMarkerSizeMismatch`; в CLI — `ExifHandler.should_recompress`); mtime не используется; маркер без размера (старый формат/HEIC/заглушка) — пропуск.
- Режим отдельной папки: оригиналы не маркируются и не переименовываются. Повтор отсекается точным именем копии (основа + `_compressed` + опц. `_N` + расширение, HEIC → `.jpg`; `FileOperationsUtil.hasCompressedVersionName`), префиксное совпадение запрещено.
- Сохранять исходное разрешение; память контролировать admission-проверкой, software/low-RAM decode и `RGB_565` для JPEG, пакетно работать с MediaStore.
- Python-логика должна сохранять семантическое соответствие Android-реализации.

## Актуальный контекст

- Мониторинг фото работает как `specialUse` foreground service на API 34+; `MonitoringController` централизует запуск и остановку.
- Сервис использует `START_STICKY`, корректно отменяет резервный Job при ручной остановке и восстанавливается после перезагрузки или обновления приложения.
- При первом включении автосжатия однократно запрашивается исключение из оптимизации батареи; флаг хранится в `SettingsManager`.
- Игнорирование фото из мессенджеров удалено: защита от повторного сжатия основана на проверке эффективности.
- HEIC (EXIF не пишется): маркер пропуска = переименование в `_compressed` (`ExifUtil.markHeicFileAsCompressed`, без backup); после rename — `OptimizedCacheUtil.invalidateExifData(uri)`, иначе устаревший кэш ведёт к пересжатию.
- Backup перед любой перезаписью на месте (`rwt`, `saveAttributes`): `BackupRegistry.createBackup` (оригинал через `setRequireOriginal`, fsync, сверка длины, реестр `path→uri`: регистрация — commit, снятие — apply); `releaseBackup` только при успехе или успешном `rollback` (вместе с вложенными `exif_backup_` того же URI — `releaseBackupsCreatedSince`), иначе backup остаётся до recovery. Файл пользователя никогда не удаляется при сбое.
- Recovery при старте (`BackupRecoveryHelper`, до `TempFilesCleaner`, который не трогает зарегистрированные backup): безусловный restore самого раннего backup URI, иначе копия в `Pictures/CompressPhotoFast/Recovered`; backup'ы текущего процесса пропускаются.
- Replace-режим: перезапись на месте только если найденный по имени файл — сам оригинал (ID MediaStore; воркер тоже сравнивает через `isSameMediaItem`, не строки URI); размер оригинала сверяется до перезаписи/удаления (TOCTOU); провал EXIF — откат/удаление новой копии. `cleanDoubleExtensions` срезает только расширения изображений (как CLI). `Orientation=NORMAL` — только при `pixelsTransformed`: JPEG/BitmapFactory пиксели не поворачиваются, тег Orientation копируется (как CLI); HEIC поворачивает ImageDecoder (`CompressionTestResult.pixelsOriented`).
- UI/E2E instrumentation-тесты удалены как неактуальные; сохранены интеграционные тесты утилит и сервисов.
- Recovery после LMK/OEM kill: cold-start и JobScheduler best-effort восстанавливают FGS, reconciliation независимо от FGS восстанавливает MediaStore URI, content-trigger использует два чередующихся job ID (update delay 3 с, URI в ignore-периоде `UriProcessingTracker` отсекаются); periodic reconciliation — 60 мин (`RECONCILIATION_INTERVAL_MINUTES`, policy UPDATE).
- Новые URI ставятся в per-URI unique WorkManager works через SHA-256 от `UriProcessingTracker.keyOf` (`external`/`external_primary` — одна работа): auto — `setInitialDelay` 30 с + KEEP; manual — expedited без delay, REPLACE неначатой auto-работы; при RUNNING — `CompressionEnqueueResult.ALREADY_RUNNING` (батч не ждёт отчёт). Legacy `sequential_image_compression` не отменяется и дренируется bounded Worker.
- Тяжёлая Bitmap/MediaStore-фаза сериализуется `CompressionExecutionGate`; transient retry ограничен пятью попытками с линейным backoff (`OutOfMemory` — одна повторная попытка), проблемный URI не блокирует соседние; уведомление о результате показывает только воркер.
- Воркер после gate не сжимает и отчитывает SKIPPED при `ALREADY_COMPRESSED`, `COMPRESSED_VERSION_EXISTS`, `IN_APP_DIRECTORY`, `ALREADY_SMALL` (`ImageCompressionWorker.ALREADY_HANDLED_REASONS`); пороги списков — `Constants.OPTIMUM_FILE_SIZE` (100 КБ). `BASIC_CHECK_FAILED` — без изменений.
- Автосжатие задерживается на 30 секунд, ручное сжатие запускается без задержки; JPEG test artifacts пишутся в `cacheDir` и удаляются после любого исхода.
- Выбор папки (FAB «Папка», `MainActivity.showFolderPicker`): свой диалог списка папок из MediaStore (`MediaStoreUtil.queryImageFolders`; системный `OpenDocumentTree` падает в DocumentsUI), затем `MainViewModel.compressFolder` → `queryImagesInFolder` (RELATIVE_PATH-диапазон, с подпапками, без лимита, без `CompressPhotoFast`, фильтр размера) → обычный ручной батч. Папки с `.nomedia` MediaStore не индексирует — не видны.
- Ручной батч (Picker/Share): share — тип `image/*` или `*/*` (смешанная выборка), не-изображения отсекаются по MIME в `compressSharedImages`; дубли URI — по `keyOf`; одна проверка существования на URI без пауз (`logFileDetails` — только debug); Snackbar «принято N», прогресс в подписи FAB (`CompressionBatchTracker.progress`), итог — `CompressionEvents.Event.BatchCompleted` → Snackbar (не зависит от уведомлений/`PREF_SHOW_COMPRESSION_TOAST`). Воркер отчитывается в батч ровно раз за попытку (COMPRESSED/SKIPPED/FAILED; занятый lock, пустой URI, отмена, OOM, сбой foreground после результата — тоже); retry — без отчёта; `setExpectedCount` после enqueue; таймаут скользящий 120 с.
- Разрешения запрашиваются все при первом запуске: один runtime-диалог (медиа/уведомления/гео EXIF) через `PermissionsManager.requestStartupPermissions`, затем All-Files-Access (`requestAllFilesAccessIfNeeded` в `MainActivity`), затем battery exemption; счётчик попыток запросов убран.
- DI: `SettingsManager` внедряется Hilt во все Hilt-компоненты (воркеры, сервисы, `MainActivity`, `CompressPhotoApp`, `CompressionWorkScheduler`); `SettingsManager.getInstance` — только в `object`/companion. Pending-delete URI — через `SettingsManager`. `ImageProcessingChecker`, `GalleryScanUtil` — инъецируемые `@Singleton`; граф `object` ациклический (новые зависимости не должны создавать циклов). Корутины синглтонов — в `@ApplicationScope`, не в самодельных scope.
- Логи в debug: меню тулбара → «Отправить журнал» (POST на `tools/log_receiver.py`, порт 8093, токен — в «Сервер логов»); приёмник — systemd user-unit `tools/compressphotofast-log-receiver.service` (enabled, linger), пишет в `~/CompressPhotoFast/log` (`/log` в `.gitignore`). В release нет `INTERNET`, cleartext и кода отправки.
- Скан галереи (FGS, Job, reconciliation) — только через `domain/GalleryScanCoordinator` (окно от watermark с overlap или HISTORY); watermark = время начала скана, продвигается только при `completedSuccessfully` и durable enqueue всех URI; triggered URI из Job watermark не двигают; live-источники (observer, Job) ставят только `.../images/media/<id>` (`UriProcessingTracker.isMediaImageItemUri`), корень/коллекция MediaStore отбрасываются; HISTORY — только catch-up при boot/`MainActivity` (не при пересоздании) и cold start; все catch-up не чаще раза в 12 ч внутри `GalleryReconciliationWorker.schedule` (`isHistoryCatchUpDue`, отметка — durable HISTORY; `catchUp=false` — только periodic), FGS стартует со скана от watermark; повторная постановка URI — no-op, если auto-работа уже ENQUEUED/BLOCKED/RUNNING (`CompressionWorkScheduler.enqueue`); в separate-режиме сжатые копии ищутся одним запросом имён app-директории на скан (`FileOperationsUtil.hasCompressedVersionName`, точное имя — см. инварианты); debug `ApplicationExitInfo` логирует человекочитаемую причину.

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

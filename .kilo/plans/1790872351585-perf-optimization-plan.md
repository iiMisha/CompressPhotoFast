# План оптимизации CompressPhotoFast

## Контекст

Аудит производительности с перекрёстной проверкой по коду. Ранее уже оптимизировано (не дублировать): один запрос MediaStore на URI, EXIF-маркер в artifact до публикации, одна работа на URI, сокращённые фоновые пробуждения.

**Проверено как уже оптимальное — не трогать:**
- `ExifUtil.writeExifToArtifact` (ExifUtil.kt:981-1019): в общем случае 2 перезаписи — это минимум двухфазной записи маркера фиксированной ширины через path-based `ExifInterface` (для сжатой копии, где размер до записи неизвестен).
- Admission-проверка памяти, `inSampleSize` + `RGB_565`, software/low-RAM ImageDecoder для HEIC.
- R8/shrinkResources/localeFilters — настроены.
- `MediaStoreUtil.handleFileNameConflict`: один `IN`-запрос на 100 имён — уже один IPC; разбиение на «точный + IN» выигрыша не даёт, при конфликте хуже.
- Bounds+decode одним FD (`ImageCompressionUtil.kt:170-180,272-274`): выигрыш — один `openInputStream`, не стоит усложнения.

Ключевые hot-path факты:
- Файл, помеченный маркером пропуска (недостаточная экономия — частый случай на уже оптимизированных галереях), сейчас стоит: **2–3 полные backup-копии + 2–3 перезаписи всего файла + 2–3 verify + ~4–6 sync commit** (фаза 1, фаза 2, опциональная корректирующая запись) + повторный EXIF-разбор при пост-проверке маркера. Плюс фаза 1 заново пишет в файл все теги, только что прочитанные из него же (`applyTags`), включая лоссовое `setLatLong`.
- Скан галереи (`GalleryScanUtil` → `ImageProcessingChecker`) на **каждый** URI делает отдельный MediaStore-запрос сжатой копии (separate-режим) и EXIF-разбор.
- HISTORY-скан за 48 ч ставится на **каждый** cold start (`CompressPhotoApp.kt:76`), включая холодные старты от WorkManager/JobScheduler после LMK, — с пустыми in-memory кэшами. В том числе **каждый запуск periodic reconciliation (60 мин) в убитом процессе** — это cold start, т.е. фактически HISTORY-скан ежечасно.

---

## Фаза 1 — низкорисковые чистые победы

### 1.1 OOM — не более одного retry
`worker/ImageCompressionWorker.kt:182-196`: `CompressionException.OutOfMemory` не должен расходовать все 5 transient-попыток, но и полностью исключать его нельзя: исключение бросается и из bounds-decode (`ImageCompressionUtil.kt:186`, `inJustDecodeBounds`), где OOM — следствие давления на heap от других компонентов процесса (Coil, UI), а не самого файла. Изменение: для `OutOfMemory` — retry только при `runAttemptCount == 0` (одна повторная попытка), дальше `failure`. `CompressionException.InsufficientMemory` (admission) — без изменений (transient).

### 1.2 Статистика: apply вместо commit
`data/StatsTracker.kt:79`: `commit()` → `apply()`. Данные advisory, UI читает из того же процесса. Ветку `if (!saved) return null` (:80-83) удалить (станет мёртвой); проверить `StatsTrackerTest`.

### 1.3 Реестр backup: release через apply
`data/BackupRegistry.kt:210` (`unregister`, вызывается из `releaseBackup`): `commit()` → `apply()`. **Create (`registerBackup`, строка 191) остаётся `commit()`** — durability реестра до опасной записи неприкосновенна.
Компромисс: `releaseBackup` (:148) сначала `unregister`, затем синхронно удаляет файл. При смерти процесса до flush `apply()` в реестре остаётся запись на **уже удалённый** файл — `BackupRecoveryHelper` (:43, :54) её отфильтрует. Restore устаревшего backup поверх нового файла возможен только при одновременном сбое `delete()` (потеря сжатия, не данных). Выигрыш небольшой: −1 fsync на backup.

### 1.4 Мёртвые аллокации сканера
`domain/GalleryScanUtil.kt:92-93,123-124`: `uriSizeMap`/`uriNameMap` заполняются, никогда не читаются — удалить (используются только `snapshots`).

### 1.5 Дедуп уведомления о результате (cleanup)
Воркер сам показывает уведомление (`worker/ImageCompressionWorker.kt:360-367`) **и** сервис повторно показывает его на тот же `Event.Result` (`service/BackgroundMonitoringService.kt:139-146`, к тому же всегда с `skipped = false`) с тем же `NOTIFICATION_ID_COMPRESSION_RESULT` — лишняя перерисовка. Убрать из `handleCompressionResult` показ уведомления и `removeProcessingUriSafe` (дублирует `finally` воркера). `setIgnorePeriod` дублирует `CompressImageUseCase.kt:189-191` — можно оставить (безвреден) или убрать вместе с подпиской. Показ остаётся в воркере — он работает и без сервиса.

---

## Фаза 2 — I/O маркеров пропуска и discovery

### 2.1 Маркер оригинала — одна запись (главный per-byte выигрыш)
Точка: `ExifUtil.applyExifFromMemory` (ExifUtil.kt:756-846). Вызовы с маркером на исходный файл: `CompressImageUseCase.markInefficient` (:282) и kept-original (:218), оба через `writeExifDataFromMemory` (:1351).

Наблюдения:
- `exifDataMemory` прочитан из **этого же** файла — `applyTags` переписывает те же значения (бесполезная работа; `setLatLong` к тому же пересчитывает GPS-рационалы с округлением). `pixelsTransformed = false`, ориентация не меняется.
- Фаза 1 с заглушкой нужна только когда размер после записи заранее неизвестен (сжатая копия). Для оригинала размер известен: строка маркера ~70 байт, дрейф `saveAttributes()` до ~1 КБ, допуск `MARKER_SIZE_TOLERANCE_BYTES` 4 КБ.

Изменение — отдельная функция `ExifUtil.writeSkipMarker(context, uri, quality, originalFileSize)` вместо `writeExifDataFromMemory` в обоих вызовах:
- размер `sizeBefore` — через `getActualFileSizeOnDisk` (fstat);
- одна `guardedExifWrite`: `setAttribute(TAG_USER_COMMENT, CompressionMarker.build(quality, now, sizeBefore, originalFileSize))` + `saveAttributes()` + verify — **без `applyTags`**;
- контрольный замер: если `|after − sizeBefore| > допуска` — одна корректирующая запись с фактическим размером (как сейчас в `writeActualSizeMarker`);
- провал → откат `guardedExifWrite` к исходному файлу (без маркера), `false`;
- без пост-проверки `getCompressionMarker` (:826, лишний полный EXIF-разбор) и `isUriExistsSuspend` (:765 — `openFileDescriptor` всё равно упадёт); сохранение `date_modified` вне replace-режима — как сейчас;
- результат пробрасывается: `markInefficient` логирует провал (исход остаётся `SkippedInefficient`); kept-original — как сейчас.

`applyExifFromMemory` с двухфазной записью остаётся (test-only, см. выше).

Смена семантики: провал записи откатывает к состоянию **без маркера** (было: возможна заглушка без размера = вечный skip) → файл повторно тестируется при следующем скане. Риск: при **стабильном** провале записи — повторный тест на каждой reconciliation (раз в 60 мин). Митигация: провал вероятен только при повреждении (verify), сюда же — счётчик/лог; при необходимости — кэш «не удалось пометить» в `UriProcessingTracker` на сессию. Инвариант «файл пользователя не теряется при сбое» сохранён; `releaseBackupsCreatedSince`-механика rwt-пути не затрагивается.

Попутно — удалить мёртвый код: `handleExifForSavedImage` (:1296) не имеет production-вызовов (только `androidTest/.../ExifUtilInstrumentedTest.kt`); через него же достижимы только `markCompressedImage` (:1199) и `copyExifData` (:324). Удалять вместе с обновлением `ExifUtilInstrumentedTest`.

Явное решение по остаткам: после замены обоих вызовов (:218, :282) тонкая обёртка `writeExifDataFromMemory` (:1351) теряет все production-вызовы — удалить её, а моки/verify в `CompressImageUseCaseTest` (:57, :116, :181) перевести на `writeSkipMarker`. `applyExifFromMemory` остаётся без production-вызовов, но широко используется androidTest-ами — оставить его (и двухфазный путь) как test-only в этом PR; полное удаление с миграцией тестов — отдельная чистка, не блокирует фазу 2.

Выигрыш на каждый skip/kept-marker: −1…2 полные копии исходника и перезаписи файла, −1…2 verify (полный decode), −2…4 sync commit, −1 EXIF-разбор, −1 MediaStore-запрос.

Обновить инвариант в AGENTS.md: «для оригинала (маркер пропуска) — одна запись с размером до записи через `guardedExifWrite`, корректирующая — при дрейфе сверх допуска».

### 2.2 Поиск сжатой копии: один запрос на скан
`FileOperationsUtil.findCompressedVersionByOriginalName` (:276) вызывается из `ImageProcessingChecker` (:219, только в separate-режиме) на каждый URI скана — N запросов.
Изменение: `GalleryScanUtil` один раз за скан (только в separate-режиме) получает `DISPLAY_NAME` с **тем же** условием по пути, что и сейчас (`RELATIVE_PATH LIKE '%CompressPhotoFast%'` — включает подпапки, напр. `Recovered`), в `List` и передаёт в checker; воркер и одиночные вызовы — без изменений (одиночный запрос).

Сопоставление в памяти должно повторять семантику `LIKE '$base%$ext'`:
- без учёта регистра для ASCII (SQLite `LIKE` по умолчанию case-insensitive): `startsWith(base, ignoreCase = true) && endsWith(ext, ignoreCase = true) && length >= base.length + ext.length`;
- `_`/`%` в имени (`IMG_1234`) в `LIKE` — wildcard; в памяти — литералы. Это строже (меньше ложных совпадений) — допустимое расхождение, зафиксировать в тесте.

Для больших app-директорий — индекс по `lowercase(ext)` или по первым символам base, если линейный проход окажется заметным (на тысячах имён — нет).

### 2.3 Наблюдатель: один snapshot вместо четырёх IPC и EXIF-разбора
`data/MediaStoreObserver.kt`: заменить `getFileNameFromUri` (:72) + `isFilePending` (:83 и повторно :111) + `isUriExistsSuspend` (:139) + `getCompressionMarker` (:87) одним `MediaItemSnapshot.query`: по нему — `_original.`, pending (`UriUtil.isPendingEffective`), существование (null snapshot) и путь в `APP_DIRECTORY` (`OptimizedCacheUtil.checkDirectoryStatus`).

**Предусловие:** EXIF-проверка свежего маркера (<60 с) сейчас — фактический барьер для новой сжатой копии **вне** `APP_DIRECTORY` (replace-режим без перезаписи на месте), если `shouldIgnore` промахнулся. А промах возможен: `UriProcessingTracker` ключует по `uri.toString()`, а один и тот же элемент приходит как `external` и `external_primary` (см. `CompressImageUseCase.kt:185`).
Поэтому порядок:
1. Нормализовать ключи `UriProcessingTracker` (ignore, recently-processed, processing) по MediaStore ID (`volume`-независимый ключ `images/<id>`), тест на `external` ↔ `external_primary`.
2. Затем — snapshot вместо EXIF-проверки. Если п.1 не делается — оставить EXIF-проверку как fallback только для файлов вне `APP_DIRECTORY` (выигрыш тогда — 4 IPC → 1 IPC + FD/EXIF только для не-app файлов).

Итог: 4 IPC + FD + EXIF-разбор → 1 IPC.

---

## Фаза 3 — изменения инвариантов (только по явному решению, отдельными PR, полный прогон)

### 3.1 Cold-start catch-up: throttle HISTORY (приоритет — самый дорогой фоновый путь)
`CompressPhotoApp.kt:76` (а также `MainActivity.kt:180`, `BootCompletedReceiver.kt:28`) ставит HISTORY-скан (48 ч) на каждый cold start, в т.ч. от WorkManager/JobScheduler после LMK и от самого periodic reconciliation — т.е. до раза в час. При этом `SINCE_WATERMARK` сам расширяется до HISTORY при старом watermark (`GalleryScanCoordinator.kt:71`), так что HISTORY нужен только для «потерянных» файлов внутри уже пройденного окна.
Изменение: HISTORY-catch-up не чаще раза в N часов (предложение: 12 ч; timestamp последнего **успешного** HISTORY в `SettingsManager`), иначе — `schedule(catchUp = false)` (скан от watermark+overlap). Проверку делать в `GalleryReconciliationWorker.doWork` (а не в местах вызова) — один источник правды. Механизм: сейчас `BootCompletedReceiver`/`CompressPhotoApp`/`MainActivity` передают неразличимый `catchUp=true`, поэтому добавить отдельный input-флаг exempt (например, `WORK_DATA_REASON`): boot и явное включение автосжатия — exempt (без throttle), обычный cold start — нет. LMK-recovery сохраняется: watermark не двигается без успешного завершения и durable enqueue. Обновить инвариант в AGENTS.md («HISTORY — только catch-up worker при cold start, не чаще раза в N ч»).

### 3.2 Gate: адаптивная параллельность — только после замеров
`domain/CompressionExecutionGate.kt`: `Mutex` → `Semaphore(permits)` — делать **только если профилирование покажет выигрыш**: encode JPEG CPU-bound, FUSE/MediaStore I/O при параллелизме конкурируют, возможен thermal throttling.
Шаг 0: замер батча (≥50 фото, 12–50 МП) на реальном устройстве с `permits = 1` и `2` (время, пиковый heap, температура).
Если выигрыш есть: `permits = 2` при `!isLowRamDevice && memoryClass >= 192`, иначе 1. Обязательно учёт резерва памяти под gate (admission сейчас смотрит на свободную память в момент проверки — две работы могут пройти одновременно и обе выделить bitmap → OOM). Проверить гонку `handleFileNameConflict` при параллельных insert с одинаковыми именами. Обновить инвариант gate в AGENTS.md, instrumentation (LMK-сценарии) и `run_all_tests.sh`.

---

## Отложено

- **Legacy settle**: per-URI `cancelUniqueWork(settleName)` в `CompressionWorkScheduler.kt:57` даёт копеечную нагрузку. Не трогать точечно; через 1–2 релиза после `255e6c9` удалить весь legacy-путь целиком (`cancelUniqueWork`, `settleName`, `ImageSettleWorker`, `enqueueFinal`) с обновлением тестов.

## Рекомендованный порядок
1. Фаза 1.
2. 2.1 (+ удаление мёртвого кода), 2.2, затем нормализация ключей трекера → 2.3.
3. 3.1 отдельным PR; 3.2 — только по результатам замеров.

## Валидация

- После каждой фазы: `./gradlew testDebugUnitTest` и `./gradlew assembleDebug`.
- Обновить/добавить unit-тесты:
  - маркер оригинала: ровно один `createBackup` и одна запись при дрейфе в допуске; корректирующая запись при дрейфе сверх допуска; провал verify → откат, `false`, файл без маркера, backup освобождён; `applyTags` не вызывается, GPS/ориентация не меняются;
  - OOM: первая попытка → retry, вторая → failure; `InsufficientMemory` — по-прежнему до 5 попыток;
  - дедуп уведомления (сервис не показывает результат);
  - трекер: `external` и `external_primary` одного ID дают один ключ;
  - observer: один запрос MediaStore, сжатая копия в app-директории отсекается без EXIF-разбора;
  - скан: один запрос сжатых копий на скан; совпадение `base…ext` без учёта регистра; `_` в имени — литерал; подпапки app-директории учитываются;
  - reconciliation: HISTORY не чаще раза в N ч, boot — без throttle.
- Затронутые тесты: `StatsTrackerTest`, `CompressImageUseCaseTest`, `CompressionMarkerSizeTest`, `ExifUtilInstrumentedTest` (удаление `handleExifForSavedImage`/`markCompressedImage`/`copyExifData` — instrumentation-прогон).
- Фаза 3: `./scripts/run_all_tests.sh`; при сомнениях в памяти — `./scripts/run_instrumentation_tests.sh`.
- Обновить AGENTS.md (CLAUDE.md — симлинк) навыком `agents-updater`: инвариант маркера оригинала «одна запись»; ключи трекера по MediaStore ID; при фазе 3 — инварианты catch-up и gate.
- Опубликовать debug-APK навыком `apk` после успешной сборки (изменён runtime-код).

## Риски и компромиссы

| Изменение | Риск | Митигация |
|---|---|---|
| 1.1 OOM 1 retry | Транзиентный OOM дважды подряд → failure | Reconciliation подберёт файл позже; ручной батч получает FAILED |
| 1.3 release apply() | Stale-запись реестра на удалённый файл после смерти процесса | Recovery фильтрует отсутствующие файлы; restore — только при сбое `delete()` (потеря сжатия, не данных) |
| 2.1 маркер одной записью | Провал записи → маркер отсутствует; при стабильном провале — повторный тест на каждой reconciliation | Провал вероятен только при повреждении; лог/сессионный кэш «не помечен» |
| 2.2 сопоставление в памяти | Расхождение с `LIKE` (регистр, wildcard, подпапки) | Явный ignoreCase, тот же фильтр пути в запросе, тесты |
| 2.3 observer snapshot | Пропуск self-trigger сжатой копии вне app-директории | Сначала ключи трекера по ID; иначе EXIF fallback для не-app файлов; KEEP + gated-проверка — второй барьер |
| 3.1 throttle HISTORY | Пропуск файлов внутри пройденного окна до следующего HISTORY | Watermark двигается только при успехе; HISTORY раз в N ч и при boot |
| 3.2 Semaphore(2) | OOM при одновременной admission; нет реального выигрыша | Замер до внедрения; учёт резерва памяти под gate |

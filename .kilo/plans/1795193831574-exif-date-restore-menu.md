# Восстановление EXIF-дат из умных источников (меню «три точки»)

## Цель

Новый пункт меню «три точки» на тулбаре: просканировать галерею и дописать EXIF-даты файлам, у которых нет ни одной даты. Даты берутся только из надёжных источников, **не из mtime**.

## Решения (подтверждено пользователем)

- Источники даты по приоритету: **1) имя файла → 2) timestamp маркера сжатия**; иначе файл пропускается. MediaStore `DATE_TAKEN` не используется (для файлов без EXIF-дат он скопирован из mtime).
- Теги: дата из **имени файла** → `DateTimeOriginal` + `DateTimeDigitized` + `DateTime` (настоящая дата съёмки в имени). Дата из **маркера** (дата сжатия, не съёмки) → только `DateTimeDigitized` + `DateTime` (как существующий бэкфилл `missingDateTags`, `DateTimeOriginal` не выдумывается).
- Существующая проверка «нет ни одной даты» переиспользуется: `ExifUtil.checkDateAvailability` (`DateTimeOriginal`, `DateTime`, `GpsDatestamp`, `DateTimeDigitized`) — при любой наличии даты файл пропускается.

## Инварианты

- Запись только через `guardedExifWrite` (backup → saveAttributes → верификация `ImageIntegrityUtil` → откат при сбое). Файл пользователя не повреждается.
- HEIC/HEIF — пропуск (`ExifInterface.saveAttributes` не поддерживает HEIC). PNG/WebP/JPEG — пишем.
- Pending-файлы — пропуск (`isPendingEffective` / `snapshot.exists`).
- После записи mtime файла **восстанавливается** всегда (пиксели не меняются, обновление миниатюр не нужно) — через `MediaStoreDateUtil.restoreModifiedDate`.
- Сериализация с сжатием: чтение EXIF и запись дат выполняются **под `CompressionExecutionGate` на каждый файл** (acquire → snapshot → parse → write → release в `finally`). Сжатие читает и пишет EXIF под тем же gate → гонок нет.
- Добавление дат меняет размер файла на ~100 байт — укладывается в `Constants.MARKER_SIZE_TOLERANCE_BYTES` (4 КБ), поле size в маркере не трогаем, пересжатие не провоцируется.
- После записи инвалидировать EXIF-кэши URI: `OptimizedCacheUtil.invalidateExifData(uri)` + приватный `exifDataCache` в `ExifUtil` (добавить внутреннюю evict-функцию).

## Изменения по файлам

### 1. `app/src/main/res/menu/main_menu.xml` (новый)

Пункт `action_restore_exif_dates`, title `@string/menu_restore_exif_dates`. Production-меню «три точки» (сейчас тулбар без меню в release — меню есть только в debug).

### 2. `MainActivity.kt` + `DebugLogTools` (оба flavor)

`setOnMenuItemClickListener` — один на тулбар, поэтому:

- **debug** `DebugLogTools.installUi(activity, toolbar)`: оставить только `toolbar.inflateMenu(R.menu.debug_menu)` (не ставить listener); новый метод `onMenuItemSelected(activity, item): Boolean` обрабатывает 3 debug-пункта, иначе `false`.
- **release** стаб: те же два пустых метода.
- `MainActivity.setupUI()` после `DebugLogTools.installUi`: `binding.toolbar.inflateMenu(R.menu.main_menu)`, затем один listener:
  - `R.id.action_restore_exif_dates` → диалог подтверждения → запуск;
  - иначе → `DebugLogTools.onMenuItemSelected(this, item)`.
- Диалог (`MaterialAlertDialogBuilder`): заголовок и текст-объяснение (сканирует галерею, дописывает даты файлам без дат из имени/маркера, с backup; HEIC пропускается); кнопки «Отмена» / «Восстановить». По подтверждению — `viewModel.scheduleExifDateFix()` + Snackbar «запущено» (строка `date_fix_started`).

### 3. Строки `app/src/main/res/values/strings.xml`

`menu_restore_exif_dates`, `date_fix_dialog_title`, `date_fix_dialog_message`, `date_fix_started`, `date_fix_result` («Даты EXIF восстановлены: %1$d · без источников даты: %2$d · ошибки: %3$d»), при нуле дат — «Даты уже есть у всех файлов» (`date_fix_nothing_done`).

### 4. `data/FileNameDateParser.kt` (новый, чистые функции)

`fun parseDateMs(displayName: String?): Long?` — ms epoch, локальная таймзона для шаблонов имён, никакой IO. Упорядоченный список шаблонов, первое совпадение выигрывает. Все цифровые шаблоны защищены границами `(?<!\d)…(?!\d)` — нельзя сматчиться внутри более длинной цифровой последовательности.

1. **Epoch с известным префиксом**: `mmexport1698741123456.jpg` (WhatsApp/WeChat экспорт), `wx_camera_1698741123456.jpg` (WeChat), `Snapchat-1698741123456.jpg` — префикс + 13 цифр → ms; диапазон ms-эпохи (2001-09…2286) плюс общий sanity-фильтр.
2. **Basename целиком из цифр**: 13 цифр → epoch-ms, 10 цифр → epoch-s (диапазоны: s — 2001-11-09…2033).
3. **Компактные дата-время** `(?<!\d)(19|20)\d{2}[01]\d[0-3]\d[_\-\. T]?[0-2]\d[0-5]\d[0-5]\d(?!\d)` (разделитель опционален — ISO-basic `20250101123456` и `20250101T123456` тоже матчатся): `IMG_20250101_123456.jpg`, `20250101_123456.jpg`, `Screenshot_20250101-123456_CompressPhotoFast.jpg`, `PANO_/MVIMG_20250101_123456.jpg`, `IMG_20250101_123456_BURST001.jpg`, `IMG_20250101_123456_compressed.jpg` (наши separate-копии).
4. **Разделённые дата-время** `yyyy[-_.]MM[-_.]dd([-_ ]HH[-_.]mm([-_.]ss)?)?` (разделители компонентов независимы, мс-хвост `-123` игнорируется): `signal-2025-01-02-03-04-05-123.jpg`, `photo_2025-01-01_12-30-45.jpg` (Telegram), `Screenshot_2022-01-01-12-30-45-123.png` (Huawei/Xiaomi), `2025-01-01.png`, `2025.01.01.jpg`, `photo_2025-01-01_12-30-45 (1).jpg` (копии с `(1)`).
5. **Только дата** `(?<!\d)(19|20)\d{2}[01]\d[0-3]\d(?!\d)` — время 00:00:00: `IMG-20250101-WA0001.jpg` / `VID-20250101-WA0001` (WhatsApp), `20250101.jpg`.

Финальная валидация каждого кандидата: strict-календарь (31 февраля, час 24+, месяц 13 — reject), окно 1990-01-01…сейчас+1 сутки (константа, легко изменить).

**Намеренно не парсим** (ложные срабатывания / неоднозначность): `dd-MM-yyyy` и `dd/MM/yyyy` (день/месяц неоднозначны); имена с месяцами словами (`Jan 2025`); числовые ID Instagram/Facebook (`received_10161672603575608` — 17 цифр, отсекается границами); цифровые прогоны длины 9, 11–12, 14+ как epoch; `DSC01234`, `GOPR1234` и прочие счётчики без даты.

### 5. `ExifUtil.kt` — новая функция записи

`suspend fun writeMissingDates(context, uri, dateSourceMs: Long, includeDateTimeOriginal: Boolean): Boolean`:
- `guardedExifWrite { ... }`: открыть `rw` дескриптор, `ExifInterface`, **свежая** перепроверка `checkDateAvailability` (под gate), выставить теги (`formatDateForExif(dateSourceMs)`: `DateTime` + `DateTimeDigitized` [+ `DateTimeOriginal` при `includeDateTimeOriginal`]), `saveAttributes()`.
- После успеха: восстановить mtime, инвалидировать кэши URI.

### 6. `domain/ExifDateFixUseCase.kt` (новый)

`@Inject constructor(executionGate)`; `suspend fun fixAll(): Outcome(scanned, fixed, noSource, failed)`:
- Один запрос `MediaStore.Images.EXTERNAL_CONTENT_URI` с `MediaItemSnapshot.PROJECTION`, `IS_PENDING=0` в selection.
- На каждый URI (в `try/finally` c gate):
  1. `executionGate.acquire()`;
  2. свежий `MediaItemSnapshot.query` — не существует/pending → skip;
  3. HEIC MIME → skip (в `noSource` не считать, отдельный счётчик не нужен — лог);
  4. `ExifUtil.getExifInterface` — один разбор: если `checkDateAvailability` → skip; `UserComment` → `CompressionMarker.parse`;
  5. дата: `FileNameDateParser.parseDateMs(displayName)` (source=FILENAME) → иначе `marker.timestamp > 0` (source=MARKER) → иначе `noSource++`;
  6. `ExifUtil.writeMissingDates(..., includeDateTimeOriginal = source == FILENAME)` → `fixed++` / `failed++`;
  7. `executionGate.release()`.
- Лог итога `LogUtil.processInfo`. Отдельный URI не валит пакет (per-file try/catch).

### 7. `worker/ExifDateFixWorker.kt` (новый) + событие

- `@HiltWorker`, inject `ExifDateFixUseCase`, `CompressionEvents`. `doWork()` → `fixAll()` → `compressionEvents.emit(Event.DateFixCompleted(outcome))` → `Result.success()` (retry при исключении верхнего уровня).
- Companion `schedule(context)`: unique one-time work `"exif_date_fix"`, `ExistingWorkPolicy.REPLACE` (повторный запуск после завершения; операция идемпотентна — файлы с датами пропускаются).
- `CompressionEvents.Event.DateFixCompleted(fixed, noSource, failed)` — новый вариант sealed interface.
- `MainActivity.observeViewModel`: ветка `DateFixCompleted` → Snackbar `date_fix_result` / `date_fix_nothing_done`.

### 8. `MainViewModel.kt`

`fun scheduleExifDateFix()` → `ExifDateFixWorker.schedule(context)` (applicationContext уже есть; WorkManager из context).

## Тесты (unit)

- `FileNameDateParserTest` (новый, чистый JUnit): полный корпус из секции 4 — позитивные (все 5 групп шаблонов, включая `mmexport…`, `Snapchat-…`, ISO-basic, `IMG-…-WA0001`, `_compressed`-суффикс, копия ` (1)`) и негативные (`DSC01234.jpg`, `GOPR1234.jpg`, `2025-13-01` (месяц), `20250230` (день), `19900101` (ниже границы), `12345678.jpg`, `received_10161672603575608` (17 цифр), `202501011234567` (15 цифр), `202501011234` (12 цифр), `01-02-2025` (неоднозначный формат)). Проверять и точное время (локальная таймзона), а для date-only — 00:00:00.
- Расширить Robolectric-тесты EXIF по образцу `SkipMarkerWriteTest`: `writeMissingDates` пишет 3 тега из имени-источника и 2 — из маркера; при существующей дате не пишет; после записи mtime восстановлен; кэш инвалидирован.
- `ExifDateFixUseCase`-тест по образцу `GalleryScanCoordinatorTest` (если применимо): happy path + файл без источников + HEIC skip.

## Валидация

1. `./gradlew assembleDebug`
2. `./gradlew testDebugUnitTest`
3. Обновить AGENTS.md навыком `agents-updater` (меню, use case, инварианты дат).
4. Опубликовать debug-APK навыком `apk` (изменён runtime-код `app/src/main/**`).

## Вне scope

- CLI-версия (соответствие Android не требуется для этого пункта меню).
- Обновление MediaStore `DATE_TAKEN` после записи EXIF.
- Foreground-уведомление/прогресс воркера (EXIF-проход быстрый; итог приходит Snackbar-событием).

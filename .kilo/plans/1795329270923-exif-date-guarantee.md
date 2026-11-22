# Гарантия EXIF-дат (`DateTime`+`DateTimeDigitized`): изменяемые файлы + мелкие файлы <100 КБ — Android + CLI

## Решения пользователя

1. Изменяемые файлы (сжатые копии, skip-маркеры, неудалённые оригиналы) — гарантия дат обязательна.
2. Достаточные теги: **`DateTime` + `DateTimeDigitized`**; `DateTimeOriginal` не выдумывается (только из имени файла в date-fix).
3. **Мелкие файлы <100 КБ** (фото из мессенджеров, «перемешивают галерею»):
   - новые — автоматически: хук в воркере на пропуске `ALREADY_SMALL`;
   - существующий бэклог — ручным «Восстановлением дат», расширенным **mtime-fallback** (отменяет прежнее «mtime не источник»; пользователь запускает один раз).
4. CLI: нижний порог скана `MIN_FILE_SIZE` 50 КБ → **0** (мелкие видны, даты дописываются; сжатие им по-прежнему запрещено `is_already_small`).
5. HEIC-мелкие файлы остаются без дат на обеих платформах: Android ExifInterface не сохраняет HEIF; CLI не реэнкодит HEIC. Зафиксировать в AGENTS.md как ограничение.

## Текущее состояние (проверено по коду)

Уже гарантировано: Android — сжатые копии (`addDigitizedDateFromFileMetadata` → `writeExifToArtifact`), skip-маркеры (`writeMarkerAndMissingDatesInPlace`), HEIC→JPEG; CLI — skip-маркеры JPEG/PNG (`_backfill_missing_dates`).

Пробелы (нумерация — для задач ниже):

| # | Пробел | Место |
|---|--------|-------|
| 1 | CLI: JPEG success-путь — нет бэкфилла дат в копию; при `source_exif=None` копия без EXIF | `compression.py:219-241` |
| 2 | CLI: PNG success-путь — EXIF отбрасывается целиком (теряются даже существующие даты) | `compression.py:242-244, 309-312` |
| 3 | CLI: HEIC→JPEG — даты бэкфиллятся от mtime уже записанного выхода (≈«сейчас»), а не исходника; маркер получает origSize = размер выхода | `compression.py:256`, `exif_handler.py:393` |
| 4 | Android: EXIF исходника нечитаем → пустая карта → artifact без дат | `CompressImageUseCase.kt:71-86` |
| 5 | Android: `writeExifToArtifact` = false в separate-режиме → копия публикуется без EXIF | `MediaStoreUtil.kt:437-444` |
| 6 | Новые мелкие файлы: observer/Job/Share/Picker размер не фильтруют, воркер видит `ALREADY_SMALL` и не трогает файл | `ImageCompressionWorker.kt:176-182` |
| 7 | Бэклог мелких: скан и выбор папки режут `<100 КБ` на уровне запроса, watermark ушёл — старые файлы никогда не попадут в воркер; ручной date-fix без mtime даёт `noSource` | `GalleryScanUtil.kt:100`, `MediaStoreUtil.kt:133`, `ExifDateFixUseCase.kt:149-161` |
| 8 | CLI: скан режет `<50 КБ`; ветка «Already small» не пишет даты | `constants.py:12`, `multiprocessing_utils.py:284-295` |

## Изменения

### CLI

1. **Захват даты исходника**: в `compress_image` в начале (до любых записей) — `source_mtime = os.path.getmtime(file_path)`.
2. **JPEG success-путь** (`compression.py`): при `preserve_exif`: `source_exif is None` → `{"0th": {}, "Exif": {}}`; `ExifHandler._backfill_missing_dates(exif_source, source_mtime)`; существующий try/except вокруг `piexif.dump` сохранить.
3. **PNG success-путь**: собрать exif-словарь как для JPEG, бэкфилл от `source_mtime`, сейв `img.save(..., exif=exif_bytes)` (eXIf-чанк, Pillow≥10 — уже используется в `_add_marker_to_png`). `metadata_preserved` для PNG = «EXIF записан»; неверный комментарий «PNG files don't support EXIF» убрать.
4. **HEIC-ветка**: `add_compression_marker(output_path, quality, source_exif, original_size=original_size, date_source_mtime=source_mtime)`.
5. **`ExifHandler.add_compression_marker`**: параметр `date_source_mtime: Optional[float] = None`; `file_mtime = date_source_mtime ?? os.path.getmtime(file_path)` во всех ветках бэкфилла. Существующие вызовы (skip-маркеры) не меняются.
6. **`ExifHandler.backfill_missing_dates(file_path) -> bool`** (новая): идемпотентная запись только дат, без маркера. HEIC → `False`. JPEG: `piexif.insert` поверх существующего EXIF; PNG: рефакторинг — выделить из `_add_marker_to_png` хелпер записи exif-чанка (`_write_exif_to_png`) и переиспользовать. Источник дат — mtime файла; при наличии любой даты — no-op.
7. **`process_single_file`** (`multiprocessing_utils.py`): в ветке «Already small» вызвать `backfill_missing_dates` (real-run только; `process_single_file_dry_run` ничего не пишет — как сейчас с маркерами).
8. **`constants.py`**: `MIN_FILE_SIZE = 0` (+ комментарий), обновить доку скан-фильтра.

### Android

9. **`ExifUtil.ensureMinimumDateTags(exifData, dateSourceMs)`**: чистая функция — если в карте нет ни одного из `DateTime`/`DateTimeOriginal`/`DateTimeDigitized`/`GPSDateStamp` и `dateSourceMs > 0`, дополнить `TAG_DATETIME` + `TAG_DATETIME_DIGITIZED` (критерий тот же, что `checkDateAvailability`).
10. **`CompressImageUseCase.invoke`**: применить п.9 к `exifDataMemory` до `saveCompressed`, источник — `snapshot?.dateModifiedMs?.takeIf { it > 0 } ?: UriUtil.getFileLastModified(...)`.
11. **`MediaStoreUtil.saveCompressedImageFromFileInternal`**: `!exifOk` → `SaveResult.Failed(SaveFailure.OTHER)` в **обоих** режимах. Публикация копии без EXIF = потеря GPS/дат; при Failed оригинал цел, файл будет повторно поставлен сканом.
12. **`ExifUtil.hasAnyDateTag(exifData: Map<String, Any>): Boolean`** — проверка 4 тегов дат по карте (для воркера).
13. **Хук воркера** (`ImageCompressionWorker`, ветка `ALREADY_HANDLED_REASONS`): при `reason == ALREADY_SMALL` и не-HEIC (`gatedSnapshot.mimeType`) и `!hasAnyDateTag(sourceExif?.data)` → `ExifUtil.writeMissingDates(context, uri, dateSourceMs = snapshot.dateModifiedMs ?: mtime, includeDateTimeOriginal = false)`. Best-effort: try/catch с логом, исход работы не меняется (SKIPPED/success). Воркер уже под gate и URI-lock, `writeMissingDates` перепроверяет даты на свежем дескрипторе, mtime восстанавливает, кэши инвалидирует.
14. **`ExifDateFixUseCase`**: третий по приоритету источник — mtime (`snapshot.dateModifiedMs`): имя файла (3 тега с `DateTimeOriginal`) → маркер (2 тега) → **mtime (2 тега)**. `noSource` остаётся только для нечитаемого EXIF. Обновить KDoc («mtime не источник» → «mtime — последний fallback»).

## Тесты

### CLI (`compressphotofast-cli/tests/`)

- `test_compression_dates.py` (новый, стиль `test_exif_handler_dates.py`, работает и без pytest):
  - JPEG без EXIF → separate-режим → копия: `DateTime`==`DateTimeDigitized`== mtime исходника, без маркера;
  - JPEG с EXIF (Make) без дат → Make сохранён + даты из mtime;
  - PNG с датами → даты сохраняются (сейчас теряются); PNG без дат → даты == mtime исходника;
  - HEIC (skip при недоступности pillow-heif) → даты == mtime исходника (не «сейчас»), `origSize` маркера == размер исходника;
  - mtime исходника не меняется.
- `test_small_files_dates.py` (новый): «Already small» JPEG/PNG без дат → skipped + даты записаны; с датами → файл не меняется (байты/mtime); HEIC → не тронут; dry-run → ничего не пишет.
- `test_scan_filters.py`: порог 0 — крошечный файл находится сканом.

### Android (по образцу существующих)

- `WriteMissingDatesTest` / новый `ExifUtilTest`: `ensureMinimumDateTags` (пустая карта → 2 тега; есть `DateTimeOriginal` → no-op; `dateSourceMs<=0` → no-op); `hasAnyDateTag`.
- `ExifDateFixUseCaseTest`: без имени/маркера → даты из mtime, 2 тега, без `DateTimeOriginal`; приоритеты источников сохраняются.
- `ImageCompressionWorkerTest`: `ALREADY_SMALL` + JPEG без дат → `writeMissingDates` вызван; с датами/HEIC → не вызван; исход — SKIPPED/success.
- `CompressImageUseCaseTest`: нечитаемый EXIF → карта для artifact содержит даты (п.9-10); при невозможности полного сценария — юнит-проверка `ensureMinimumDateTags`.

## Порядок выполнения

1. CLI (п.1-8) → `pytest compressphotofast-cli/tests/` (venv `compressphotofast-cli/venv`).
2. Android (п.9-14) → `./gradlew assembleDebug` → `./gradlew testDebugUnitTest`.
3. AGENTS.md навыком `agents-updater`: гарантия дат для изменяемых файлов; `ALREADY_SMALL`-хук; mtime-fallback в date-fix; CLI `MIN_FILE_SIZE=0`; HEIC-ограничение обеих платформ.
4. Опубликовать debug-APK навыком `apk` (изменён runtime-код `app/src/main/**`).

## Риски

- П.11: редкий случай, но поведение меняется с «копия без EXIF лучше ничего» на «без EXIF копия не публикуется» — согласовано с критичностью дат.
- П.13: первая встреча каждого мелкого файла без дат = одна guarded-запись (backup+fsync, <100 КБ) — идемпотентно, сходится; шторм observer-событий гасится перепроверкой дат и `addRecentlyProcessedUri`.
- П.14: ручной фикс с mtime пишет даты всем бездатым изображениям (не только мелким) — принято пользователем явно.
- П.8: объём скана CLI растёт (мелкие файлы получают EXIF при первом прогоне) — принято.
- HEIC-мелкие остаются без дат — задокументированное ограничение платформ.

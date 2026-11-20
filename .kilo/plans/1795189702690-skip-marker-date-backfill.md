# Маркер пропуска: подстановка дат в EXIF оригинала

## Контекст и цель

При неэффективном сжатии `writeSkipMarker` пишет в оригинал только `UserComment`-маркер (quality=99) — даты в EXIF не добавляются. Успешный путь добавляет `DateTime` + `DateTimeDigitized` из mtime файла, но только если в EXIF нет ни одной даты (`ExifUtil.addDigitizedDateFromFileMetadata` + `checkDateAvailability`), и только в сжатую копию.

**Цель** (решения пользователя): при записи маркера пропуска в оригинал дописывать те же даты `DateTime` + `DateTimeDigitized` (формат `yyyy:MM:dd HH:mm:ss`) из mtime файла — строго при полном отсутствии дат. Охват: Android + CLI.

- Изменение внутри `ExifUtil.writeSkipMarker` автоматически покрывает оба вызова: `markInefficient` (неэффективное сжатие) и `CompressedOriginalKept` (неудалённый оригинал) — это желаемое следствие.
- HEIC — без изменений (маркер переименованием в `_compressed`, EXIF не пишется).
- CLI: подстановки дат нет ни в одном пути; добавляем только в `add_compression_marker` (путь маркера). Паритет успешного пути CLI — вне охвата.

## Android: `app/src/main/java/com/compressphotofast/data/ExifUtil.kt`

1. `writeSkipMarker` (~строки 595–648):
   - Захватить `fileLastModifiedMs = UriUtil.getFileLastModified(context, uri)` один раз ДО записи (заменяет условный `originalLastModified`; восстановление mtime после записи — только при `!isReplaceMode`, как сейчас).
   - Вызов `writeUserCommentInPlace` внутри `guardedExifWrite` заменить на новый `writeMarkerAndMissingDatesInPlace(context, uri, marker, fileLastModifiedMs)` — одна запись `saveAttributes`, один backup, инвариант «одна запись size до записи (fstat)» сохраняется.
   - Дрейф: +2 тега ≈ 100 байт < `Constants.MARKER_SIZE_TOLERANCE_BYTES` (4 КБ); корректирующая `writeMarkerWithSize` не меняется (`saveAttributes` сохраняет уже записанные даты).
2. Новый internal `writeMarkerAndMissingDatesInPlace` (рядом с `writeUserCommentInPlace`, ~строка 969):
   - `openFileDescriptor(uri, "rw")` → `ExifInterface(fd)`;
   - если `!checkDateAvailability(exif)` и `dateSourceMs > 0` → `setAttribute(TAG_DATETIME, …)` и `setAttribute(TAG_DATETIME_DIGITIZED, …)` из `formatDateForExif(dateSourceMs)`;
   - `setAttribute(TAG_USER_COMMENT, comment)`; `saveAttributes()`.
   - `writeUserCommentInPlace` не трогать — он нужен корректирующей записи.
   - Решение о тегах вынести в чистую функцию (например `missingDateTags(exif, dateSourceMs): Map<String, String>?`) для тестирования на JVM.
   - Логи — на русском, в стиле соседних строк.

## Android-тесты: `app/src/test/java/com/compressphotofast/util/SkipMarkerWriteTest.kt`

- Перевести мок с `writeUserCommentInPlace` на `writeMarkerAndMissingDatesInPlace` (эмуляция по пути файла: маркер + даты при их отсутствии — семантика как в проде).
- Новые случаи:
  - файл без дат → после `writeSkipMarker` маркер + `DateTime` == `DateTimeDigitized` == форматированный mtime (фиксировать mtime через `File.setLastModified` до вызова);
  - файл с `DateTimeOriginal` → существующие даты не тронуты, новые не добавлены;
  - чистая функция: нет дат → 2 тега; есть любая из 4 дат → null.
- Существующие тесты (один backup, допуск размера, HEIC rename, откат при провале) — остаются зелёными.
- `CompressImageUseCaseTest`: сигнатура `writeSkipMarker` не меняется (4 аргумента) — моки `coEvery/coVerify` живы без правок.

## CLI: `compressphotofast-cli/src/exif_handler.py`

1. Хелпер `_has_any_date(exif_dict) -> bool`: `piexif.ImageIFD.DateTime` (0th), `piexif.ExifIFD.DateTimeOriginal`, `piexif.ExifIFD.DateTimeDigitized` (Exif), `piexif.GPSIFD.GPSDateStamp` (GPS) — зеркально `checkDateAvailability`.
2. `add_compression_marker` (~строки 187–275): в начале функции `mtime = os.path.getmtime(file_path)` (строго до `piexif.insert`); в обеих ветках (с `source_exif` и без) перед `piexif.dump`: если `not _has_any_date(exif_dict)` → добавить `exif_dict["0th"][piexif.ImageIFD.DateTime]` и `exif_dict["Exif"][piexif.ExifIFD.DateTimeDigitized]` = `datetime.fromtimestamp(mtime).strftime("%Y:%m:%d %H:%M:%S")`; гарантировать наличие словарей `"0th"`/`"Exif"`.

## CLI-тесты: `compressphotofast-cli/tests/test_exif_handler_dates.py` (новый, pytest)

- JPEG без дат → `add_compression_marker(path, 99)` → маркер + `DateTime` == `DateTimeDigitized` == форматированный mtime (фиксировать через `os.utime`).
- JPEG с `DateTimeOriginal` → даты не перезаписаны и не добавлены.
- Обе ветки: с `source_exif=None` и с `source_exif` без дат.

## Документация

- Обновить AGENTS.md навыком `agents-updater`: инвариант `writeSkipMarker` — «теги не переписываются, кроме добавления `DateTime`/`DateTimeDigitized` из mtime при полном отсутствии дат»; отметить подстановку в CLI `add_compression_marker`.

## Валидация

1. `./gradlew assembleDebug`
2. `./gradlew testDebugUnitTest`
3. CLI: `python3 -m pytest compressphotofast-cli/tests -q` (зависимости из `compressphotofast-cli/requirements.txt`)
4. После успешной сборки — опубликовать debug-APK навыком `apk` (изменён runtime-код `app/src/main/**`).
5. Instrumentation-тесты не требуются (нет изменений UI/сервисов/Android API).

## Риски и граничные случаи

- Дрейф размера маркера покрыт допуском 4 КБ; при сверхдрейфе корректирующая запись сохраняет даты.
- `ExifInterface` по FD недоступен в JVM-тестах → семантика дат проверяется мок-эмуляцией и чистой функцией; вручную на устройстве — любым EXIF-просмотрщиком.
- Не-replace режим: восстановление mtime после записи уже есть — даты в EXIF совпадут с восстановленным mtime.
- Провал записи → откат к оригиналу без маркера и без дат (`guardedExifWrite` без изменений).

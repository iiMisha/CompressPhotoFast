# План: эвристика «модификации после сжатия» только на основе размера файла

## Контекст и диагноз

**Проблема:** при сканировании галереи для изображений с EXIF-маркером `CompressPhotoFast_Compressed:quality:timestamp` выполняется тестовое сжатие и показывается уведомление о неэффективном пропуске (`notification_skipping_inefficient`).

**Корневая причина** — `ImageProcessingChecker.isProcessingRequired()` (строки 221–257): эвристика «mtime > timestamp маркера + 20 сек → повторная обработка». Timestamp маркера пишется в мс до записи файла (`ExifUtil.kt:831, 997`), а `DATE_MODIFIED` из MediaStore (секунды) отражает любое касание файла (копирование, перенос, восстановление, пересканирование) → ложные повторные обработки уже сжатых файлов → `handleInefficientSkip` (`ImageCompressionWorker.kt:509`) → уведомление.

## Принятые решения

1. **Единственный критерий модификации — размер файла.** Маркер расширяется до `CompressPhotoFast_Compressed:quality:timestamp:size`. Логика: маркер есть → текущий размер == size из маркера → `ALREADY_COMPRESSED` (skip без тестового сжатия); размер отличается → повторная обработка.
2. **mtime-эвристика удаляется полностью** (вместе с допуском 20 сек и вызовом `getFileLastModified` в этой проверке).
3. **Старые маркеры без size:** безоговорочный skip (доверяем маркеру). Миграция не нужна; новые маркеры пишутся только с size. Обоснование риска: редактирование фото обычно меняет размер и/или затирает EXIF `UserComment` (маркер теряется → файл обрабатывается как несжатый) — остаётся двойная защита.

## Изменения

### 1. `ExifUtil.kt` — формат маркера
- Все точки формирования маркера (строки ~831, ~997): `"$EXIF_COMPRESSION_MARKER:$quality:$timestamp:$size"`. Размер передавать параметром; при недоступности — `-1`.
- `getCompressionMarker()` (~1024): заменить `Triple` на data class `CompressionMarkerInfo(isCompressed, quality, timestamp, size: Long?)`. Парсинг: `parts.size >= 3` валиден (обратная совместимость), четвёртый элемент → `size` (null/нечисловый → null). Обновить call-sites: `ImageProcessingChecker`, `MediaStoreObserver`.
- HEIC-ветка (суффикс `_compressed`): size недоступен из имени → `size = null`; с новой логикой null-size = безоговорочный skip — семантика сохраняется.

### 2. `ImageProcessingChecker.kt` — только размер
- Удалить строки 221–257 (mtime-сравнение, допуск 20 сек) и получение `modificationTimestamp` (строка 199), если не используется иначе.
- Новая ветка `isCompressed`:
  - `markerSize != null && markerSize >= 0 && fileSize == markerSize` → `ALREADY_COMPRESSED`;
  - `markerSize == null` (старый формат / HEIC) → `ALREADY_COMPRESSED` (доверяем);
  - `fileSize != markerSize` → `processingRequired = true` (файл реально изменился).
- `ProcessingCheckResult`: добавить `compressedFileSize: Long?`; поле `fileModificationTimestamp` удалить, если не используется в других местах.
- `OptimizedCacheUtil.CachedExifData`: добавить поле `size`; ключ/валидация кэша EXIF строить на `uri + fileSize` вместо `modificationTimestamp` (стабильнее: копирование не инвалидирует кэш).

### 3. `ImageCompressionWorker.kt` — запись size
- `performCompression`: маркер пишется с размером фактически сохранённого файла (`compressedSize`, строка ~432).
- `handleInefficientSkip`: `writeExifDataFromMemory` с quality=99 и `sourceSize` — файл не пережимается, размер совпадёт при следующем сканировании → повторных уведомлений не будет.

### 4. Тесты
- Обновить/добавить unit-тесты (Robolectric):
  - парсинг маркера: новый формат с size, старый без size, некорректный size → null;
  - `isProcessingRequired`: маркер с size + размер совпадает → skip; размер отличается → processing; маркер без size → skip;
  - `handleInefficientSkip` пишет маркер с size = sourceSize;
  - при необходимости обновить тесты, завязанные на mtime-логику (поиск по `getFileLastModified` / `allowedTimeDifferenceSeconds`).
- `HeicInstrumentationTest`: проверки `contains("CompressPhotoFast_Compressed")` остаются валидными (префикс не меняется); проверить и при необходимости обновить точный формат строки.

## Логика процесса (после реализации)

1. Внести изменения (пункты 1–3).
2. Сборка: `./gradlew assembleDebug`.
3. Unit-тесты через навык `android-test-suite` (`./gradlew testDebugUnitTest`).
4. Обновить AGENTS.md навыком `agents-updater` (инварианты сжатия: формат маркера `marker:quality:timestamp:size`, эвристика модификации — только размер).
5. Опубликовать debug-APK навыком `apk` (изменён runtime-код `app/src/main/**`).

## Риски и граничные случаи
- **Модификация с сохранением точного размера в байтах:** будет пропущена. Вероятность ничтожна (пережатие меняет размер); большинство редакторов дополнительно затирают EXIF `UserComment`, теряя маркер.
- **Legacy-файлы, реально отредактированные до обновления:** со старым маркером без size пропускаются навсегда. Принято осознанно (доверять маркеру) ради удаления mtime-сложности; таких файлов мало, новый формат закрывает все последующие случаи.
- **MediaStore округление секунд:** больше не участвует — источник проблемы устранён, а не обойдён.
- **size = -1** (размер был недоступен при записи): трактовать как null → доверять маркеру (skip).

## Валидация сценария пользователя
После деплоя: повторное сканирование галереи не показывает `notification_skipping_inefficient` для файлов с маркером; в логе вместо тестового сжатия — `ALREADY_COMPRESSED`. Изменение размера файла (реальная модификация) корректно триггерит повторную обработку.

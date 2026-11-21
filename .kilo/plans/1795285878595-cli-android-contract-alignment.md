# План: устранение расхождений CLI с Android-контрактами

## Контекст

Аудит `compressphotofast-cli` выявил расхождения с Android-реализацией. По правилу AGENTS.md «Python-логика должна сохранять семантическое соответствие Android-реализации» — CLI выравнивается под Android.

Уточнение по HEIC (важно, меняет прежние выводы): Android **тоже** конвертирует HEIC → JPEG при сжатии, включая replace-режим (см. `FileOperationsUtil.kt:66` «.heic -> .jpg», `MediaStoreUtil` mimeType `image/jpeg`). Дивергенции в записи JPEG-контента в `.heic`-имя **нет** — чинить не надо. Речь ниже только о **skip-маркерах** для HEIC/PNG.

Решения пользователя:
- `--quality auto`: убрать перебор quality, использовать фиксированное значение (паритет с Android).
- Skip-маркеры HEIC/PNG: полный паритет с Android.

## Изменения (все в `compressphotofast-cli/src/`)

### 1. `constants.py`
- `COMPRESSION_QUALITY_HIGH`: 85 → **80** (паритет с `Constants.kt:64`).
- `DEFAULT_COMPRESSION_QUALITY`: 85 → **70** (= `COMPRESSION_QUALITY_MEDIUM`, как дефолт в Android `SettingsManager.getCompressionQuality`).

### 2. `compression.py` — убрать перебор quality
- Удалить `ImageCompressor.find_optimal_quality` (иQualities list, логику best_ratio).
- `cli.py`: `quality_map["auto"]` → `DEFAULT_COMPRESSION_QUALITY` (вместо `0`); убрать вызовы find_optimal_quality, если остались (проверить `multiprocessing_utils.py` — там `process_single_file`/`process_single_file_dry_run`).
- Проверить, не используется ли `quality == 0` как сигнал auto в `multiprocessing_utils.py` — заменить на фиксированное значение.

### 3. `exif_handler.py` + `compression.py` — HEIC skip-маркер через rename (паритет Android)
Контракт Android (`CompressionMarker.kt`): HEIC не получает EXIF-маркер; пропуск помечается переименованием `name.heic` → `name_compressed.heic`; распознаётся regex `_compressed\.(heic|heif)$` (ignore case).

- Новые чистые функции в `exif_handler.py` (аналог `CompressionMarker.hasHeicCompressedSuffix`):
  - `has_heic_compressed_suffix(file_name) -> bool` (regex `\_compressed\.(heic|heif)$`, IGNORECASE).
- В `compression.py`:
  - В `compress_image` при неэффективном сжатии HEIC-исходника (и вообще при попытке `add_compression_marker` на HEIC) — не вызывать piexif; вместо этого вызвать rename-маркер.
  - В `compress_image_safe` (replace-режим): при skip — после restore из `.bak` переименовать файл в `*_compressed.heic/.heif` (контракт Android: «rename, без backup», после restore backup удаляется как обычно).
  - Скан (`file_utils.find_image_files`): файлы `_compressed.heic/.heif` — пропускать как уже сжатые (проверка `should_recompress` дополнить: HEIC с суффиксом → skip). Сейчас `SUPPORTED_EXTENSIONS` их включает, и они будут пересжаты.
- `should_recompress`/`is_image_compressed`: для HEIC маркер из EXIF недоступен — первичный признак сжатости = суффикс имени (для `.heic/.heif`).

### 4. `exif_handler.py` — PNG skip-маркер через Pillow eXIf-чанк
- `add_compression_marker`: для `.png` не использовать `piexif.insert`. Вместо этого:
  - Прочитать PNG через `PIL.Image.open`, взять существующий `img.info.get("exif")` (если есть), собрать/дополнить EXIF-байты с UserComment-маркером (можно через `piexif.dump` в памяти — он работает с байтами, не с файлом), затем `img.save(path, exif=exif_bytes)` — Pillow пишет eXIf-чанк PNG.
  - Fallback: если не удалось — вернуть False и залогировать (сейчас молчание).
- Чтение маркера (`_read_marker_parts`): для PNG парсить `piexif.load` из байт `img.info.get("exif")` (piexif.load принимает bytes), не файл напрямую.

### 5. `file_utils.py` / скан — фильтры размера (паритет Android-скану)
- Применить `MIN_FILE_SIZE` (50 КБ) и `MAX_FILE_SIZE` (100 МБ) при скане в `find_image_files` — как Android фильтрует на этапе отбора файлов.
- Внимание: не конфликтует с `MIN_PROCESSABLE_FILE_SIZE` (100 КБ) — это порог обработки, остаётся.

### 6. Тесты (`compressphotofast-cli/tests/`)
- Обновить/добавить:
  - HEIC: rename-маркер ставится при skip; файл с `_compressed.heic` не сканируется/пропускается; `has_heic_compressed_suffix` кейсы.
  - PNG: маркер записывается и читается обратно (round-trip через Pillow).
  - `quality auto` → 70, `high` → 80.
  - Фильтры 50 КБ / 100 МБ при скане.
- Запуск: `pytest compressphotofast-cli/tests/` (venv: `compressphotofast-cli/venv`).

## Не делаем (вне скоупа)
- HEIC → JPEG конвертация при сжатии уже совпадает с Android.
- Двухфазная запись маркера с байтовым патчем (Android-специфика MediaStore) — CLI пишет маркер одной записью; формат совместим.
- Reliability-вопросы из первого ответа (fsync, TOCTOU) — отдельная задача, не контракт.

## Валидация
1. `pytest compressphotofast-cli/tests/` — все зелёные.
2. Ручной smoke: создать JPEG >100 КБ и HEIC/PNG-заглушки, прогнать `compressphotofast.sh` (dry-run и бой), проверить: JPEG-маркер читается; HEIC skip → rename; PNG skip → eXIf-чанк с маркером; повторный запуск ничего не пересжимает.
3. Кросс-проверка формата маркера: строка из CLI парсится по контракту `CompressionMarker.kt` (prefix, 5 полей, ширина 15/15, нули-заглушка).
4. Обновить AGENTS.md навыком `agents-updater` (убрать/уточнить формулировки про HEIC в CLI, зафиксировать quality-пресеты).

## Риски
- Rename HEIC меняет имя файла на диске — пользователи CLI должны быть предупреждены в README (короткая правка README.md).
- Существующие PNG/HEIC, ранее обработанные CLI без маркера, после фикса получат маркер при следующем пропуске — это желаемое поведение.

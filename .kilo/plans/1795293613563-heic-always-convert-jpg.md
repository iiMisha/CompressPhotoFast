# HEIC: всегда конвертировать в JPEG (убрать недетерминизм convert/rename)

## Контекст и первопричина

Симптом: один и тот же HEIC при одних и тех же настройках в одном сеансе конвертируется в
`_compressed.jpg`, в другом — остаётся HEIC с именем `*_compressed.heic` (EXIF при этом
сохраняются — часть приложений просто не читает EXIC из HEIC; это не баг).

Причина: `CompressImageUseCase.invoke` (app/src/main/java/com/compressphotofast/domain/CompressImageUseCase.kt:118)
ветвится по `testResult.isEfficient()` — `isImageProcessingEfficient`
(app/src/main/java/com/compressphotofast/domain/ImageCompressionUtil.kt:442) требует ≥30%
экономии и ≥10 КБ. HEIC камеры уже хорошо сжат, вердикт пограничный и «плавает» (jitter
размера JPEG, motion-video attach/strip, время обработки). Ветка «неэффективно» →
`markInefficient` → `ExifUtil.writeSkipMarker` → для HEIC это переименование оригинала в
`_compressed.heic` (ExifUtil.kt:606-609, `markHeicFileAsCompressed`) без какой-либо конвертации.

## Решённые решения (подтверждены пользователем)

1. **HEIC ≥ 100 КБ всегда конвертируется в JPEG** (авто и ручное сжатие), даже если JPEG
   получится больше оригинала. Правило 30%/10 КБ для HEIC не применяется (для JPEG/PNG/WebP —
   без изменений).
2. **Бэклог не трогаем**: существующие `*_compressed.heic` остаются пропускаемыми
   (HEIC-маркер в имени = «обработан»). Пользователь при желании убирает суффикс вручную.
3. **CLI обновляется синхронно** (compressphotofast-cli): тот же контракт «HEIC всегда → JPEG».
4. Переименование `_compressed.heic` как маркер сохраняется только для пути
   `CompressedOriginalKept` (не удалось удалить оригинал в replace-режиме) и для бэклога.

## Задачи — Android

1. **`CompressImageUseCase.invoke`**: определить MIME исходника один раз
   (`snapshot?.mimeType ?: UriUtil.getMimeType(context, imageUri)`); если HEIC/HEIF —
   всегда идти в `saveCompressed` (минуя `isEfficient()`); иначе — прежняя ветка.
   `markInefficient` становится недостижимым для HEIC.
2. **Отображение роста размера**: при `compressedSize > originalSize` (теперь возможно для
   HEIC) `sizeReduction` отрицательный — в `NotificationUtil.showCompressionResultNotification`
   и батч-итогах (`CompressionBatchTracker`) показать нейтральный текст (например, «Конвертирован,
   размер вырос на N%»), не «−N%». `StatsTracker` уже безопасен (`savedBytes.coerceAtLeast(0)`).
3. Без изменений: `writeSkipMarker`/`markHeicFileAsCompressed` (маркер для
   CompressedOriginalKept), чекер (HEIC < 100 КБ — по-прежнему ALREADY_SMALL; HEIC-маркер в
   имени — по-прежнему ALREADY_COMPRESSED), EXIF-перенос и `pixelsOriented`
   (Orientation=NORMAL), replace-режим (имя копии `photo.heic` → `photo.jpg`, коллизии имён —
   существующая логика уникальных имён/isSameMediaItem), deleteOriginal.

## Задачи — CLI (compressphotofast-cli)

4. Изучить текущее поведение (`compression.py`, `exif_handler.py`, `file_utils.py`,
   `constants.py`): сейчас HEIC никогда не сжимается, только rename-маркер
   (`mark_heic_file_as_compressed`), скан исключает `has_heic_compressed_suffix`.
5. Новый контракт, зеркально Android: HEIC ≥ 100 КБ декодируется через pillow-heif и
   конвертируется в JPEG всегда (без проверки 30%/10 КБ для HEIC); EXIF исходника переносится
   в JPEG (piexif), маркер сжатия — стандартный UserComment, `_backfill_missing_dates`
   сохраняется; rename `_compressed.heic` остаётся только как backlog-маркер (новые файлы
   через rename не помечаются). Поведение JPEG/PNG/WebP не меняется.
6. Обновить/добавить pytest-тесты (`compressphotofast-cli/tests/`): HEIC → JPEG всегда
   (включая случай, когда JPEG больше оригинала), EXIF-перенос, маркер, скан по-прежнему
   исключает `_compressed.heic`.

## Тесты — Android

7. `CompressImageUseCaseTest`: новый тест — HEIC, неэффективное тестовое сжатие → всё равно
   `saveCompressed`/`Outcome.Compressed`; существующие тесты `SkippedInefficient` (строки 115,
   183) — проверить, что они про JPEG, и не сломались. `SkipMarkerWriteTest`,
   `ExifUtilHeicTest` — без изменений (writeSkipMarker HEIC-путь остаётся).

## Документация и сборка

8. Обновить AGENTS.md навыком `agents-updater`: инвариант сжатия получает исключение
   «HEIC ≥ 100 КБ всегда конвертируется в JPEG независимо от экономии 30%/10 КБ; rename-маркер
   `_compressed.heic` — только для неудалённого оригинала и бэклога»; CLI-контракты — синхронно.
9. Сборка/проверка: `./gradlew assembleDebug`, `./gradlew testDebugUnitTest`,
   `pytest compressphotofast-cli/tests/` (venv `compressphotofast-cli/venv`).
10. После успешной сборки опубликовать debug-APK навыком `apk` (изменён runtime-код).

## Риски и edge-кейсы

- Replace-режим: JPEG может быть больше удалённого HEIC — рост занимаемого места; принято
  пользователем осознанно.
- `photo.heic` → `photo.jpg` рядом с парным `photo.jpg` (Samsung HEIC+JPEG): срабатывает
  существующая логика уникальных имён (не перезапись чужого файла, `isSameMediaItem`).
- Прочие режимы (separate): копия `photo_compressed.jpg` в папке приложения, оригинал не
  трогается; повтор отсекается поиском имени (уже работает).

## Вне рамок

- Миграция/переобработка существующих `*_compressed.heic` (бэклог остаётся как есть).
- UI-настройка вкл/выкл конвертации HEIC.
- Instrumentation-тесты (Android API не меняется; только unit-тесты).

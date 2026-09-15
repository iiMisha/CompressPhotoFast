# Анализ устойчивости к потере данных: Android (только Android-версия)

Версия: 2.2.10. Проанализированы: `MediaStoreUtil.kt`, `ExifUtil.kt`, `BackupRegistry.kt`, `BackupRecoveryHelper.kt`, `ImageCompressionWorker.kt`, `ImageCompressionUtil.kt`, `FileOperationsUtil.kt`, `CompressPhotoApp.kt`, `TempFilesCleaner.kt`. CLI вне объёма.

## Общий вердикт

Android-сторона защищена заметно лучше среднего: in-place перезапись вместо delete+create, backup с персистентным реестром и восстановлением при старте приложения, fsync, верификация целостности до снятия IS_PENDING, удаление оригинала только после подтверждённого сохранения. Оставшиеся окна уязвимости узкие, но реальные; главное — перезапись может выполняться без backup, если backup не создался.

## Сильные стороны

1. **In-place перезапись в replace-режиме** — `createMediaStoreEntryV2` возвращает существующий URI, перезапись через ParcelFileDescriptor `"rwt"` (`MediaStoreUtil.safeUpdateExistingFile`, app/src/main/java/com/compressphotofast/util/MediaStoreUtil.kt:684). Нет окна «оригинал удалён — новый ещё не создан», URI и дата файла сохраняются.
2. **Backup + персистентный реестр** — перед `rwt`-перезаписью создаётся `replace_backup_*` в cacheDir и регистрируется в `BackupRegistry` (SharedPreferences) ДО рискованной операции (MediaStoreUtil.kt:492–506). Аналогично для `saveAttributes()` в ExifUtil.kt:827–845.
3. **Восстановление после crash** — `BackupRecoveryHelper.recoverPendingBackups` при старте приложения (CompressPhotoApp.kt:118): проверяет целостность URI, восстанавливает файл из orphan backup; stale записи и orphan-файлы чистятся (`TempFilesCleaner`, `cleanupStalePendingEntries`).
4. **fsync** — `pfd.fileDescriptor.sync()` после перезаписи и после restore (durability при power loss / forced reboot).
5. **Верификация до публикации** — `verifyImageIntegrity` (decodeStream inJustDecodeBounds) до снятия IS_PENDING; повреждённый файл удаляется и не попадает в галерею (MediaStoreUtil.kt:570–584). JPEG-artifact дополнительно проверяется по SOI/EOI + decode (`ImageCompressionUtil.validateJpegArtifact`).
6. **Порядок «сначала сохрани, потом удали»** — оригинал удаляется только после сохранения И верификации нового файла (ImageCompressionWorker.kt:437–456); при неудачном удалении в оригинал пишется EXIF-маркер, возвращается success (нет повторной обработки, нет потери).
7. **Защита от конкурентной записи** — per-path Mutex (MediaStoreUtil.kt:33–54), `CompressionExecutionGate`, `UriProcessingTracker`.
8. **Дисковый spool** сжатого потока (`stream_cache_*`) позволяет fallback-повтор записи без повторного сжатия (MediaStoreUtil.kt:466–475).

## Окна уязвимости (по убыванию риска)

1. **Перезапись без backup при неудачном создании backup.** В replace-режиме, если `replace_backup_*` не создался (disk full, I/O error), код только логирует warning и ВСЁ РАВНО выполняет `safeUpdateExistingFile` (MediaStoreUtil.kt:507–519). Если запись оборвётся посередине (kill, I/O error) — оригинал усечён/потерян без возможности восстановления. Fallback создаст новый файл из сжатой копии, но несжатые байты оригинала потеряны.
2. **ExifUtil: saveAttributes() без страховки.** «Backup не создан — выполняем saveAttributes() без страховки» (ExifUtil.kt:848–851) — то же осознанно принятое окно повреждения файла.
3. **Backup-файлы в cacheDir.** Система может очистить cacheDir под дисковым давлением, пока pending-запись в реестре актуальна → recovery удалит запись, а файл уже повреждён — данные потеряны. Вероятность низкая, но семантика «оригинал дороже cacheDir» не соблюдается.
4. **sync() failure не трактуется как ошибка записи** — только warning (MediaStoreUtil.kt:698–702, ExifUtil.kt:955). При последующем power loss данные могут не дойти до носителя.
5. **Legacy fallback `createMediaStoreEntry` с delete-then-insert** (MediaStoreUtil.kt:275–282): существующий файл удаляется ДО insert; если insert упадёт — окно потери. Сейчас путь используется как `_fallback` (коллизия имён маловероятна), но окно существует.
6. **Верификация проверяет только заголовки** (inJustDecodeBounds) — усечение середины файла пройдёт проверку. SOI/EOI-проверка есть только для artifact до записи, не после.
7. **BackupRegistry перезаписывается по ключу `uriString`**: `replace_backup` и последующий `exif_backup` для того же URI затирают запись друг друга. При crash во время EXIF-фазы restore возьмёт exif-копию (пост-сжатый файл) — не «вернуть оригинал», но не потеря данных.

## Рекомендуемые доработки (приоритетный порядок)

1. **A1 (высокий).** `saveCompressedImageFromStreamInternal`: если `replaceBackupCreated == false` — не выполнять `rwt`-перезапись; идти в fallback-путь создания нового файла (data-safe: оригинал не трогаем). Аналогично в ExifUtil.kt:848 — отказ от `saveAttributes()` без backup (пропустить запись маркера/EXIF, файл не портится).
2. **A2 (средний).** Провал `sync()` считать ошибкой записи: `safeUpdateExistingFile` возвращает false → сработает существующий restore-из-backup.
3. **A3 (средний).** Перенести backup-файлы из `cacheDir` в `filesDir/no_backup` (защита от очистки системой); обновить `TempFilesCleaner` (префиксы `exif_backup_` / `replace_backup_`) и пути создания backup в MediaStoreUtil/ExifUtil.
4. **A4 (низкий).** Убрать delete-then-insert в legacy `createMediaStoreEntry` (сначала insert нового, затем delete старого).
5. **A5 (низкий, опционально).** Полная верификация (decode целиком) для replace-режима, где цена ошибки максимальна.

## Валидация доработок

- Unit-тесты (Robolectric/MockK): backup-создание падает → `rwt`-перезапись не выполняется, создаётся fallback-файл, оригинал не тронут (A1); `sync()` бросает исключение → `safeUpdateExistingFile == false` и restore из backup (A2); backup создаётся в новом каталоге и чистится (A3).
- Прогнать `./gradlew testDebugUnitTest` через навык android-test-suite; сборка `./gradlew assembleDebug`.

## Out of scope

- CLI-версия (по решению пользователя).
- Защита от изменений файлов пользователем/сторонними приложениями между операциями.
- Двойное хранение оригиналов (cost > benefit для утилиты сжатия).

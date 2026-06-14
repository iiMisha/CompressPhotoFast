# План: Анализ и очистка Android-версии от мёртвого и дублирующегося кода

**Проект:** CompressPhotoFast · **Слой:** Android (Kotlin) · **Дата:** 2026-06-14

## Контекст и методология

Ализ проведён комбинированно:
1. Автоматические скрипты скилла `code-analyzer` (`find_unused.py`, `find_duplicates.py`) по `app/src/main/java`.
2. Глубокий ручной анализ + делегирование explore-агенту с верификацией каждого «0 использований» через полнотекстовый `grep` по всему `app/src` (main + test + androidTest).

**Ложные срабатывания скрипта `find_unused.py`** (он пропускает ALL-CAPS идентификаторы):
- `import java.util.UUID` в `MainViewModel.kt` — ИСПОЛЬЗУЕТСЯ (`mutableMapOf<UUID, ...>`).
- `import com.compressphotofast.R` в `MainActivity/PermissionsManager/NotificationUtil/ImageCompressionWorker` — ИСПОЛЬЗУЕТСЯ (`R.string.*`).
- Единственный реально мёртвый R-импорт — в `CompressPhotoApp.kt`.

---

## Логика процесса выполнения

```mermaid
flowchart LR
    A[Фаза 1: Удаление<br/>мёртвого кода] --> B[Фаза 2: Ручная проверка<br/>пограничных случаев]
    B --> C[Фаза 3: Рефакторинг<br/>дубликатов]
    C --> D[Фаза 4: Сборка + тесты]
    D --> E[Фаза 5: AGENTS.md<br/>обновление]
```

- Каждый этап заканчивается компиляцией `./gradlew assembleDebug`.
- Удаление идёт от безопасного к рискованному: сначала чистый dead code, затем test-only (`@VisibleForTesting`), затем рефакторинг дубликатов.
- Рефакторинг дубликатов (Фаза 3) выносить только после согласования с пользователем — это поведенческие изменения.

---

## Фаза 1 — Удаление подтверждённого мёртвого кода (безопасно, высокая уверенность)

### 1.1 Мёртвые публичные функции object/классов (main=0, test=0)

| # | Файл:строка | Имя | Действие |
|---|---|---|---|
| 1 | `util/LogUtil.kt:64` | `fileOperation(uri, operation, details)` | удалить |
| 2 | `util/ExifUtil.kt:1179` | `getCompressionMarkerSuspend(...)` | удалить (тривиальный делегат) |
| 3 | `util/ExifUtil.kt:1354` | `clearExifCaches()` | удалить |
| 4 | `util/MediaStoreUtil.kt:474` | `saveCompressedImageToGallery(...)` | удалить |
| 5 | `util/MediaStoreUtil.kt:820` | `checkFileNameConflictsBatch(...)` | удалить |
| 6 | `util/ImageCompressionUtil.kt:541` | `compressStream(...)` | удалить |
| 7 | `util/ImageCompressionUtil.kt:725` | `compressToTempFile(...)` | удалить |
| 8 | `util/ImageCompressionUtil.kt:826` | `createMediaStoreEntry(...)` | удалить (обёртка над `MediaStoreUtil`) |
| 9 | `util/ImageCompressionUtil.kt:850` | `copyExifData(...)` | удалить (живой аналог в `ExifUtil.copyExifData`) |
| 10 | `util/FileOperationsUtil.kt:260` | `hasEnoughDiskSpace(...)` | удалить |

### 1.2 Мёртвые приватные функции-зомби

| # | Файл:строка | Имя | Действие |
|---|---|---|---|
| 11 | `util/ImageProcessingChecker.kt:325` | `private fun isInAppDirectory(path)` | удалить (замена: `isInAppDirectoryNormalized`) |
| 12 | `util/ImageProcessingChecker.kt:336` | `private fun isMessengerImage(path)` | удалить (замена: `OptimizedCacheUtil`) |
| 13 | `util/BatchMediaStoreUtil.kt:469` | `private fun Cursor.getLongOrNull(index)` | удалить |
| 14 | `util/BatchMediaStoreUtil.kt:484` | `private fun Cursor.getStringOrNull(index)` | удалить |

### 1.3 Мёртвый приватный property

| # | Файл:строка | Имя | Действие |
|---|---|---|---|
| 15 | `util/ImageProcessingChecker.kt:30` | `private val TAG` | удалить (логи используют хардкод-строки) |

### 1.4 Мёртвые константы в `Constants.kt`

| # | Строка | Имя | Действие |
|---|---|---|---|
| 16 | `:46` | `NOTIFICATION_GROUP_COMPRESSION` | удалить |
| 17 | `:48` | `NOTIFICATION_ID_COMPRESSION_INDIVIDUAL_BASE` | удалить |
| 18 | `:91` | `EXTRA_PROGRESS` | удалить |
| 19 | `:92` | `EXTRA_TOTAL` | удалить |

### 1.5 Мёртвые импорты

| # | Файл | Импорт | Действие |
|---|---|---|---|
| 20 | `CompressPhotoApp.kt` | `com.compressphotofast.R` | удалить |
| 21 | `ImageProcessingChecker.kt` | 11 импортов (строки 5–7, 11–18) | удалить — следы удалённой логики дат |

Список 11 импортов в `ImageProcessingChecker.kt`: `MediaStore`, `DocumentsContract`, `DocumentsContract.Document`, `Instant`, `LocalDateTime`, `ZoneId`, `ZoneOffset`, `DateTimeFormatter`, `DateTimeParseException`, `Date`, `DocumentFile`.

### 1.6 Мёртвый deprecated-метод

| # | Файл:строка | Имя | Действие |
|---|---|---|---|
| 22 | `util/CompressionBatchTracker.kt:68` | `getInstance(context)` (@Deprecated, 0 вызовов) | удалить |

---

## Фаза 2 — Ручная проверка пограничных случаев (перед изменением)

| # | Файл | Что проверить | Действие по результату |
|---|---|---|---|
| 2.1 | `util/MediaStoreObserver.kt:31` | Поле `optimizedCacheUtil: OptimizedCacheUtil` — используется ли в теле класса? Если нет — мёртвый параметр конструктора | удалить параметр или пометить unused |
| 2.2 | `util/OptimizedCacheUtil.kt:219` | `getCachedExifData` — подтвердить использование | удалить/оставить |
| 2.3 | `util/UriUtil.kt:678` | `queryMediaStoreWithIdFallbackApi30` — сделать `private` (внешних вызовов нет) | сузить видимость |

---

## Фаза 3 — Удаление test-only кода ВМЕСТЕ с зависящими тестами

> Решение пользователя: удалять. Для каждого метода — удалить объявление в main + удалить/почистить зависящие тест-методы.

| # | Файл:строка (main) | Имя | Зависящие тесты |
|---|---|---|---|
| T1 | `ExifUtil.kt:1039` + `:1115` | `getCompressionInfo` + `isImageCompressed` | `ExifUtilInstrumentedTest` (test08, test25, ссылки в ~6 методах) |
| T2 | `PerformanceMonitor.kt:149` | `recordLegacyProcessing()` | `PerformanceMonitorTest` |
| T3 | `PerformanceMonitor.kt:270` | `resetStats()` | `PerformanceMonitorTest` (3) |
| T4 | `PerformanceMonitor.kt:292` | `calculateOptimizationSavings()` | `PerformanceMonitorTest` (2) |
| T5 | `LogUtil.kt:55` | `log(tag, message)` | `LogUtilTest` |
| T6 | `LogUtil.kt:238,249,266,278` | `batchStart/Progress/Complete/Error` (×4) | `LogUtilTest` |
| T7 | `CompressionBatchTracker.kt:81,99,117,126,135` | 5 deprecated `*Compat` методов | `CompressionBatchTrackerTest` (множество) |

⚠️ T7: перед удалением проверить, что Hilt-инстанс `CompressionBatchTracker` покрывает всю логику Compat-методов (т.е. тесты можно переписать на инстанс, а не просто удалить). Если переписать дорого — удалить тесты вместе с методами.

---

## Фаза 4 — Рефакторинг дубликатов (поведенческие изменения, по согласованию)

> Приоритет — от самого безопасного к рискованному. Каждый пункт = отдельный коммит после сборки + тестов.

### C1/C11. Объединить `verifyImageIntegrity`
- `MediaStoreUtil.kt:708` (suspend) и `ExifUtil.kt:1360` (не suspend) — ~95% совпадения.
- → вынести в `object ImageIntegrityUtil.verify(context, uri): Boolean` (или в `ImageCompressionUtil`).

### C2. Унифицировать HEIC-детекцию
- `ImageCompressionUtil.kt:102 isHeicFormat` и `ExifUtil.kt:174 isHeicFile` — ~100% core-логика.
- → вынести `ImageFormatUtil.isHeic(mimeType)`.

### C3. Делегировать `isProcessableMimeType`
- `ImageProcessingChecker.kt:314` (без кэша) и `OptimizedCacheUtil.kt:266` (с кэшем) — ~85%.
- → `ImageProcessingChecker` делегирует в `OptimizedCacheUtil`; локальную приватную удалить.

### C4. Централизовать логику app-директория/messenger
- 3 пересекающиеся реализации + дубликат паттернов мессенджеров.
- → единый источник в `OptimizedCacheUtil`, удалить мёртвые методы (см. 1.2).

### C5/C6. Дедуплицировать парсинг EXIF-маркера в `ExifUtil`
- `getCompressionInfo` (`:1039`) и `getCompressionMarker` (`:1128`) — ~95%; повторяющийся блок `split(":")` парсинга (`:1056`, `:1156`).
- → вынести `private fun parseCompressionMarker(userComment): Triple<Boolean,Int,Long>`; оставить одну публичную.

### C8. `buildPathVariants` в MediaStoreUtil
- 100% копи-паст блока `pathWithoutSlash/pathWithSlash` в 8 местах (`:124,132,168,176,839,849,910,927`).
- → `private fun buildPathVariants(relativePath): Pair<String,String>`.

### C7. Helper для `DATE_MODIFIED * 1000`
- 5 мест (`BatchMediaStoreUtil:217,331`, `ExifUtil:203`, `UriUtil:594,612`).
- → расширение/метод в `MediaStoreDateUtil`.

### C9. Generic-обёртки для ContentResolver-запросов (крупный рефакторинг)
- ~20 экземпляров паттерна `contentResolver.query()?.use { getColumnIndexOrThrow; moveToNext; LogUtil.error }` (`UriUtil` — 10, `MediaStoreUtil` — 5, и др.).
- → `inline fun <T> ContentResolver.queryFirst(...)` и `queryAll(...)`.
- ⚠️ Высокий риск — вынести отдельной задачей/PR.

### C10. `runCatchingCancellable` обёртка
- Массовый паттерн `try { } catch (e) { if (e is CancellationException) throw e; LogUtil.error(...) }`.
- → `suspend fun <T> runCatchingCancellable(tag, block): T?`.
- ⚠️ Высокий риск — отдельная задача.

---

## Фаза 5 — Проверка и фиксация

1. `./gradlew assembleDebug` после каждой фазы.
2. Unit-тесты через скилл `android-test-suite`: `./gradlew testDebugUnitTest`.
3. (опционально, перед PR) Instrumentation-тесты: `./scripts/run_instrumentation_tests.sh`.
4. Обновить `AGENTS.md` через скилл `agents-updater` (зафиксировать метрики: удалено строк, кол-во функций).

---

## Открытые вопросы к пользователю

1. ~~Глубина работ~~ — **Решено:** Фаза 1 + дубликаты C1–C8 (крупные C9/C10 — отдельные задачи).
2. ~~Test-only код~~ — **Решено:** удалять вместе с зависящими тестами.
3. **T7 (deprecated Compat-методы):** переписать тесты `CompressionBatchTrackerTest` на Hilt-инстанс или удалить тесты вместе с методами?
4. **Deprecated `UriProcessingTracker.getInstance` и `getOrCreateAutoBatchCompat`** (активно в production): проводить миграцию `ImageProcessingUtil`/object-классов на Hilt-инстанс в этом цикле или оставить TODO?

---

## Ожидаемый результат

- **Фаза 1:** удалено ~22 единицы мёртвого кода + 12 мёртвых импортов + 4 константы.
- **Фаза 3:** удалён test-only код + почищены зависящие тесты (T1–T7).
- **Фаза 4:** устранено 8 категорий дубликатов (C1–C8); C9/C10 вынесены в отдельные задачи.
- Сборка зелёная, unit-тесты проходят.
- AGENTS.md обновлён через `agents-updater`.

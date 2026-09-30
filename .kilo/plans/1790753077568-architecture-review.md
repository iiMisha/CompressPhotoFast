# Архитектурный обзор CompressPhotoFast (Android)

Дата: 2026-09-30. Объём: 46 Kotlin-файлов (~11 430 строк, `app/src/main`), 40 unit-тестов, 16 instrumentation-тестов, CLI на Python. Все находки проверены по коду с точными ссылками.

## Вердикт

Архитектура **в целом грамотная** (7/10). Слои чистые, конвейер сжатия продуман исключитель­но хорошо для мобильного стандартa (backup/rollback, TOCTOU-защиты, идемпотентность через маркер, сериализация тяжёлой фазы, bounded retry). Главные слабости: **реализовавшийся дрейф инвариантов Android↔CLI↔AGENTS.md**, два пакетных цикла, статический `object`-слой данных, блокирующий JVM-тестирование, и god-object `ExifUtil` (1320 строк).

## Что сделано грамотно

- **Слои**: 0 прямых нарушений — `data/`/`util/` не импортируют `domain/`, `ui` не импортируется никем; `util` — настоящий лист.
- **Отказоустойчивость конвейера** (сильнейшая сторона): backup перед любой перезаписью (`BackupRegistry` + fsync + сверка длины), rollback с сохранением backup до recovery при провале, TOCTOU-проверки размера/идентичности (`MediaStoreUtil.kt:359`, `isSameMediaItem`), «файл пользователя никогда не удаляется при сбое» выдержано по всему пути.
- **Идемпотентность**: EXIF-маркер с фиксированной шириной полей, допуск 4 КБ, per-URI unique WorkManager works через SHA-256 — проблемный URI не блокирует соседние.
- **Конкурентность**: нет `runBlocking`/`GlobalScope`; `CompressionExecutionGate` — минимальный `Mutex`; синглтон-корутины в `@ApplicationScope`; watermark скана продвигается только при полном успехе и durable enqueue (`GalleryScanCoordinator.kt:40-47`).
- **Соответствие AGENTS.md по DI**: `SettingsManager.getInstance` — только в `object`/companion (7 мест, все легальны); граф ацикличен на уровне бинов.
- **Worker — тонкий адаптер** (~последовательность lock → gate → foreground → use case → outcome), как задокументировано.

## Проблемы (по убыванию критичности)

### P0 — реализовавшийся дрейф инвариантов сжатия

| Инвариант | Android | CLI | AGENTS.md |
|---|---|---|---|
| Мин. размер файла | `MIN_FILE_SIZE=50КБ` (ручной путь, `FileOperationsUtil.kt:252`) **и** `OPTIMUM_FILE_SIZE=102.4КБ` (авто, `ImageProcessingChecker.kt:259`); фильтр скана — 50 КБ (`GalleryScanUtil.kt:105`) | 100 КБ | «не меньше 100 КБ» |
| Пресет HIGH | 80 (`Constants.kt:63`) | 85 (`constants.py:5`) | — |
| Мин. экономия в байтах | 10 КБ — magic number inline (`ImageCompressionUtil.kt:516`) | константа `MIN_BYTES_SAVING` | «30% и 10 КБ» |

Ручное сжатие файла 60 КБ на Android пройдёт и создаст файл ≤100 КБ, который CLI и авто-путь считают «уже оптимизированным». Это не стиль, а рассинхрон поведения трёх компонентов.

### P1 — циклы зависимостей пакетов (2)

1. `platform ↔ service`: `platform/NotificationUtil.kt:14,442` → `BackgroundMonitoringService` (Intent stop-action), а сервис зовёт `NotificationUtil` (`BackgroundMonitoringService.kt:143,159,278`).
2. `domain ↔ worker`: `domain/CompressionWorkScheduler.kt:13-14` строит `WorkRequest` для обоих воркеров, а `worker/ImageSettleWorker.kt:17,23` инжектит сам `CompressionWorkScheduler`.

Ломают заявленную в AGENTS.md ацикличность «`domain/` без ссылок на `worker/`». Штатное лечение: интерфейс (например, `CompressionWorkerContract` в domain, реализация в worker) либо перенос build-функций WorkRequest в `worker/`.

### P1 — статический `object`-слой данных = нетестируемость use case

`CompressImageUseCase` зовёт `ExifUtil`, `MediaStoreUtil`, `FileOperationsUtil`, `ImageCompressionUtil`, `UriUtil`, `StatsTracker` как статические объекты (`CompressImageUseCase.kt:63,92,140-152,181,212,217,280`). Бизнес-правила (пороги эффективности, качество 99, отложенное удаление) проверяются только через Robolectric/instrumentation — единственный `domain/` unit-тест подтверждает. Случаи-«гибриды»: `UriProcessingTracker.init { fallbackInstance = this }` (`UriProcessingTracker.kt:23-25`) — каждый Hilt-инстанс перепубликует себя в статическое поле.

### P1 — `SettingsManager.getInstance` в горячем пути

`FileOperationsUtil.isSaveModeReplace(context)` пересоздаёт accessor **на каждый вызов** (`FileOperationsUtil.kt:23-30`) и вызывается минимум из 6 точек конвейера (`CompressImageUseCase.kt:140`, `ImageProcessingChecker.kt:198`, `MediaStoreUtil.kt:306,373,430`, `ExifUtil.kt:715`). Формально легально (object), но это deprecated-паттерн в самом нагруженном ветвлении.

### P2 — god objects и длинные функции

- `data/ExifUtil.kt` — 1320 строк: кэш + копирование тегов + GPS-диагностика разрешений + HEIC-rename + двухфазная запись маркера + backup-оркестрация. `applyExifFromMemory` ≈ 200 строк, вложенность 6+ (`ExifUtil.kt:695-894`).
- `MediaStoreUtil` 797, `NotificationUtil` 645, `MainActivity` 602, `UriUtil` 569, `ImageCompressionUtil` 559 строк.
- Функции >80 строк: `ImageCompressionWorker.doWork` (~133), `ImageProcessingChecker.isProcessingRequired` (~105), `CompressImageUseCase.saveCompressed` (~103), `ImageCompressionUtil.compressImageToFile` (~103).

### P2 — разрозненные magic numbers и дубли

- Retry-лимит: константа `5` в `ImageCompressionWorker.kt:47` и magic `5` в `ImageSettleWorker.kt:26`.
- Timeout 120 с (`ImageCompressionUtil.kt:318`), маркер качества 99 (`CompressImageUseCase.kt:217,280`), HEIC-качество 85 (`ExifUtil.kt:1122`), резерв памяти 32 МБ (`FileOperationsUtil.kt:218`), batch-тайминги (`CompressionBatchTracker.kt:37-38,259`) — всё вне `Constants`.
- Origin сравнивается сырыми строками `"AUTO"/"MANUAL"` (`ImageCompressionWorker.kt:63,231`) при существующем enum `CompressionOrigin`, и правило вывода origin расходится между scheduler и worker.
- Две проверки «файл в каталоге приложения» с разной семантикой: `ImageProcessingChecker.isInAppDirectoryNormalized` (паттерны) vs `OptimizedCacheUtil.checkDirectoryStatus` (substring) — обе в одном конвейере (`ImageProcessingChecker.kt:129,188`), вердикты могут расходиться.
- Два независимых EXIF-кэша без ко-инвалидации (`ExifUtil.kt:47-98` — теги, TTL 10 мин; `OptimizedCacheUtil.kt:29-45` — маркеры): после записи маркера теговый кэш отдаёт данные до записи.
- `MediaStoreUtil.fileSaveLocks` eviction >200 записей — гонка `getOrPut` vs удаление (`MediaStoreUtil.kt:67-77`): два потока могут взять разные мьютексы одного пути (спасает execution gate).
- `markInefficient` игнорирует результат `writeExifDataFromMemory` (`CompressImageUseCase.kt:280`) — файл без маркера будет перетестироваться каждым сканом.
- EXIF-белые списки Android (~50 тегов) и CLI различаются — добавление тега на одной стороне молча теряет его на другой.

## Рекомендованный план улучшений

Порядок execution-ready; каждый шаг независим, шаги 1–3 —行为-фиксы, 4+ — рефакторинг.

1. **Унифицировать пороги**: один источник правды для мин. размера (решить: 100 КБ по AGENTS.md или осознанно легализовать 50 КБ ручного пути — тогда обновить AGENTS.md), `MIN_BYTES_SAVING` в `Constants`, `COMPRESSION_QUALITY_HIGH` выровнять Android/CLI (80 или 85 — решение владельца). Добавить cross-platform-тест/скрипт, сверяющий `Constants.kt` ↔ `constants.py`.
2. **Разорвать циклы**: (a) `NotificationUtil` не должен строить `Intent(BackgroundMonitoringService)` — передавать PendingIntent/класс через параметр или событие; (b) build-функции `WorkRequest` перенести из `CompressionWorkScheduler` в `worker/`, в domain оставить только интерфейс постановки.
3. **Мелкие фиксы**: retry-limit константой в `Constants`; origin через enum по всему пути; `markInefficient` обрабатывать false (retry маркера на следующем проходе — как минимум залогировать и не считать файл обработанным); unify проверку каталога приложения.
4. **`SettingsManager` в горячий путь**: заменить `isSaveModeReplace(context)` на инжектируемый `SettingsManager` (передавать из воркера/use case, где он уже есть).
5. **Расщепить `ExifUtil`**: минимум на `ExifTagCopier` (теги/GPS), `CompressionMarkerWriter` (двухфазная запись + guarded write), `ExifCache`; `applyExifFromMemory` декомпозировать до <80 строк/функцию.
6. **(Опционально, крупно)** вытянуть data-слой в интерфейсы + Hilt-бины для `CompressImageUseCase`, чтобы `domain/`-тесты работали без Robolectric. Делать поэтапно: сначала `MediaStoreUtil`/`ExifUtil` фасады.

Вне объёма обзора: производительность UI (1 Activity, Complaint не проверял), безопасность (ключей нет), CI.

## Валидация

- Шаги 1–4: `./gradlew testDebugUnitTest` + `./gradlew assembleDebug`; для CLI — её тесты (`compressphotofast-cli`).
- Шаг 5: полный прогон `./scripts/run_all_tests.sh` (затрагивает EXIF-пути, покрытые integration-тестами `androidTest/util/`).
- Любой шаг, меняющий поведение: обновить AGENTS.md (навык `agents-updater`).

## Открытые вопросы (решает владелец)

1. Мин. размер: легализовать 50 КБ для ручного пути или привести всё к 100 КБ?
2. HIGH-пресет: 80 или 85?
3. Делать ли шаг 6 (интерфейсы для data-слоя) сейчас или отложить?

# Восстановление мониторинга после завершения процесса на Huawei

## Цель и принятые решения

- После обычного LMK/OEM-завершения процесса тихо восстановить `BackgroundMonitoringService` и автосжатие без открытия `MainActivity`.
- Новое фото не должно теряться, даже если Android 12 запретил фоновый запуск FGS: корректность обеспечивают `JobScheduler` и долговечная очередь WorkManager, а восстановление уведомления из фоновой точки входа выполняется только best-effort.
- Автоматическое сжатие начинать примерно через 30 секунд после обнаружения фото; ручное сжатие не задерживать.
- Сохранять полное разрешение изображения. Не исправлять проблему памяти за счёт уменьшения до 4096 px; вместо этого снизить пик heap, проверять доступную память и откладывать работу.
- Не использовать alarm-loop, автозапуск Activity и обход force-stop/OEM-ограничений. После настоящего force-stop восстановление до ручного запуска невозможно по правилам Android.

## План реализации

1. Исправить lifecycle резервного content-trigger job в `app/src/main/java/com/compressphotofast/service/ImageDetectionJobService.kt`.
   - Убрать сочетания `jobFinished()` с `return false`; для асинхронного запуска возвращать `true` и завершать каждый запуск ровно один раз через единый completion path.
   - Ввести per-run terminal state: после `onStopJob()` отменять scope, не вызывать поздний `jobFinished()` и возвращать `true` для системного reschedule; `CancellationException` не проглатывать.
   - Перевооружать одноразовый content-trigger только после завершения текущего job и только при включённом автосжатии. Проверять результат `JobScheduler.schedule`; ошибка rearm не должна падать вместе с процессом.
   - Убрать ранний пропуск URI при `BackgroundMonitoringService.isRunning == true`: Job должен всегда довести событие до долговечной WorkManager-очереди, а существующие URI-lock/unique work устранят дубль с `ContentObserver`.
   - Упростить debounce так, чтобы suspend-цепочка ожидалась текущим job, а не запускалась вложенной корутиной, способной завершиться без `jobFinished()`.
   - При `triggeredContentUris == null/empty` считать событие overflow/неполным и выполнять ограниченный `GalleryScanUtil.scanRecentImages`; продвигать `lastScanTimestamp` только после успешной постановки найденных URI в WorkManager.
   - `cancelJob()` должен отменять все реально используемые job ID; если для атомарного rearm понадобятся два чередующихся ID, сначала успешно вооружать следующий slot, затем завершать текущий, а обычный `scheduleJob()` должен оставлять ровно один ожидающий trigger.

2. Сделать восстановление инфраструктуры идемпотентным в `MonitoringController.kt`, `CompressPhotoApp.kt` и `BackgroundMonitoringService.kt`.
   - В `MonitoringController` отделить `ensureDetectionJob()` от best-effort `startForegroundService()`: сначала всегда обеспечить Job, затем пробовать FGS и штатно возвращать `FAILED` при `ForegroundServiceStartNotAllowedException`, `SecurityException` или OEM-ошибке.
   - На холодном старте `CompressPhotoApp`, если автосжатие включено, вызвать идемпотентное восстановление после создания notification channel/WorkManager. Это позволит Job или отложенному Worker разбудить процесс и одновременно попытаться вернуть monitoring FGS; запрещённый Android 12 старт не должен влиять на очередь.
   - В `ImageDetectionJobService`, если monitoring service не готова, выполнить ту же best-effort попытку, но продолжить enqueue URI независимо от результата.
   - В `BackgroundMonitoringService` публиковать `isRunning/isReady=true` только после успешного `startForeground`, проверки настройки и регистрации observer; при любой ошибке старта и в `onDestroy` гарантированно сбрасывать state.
   - При системном восстановлении `START_STICKY` и в `onTaskRemoved` повторно проверять наличие content-trigger job. При уничтожении с включённой настройкой оставить Job вооружённым; явный Stop по-прежнему выключает настройку и отменяет оба механизма.
   - В gallery scan переносить запись `lastScanTimestamp` с момента до обработки на момент после постановки всех найденных URI в долговечную очередь, чтобы kill между scan и enqueue не создавал окно потери.

3. Отделить обнаружение фото от тяжёлой обработки в `Constants.kt` и `ImageProcessingUtil.kt`.
   - Добавить общую константу initial delay 30 секунд для автоматической обработки.
   - При создании `OneTimeWorkRequest<ImageCompressionWorker>` задавать `setInitialDelay(30 секунд)` только для `forceProcess=false`; ручные share/UI-запросы выполнять без задержки.
   - Оставить существующую последовательную unique chain, чтобы серия фото не декодировалась параллельно. Discovery-пути должны только проверить URI и поставить Work, но не декодировать Bitmap внутри Service/Job scope.
   - Вынести построение WorkRequest в небольшой internal helper, чтобы unit-тест проверял delay, теги, constraints и отсутствие задержки в ручном режиме.

4. Снизить пик памяти без потери разрешения в `ImageCompressionUtil.kt`, `ImageCompressionWorker.kt`, `FileOperationsUtil.kt` и `TempFilesCleaner.kt`.
   - Не применять downsampling к автоматическим и ручным фото: итоговые pixel dimensions после учёта EXIF orientation должны соответствовать исходнику. Обновить вводящие в заблуждение `MAX_IMAGE_*`/тесты так, чтобы они не обещали ограничение 4096 px.
   - Для `ImageDecoder` включить software allocator и `MEMORY_POLICY_LOW_RAM`; для `BitmapFactory` сохранить `RGB_565`, когда формат это допускает.
   - Перед decode рассчитывать консервативный peak с учётом формата, возможного второго Bitmap при EXIF-transform и текущего process heap; одновременно учитывать `ActivityManager.MemoryInfo.lowMemory/availMem`. При недостаточном headroom не начинать decode, а вернуть отдельный transient outcome.
   - Заменить `ByteArrayOutputStream` в `CompressionTestResult` на файловый временный artifact в `cacheDir`: писать JPEG напрямую в файл, проверять bounds и SOI/EOI потоково, затем передавать `FileInputStream` в `MediaStoreUtil`. Это уберёт `toByteArray()` и вторую полную копию сжатого файла.
   - Гарантированно удалять artifact на success, skip, exception и cancellation; использовать уже поддерживаемый `compressed_*` prefix, чтобы orphan-файл подбирал `TempFilesCleaner` после смерти процесса.
   - Не проглатывать `CompressionException.OutOfMemory`/новый insufficient-memory outcome как необратимый `null`: Worker должен классифицировать их, `IOException` и запрещённый foreground promotion как transient.
   - Ограничить transient retry числом попыток/общим backoff, чтобы один URI не блокировал глобальную последовательную цепочку бесконечно. После исчерпания попыток освободить цепочку, но не ставить EXIF/recently-processed marker, чтобы последующий scan мог подобрать фото снова.
   - В `ImageCompressionWorker` удалять URI-lock всегда, но добавлять `recentlyProcessed` только после подтверждённого success или terminal skip; retry/cancellation/failure не должны скрывать фото от следующей попытки.

5. Убрать дополнительные причины ANR/давления памяти в `MediaStoreObserver.kt`, `OptimizedCacheUtil.kt` и `CompressPhotoApp.kt`.
   - В `ContentObserver.onChange()` на main thread только захватывать URI; `getFileName`, `isFilePending`, EXIF и остальные `ContentResolver` операции выполнять в owned IO scope.
   - Transient provider/IO exception не должен удалять pending URI без retry/fallback scan; unregister должен отменять все принадлежащие observer задачи без статического вечного scope.
   - Добавить `OptimizedCacheUtil.evictAll()` и вызывать освобождение необязательных кэшей из `Application.onTrimMemory()` на low/critical уровнях, не очищая WorkManager, backup registry или долговечные настройки.
   - На API 30+ при следующем холодном старте в debug-сборке логировать последний `ApplicationExitInfo` (reason, timestamp, PSS/RSS), чтобы на Huawei отличить LMK от crash/user stop. Диагностика не должна влиять на recovery.

6. Добавить целевые тесты.
   - Robolectric/unit для `ImageDetectionJobService`: FGS ready/not ready, пустые URI, overflow scan, success, exception, cancellation и `onStopJob`; проверить ровно один terminal path и наличие одного вооружённого trigger после первого, второго и нескольких запусков.
   - Unit для WorkRequest: automatic delay 30 секунд, manual delay 0, последовательное unique work и сохранение URI при transient failure.
   - Unit для memory admission/file artifact: JPEG и HEIC оцениваются с корректным peak, low-memory возвращает retry, временный файл валиден и удаляется во всех terminal/cancellation сценариях, dimensions не уменьшаются.
   - Обновить `ImageCompressionUtilBitmapTest`: убрать тесты, закрепляющие скрытое уменьшение больших фото; добавить проверки сохранения полного разрешения и low-memory policy.
   - Instrumentation для API 31+: создать MediaStore image, дождаться Job, проверить, что Work enqueued с delay и Job снова pending; повторить несколько раз. Отдельно смоделировать заблокированный background FGS start: процесс не падает, URI не теряется.
   - Ручной сценарий Huawei: включить автосжатие, проверить разрешения Huawei «Автозапуск/Косвенный запуск/Работа в фоне», сделать серию фото, дождаться более 30 секунд. После обычного `adb shell am kill com.compressphotofast` новое фото должно попасть в очередь не позднее 60 секунд; уведомление восстанавливается best-effort. `am force-stop` проверить отдельно как неподдерживаемый системный сценарий.

## Проверка и завершение

1. Собрать `./gradlew assembleDebug` и проверить merged manifest/foreground service types.
2. Через обязательный skill `android-test-suite` запустить unit-тесты; из-за изменений `Service`, `JobService`, `Application` и Android API также запустить instrumentation-тесты.
3. Через `dumpsys jobscheduler` подтвердить: после каждого срабатывания остаётся ровно один content-trigger job; через `dumpsys activity services` подтвердить отсутствие дубликатов monitoring FGS.
4. Проверить серию больших JPEG/HEIC: разрешение результата не меняется, compression workers не выполняются параллельно, нет OOM/ANR, а transient memory pressure приводит к retry, а не потере URI.
5. Обновить `AGENTS.md` навыком `agents-updater`, затем собрать и передать debug APK через обязательный навык `apk` для проверки на Huawei P60 Pro.

## Критерии готовности

- После обычного low-memory/OEM kill следующее MediaStore-событие или уже отложенный Work разбудит процесс; фото будет поставлено в долговечную очередь независимо от успеха восстановления FGS.
- Content-trigger не исчезает после первого события и остаётся вооружённым после success, skip и recoverable error.
- Тяжёлое автосжатие не начинается раньше 30 секунд, ручное сжатие не задерживается, серия выполняется последовательно.
- Большие фото сохраняют исходное разрешение; недостаток памяти приводит к ограниченному retry без EXIF/recently-processed marker и без блокировки очереди навсегда.
- MainActivity никогда не открывается автоматически; force-stop и жёсткая OEM-блокировка явно остаются вне программных гарантий.

## Вне текущего scope

- Обнаруженная отдельно гонка между post-crash backup recovery и `TempFilesCleaner` требует отдельного исправления, поскольку не объясняет исчезновение monitoring FGS и затрагивает инварианты восстановления оригиналов.

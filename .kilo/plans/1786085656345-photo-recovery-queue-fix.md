# Надёжное восстановление обработки фото без глобальной блокировки очереди

## Цель и принятые решения

- После обычного LMK/OEM kill не гарантировать «неубиваемость» процесса или немедленный возврат foreground-уведомления: Android 12 этого не обещает. Гарантировать системно сохраняемое обнаружение и постановку фото в WorkManager; FGS восстанавливать best-effort и из разрешённых точек входа.
- Нормальная задержка автосжатия: около 30 секунд после обнаружения готового фото. Ручная обработка не получает initial delay.
- Временная проблема одного URI не должна задерживать соседние фото: проблемный URI повторяется независимо, следующие работы могут его обойти.
- Тяжёлую фазу с Bitmap/MediaStore выполнять по одной, сохраняя текущее полное разрешение и memory admission.
- Force stop, отзыв разрешений и жёсткая OEM-блокировка остаются неподдерживаемыми системными сценариями.

## Подтверждённые причины

- `ImageProcessingUtil.kt` добавляет все URI в одну `sequential_image_compression` через `APPEND_OR_REPLACE`. Retry/constraint головной работы блокирует всех потомков; failure уже построенной головы может терминально погасить существующих потомков.
- `requiresBatteryNotLow(true)` не связан с пользовательским переключателем энергосбережения и способен удерживать работы часами при системном состоянии battery-not-low=false.
- Ранние `Result.retry()` для pending в `ImageCompressionWorker.kt` обходят `MAX_TRANSIENT_ATTEMPTS`; экспоненциальный backoff WorkManager доходит до часов.
- `all { ... }` в scan-путях останавливает обход после первого duplicate/pending/error. При сортировке от новых к старым соседние фото попадают только в следующие 15-минутные циклы.
- `UriProcessingTracker` захватывает URI до асинхронного commit WorkManager. Kill/cancel оставляет process-local lock до 30-минутного stale timeout; enqueue `Operation` при этом не ожидается.
- WorkManager tag `compress_${uri.hashCode()}` не является unique/durable dedup. После смерти процесса повторный scan добавляет дубликаты.
- `MainActivity.cleanupStuckWorkManagerChain()` при backlog > 50 отменяет всю восстановленную цепочку и может по гонке отменить только что поставленную ручную работу.
- Ошибка/null cursor в `GalleryScanUtil` выглядит как успешный пустой scan, позволяя ошибочно продвинуть watermark.
- Реарм content-trigger с тем же `JOB_ID` после `jobFinished()` имеет окно гонки; целевых lifecycle-тестов для JobService сейчас нет.

## План реализации

1. **Заменить глобальную цепочку типизированным per-URI scheduler.**
   - Вынести постановку работ из `util/ImageProcessingUtil.kt` в небольшой `CompressionWorkScheduler` с типизированным результатом: `DURABLY_ACCEPTED`, `NOT_REQUIRED`, `RETRYABLE_FAILURE`.
   - Строить стабильный digest из полного `uri.toString()` (SHA-256), не из `hashCode`; имена: `image_settle_v2_<digest>` и `image_compression_v2_<digest>`.
   - Автоматический путь ставит unique `ImageSettleWorker` с `KEEP`, initial delay 30 секунд и без `requiresBatteryNotLow`; settle worker ставит final per-URI work с `KEEP` и завершается только после подтверждения enqueue.
   - Ручной путь отменяет только settle-work данного URI и сразу ставит ту же final unique-work без delay; race settle/manual безопасно схлопывается политикой `KEEP`. Ручной request пометить expedited с `RUN_AS_NON_EXPEDITED_WORK_REQUEST` при исчерпании квоты.
   - Ожидать завершения `WorkManager.enqueueUniqueWork(...).await()` перед возвратом `DURABLY_ACCEPTED` и перед продвижением scan watermark. Duplicate под `KEEP` считать принятым, если unfinished unique work уже существует.
   - Удалить `NetworkType.NOT_REQUIRED` и `requiresBatteryNotLow`: сеть по умолчанию не требуется, а memory/battery pressure обрабатываются внутри конкретного worker без глобального ожидания.

2. **Отделить durable enqueue от process-local блокировок.**
   - Убрать захват `UriProcessingTracker` из `ImageProcessingUtil.handleImage`; URI, ожидающий WorkManager, не должен считаться выполняемым в памяти процесса.
   - `ImageCompressionWorker` всегда самостоятельно захватывает URI-lock и учитывает фактический результат, не доверяя `is_handled_by_ipu` и не считая tag механизмом эксклюзивности.
   - Если lock уже принадлежит legacy/v2 worker того же URI, завершать дубликат без тяжёлой обработки; победивший worker перед записью повторно проверяет EXIF/processable state.
   - Добавить Hilt/application singleton `CompressionExecutionGate` с одним permit. Под permit поместить decode/test compression, artifact, запись/замену MediaStore и integrity verification; дешёвые проверки существования/pending/MIME выполнять до него, а processable/EXIF повторить после входа.
   - Передавать явный origin `AUTO`/`MANUAL` и timestamps обнаружения/enqueue для корректных уведомлений и диагностических логов; не выводить режим из наличия `batchId`.

3. **Сделать retry ограниченным и независимым для каждого URI.**
   - В `worker/ImageCompressionWorker.kt` провести все transient-пути через один helper `retryOrFinish`: pending в ранней проверке, повторный pending, `IOException`, memory admission/OOM, временный provider/foreground отказ и timeout.
   - Ограничить полный запуск пятью попытками для каждого URI, включая две текущие ранние ветки; использовать короткий линейный backoff вместо растущего до часов экспоненциального ожидания.
   - Не повторять бесконечно ошибки, требующие UI/нового разрешения. После лимита завершать только данный URI с диагностируемой причиной, освобождать lock/artifact и не ставить EXIF/recently-processed marker.
   - Terminal failure не влияет на другие URI. Периодический reconciliation сможет создать новую final unique-work после cooldown следующего scan.

4. **Исправить discovery и reconciliation независимо от FGS.**
   - В `BackgroundMonitoringService.kt`, `ImageDetectionJobService.kt` и `GalleryScanUtil.kt` заменить short-circuit `all {}` обычным циклом: попытаться durable-enqueue каждый найденный URI, собрать итог без остановки на duplicate/error.
   - Расширить `GalleryScanUtil.ScanResult` признаком успешного завершения query. Exception и null cursor возвращают `completedSuccessfully=false`; watermark не меняется.
   - Продвигать `lastScanTimestamp` только после успешного scan и после того, как каждый подходящий URI получил `DURABLY_ACCEPTED` или `NOT_REQUIRED`. Использовать перекрывающееся окно (`>=` и минимум 15 минут), чтобы одинаковые `DATE_ADDED` не терялись; per-URI unique work удаляет дубли.
   - В JobService не ждать pending URI 3+6+12 секунд и не выполнять тяжёлые metadata/EXIF проверки: быстро передавать trigger URI в auto settle-work. Pending окончательно проверяет durable worker.
   - Добавить `GalleryReconciliationWorker`: unique one-time запуск на cold/manual start и unique periodic запуск с минимальным системным интервалом 15 минут, без battery/network constraints. Cold-start catch-up сканирует 48 часов; регулярный scan использует watermark с overlap.
   - Планировать reconciliation из `CompressPhotoApp`, `BootCompletedReceiver` и при видимом старте `MainActivity` независимо от успеха FGS. Существующий FGS/ContentObserver остаётся быстрым каналом, но не условием корректности.

5. **Закрыть окно потери content-trigger JobScheduler.**
   - Перевести `ImageDetectionJobService` на два чередующихся job ID. В начале `onStartJob` вооружать alternate slot до обработки текущего события; после terminal path текущий одноразовый job исчезает, alternate остаётся наблюдать MediaStore.
   - Хранить в per-run state результат rearm. `onStopJob` просит системный reschedule текущего slot только если alternate не удалось вооружить; при успешном alternate не создавать второй постоянный trigger.
   - `scheduleJob/ensureDetectionJob` проверяет оба ID, оставляет один ожидающий trigger вне короткого перехода «один running + один pending» и возвращает явный результат `SCHEDULED/ALREADY_ARMED/FAILED`.
   - `cancelJob` отменяет оба ID. Duplicate content events безопасны благодаря final per-URI unique name.
   - Best-effort попытку поднять `BackgroundMonitoringService` сохранить, но её ошибка не влияет на enqueue/rearm. При ручном открытии Activity FGS стартуется из разрешённого foreground-контекста.

6. **Безопасно мигрировать уже сохранённую legacy-очередь.**
   - После обновления больше ничего не добавлять в `sequential_image_compression`.
   - Удалить `MainActivity.cleanupStuckWorkManagerChain()` и пороговую отмену `>50`; UI не должен очищать durable work.
   - Не отменять legacy chain автоматически: её input URI нельзя lossless извлечь через публичный `WorkInfo`, среди работ могут быть внешние/manual URI вне MediaStore.
   - Оставить legacy works дренироваться на обновлённом bounded worker и общем `CompressionExecutionGate`; startup 48-hour reconciliation параллельно восстановит MediaStore URI под v2 unique names.
   - Legacy/v2 дубль одного URI схлопывается URI-lock и повторной EXIF-проверкой. Failed legacy descendants подбираются v2 reconciliation; старый battery constraint больше не блокирует новые per-URI works.

7. **Добавить наблюдаемость без влияния на release-поведение.**
   - В debug-логах фиксировать для URI: discovery source, discovered/enqueued/started timestamps, unique digest, attempt, constraint/retry reason и terminal outcome.
   - Расширить существующий `ApplicationExitInfo` лог человекочитаемым reason (`LOW_MEMORY`, crash, ANR, user requested и т.п.), чтобы отличать LMK от OEM/force-stop. Не использовать reason для обхода системных ограничений.

## Проверка

1. Unit/Robolectric:
   - auto создаёт settle-work с 30 секундами; manual создаёт final-work без delay; ни одна final work не имеет battery-not-low constraint;
   - два конкурентных enqueue одного URI оставляют одну unfinished final unique-work, enqueue ожидается до `DURABLY_ACCEPTED`;
   - retry URI A не мешает URI B перейти к тяжёлой фазе; максимальная конкурентность тяжёлой фазы равна единице;
   - все pending/transient ветки ограничены пятью попытками и не ставят processed marker после failure;
   - scan `[duplicate, transient failure, new URI]` пытается поставить все элементы, но не двигает watermark при transient failure;
   - exception/null cursor не меняет watermark;
   - manual request безопасно обходит/схлопывает auto settle того же URI;
   - legacy и v2 worker одного URI не входят в тяжёлую фазу одновременно.
2. JobScheduler Robolectric/instrumentation:
   - после первого, второго и серии content events остаётся alternate pending trigger;
   - success, exception, cancellation и `onStopJob` имеют один terminal path;
   - blocked background FGS start не роняет процесс, не мешает durable enqueue и rearm;
   - выключение автосжатия отменяет оба job ID.
3. Instrumentation API 31+:
   - создать MediaStore JPEG/HEIC и проверить settle/final WorkInfo, delay и повторное вооружение job;
   - серия фото выполняет только одну тяжёлую компрессию одновременно, а pending/low-memory одного URI не задерживает соседний;
   - cold/manual start с backlog >50 не отменяет legacy или новую manual work.
4. Ручной Huawei-сценарий:
   - включить автосжатие и разрешённые OEM «Автозапуск/Косвенный запуск/Работа в фоне»;
   - сделать серию фото, включая большие JPEG/HEIC; нормальные URI начинают обрабатываться примерно после 30 секунд, без разброса на часы;
   - выполнить `adb shell am kill com.compressphotofast` (не `force-stop`), сделать новые фото и проверить через `dumpsys jobscheduler`/`dumpsys activity service WorkManager` автоматический durable enqueue; постоянное уведомление оценивается только best-effort;
   - открыть приложение вручную и убедиться, что immediate reconciliation ставит все пропущенные фото независимо, не отменяя backlog.
5. Выполнить `./gradlew assembleDebug`; Android unit/instrumentation тесты запускать только через skill `android-test-suite`. После реализации обновить `AGENTS.md` через `agents-updater`, затем собрать и передать debug APK через skill `apk`.

## Риски и границы

- JobScheduler/WorkManager на OEM могут быть отложены системой, поэтому нельзя обещать точный срок после LMK; план устраняет программные часы ожидания и глобальную блокировку, но не обходит force stop.
- Существующая legacy chain может оставаться constrained до изменения состояния батареи, однако больше не является prerequisite для v2 работ.
- Внешние share/photo-picker URI требуют отдельно сохранить доступ (`takePersistableUriPermission`, если provider это поддерживает, либо app-owned staging) перед durable enqueue; при реализации проверить текущий intent grant и добавить staging только для неперсистентных URI, не меняя MediaStore-путь.

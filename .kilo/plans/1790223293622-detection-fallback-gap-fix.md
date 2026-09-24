# Устранение дыры обнаружения после коммита 8f18488 (content-trigger только recovery)

## Проблема

Коммит `8f18488` сделал живой ContentObserver единственным активным путём обнаружения:
- `BackgroundMonitoringService.onCreate` отменяет armed content-trigger Job'ы при `isReady=true` (`cancelRecoveryJobsWhileReady`);
- `startPeriodicScanning` пропускает периодический скан при живом observer;
- `ImageDetectionJobService.onStartJob` делает no-op при `isReady=true`.

Дыра: **ContentObserver не будит процесс** — события доставляются только исполняющемуся процессу. В режиме энергосбережения процесс может быть заморожен/OEM-приостановлен при живом FGS: события копятся и не доставляются. Единственный механизм, способный **разбудить** процесс — JobScheduler content-trigger — отменён при ready, а при случайном срабатывании отбрасывается guard'ом. Периодический скан тоже не работает: корутина `delay` не тикает в замороженном процессе. Результат: задержки обнаружения десятки минут (наблюдается на устройстве в battery saver).

Дополнительная деталь: `startPeriodicScanning` вообще не помогает при заморозке (корутины не исполняются), поэтому полагаться на него как на страховку нельзя — страховка должна жить в JobScheduler.

## Цель

Вернуть пробуждающий fallback, сохранив основную идею оптимизации (без дублирующих тяжёлых фаз): content-trigger Job остаётся **armed всегда** и при `isReady` обрабатывает только лёгкую durable-постановку URI (существующий dedup исключает двойное сжатие). Периодический скан при живом observer выполняется редко как дешёвая страховка.

## Изменения

### 1. `BackgroundMonitoringService.kt`
- Удалить вызовы `cancelRecoveryJobsWhileReady()` из `onCreate` и `onStartCommand`; сам метод удалить.
- Вместо удаления — гарантировать armed-состояние: в `onCreate` после `isReady = true` вызвать `ImageDetectionJobService.scheduleJob(applicationContext)` (no-op, если уже armed).
- `startPeriodicScanning`: заменить пропуск на редкий страховочный проход. При живом observer сканировать раз в `Constants.BACKGROUND_SCAN_INTERVAL_FALLBACK_MINUTES` (новая константа, 60 мин), иначе — как раньше (`BACKGROUND_SCAN_INTERVAL_MINUTES`, 15 мин). Реализация: в цикле выбирать задержку по условию `isReady && isRunning && !isServiceDestroyed.get()` перед `scanForNewImages()`.

### 2. `ImageDetectionJobService.kt`
- Guard `if (BackgroundMonitoringService.isReady)` в `onStartJob` заменить: при `isReady` НЕ перезапускать FGS (`MonitoringController.startForegroundService` — только при `!isReady`), но **обрабатывать** `triggeredContentUris` через `scheduler.enqueue(...)` как сейчас (dedup: unique work KEEP + `UriProcessingTracker` + маркеры).
- Overflow-scan (`processOverflowScan`) при `isReady` — пропускать (это тяжёлый путь; его роль при заморозке закрывает content-trigger от реальных событий MediaStore, которые JobScheduler копит даже для замороженного процесса).
- `finishRun` уже перепланирует Job при `reschedule` — оставшийся armed-слот сохраняется, поведение не менять.

### 3. `Constants.kt`
- Добавить `const val BACKGROUND_SCAN_INTERVAL_FALLBACK_MINUTES = 60L`.

### 4. Инвариант дедупликации (без изменений кода, зафиксировать в комментарии)
Двойная доставка (observer + Job) одного URI безопасна: `enqueueUniqueWork(KEEP)`, `StatsTracker.shouldProcessImage`, маркер сжатия. Проверить, что путь Job → `scheduler.enqueue` не обходит `shouldProcessImage` (сейчас обход — но unique work KEEP закрывает; убедиться тестом).

## Тесты

- Обновить `RecoveryJobLifecycleTest`: вместо «Job отменяется при ready» — «Job остаётся armed при ready»; «onStartJob при isReady ставит работы для triggered URIs и не стартует FGS»; «overflow-scan при isReady пропускается».
- Добавить тест: после `finishRun` с `reschedule=true` pending Job присутствует (armed-инвариант).
- Прогнать `./gradlew testDebugUnitTest` через навык `android-test-suite`; сборка `./gradlew assembleDebug`.

## Риски

- Возврат части wake-up'ов: один Job-пробуждение на новое фото даже при живом FGS. Приемлемо: это цена стабильности; тяжёлая Bitmap/MediaStore-фаза по-прежнему единственная (Worker с unique KEEP).
- Overflow-scan при dead FGS остаётся как раньше (не трогаем).
- Battery saver системный всё равно откладывает Job — полностью задержки не уйдут, но окно «заморожен при живом FGS» закрывается: Job будит процесс, durable enqueue выполняется, settle-worker обрабатывает при первом окне.

## Ручная валидация

1. Включить автосжатие, убедиться по логам, что armed Job присутствует при готовом FGS.
2. Снять серию фото с интервалом 1–5 мин в режиме энергосбережения; сравнить `discoveredAt → enqueuedAt` и зазоры сжатия с текущим поведением.
3. Убедиться в отсутствии двойного сжатия одного URI (маркер + unique work).

## Out of scope

- Исключения из системного Battery Saver (невозможно программно, только пользователь).
- Изменение debounce/задержек settle-фазы.
- CLI.

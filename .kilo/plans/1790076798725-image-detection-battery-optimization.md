# Оптимизация обнаружения новых изображений (энергопотребление)

## Цель
Устранить дублирующую работу механизмов обнаружения при живом FGS: ContentObserver остаётся единственным активным путём, content-trigger Job — только recovery, периодический скан при живом observer не выполняется. Результат — меньше wake-up'ов, сканов и повторных enqueue на каждое новое фото.

## Текущее состояние
Три параллельных пути, каждый обрабатывает одни и те же URI:
1. `MediaStoreObserver` (ContentObserver в FGS) — real-time.
2. `ImageDetectionJobService` (2 слота content-trigger) — срабатывает даже при живом FGS.
3. Периодический scan в `BackgroundMonitoringService` каждые `BACKGROUND_SCAN_INTERVAL_MINUTES` + стартовый history-scan.

## Изменения

### 1. Content-trigger Job — только recovery (`ImageDetectionJobService.kt`, `BackgroundMonitoringService.kt`)
- После успешного перехода FGS в ready (`isReady = true` в `onCreate`) отменять armed content-trigger jobs: `ImageDetectionJobService.cancelJob(...)`.
- Сохранить существующее планирование в `onDestroy` (если не `isUserStopped` и автосжатие включено) и в `onTaskRemoved` — Job становится чисто recovery-механизмом.
- `ImageDetectionJobService.onStartJob` уже поднимает FGS (`MonitoringController.startForegroundService`) — не менять; Job при срабатывании восстанавливает полный цикл.
- Логику двух слотов, `armAlternate`, overflow-scan внутри Job не трогать — она нужна, когда Job — активный путь.

### 2. Периодический скан — пропуск при живом observer (`BackgroundMonitoringService.kt`)
- В `startPeriodicScanning` перед `scanForNewImages()` проверять `isReady && isRunning && !isServiceDestroyed.get()`; если observer жив — пропускать итерацию (не отменять цикл).
- Стартовый `scanGalleryForUnprocessedImages()` (48ч history-scan) в `setupContentObserver` оставить без изменений — он покрывает окно потери после kill/перезагрузки.
- `scanForNewImages()` остаётся вызываемым из `onStartCommand` (первичный скан при старте) — оставить.

### 3. Мелочи (по желанию, низкий приоритет)
- `MediaStoreObserver.handleChange`: очистка `recentlyObservedUris` полным проходом на каждое событие — заменить на периодическую (например, раз в N событий) или оставить как есть (не критично для батареи).

## Не делаем (out of scope)
- Фильтрацию ContentObserver по типу изменения (insert vs update) — события не будят процесс, выигрыш только в CPU.
- Уменьшение debounce/задержек (это anti-цель по батарее).
- CLI-часть.

## Риски и миграция
- **LMK/OEM kill при живом FGS**: раньше armed Job подстраховывал; после отмены Job'ов при abrupt kill оба механизма мертвы до START_STICKY-восстановления. Это уже покрыто существующей схемой: recovery после LMK (cold-start + JobScheduler best-effort) и `GalleryReconciliationWorker`, восстанавливающий MediaStore URI независимо от FGS. Дополнительно: `onDestroy` планирует Job заново при штатной смерти.
- Проверить, что `cancelJob` при `isReady` не конфликтует с `scheduleJob` из `onDestroy`/`onTaskRemoved` (последовательность: ready → cancel; смерть → schedule).
- Переключение автосжатия ON: убедиться, что `MonitoringController.startForegroundService` вызывается (Job/настройки) и после ready Job'ы очищаются.

## Валидация
1. `./gradlew assembleDebug`.
2. Unit-тесты `./gradlew testDebugUnitTest` через навык `android-test-suite` (затронуты `ImageDetectionJobService`, `BackgroundMonitoringService` — обновить/добавить тесты: cancel при ready, schedule при destroy/onTaskRemoved, пропуск периодического скана при isReady).
3. Ручная проверка сценариев: новое фото при живом FGS (ровно один enqueue), kill процесса (recovery через Job/reconciliation), toggle автосжатия off/on.

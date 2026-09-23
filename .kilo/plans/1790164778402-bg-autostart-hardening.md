# План: автозапуск фоновых процессов после закрытия (минимальное изменение)

Цель: повысить вероятность самовосстановления фоновой работы после LMK/OEM-kill, не добавляя сложной логики.

## Контекст (уже реализовано)
- `BackgroundMonitoringService` — specialUse FGS, `START_STICKY`, восстановление в `onDestroy`/`onTaskRemoved`.
- `ImageDetectionJobService` — dual content-trigger JobScheduler (job ID 1000/1001).
- `GalleryReconciliationWorker` — periodic WM (15 мин), независим от FGS.
- `BootCompletedReceiver` — `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` → `MonitoringController.startMonitoring()`.
- Cold-start recovery в `CompressPhotoApp.onCreate`.

## Изменения (2 файла, минимум кода)

### 1. `setPersisted(true)` для content-trigger джобов
Файл: `app/src/main/java/com/compressphotofast/service/ImageDetectionJobService.kt`
- В `buildJob` (там, где `JobInfo.Builder`) добавить `.setPersisted(true)` для обоих чередующихся слотов (job ID 1000/1001).
- Разрешение `RECEIVE_BOOT_COMPLETED` уже есть в манифесте — джоб переживёт перезагрузку без доставки `BOOT_COMPLETED` (частая проблема на OEM-прошивках).
- Проверить, что `onStartJob` устойчив к запуску джоба сразу после загрузки, когда FGS ещё не поднят (уже так: job сам поднимает FGS через `MonitoringController.startForegroundService`).

### 2. Self-heal при стартах процесса
Файл: `app/src/main/java/com/compressphotofast/service/MonitoringController.kt`
- В `startMonitoring` добавить проверку перед `ensureDetectionJob`: если оба слота не зарегистрированы (`JobScheduler.getPendingJob(1000/1001) == null`), считать джобы потерянными и ре-arm; сейчас, вероятно, проверяется только флаг «джоб уже запланирован» — убедиться, что метод гарантирует наличие хотя бы одного armed слота (идемпотентно).
- Файл `CompressPhotoApp.onCreate` уже вызывает `startMonitoring` — это и есть точка автозапуска после любого старта процесса (кроме force stop, который обойти невозможно).

### Явно НЕ делаем
- Обход force stop, отзыва разрешений, ручного ограничения батареи — технически невозможен.
- Screen-on триггеры, периодический JobScheduler fallback, release-диагностика exit — вне рамок «максимально простого» решения.

## Валидация
1. `./gradlew assembleDebug` — сборка.
2. `./gradlew testDebugUnitTest` через навык `android-test-suite`.
3. Ручная проверка (при наличии эмулятора/устройства):
   - `adb shell am kill <package>` → процесс перезапускается, `MonitoringController` восстанавливает job+FGS.
   - Перезагрузка устройства → job persisted, FGS поднимается.

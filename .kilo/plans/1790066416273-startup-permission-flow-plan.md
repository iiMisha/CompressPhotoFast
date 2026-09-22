# Единый запрос всех разрешений при первом запуске (как в RogaineHelper)

## Цель

При запуске приложения пользователь получает подряд все системные запросы разрешений
(медиа → уведомления → геолокация EXIF → «Доступ ко всем файлам» → исключение из
оптимизации батареи) и не должен искать тумблеры в настройках вручную. Эталон —
`RogaineHelper/app/src/main/java/com/rogainehelper/MainActivity.kt:136`
(`requestStartupPermissions`, `RequestMultiplePermissions`) и
`RogaineHelper/.../core/permission/BatteryOptimizationHelper.kt` (прямой системный диалог).

## Текущее состояние (что уже есть / что мешает)

- Battery exemption **уже** показывается прямым системным диалогом
  (`util/BatteryOptimizationHelper.kt:40`), но только при первом включении автосжатия
  (`ui/MainActivity.kt:533` → `requestBatteryExemptionIfNeeded`).
- Runtime-разрешения (медиа/уведомления/гео) запрашиваются при старте
  (`MainActivity.onCreate` → `checkAndRequestPermissions`, `MainActivity.kt:259`),
  но `PermissionsManager.checkAndRequestAllPermissions` (`util/PermissionsManager.kt:62`)
  блокирует повторные запросы флагами `PREF_PERMISSION_SKIPPED` и
  `PREF_PERMISSION_REQUEST_COUNT >= 3` — после пропуска/отказов запросы больше не
  показываются никогда.
- «Доступ ко всем файлам» (MANAGE_EXTERNAL_STORAGE) — единственное разрешение, для
  которого системного диалога не существует по пруродe Android: показывается
  in-app объяснение, затем открывается системный экран настроек
  (`PermissionsManager.showStoragePermissionDialog`, `PermissionsManager.kt:257`).
  Это остаётся неизбежным, но делается один раз и в конце цепочки.
- Режим Replace перезаписывает чужие фото на месте
  (`util/MediaStoreUtil.kt:543`), поэтому отказ от MANAGE_EXTERNAL_STORAGE
  **невозможен** — он сохраняется как обязательный шаг.

## Решённые решения (с пользователем)

1. Все разрешения — при первом запуске (не «перед первой работой»).
2. Battery exemption тоже показывается при первом запуске.
3. MANAGE_EXTERNAL_STORAGE сохраняется (нужен для replace-перезаписи), но его
   экран настроек открывается один раз в конце цепочки запросов.

## Изменения

### 1. `util/PermissionsManager.kt` + `util/IPermissionsManager.kt`

- Добавить метод `requestStartupPermissions(onComplete: () -> Unit)`:
  - собрать все отсутствующие runtime-разрешения одним списком
    (логика `getRequiredStoragePermissions` + `POST_NOTIFICATIONS` +
    `ACCESS_MEDIA_LOCATION`) и запустить **один** `RequestMultiplePermissions`;
  - после результата: если всё выдано или остались только «необязательные» отказы —
    продолжить цепочку (шаг 2).
- Убрать подавление запросов: счётчик `PREF_PERMISSION_REQUEST_COUNT` и
  авто-подавление по `PREF_PERMISSION_SKIPPED`. RogaineHelper просто запрашивает
  при каждом запуске, пока не выдано; системный «don't ask again» сам защищает от
  навязчивости. Для permanently-denied остаётся explanation-dialog с кнопкой
  перехода в настройки приложения.
- Нельзя удалять методы, используемые тестами (`test`/`androidTest`), без
  обновления тестов — проверить `grep -r "requestStoragePermissions\|requestCount"`.

### 2. Цепочка в `ui/MainActivity.kt`

- Заменить вызов `checkAndRequestPermissions()` (onCreate, `MainActivity.kt:259`)
  на новый flow:
  1. `permissionsManager.requestStartupPermissions { ... }` — системные диалоги;
  2. если API 30+ и `!Environment.isExternalStorageManager()` — краткое in-app
     объяснение (существующий `showStoragePermissionDialog`) → системный экран
     All-Files-Access (прямой intent с package URI, как сейчас);
  3. после возврата — `requestBatteryExemptionIfNeeded()` (переиспользовать
     существующий метод `MainActivity.kt:547` без изменений, он идемпотентен
     по флагу `isBatteryExemptionRequested`);
  4. затем существующие `checkMediaLocationPermission`-последствия:
     `updatePhotoPickerButtonVisibility()` + `initializeBackgroundServices()`.
- Share-интенты (`ACTION_SEND`/`ACTION_SEND_MULTIPLE`) по-прежнему не запускают
  запросы (`MainActivity.kt:257`).
- Существующий listener тумблера автосжатия (`MainActivity.kt:533`) остаётся:
  `requestBatteryExemptionIfNeeded` идемпотентен, при первом запуске флаг уже
  выставлен — повторного диалога не будет.
- Карточка-предупреждение и `btnOpenBatterySettings` остаются без изменений
  (ручной путь в настройки батареи для тех, кто отказался).

### 3. Константы и префы

- `Constants.PREF_PERMISSION_REQUEST_COUNT` и `Constants.PREF_PERMISSION_SKIPPED`:
  оставить записи в SharedPreferences нетронутыми (не мигрировать), просто
  перестать читать счётчик как блокировщик. `PREF_PERMISSION_SKIPPED`
  переиспользуется только для permanently-denied-обработки (как сейчас в
  `handleStoragePermissionDenied`).

## Риски

- Google Play: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` требует core-functionality
  обоснование; показ при первом запуске — допустимо (разрешение уже объявлено и
  используется сейчас), но rejection-риск не выше текущего.
- Пользователь, отклонивший runtime-разрешения, получит explanation-диалоги при
  следующих запусках только для permanently-denied — иначе поведение совпадает с
  RogaineHelper (запрос на каждый запуск, пока не выдано).
- Порядок «медиа → все файлы → battery» важен: сначала невсегда-отменяемые
  системные диалоги, потом экран настроек, потом battery-диалог — пользователь
  не теряет цепочку.

## Проверка

1. `./gradlew assembleDebug`.
2. `./gradlew testDebugUnitTest` через навык `android-test-suite`
   (обновить существующие тесты PermissionsManager, если они зависят от
   счётчика запросов).
3. Ручной сценарий на устройстве/эмуляторе (API 33+): чистая установка →
   запуск → подряд: медиа, уведомления, гео, экран All-Files-Access, battery-диалог;
   второй запуск не показывает повторных запросов; отказ на каждом шаге не
   блокирует приложение; Share-интент не запускает запросы.

# План: добавить разрешение Ultra HD (Android 16)

## Контекст
- На Android 16 (API 36) при targetSdk 36 доступ к Ultra HD-фотографиям (высокое разрешение, 8K/50MP+) требует нового runtime-разрешения `android.permission.READ_MEDIA_ULTRA_HD_IMAGES`. Без него система скрывает такие фото от приложения.
- Проект уже: `compileSdk = 36`, `targetSdk = 36` (`app/build.gradle.kts:14,19`) — константа `Manifest.permission.READ_MEDIA_ULTRA_HD_IMAGES` доступна.
- Текущий флоу разрешений: один runtime-диалог при первом запуске через `PermissionsManager.requestStartupPermissions` (`app/src/main/java/com/compressphotofast/util/PermissionsManager.kt:89`), который собирает список из `getRequiredStoragePermissions()` + уведомления + `ACCESS_MEDIA_LOCATION`. Также используется All-Files-Access (`MANAGE_EXTERNAL_STORAGE`) отдельным шагом в `MainActivity`.

## Решения
- Разрешение добавляется в общий runtime-диалог при запуске (не отдельным шагом) — соответствует текущему паттерну «все разрешения при первом запуске».
- Гейт по версии: `Build.VERSION.SDK_INT >= 36` (BAKLAVA), чтобы не запрашивать на старых версиях.
- Учитывать разрешение как «нежёсткое»: его отсутствие не должно блокировать основную работу приложения (аналогично `ACCESS_MEDIA_LOCATION` — доп. функциональность).

## Задачи
1. `app/src/main/AndroidManifest.xml`: добавить
   `<uses-permission android:name="android.permission.READ_MEDIA_ULTRA_HD_IMAGES" />` рядом с `READ_MEDIA_IMAGES` (строка 8) с комментарием про Android 16 Ultra HD фото.
2. `PermissionsManager.getRequiredStoragePermissions()` (строка 137): внутри ветки `TIRAMISU` добавить блок — если `SDK_INT >= 36` и разрешение не выдано, добавить `Manifest.permission.READ_MEDIA_ULTRA_HD_IMAGES` в список.
3. `PermissionsManager`: добавить метод `hasUltraHdImagesPermission(): Boolean` (проверка `checkSelfPermission`, на API < 36 возвращает true) — для диагностики и потенциального UI-статуса; в интерфейс `IPermissionsManager` добавить соответствующий метод.
4. Диагностика: в `ExifUtil.kt:693-702` (диагностический лог разрешений) добавить строку про Ultra HD разрешение при API 36+.
5. Тексты: по желанию — строка в `strings.xml` не обязательна (системный диалог), объясняющие диалоги не менять.

## Не входит в объём
- `READ_MEDIA_ULTRA_HD_VIDEO` — приложение работает только с изображениями.
- Изменение порядка запроса All-Files-Access / battery exemption.
- Изменения CLI.

## Риски
- `hasStoragePermissions()` (строка 235) с кэшем `has_storage_permission_granted` — не менять: отсутствие Ultra HD не должно считаться «нет разрешений на хранилище».
- На устройствах без Ultra HD фото система просто одобрит запрос без отдельного диалога — приемлемо.

## Валидация
- `./gradlew assembleDebug` — сборка.
- `./gradlew testDebugUnitTest` через навык `android-test-suite`.
- Ручная проверка на устройстве с Android 16: при первом запуске в диалоге присутствует пункт «Фото Ultra HD»; после выдачи — Ultra HD фото видны и сжимаются.
- Обновить AGENTS.md (раздел про разрешения) через навык `agents-updater`, затем собрать и опубликовать APK через навык `apk`.

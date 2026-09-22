# Модернизация главного экрана: Material 3, dynamic color, минимализм

## Контекст и принятые решения

Текущее состояние: тема `Theme.MaterialComponents` (M2) без app bar, эмодзи в заголовках секций, две группы radio-кнопок, раскрывающееся HTML-предупреждение, дублированные стили в `themes.xml`/`styles.xml`, мёртвый `ProgressBar` в layout, захардкоженные строки диалогов в `MainActivity.kt:625-638`. Kotlin-код не ссылается на `R.color.*` (проверено grep) — палитру можно реструктурировать свободно. Тесты не ссылаются на UI-строки/radio-группы.

Решения пользователя:
1. **Material 3 + dynamic color**: на Android 12+ динамическая палитра из обоев, на API 29–31 — статичная M3-палитра на базе текущего синего `#1976D2`.
2. **Сегментные кнопки** (`MaterialButtonToggleGroup`) вместо обеих radio-групп.
3. **Без app bar** — заголовок приложения не добавляем, сохраняем текущий беззаголовочный минимализм.
4. **Эмодзи убираем только с главного экрана**; Toast-сообщения и уведомления статус-бара не трогаем.
5. **Тёмная тема**: сохранить AMOLED true-black (`#000000`), поверх — динамические акценты M3.
6. **Предупреждение о фоновой работе**: сворачиваемая карточка с чистым текстом (без HTML/эмодзи) и кнопкой перехода в настройки батареи.

## Шаги реализации

### 1. Палитра и темы (M3)

- `values/colors.xml`: статичная светлая M3-палитра из seed `#1976D2`: `colorPrimary`/`onPrimary`/`primaryContainer`/`onPrimaryContainer`, `surface`/`onSurface`/`surfaceVariant`/`onSurfaceVariant`, `outline`, `background`/`onBackground`, `error`/`onError`. Старые имена (`accent`, `primary_text`, `divider`, …) удалить — ссылок в коде нет.
- `values-night/colors.xml`: тёмные тона primary (light blue), `background` = `surface` = `#000000` (AMOLED), нейтральные тона темнее стандартных.
- `values/themes.xml` и `values-night/themes.xml`: parent → `Theme.Material3.DayNight.NoActionBar`; токены M3 (`colorPrimary`, `colorSurface`, `android:colorBackground`, `colorOnSurfaceVariant`, `colorOutline`); стиль диалога → `ThemeOverlay.Material3.MaterialAlertDialog` (позитивная кнопка — text button без bold). Удалить стили `Widget.CompressPhotoFast.Switch` (M3 `MaterialSwitch` по умолчанию), `Widget.CompressPhotoFast.Button` → `Widget.Material3.Button.Tonal` для «Выбрать фото». Обе темы держать симметрично.
- В `MainActivity.onCreate()`: `DynamicColors.applyIfAvailable(this)` (`com.google.android.material.color.DynamicColors`, material 1.12.0 уже в зависимостях).

### 2. Edge-to-edge и insets (обязательный фикс)

`targetSdk 36` → на Android 15+ edge-to-edge принудителен, сейчас контент заезжает под статус-бар (paddingTop всего 12dp):

- В `onCreate()` вызвать `enableEdgeToEdge()` (androidx.activity) — она сама выставляет `windowLightStatusBar` по теме.
- `ViewCompat.setOnApplyWindowInsetsListener` на корневом `ScrollView`: `setPadding(bars.left + 16dp, bars.top + 8dp, bars.right + 16dp, bars.bottom + 16dp)`.

### 3. Layout `activity_main.xml` — реструктуризация

Единый вертикальный поток (NestedScrollView/ScrollView + вертикальный ConstraintLayout/LinearLayout), отступы из `dimens.xml`:

- **Секция «Автоматическое сжатие»**: строка-переключатель `MaterialSwitch` (label 16sp onSurface) + подпись 13–14sp `onSurfaceVariant`; под ними строка «Работает не на всех устройствах?» с chevron (`ic_expand_more`).
- **Сворачиваемая карточка** `MaterialCardView` (`visibility="gone"`): заголовок 14sp medium, шаги 1./2. обычным текстом (без HTML), внизу `MaterialButton` стиль `TextButton` «Открыть настройки батареи» → `BatteryOptimizationHelper.openBatterySettings(this)`. Состояние управляется существующим `viewModel.isWarningExpanded`, анимация — существующим `TransitionManager.beginDelayedTransition`.
- **Секция «Замена оригинальных файлов»**: строка-переключатель + подпись.
- **Секция «Качество сжатия»**: заголовок 14sp medium letterSpacing 0.1 `onSurfaceVariant`; `MaterialButtonToggleGroup` (`singleSelection`, `selectionRequired`) с 3 кнопками: тексты из `_with_value`-строк, 12–13sp.
- **Секция «Разрешение»**: тот же паттерн, toggle group «Оригинал / 1920 px / 1280 px».
- Кнопка «Выбрать фото» — `Widget.Material3.Button.Tonal`, логика видимости не меняется.
- Секции разделять whitespace ~24dp; линейный разделитель `divider2` удалить.
- **Удалить мёртвый `ProgressBar`** (никогда не показывается кодом).

### 4. `MainActivity.kt`

- Переписать `setupCompressionQualityRadioButtons()`/`setupResolutionRadioButtons()` на toggle-группы: `addOnButtonCheckedListener` → те же вызовы `viewModel.setCompressionPreset(...)`/`setMaxResolution(...)`; восстановление состояния — `check(id)`/`checkedButtonId`; подписи обновлять в `updateQualityRadioButtonTexts()` через `binding.btnQualityLow.text = ...` (кнопки вместо radio). ViewModel не менять.
- Удалить `Html.fromHtml`-блок; клик карточки/кнопки → `openBatterySettings` (существующий код из `tvBackgroundModeWarning` listener).
- `observeViewModel()`: `isWarningExpanded` → видимость карточки + поворот `ivExpandArrow` (как сейчас).
- Захардкоженные строки → ресурсы: диалог геолокации (заголовок/сообщение/кнопки), «Пожалуйста, откройте настройки вручную», «GPS координаты не будут сохраняться…», «Функциональность приложения может быть ограничена…».
- `showToast()`/`NotificationUtil` **не менять** (эмодзи в Toast/уведомлениях остаются по решению пользователя).

### 5. `strings.xml`

- Убрать эмодзи из экранных строк: `auto_compression_toggle`, `save_mode_toggle`, `compression_quality_title`, `compression_quality_low/medium/high`(+`_with_value`), `resolution_title`, `resolution_original/1920/1280`. Формат `%d%%` в `_with_value` сохранить.
- `background_mode_warning`: новый plain-text без CDATA (два шага настройки батареи). Новые: `warning_card_title` («Работает не на всех устройствах?»), `warning_open_battery_settings` («Открыть настройки батареи»).
- Добавить строки диалогов из шага 4.

### 6. Чистка ресурсов

Удалить (ссылок нет, проверено): `drawable/auto_compression_header_background.xml`, `drawable/progress_background.xml`, `values/styles.xml` (после удаления `LargeProgressBar`), `anim/rotate.xml` — перед удалением проверить grep; при наличии ссылок оставить. Нотификационные строки с эмодзи не трогать.

## Не меняется

MainViewModel и вся логика разрешений/сервисов/сжатия; Toast-эмодзи и тексты уведомлений; CLI; `values-w600dp/dimens.xml`.

## Риски

- Ширина сегментов на 360dp: «Низкое (60%)» при 12–13sp помещается (сейчас те же тексты в radio), но проверить визуально на узком экране; при переполнении — укоротить подписи до «Низкое 60%».
- Dynamic color + AMOLED-чёрный: динамический тёмный primary светлый, контраст на чёрном достаточный; fallback-палитра API 29–31 должна иметь тот же тон.
- Edge-to-edge на Android < 15: insets приходят и там, листенер универсален; `enableEdgeToEdge()` корректно ставит `windowLightStatusBar` по night-режиму.
- Robolectric-тесты поднимают тему приложения — после смены parent на Material3 прогнать тесты обязательно.

## Валидация

1. `./gradlew assembleDebug`
2. `./gradlew testDebugUnitTest` через навык `android-test-suite`
3. Визуальная проверка light/dark и динамики сворачивания карточки на эмуляторе `Small_Phone` (опционально)
4. Изменён runtime-код `app/src/main/**` → после успешной сборки опубликовать debug-APK через навык `apk` (правило AGENTS.md)

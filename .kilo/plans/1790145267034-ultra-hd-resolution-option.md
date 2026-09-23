# План: добавить разрешение 2560 px (QHD) и убрать 1280 px

## Цель
В radio-группе выбора максимальной стороны фото: добавить вариант «2560 px (QHD)» и удалить вариант «1280 px». Итоговые варианты: Оригинал / 2560 px (QHD) / 1920 px.

## Решения
- Значение нового варианта = **2560** (уточнено пользователем вместо 2160), константа `RESOLUTION_2560 = 2560`.
- Вариант 1280 px полностью удаляется из UI.
- Миграция сохранённых настроек: у пользователей со старым значением `1280` при загрузке настроек значение заменяется на дефолт `RESOLUTION_ORIGINAL` — иначе сохранённое значение останется рассинхронизированным с UI. Явная миграция в `SettingsManager.getMaxResolution()`: если сохранено 1280 — вернуть и записать `DEFAULT_MAX_RESOLUTION`.
- `RESOLUTION_1280` из `Constants` удалить, все ссылки заменить; тесты с 1280 обновить на 2560.

## Изменения

1. **`app/src/main/java/com/compressphotofast/util/Constants.kt`** (~строки 125–128)
   - Удалить `const val RESOLUTION_1280 = 1280`.
   - Добавить `const val RESOLUTION_2560 = 2560` (порядок: ORIGINAL, 2560, 1920).
   - `DEFAULT_MAX_RESOLUTION` остаётся `RESOLUTION_ORIGINAL`.

2. **`app/src/main/java/com/compressphotofast/util/SettingsManager.kt`** (`getMaxResolution`, ~строка 104)
   - Миграция: если прочитанное значение == 1280, перезаписать его на `DEFAULT_MAX_RESOLUTION` и вернуть дефолт.

3. **`app/src/main/res/values/strings.xml`** (~строки 20–24)
   - Удалить `resolution_1280`.
   - Добавить `<string name="resolution_2560">2560 px (QHD)</string>`.

4. **`app/src/main/res/layout/activity_main.xml`** (~строки 248–278)
   - Удалить RadioButton `rbResolution1280`.
   - Добавить RadioButton `android:id="@+id/rbResolution2560"` с текстом `@string/resolution_2560` (стиль как у существующих, позиция — между «Оригинал» и «1920 px»).

5. **`app/src/main/java/com/compressphotofast/ui/MainActivity.kt`** (`setupResolutionRadioButtons`, строки 700–721)
   - В обоих `when` и в listener: убрать ветки `RESOLUTION_1280` / `rbResolution1280`, добавить `RESOLUTION_2560` / `rbResolution2560`.

6. **Тесты**
   - `app/src/test/java/com/compressphotofast/util/ImageCompressionUtilResolutionTest.kt`: кейсы с `RESOLUTION_1280` заменить на `RESOLUTION_2560` (уменьшение >2560, пропуск ≤2560, портрет).
   - Добавить unit-тест миграции в `SettingsManager` (сохранено 1280 → читается `RESOLUTION_ORIGINAL`), если есть существующий тестовый класс — проверить через glob; при отсутствии — отметить как ручную проверку.

## Риски / примечания
- WorkManager-задачи в очереди со старым значением 1280 продолжат выполняться с 1280 — допустимо (значение уже зафиксировано в input data); `computeScalePlan` универсальна, некорректных значений не возникнет.
- Старые сборки со значением 2560 в prefs: старый код трактует через `else` как «Оригинал» — приемлемо (downgrade-сценарий).
- Память: decode/admission-логика в `ImageCompressionUtil` уже ограничивает большой decode; 2560 безопасно.

## Валидация
- `./gradlew assembleDebug`.
- `./gradlew testDebugUnitTest` через навык `android-test-suite`.
- Вручную: выбрать 2560, перезапуск — выбор сохранён; preset 1280 из прошлой версии после обновления сбрасывается в «Оригинал»; фото >2560 уменьшается до 2560, ≤2560 — без изменения размеров; в UI отсутствует 1280.

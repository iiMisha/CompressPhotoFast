# План: фикс обрезки подписей кнопок в activity_main.xml

## Проблема
В `MaterialButtonToggleGroup` (качество сжатия и разрешение) кнопки имеют равный вес (`0dp` + `weight=1`), подписи вида «Среднее (70%)» / «2560 px (QHD)» не помещаются и обрезаются. У Material-кнопок по умолчанию `textAllCaps=true`, что усугубляет проблему.

## Решение (выбор пользователя: уменьшить шрифт)
Уменьшить `textSize` кнопок с 12sp до 11sp и явно отключить `textAllCaps`.

## Изменения
Файл: `app/src/main/res/layout/activity_main.xml`

Для всех 6 кнопок в двух группах:
- `rbQualityLow` (строка ~184)
- `rbQualityMedium` (~195)
- `rbQualityHigh` (~206)
- `rbResolutionOriginal` (~247)
- `rbResolution2560` (~258)
- `rbResolution1920` (~269)

1. `android:textSize="12sp"` → `android:textSize="11sp"`
2. Добавить `android:textAllCaps="false"` (важно: без этого caps съедает эффект уменьшения шрифта)

## Валидация
- `./gradlew assembleDebug` — сборка без ошибок.
- Визуально проверить на узком экране (Small Phone), что подписи «Среднее (70%)» и «2560 px (QHD)» помещаются.
- Юнит-тесты не затрагиваются (изменение только layout), запуск `testDebugUnitTest` не обязателен; при желании — через навык `android-test-suite`.

## Риски
- На экстремально узких экранах/крупном системном шрифте подписи всё же могут обрезаться; тогда вернуться к вопросу о переносе строк (out of scope сейчас).

# План: эффективность сжатия в уведомлениях — «% и в разах»

## Цель
В уведомлениях об эффективности сжатия показывать процент и дополнительно коэффициент в разах в максимально лаконичной форме, например: `-45% · 1.8x`.

Формула: `times = 100 / (100 - reductionPercent)`. Процент — целое (`%.0f`), коэффициент — `%.1f` c суффиксом `x` (короче, чем «в N раза»). Пример: `-45% · 1.8x`.

## Изменения

### 1. `app/src/main/java/com/compressphotofast/util/NotificationUtil.kt`
- Добавить helper: `private fun formatReduction(reductionPercent: Float): String` → например `"-45% · 1.8x"` (одним вызовом, целое `%` + `%.1fx` через `100f / (100f - reductionPercent)`).
- `showCompressionResultNotification` (~598–621): вместо отдельного `reductionStr` передавать компактную строку; строковые ресурсы упрощаются до одного плейсхолдера: успешное — `🖼️ файл: 4.2 MB → 2.3 MB (-45% · 1.8x)`, пропущенное — `📱 файл: экономия мала (-1% · 1.0x)`.
- `formatDailyStats` (~548–560): `notification_daily_stats_saved` → `Сэкономлено 12 MB (-45% · 1.8x)`.

### 2. `app/src/main/res/values/strings.xml`
Единственная локализация (values-* вариантов нет):
- `notification_compression_completed_text`: `🖼️ %1$s: %2$s → %3$s (%4$s)` — где `%4$s` = `-45% · 1.8x`
- `notification_compression_skipped_text`: `📱 %1$s: экономия мала (%2$s)`
- `notification_daily_stats_saved`: `Сэкономлено %1$s (%2$s)`

### 3. Проверить вызовы строковых ресурсов
- Грепнуть `notification_compression_completed_text`, `notification_compression_skipped_text`, `notification_daily_stats_saved` по `app/src` (включая тесты) — обновить все `getString(...)` под новый набор аргументов.
- Toast-сообщения (`showCompressionResultToast`, `showBatchToast`) НЕ менять — по решению пользователя.

### 3. Проверить вызовы строковых ресурсов
- Грепнуть `notification_compression_completed_text`, `notification_compression_skipped_text`, `notification_daily_stats_saved` по `app/src` (включая тесты) — обновить все места вызова `getString(...)` с новым аргументом.
- Toast-сообщения (`showCompressionResultToast`, `showBatchToast`) НЕ менять — по решению пользователя.

## Крайние случаи
- reduction = 0 → `-0% · 1.0x`; близко к 100 → коэффициент корректно растёт.
- Формат `x` (латиница) вместо «раза» — компромисс ради лаконичности, выбран пользователем.

## Валидация
- `./gradlew assembleDebug`.
- Unit-тесты через навык `android-test-suite` (проверить, нет ли тестов, проверяющих текст уведомлений/строки).

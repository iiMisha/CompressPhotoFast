# Суточная статистика сжатия в уведомлении

## Цель и решения

- Заменить итоговые уведомления `CompressionBatchTracker` единым уведомлением со статистикой за текущие локальные календарные сутки.
- Учитывать только успешно сохранённые и прошедшие проверку целостности сжатые файлы; пропуски, ошибки и retry в агрегат не включать.
- После каждого успешного сжатия показывать: число фото, суммарный исходный и итоговый объёмы, абсолютную и процентную экономию.
- Обновлять один notification ID всегда бесшумно. При смене суток лениво начать новый агрегат при следующем успешном сжатии; старое уведомление до этого не перепланировать.
- Сохранить существующие batch Toast и уведомления об ошибках. Старые индивидуальные и групповые result-уведомления из batch-потока больше не публиковать.

## Реализация

1. Добавить персистентный суточный агрегат в `app/src/main/java/com/compressphotofast/util/StatsTracker.kt`.
   - Ввести immutable snapshot `DailyCompressionStats(epochDay, successfulCount, totalOriginalBytes, totalCompressedBytes)` с вычисляемыми `savedBytes` и общим процентом сокращения по суммам.
   - Добавить синхронизированные операции чтения и `recordSuccessfulCompression(context, originalSize, compressedSize, epochDay = LocalDate.now().toEpochDay()): DailyCompressionStats?`.
   - В одной read-modify-write секции сравнивать сохранённый `epochDay` с текущим, обнулять значения при несовпадении, прибавлять один результат и сохранять все поля одним `SharedPreferences.Editor.commit()` до публикации уведомления.
   - Не изменять агрегат при неположительных размерах; при невалидных данных или неуспешном `commit()` вернуть `null` и залогировать причину, чтобы не публиковать неперсистентную или ложную статистику.

2. Описать хранение и идентификаторы в `app/src/main/java/com/compressphotofast/util/Constants.kt`.
   - Использовать отдельный prefs-файл и ключи даты, количества, исходного и итогового объёмов.
   - Переименовать семантику ID `10` с batch summary на daily stats, сохранив числовое значение, чтобы новое уведомление заменяло старое после первого сжатия.
   - Добавить отдельный ID канала суточной статистики.
   - Исключить файл суточной статистики из cloud backup и device transfer в `app/src/main/res/xml/backup_rules.xml` и `app/src/main/res/xml/data_extraction_rules.xml`: это локальная эфемерная статистика, а не пользовательская настройка.

3. Реализовать суточное уведомление в `app/src/main/java/com/compressphotofast/util/NotificationUtil.kt` и строки в `app/src/main/res/values/strings.xml`.
   - Создать отдельный канал с `IMPORTANCE_LOW`, без звука, вибрации и подсветки; не переиспользовать high-importance completion channel и foreground silent channel.
   - Добавить `showDailyCompressionNotification(context, stats)`, проверяющий разрешение через существующий `canShowNotifications()`.
   - Заголовок: `Сегодня сжато: N фото`. Текст/`BigTextStyle`: `исходный объём → итоговый объём` и `Сэкономлено X (Y%)`, используя `FileOperationsUtil.formatFileSize()` и процент от суммарных байтов.
   - Использовать постоянный daily notification ID, `setSilent(true)`, `setOnlyAlertOnce(true)`, low priority, `autoCancel` и существующий переход в `MainActivity`.
   - Удалить ставшие ненужными batch-summary builder, `showBatchCompressionNotification()` и `BatchNotificationItem`; не затрагивать error/foreground notifications.

4. Подключить обновление в `app/src/main/java/com/compressphotofast/worker/ImageCompressionWorker.kt`.
   - Сразу после успешной `verifyImageIntegrity()` получить фактический размер `savedUri` с существующим fallback на результат тестового сжатия.
   - В единственной точке вызвать `StatsTracker.recordSuccessfulCompression(...)`, затем при ненулевом результате `NotificationUtil.showDailyCompressionNotification(...)` с возвращённым snapshot.
   - Расположить вызов до попытки удалить оригинал: сжатый файл уже валиден, поэтому ветка `deleteFailed` также учитывается ровно один раз; integrity failure, inefficient skip и retry до этой точки не учитываются.
   - Повторно использовать вычисленный `compressedSize` ниже для batch/Toast результата, не выполнять второй учёт в `sendCompressionStatusNotification()`.

5. Отключить старые result-уведомления batch-потока в `app/src/main/java/com/compressphotofast/util/CompressionBatchTracker.kt`.
   - Для одного результата оставить текущий Toast, но убрать `showIndividualNotification()`.
   - Для нескольких результатов оставить `showBatchToast()`, но убрать `showBatchNotifications()`.
   - Батчи, таймауты и накопление результатов пока сохранить, поскольку они нужны для группировки Toast и не входят в эту задачу.
   - Legacy-путь Worker без `batchId` не расширять и не удалять в рамках задачи; основные production-входы назначают batch ID. Суточное уведомление всё равно публикуется независимо от batch ID.

## Тесты и проверка

1. Расширить `app/src/test/java/com/compressphotofast/util/StatsTrackerTest.kt` на Robolectric:
   - первый успех создаёт агрегат;
   - несколько успехов в один epoch day суммируют количество и байты;
   - другой epoch day начинает значения с нуля;
   - повторное чтение получает сохранённые данные;
   - конкурентные обновления не теряют increments;
   - некорректные размеры не изменяют snapshot.
2. Добавить Robolectric-тест `NotificationUtil` с `ShadowNotificationManager`: канал суточной статистики бесшумный/low importance, уведомление опубликовано под ID `10`, title/text отражают актуальный snapshot и повторная публикация заменяет его.
3. Обновить `CompressionBatchTrackerTest`: удалить моки старых notification API и явно подтвердить, что финализация single/multi batch сохраняет Toast-поведение без result-уведомления.
4. Проверить в коде Worker сценарии: обычный success и success с ошибкой удаления дают один update; integrity failure, skip и retry дают ноль updates.
5. Через обязательный skill `android-test-suite` запустить только unit-тесты (`./gradlew testDebugUnitTest`). Затем отдельно собрать `./gradlew assembleDebug` и проверить компиляцию androidTest через `./gradlew compileDebugAndroidTestKotlin`; instrumentation не требуется, поскольку Activity/Receiver/UI-поведение не меняется.

## Границы и риски

- Текущее уведомление предыдущего дня остаётся в shade до следующего успешного сжатия согласно выбранному ленивому сбросу.
- Если пользователь удалил уведомление, следующее успешное сжатие создаст его снова.
- Смена даты или часового пояса начинает новый агрегат при несовпадении `epochDay`; обратного объединения суток нет.
- Ошибка сохранения prefs должна логироваться и не превращать успешное сжатие в failed WorkManager result; уведомление в таком случае строить только из подтверждённого сохранённого snapshot, не из неперсистентного предположения.
- Миграция данных не нужна: прежняя batch-статистика была только в памяти процесса и не имела совместимого persisted state.

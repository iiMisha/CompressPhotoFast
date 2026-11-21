# Прогрессбар и отмена восстановления EXIF-дат

Модальный диалог с determinate-прогрессбаром, счётчиками и кнопкой «Отмена»
(прерывание после текущего файла). Прогресс живёт в `StateFlow` (переживает
поворот активити), отмена — штатная отмена `CoroutineWorker` через
`WorkManager.cancelUniqueWork`.

## Изменения

### 1. `domain/ExifDateFixUseCase.kt`
- Добавить `data class Progress(scanned, total, fixed, noSource, failed)` —
  `total == 0` означает «скан галереи» (indeterminate).
- `val progress: StateFlow<Progress?>` (`MutableStateFlow`, `@Singleton` уже есть):
  - `_progress.value = Progress(0,0,0,0,0)` на старте `fixAll`;
  - обновление на каждой итерации цикла;
  - `_progress.value = null` в `finally` (в т.ч. при отмене).
- Чекпоинт отмены на каждой итерации до `executionGate.acquire()`:
  `currentCoroutineContext().ensureActive()` — gate при свободном mutex не
  приостанавливает, без этого отмена задержится до следующей suspend-точки.
- CE продолжает пробрасываться (уже есть); частичный итог доступен из
  `progress.value` на момент отмены.

### 2. `domain/CompressionEvents.kt`
- `DateFixCompleted` + поле `cancelled: Boolean = false`.

### 3. `worker/ExifDateFixWorker.kt`
- Проблема снапшота: `finally` use case сбрасывает `progress` в null ДО того,
  как CE дойдёт до catch воркера — читать `progress.value` в воркере поздно.
  Решение: use case хранит `var lastOutcome: Progress?` — заполняется в конце
  `fixAll` (успех) и в catch CE (частичный итог) до rethrow; воркер читает его.
- `doWork`: try/catch `CancellationException` — до rethrow эмитить
  `DateFixCompleted(cancelled = true, fixed/noSource/failed из
  useCase.lastOutcome ?: нули)` (`tryEmit` — не suspend, работает в
  отменённой корутине).
- Companion: `WORK_NAME` → `internal const`, добавить
  `fun cancel(context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)`.
- Companion: `WORK_NAME` → `internal const`, добавить
  `fun cancel(context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)`.

### 4. `ui/MainViewModel.kt`
- Инъекция `ExifDateFixUseCase`; `val dateFixProgress = useCase.progress`.
- `fun cancelExifDateFix()` → `ExifDateFixWorker.cancel(getApplication())`.
- `scheduleExifDateFix()` без изменений.

### 5. UI `ui/MainActivity.kt` + layout
- Новый `res/layout/dialog_date_fix_progress.xml`: TextView сообщение,
  горизонтальный `ProgressBar` (`?android:attr/progressBarStyleHorizontal`),
  TextView счётчиков.
- `showDateFixDialog()` (подтверждение): positive больше не показывает Snackbar
  «запущено» — диалог прогресса откроется из StateFlow.
- В `observeViewModel` новый collect `viewModel.dateFixProgress`:
  - non-null и диалог не показан → `MaterialAlertDialogBuilder`,
    `setCancelable(false)`, negative «Отмена» (`android.R.string.cancel`) →
    `viewModel.cancelExifDateFix()`, кнопка disable + текст
    «Прерываем после текущего файла…»;
  - обновление: `total == 0` → indeterminate + «Сканирование галереи…», иначе
    max=total, progress=scanned, текст «Обработано X из Y · записано Z»;
  - null → dismiss.
- Диалог хранится в поле; пересоздание при повороте — из того же collect
  (StateFlow в STARTED). Ссылку на диалог обнулять в `onDestroy`.
- Ветка `DateFixCompleted`: `cancelled == true` → Snackbar «Прервано…» (новая
  строка), иначе существующие строки. Диалог к этому моменту уже закрыт
  (progress → null в finally use case).

### 6. `res/values/strings.xml`
- `date_fix_progress_scanning` «Сканирование галереи…»
- `date_fix_progress_counts` «Обработано %1$d из %2$d · записано: %3$d»
- `date_fix_cancelling` «Прерываем после текущего файла…»
- `date_fix_cancelled` «⏸ Прервано: даты восстановлены %1$d файлам · без источников: %2$d · ошибки: %3$d»
- `date_fix_started` удалить (больше не используется).

## Инварианты
- Отмена безопасна: запись каждого файла атомарна (`guardedExifWrite`, откат
  под `NonCancellable`, CE пробрасывается — уже проверено), отмена срабатывает
  только между файлами; проход идемпотентен (повторный запуск пропустит
  датированные).
- REPLACE-политика `exif_date_fix` при повторном запуске отменяет текущий
  проход — согласовано с ручной отменой.
- Гибель процесса: WorkManager перезапустит ENQUEUED-воркер; диалог не
  восстановится (нет replay) — приемлемо, как у событий сжатия.

## Тесты (`testDebugUnitTest`)
- `ExifDateFixUseCaseTest`: жизненный цикл progress (null → scanning(0,0) →
  рост scanned/fixed → null); отмена: отменяющаяся корутина → CE проброшен,
  `lastOutcome` содержит частичные счётчики, progress == null.
- Существующие тесты не ломаются (конструктор use case не меняется).

## Проверка
1. `./gradlew assembleDebug`
2. `./gradlew testDebugUnitTest`
3. Обновить AGENTS.md навыком `agents-updater` (буллет про date-fix: диалог
   прогресса + отмена).
4. Опубликовать debug-APK навыком `apk` (изменён runtime-код).

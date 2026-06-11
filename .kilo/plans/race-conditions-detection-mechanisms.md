# Анализ конкуренции в механизмах обнаружения новых файлов

## Обзор архитектуры обнаружения

В приложении работают **4 параллельных источника обнаружения** новых изображений, которые могут одновременно найти один и тот же URI:

```
┌─────────────────────────────────────────────────────────────────┐
│                   ИСТОЧНИКИ ОБНАРУЖЕНИЯ                         │
│                                                                 │
│  1. MediaStoreObserver ─────┐                                   │
│     (ContentObserver,       │                                   │
│      задержка 10 сек)       │                                   │
│                             ▼                                   │
│  2. scanForNewImages ───► processNewImage(uri) ──► handleImage  │
│     (периодический, 15 мин)         ▲            (IPU)          │
│                                     │                           │
│  3. scanGalleryForUnprocessed ──────┘    │                      │
│     (начальное сканирование, 48 ч)       ▼                      │
│                                   WorkManager                   │
│  4. ImageDetectionJobService          очередь                   │
│     (JobScheduler,                       │                     │
│      ContentTrigger)                      ▼                     │
│                                   ImageCompressionWorker        │
└─────────────────────────────────────────────────────────────────┘
```

### Ключевые защитные механизмы от дублей

| Механизм | Где | Тип синхронизации |
|----------|------|-------------------|
| `addProcessingUriSafe` | `UriProcessingTracker` | Per-URI Mutex |
| `shouldIgnore` | `UriProcessingTracker` | ConcurrentHashMap + volatile timestamp |
| `isImageBeingProcessed` | `UriProcessingTracker` | ConcurrentHashMap read |
| `perUriTag` check | `ImageProcessingUtil` | WorkManager query |
| `enqueueUniqueWork` | `ImageProcessingUtil` | WorkManager guarantee |
| `scanMutex` | `BackgroundMonitoringService` | Coroutine Mutex |
| `recentlyObservedUris` | `MediaStoreObserver` | ConcurrentHashMap |
| `isRunning` flag | `BackgroundMonitoringService` | Volatile |

---

## Обнаруженные проблемы конкуренции

### RC-1: КРИТИЧЕСКОЕ — TOCTOU в `ImageProcessingUtil.handleImage` при проверке WorkManager

**Файл:** `ImageProcessingUtil.kt:92-113`

```kotlin
// Проверка (не атомарна с enqueue)
val alreadyQueued = WorkManager.getInstance(context)
    .getWorkInfosByTag(perUriTag).get()     // ШАГ 1: проверка
    .any { it.state == ENQUEUED || RUNNING }

if (alreadyQueued) { return }

// ... подготовка inputData ...

WorkManager.getInstance(context)             // ШАГ 3: enqueue
    .enqueueUniqueWork("sequential_image_compression",
        ExistingWorkPolicy.APPEND_OR_REPLACE, ...)
```

**Проблема:** Между шагом 1 (проверка) и шагом 3 (enqueue) другой поток может:
- Пройти ту же проверку (`alreadyQueued = false`)
- Застатьить оба потока enqueue одну и ту же работу

**Смягчение:** `enqueueUniqueWork` с `APPEND_OR_REPLACE` предотвращает фактический дубликат. `addProcessingUriSafe` в Worker также защитит от двойного сжатия.

**Вероятность:** Низкая (нужны два потока в ~100мс окне для одного URI)
**Влияние:** Среднее (Waste ресурсов на enqueue + Worker, но без двойного сжатия)

**Рекомендация:** Убрать проверку `alreadyQueued` и полагаться исключительно на `addProcessingUriSafe` + `enqueueUniqueWork`. Или обернуть всю проверку+enqueue в per-URI Mutex.

---

### RC-2: СРЕДНЕЕ — Небезопасный drain `pendingBatch` в `ImageDetectionJobService`

**Файл:** `ImageDetectionJobService.kt:172-192`

```kotlin
pendingBatch.addAll(triggerUris)  // <-- БЕЗ synchronized!

// ...
synchronized(pendingBatch) {       // <-- Lock на wrapper object
    batchToProcess.addAll(pendingBatch)
    pendingBatch.clear()
}
```

**Проблема:** `addAll()` не входит в `synchronized(pendingBatch)`. ConcurrentHashMap обеспечивает потокобезопасность отдельных операций, но `drain + clear` — составная операция. Новые URI могут добавляться:
1. Во время `batchToProcess.addAll(pendingBatch)` — частично захватятся
2. Между `addAll` и `clear` — будут потеряны (clear удалит)

**Влияние:** Низкое (потерянные URI будут обнаружены при следующем JobService trigger или ContentObserver). Двойная обработка невозможна (защита downstream).

**Рекомендация:** Обернуть и `addAll`, и drain в один `synchronized(pendingBatch)`:
```kotlin
synchronized(pendingBatch) {
    pendingBatch.addAll(triggerUris)
    debounceJob?.cancel()
    debounceJob = jobScope.launch {
        delay(DEBOUNCE_DELAY_MS)
        val batchToProcess = mutableSetOf<Uri>()
        synchronized(pendingBatch) {
            batchToProcess.addAll(pendingBatch)
            pendingBatch.clear()
        }
        // process...
    }
}
```

---

### RC-3: СРЕДНЕЕ — `isRunning` flag и переходный период Service ↔ JobService

**Файлы:** `BackgroundMonitoringService.kt:56-59`, `ImageDetectionJobService.kt:116-121`

```kotlin
// BackgroundMonitoringService
@Volatile var isRunning: Boolean = false  // set в onCreate/onDestroy

// ImageDetectionJobService
if (BackgroundMonitoringService.isRunning) {
    // Пропускаем обработку
    jobFinished(params, false)
    return false
}
```

**Проблема:** Два сценария race condition:

1. **Запуск Service:** `isRunning = true` в `onCreate`, но ContentObserver ещё не зарегистрирован → JobService видит `true`, пропускает, но ContentObserver ещё не ловит события → **потеря событий**.

2. **Остановка Service:** `isRunning = false` в `onDestroy`, но корутины `processNewImage` ещё выполняются → JobService видит `false`, начинает обработку того же URI → **дублирование enqueue**.

**Влияние:** Среднее. В сценарии 2 защита downstream (`addProcessingUriSafe`) предотвращает двойное сжатие. В сценарии 1 события будут обработаны при следующем periodic scan.

**Рекомендация:** Заменить простой boolean на `AtomicBoolean` + дополнительная проверка `uriProcessingTracker.isImageBeingProcessed(uri)` в JobService перед обработкой.

---

### RC-4: СРЕДНЕЕ — Stale URI cleanup vs Worker takeover

**Файлы:** `UriProcessingTracker.kt:123-143`, `ImageCompressionWorker.kt:99-114`

```
Таймлайн:
1. ImageProcessingUtil.handleImage() → addProcessingUriSafe(uri) → успех
2. URI добавлен в processingUris, timestamp записан
3. WorkManager enqueue (Worker ещё не запущен)
4. ...проходит время...
5. cleanupStaleUris() срабатывает (STALE_URI_THRESHOLD = 15 мин)
6. URI удаляется из processingUris
7. ContentObserver/scan находит тот же URI → handleImage() → addProcessingUriSafe() → УСПЕХ (uri был удалён!)
8. Worker #1 стартует → addProcessingUriSafe() → true (takeover) → isLockOwner = true
9. Worker #2 стартует → addProcessingUriSafe() → false → пропускает
```

**Проблема:** Если Worker долго ждёт в очереди WorkManager (>15 мин), URI может быть очищен из processingUris, позволяя повторный enqueue. Два Worker'а будут в очереди для одного URI.

**Смягчение:** Worker #2 пропустит через `addProcessingUriSafe() → false`. Пер-URI tag в WorkManager и так не проверяется при enqueue (только `enqueueUniqueWork` по общему имени цепочки).

**Влияние:** Низкое (Worker #2 пропустит, но ресурсы на enqueue потрачены).

**Рекомендация:** Увеличить `STALE_URI_THRESHOLD` до 30-60 минут или добавить проверку per-URI tag перед `cleanupStaleUris`.

---

### RC-5: НИЗКОЕ — TOCTOU в `processNewImage` между проверками и `handleImage`

**Файл:** `BackgroundMonitoringService.kt:362-392`

```kotlin
private suspend fun processNewImage(uri: Uri) {
    // Шаг 1: Проверка shouldIgnore
    if (uriProcessingTracker.shouldIgnore(uri)) return
    // Шаг 2: Проверка isImageBeingProcessed
    if (uriProcessingTracker.isImageBeingProcessed(uri)) return
    // Шаг 3: handleImage (внутри addProcessingUriSafe — реальная защита)
    val result = ImageProcessingUtil.handleImage(applicationContext, uri)
}
```

**Проблема:** Между шагом 2 и шагом 3 другой поток может добавить URI в обработку. Но `addProcessingUriSafe` внутри `handleImage` — истинная защита.

**Влияние:** Очень низкое. Ранние проверки — оптимизация (early exit), не критический путь.

**Рекомендация:** Добавить комментарий, что проверки — best-effort optimization.

---

### RC-6: НИЗКОЕ — `MediaStoreObserver.observerScope` cancel и retry jobs

**Файл:** `MediaStoreObserver.kt:252-266`

```kotlin
fun unregister() {
    // ...
    observerScope.cancel()  // Отменяем scope
}
```

Retry jobs запускаются в `handlerScope` (статический shared scope), но existence-проверка выполняется в `observerScope`. После `unregister()`:
- Retry job в `handlerScope` продолжает работать
- `observerScope.launch` внутри `processUriWithRetry` получает `CancellationException`
- `catch (e: Exception)` поглощает CancellationException — это нарушает structured concurrency

**Проблема:** Retry jobs продолжают работать после уничтожения observer, расходуя ресурсы.

**Влияние:** Низкое (ограничено maxRetries = 4).

**Рекомендация:** В `processUriWithRetry`, catch `CancellationException` отдельно и не логировать как ошибку. Или проверить `isActive` перед retry.

---

### RC-7: НИЗКОЕ — Redundant `shouldIgnore` check

**Файлы:** `BackgroundMonitoringService.kt:373-375`, `UriProcessingTracker.kt:412-422`

`processNewImage` проверяет `shouldIgnore(uri)` → затем `isImageBeingProcessed(uri)`. Но `isImageBeingProcessed` ВНУТРИ также вызывает `shouldIgnoreUri`:

```kotlin
suspend fun isImageBeingProcessed(uri: Uri): Boolean {
    val isProcessing = processingUris.contains(uriString)
    val isIgnored = shouldIgnoreUri(uriString)      // <-- ДУБЛИРУЕТ проверку
    val isRecentlyProcessed = isUriRecentlyProcessed(uriString)
    return isProcessing || isIgnored || isRecentlyProcessed
}
```

**Проблема:** `shouldIgnore` вызывается дважды для одного URI в одном методе.

**Влияние:** Очень низкое (трата ресурсов на ConcurrentHashMap reads).

**Рекомендация:** Убрать отдельный `shouldIgnore` check из `processNewImage`, так как `isImageBeingProcessed` уже включает эту проверку.

---

### RC-8: НИЗКОЕ — `OptimizedCacheUtil` cache stampede

**Файл:** `OptimizedCacheUtil.kt:176-213`

```kotlin
// Fast path (read lock)
exifCacheLock.read { val cached = exifCache.get(key) ... }

// Slow path: compute WITHOUT lock
val computed = compute()  // <-- Несколько потоков могут вычислять одновременно

// Write with double-check
exifCacheLock.write { ... put(key, computed) }
```

**Проблема:** При cache miss несколько потоков одновременно вычисляют EXIF данные для одного URI. Это cache stampede — ресурсы тратятся на повторные вычисления.

**Влияние:** Низкое (EXIF чтение — fast operation, double-check write предотвращает перезапись свежего кэша).

**Рекомендация:** Приемлемо для данного случая. Если EXIF чтение станет дорогостоящим, можно использовать per-key compute lock.

---

## Сводная таблица рисков

| ID | Серьёзность | Вероятность | Влияние | Рекомендация |
|----|------------|-------------|---------|-------------|
| RC-1 | КРИТИЧЕСКОЕ | Низкая | Среднее | Обернуть check+enqueue в Mutex |
| RC-2 | СРЕДНЕЕ | Средняя | Низкое | synchronized на addAll + drain |
| RC-3 | СРЕДНЕЕ | Средняя | Среднее | AtomicBoolean + isImageBeingProcessed check |
| RC-4 | СРЕДНЕЕ | Низкая | Низкое | Увеличить STALE_URI_THRESHOLD |
| RC-5 | НИЗКОЕ | Высокая | Очень низкое | Комментарий (best-effort) |
| RC-6 | НИЗКОЕ | Средняя | Низкое | Catch CancellationException отдельно |
| RC-7 | НИЗКОЕ | 100% | Очень низкое | Убрать дублирующий check |
| RC-8 | НИЗКОЕ | Средняя | Очень низкое | Приемлемо |

---

## План исправлений

### Шаг 1: Исправить RC-1 — Per-URI Mutex для check+enqueue

**Файл:** `ImageProcessingUtil.kt`

Заменить блок проверки + enqueue на атомарную операцию с использованием существующего `uriProcessingTracker.withUriLock()`:

```kotlin
val perUriTag = "compress_${uri.hashCode()}"

// Атомарная проверка + enqueue внутри per-URI Mutex
return uriProcessingTracker.withUriLock(uri) {
    val alreadyQueued = WorkManager.getInstance(context)
        .getWorkInfosByTag(perUriTag).get()
        .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

    if (alreadyQueued) {
        return@withUriLock Triple(true, false, "Изображение уже в очереди")
    }

    // ... enqueue ...
    Triple(true, true, "Сжатие запущено")
}
```

**Проблема с этим подходом:** `withUriLock` вызывает `addProcessingUri` внутри, что конфликтует с текущим вызовом `addProcessingUriSafe` в handleImage. Нужен рефакторинг.

**Альтернативный вариант (проще):** Убрать `alreadyQueued` check совсем и положиться на `enqueueUniqueWork` + `addProcessingUriSafe` в Worker.

### Шаг 2: Исправить RC-2 — synchronized pendingBatch

**Файл:** `ImageDetectionJobService.kt`

Обернуть `pendingBatch.addAll` в synchronized:

```kotlin
synchronized(pendingBatch) {
    pendingBatch.addAll(triggerUris)
}
```

### Шаг 3: Исправить RC-3 — Защита при переходе Service ↔ JobService

**Файл:** `ImageDetectionJobService.kt`

Добавить проверку `isImageBeingProcessed` в дополнение к `isRunning`:

```kotlin
// В processOptimizedBatch, для каждого URI:
if (uriProcessingTracker.isImageBeingProcessed(uri)) {
    LogUtil.processDebug("JobService: URI уже обрабатывается Service, пропуск")
    skippedCount++
    continue
}
```

### Шаг 4: Исправить RC-4 — Увеличить STALE_URI_THRESHOLD

**Файл:** `UriProcessingTracker.kt`

```kotlin
// Было:
private val STALE_URI_THRESHOLD = 15 * 60 * 1000L
// Станет:
private val STALE_URI_THRESHOLD = 30 * 60 * 1000L  // 30 минут
```

### Шаг 5: Исправить RC-6 — CancellationException в MediaStoreObserver

**Файл:** `MediaStoreObserver.kt`

```kotlin
observerScope.launch {
    try {
        // ...
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Scope отменён — нормальное поведение при destroy
        throw e  // Пробрасываем для structured concurrency
    } catch (e: Exception) {
        LogUtil.error(...)
    }
}
```

### Шаг 6: Исправить RC-7 — Убрать дублирующий shouldIgnore

**Файл:** `BackgroundMonitoringService.kt`

Убрать отдельный `shouldIgnore` check из `processNewImage`, так как `isImageBeingProcessed` включает его:

```kotlin
private suspend fun processNewImage(uri: Uri) {
    // shouldIgnore уже проверяется внутри isImageBeingProcessed
    if (uriProcessingTracker.isImageBeingProcessed(uri)) return
    // ...
}
```

---

## Вывод

Текущая архитектура имеет **многоуровневую защиту** от двойной обработки (processingUris → ignorePeriod → recentlyProcessed → EXIF marker → WorkManager tag). Основные race conditions не приводят к **дублированию сжатия** (самое страшное последствие), но могут вызывать:
- Лишние enqueue в WorkManager (ресурсы)
- Потерю событий при переходных состояниях (обработается при следующем scan)
- Лишние проверки EXIF при cache stampede

Наиболее важные исправления: **RC-1** (per-URI Mutex для handleImage) и **RC-3** (защита при Service lifecycle переходах).

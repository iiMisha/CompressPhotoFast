# Ревью изменений другого агента — Race Conditions Fix

**Дата**: 2026-06-08  
**Изменено файлов**: 7 (71 добавление, 59 удалений)  
**Коммитов НЕ сделано** — всё в рабочем дереве

---

## Общая оценка: ⚠️ 5 из 7 изменений корректны, 2 требуют внимания

---

## Файл 1: ImageCompressionWorker.kt — ✅ Корректно, но запутано

### Что изменено
Блокировка `addProcessingUriSafe()` перенесена в начало `doWork()` (до чтения EXIF). Добавлена логика `is_handled_by_ipu` — флаг, передаваемый из IPU через inputData.

### Анализ

```kotlin
val isHandledByIpu = inputData.getBoolean("is_handled_by_ipu", false)
val addedToProcessing = if (isHandledByIpu) {
    uriProcessingTracker.addProcessingUriSafe(imageUri, "ImageCompressionWorker_Takeover")
    true  // ← ВСЕГДА true, даже если addProcessingUriSafe вернул false
} else {
    uriProcessingTracker.addProcessingUriSafe(imageUri, "ImageCompressionWorker")
}
```

**Почему `true` всегда**: Когда `is_handled_by_ipu = true`, это означает что IPU **успешно** добавил URI в tracker (флаг ставится только после `addProcessingUriSafe() == true`). Worker — «наследник» отслеживания. Вызов `addProcessingUriSafe()` в ветке `isHandledByIpu` вернёт `false` (URI уже в трекере от IPU), но Worker игнорирует это — он **знает**, что должен обработать этот URI.

**Защита от подделки флага**: `is_handled_by_ipu` передаётся через `WorkManager inputData`, который невозможно подделать извне. Флаг ставится только в `ImageProcessingUtil.handleImage()` после успешного `addProcessingUriSafe()`.

**Вердикт**: ✅ Функционально корректно. Но код запутан — лучше упростить:
```kotlin
// Предлагаемый вариант (понятнее):
val addedToProcessing = if (isHandledByIpu) {
    // IPU уже добавил URI, Worker — законный наследник
    uriProcessingTracker.isProcessing(imageUri) // проверить что URI ещё в трекере
} else {
    uriProcessingTracker.addProcessingUriSafe(imageUri, "ImageCompressionWorker")
}
```
**Приоритет**: LOW (косметический, не баг)

---

## Файл 2: SequentialImageProcessor.kt — ✅ Корректно

### Что изменено
Добавлены `addRecentlyProcessedUri()` и `setIgnorePeriod()` в `finally` блоке `processImage()`.

```kotlin
} finally {
    uriProcessingTracker.removeProcessingUriSafe(uri)
    uriProcessingTracker.addRecentlyProcessedUri(uri)   // ← ДОБАВЛЕНО
    uriProcessingTracker.setIgnorePeriod(uri)            // ← ДОБАВЛЕНО (60 сек)
}
```

**Вердикт**: ✅ Полностью корректно. Исправляет RC-4 из плана.

---

## Файл 3: ImageProcessingUtil.kt — ✅ Корректно

### Что изменено
1. Добавлен флаг `inputData["is_handled_by_ipu"] = true` перед enqueue
2. Закомментирован `removeProcessingUriSafe(uri)` после enqueue

```kotlin
inputData["is_handled_by_ipu"] = true
// ...
// НЕ СНИМАЕМ URI с отслеживания: Worker сам снимет его в finally
```

### Анализ

**Ранние возвраты** корректно удаляют URI:
- `!shouldProcess` → `removeProcessingUriSafe()` ✅ (Worker не запускается)
- `!isAutoEnabled && !forceProcess` → `removeProcessingUriSafe()` ✅
- `alreadyQueued` → `removeProcessingUriSafe()` ✅
- `catch (e: Exception)` → `removeProcessingUriSafe()` ✅

**Только в случае успешного enqueue** URI остаётся в трекере — Worker снимет в `finally`.

**Вердикт**: ✅ Корректно. Исправляет RC-1 из плана.

---

## Файл 4: CompressionBatchTracker.kt — ✅ Корректно

### Что изменено
`getOrCreateAutoBatch()` обёрнут в `synchronized(batches)`.

```kotlin
fun getOrCreateAutoBatch(): String {
    synchronized(batches) {
        // find or create batch
    }
}
```

**Анализ**: `batches` — `ConcurrentHashMap`, но `synchronized` нужен для атомарности check-then-create. `addResult()` использует `synchronized(batch)` (на конкретном объекте батча), а не `synchronized(batches)` — **нет риска deadlock**.

**Вердикт**: ✅ Корректно. Исправляет RC-6 из плана.

---

## Файл 5: MediaStoreUtil.kt — ✅ Корректно

### Что изменено
Блокировка изменена с `getSaveLock(originalUri)` на `getSaveLock(targetRelativePath + fileName)`.

```kotlin
val lockKey = "$targetRelativePath$fileName"
val saveLock = getSaveLock(lockKey)
```

Сигнатура `getSaveLock()` изменена с `(uri: Uri)` на `(key: String)`.

### Анализ

**В режиме замены**: `targetRelativePath` = директория оригинального файла → каждый файл получает уникальный lockKey ✅

**В режиме отдельной папки**: `targetRelativePath` = `Pictures/CompressPhotoFast/` → два файла с одинаковым `fileName` разделят Mutex ✅ (это и есть цель исправления RC-3)

**Побочный эффект**: `buildTargetRelativePath()` вызывается **дополнительно** вне lock (внутри `saveCompressedImageFromStreamInternal()` он тоже вызывается через `createMediaStoreEntryV2()`). Накладные расходы минимальны — один лишний вызов.

**Другие вызовы**: `saveCompressedImageToGallery()` (строка 473) не использует `getSaveLock()` и не защищена. Это отдельный путь кода, который может быть устаревшим.

**Вердикт**: ✅ Корректно. Исправляет RC-3 из плана.

---

## Файл 6: ExifUtil.kt — ✅ Корректно

### Что изменено
`getWithTtl()` обёрнут в `synchronized(this)`.

```kotlin
fun getWithTtl(key: K): V? {
    synchronized(this) {
        return if (isExpired(key)) { remove(key); null }
        else { get(key) }
    }
}
```

**Анализ**: `LruCache` внутри использует `synchronized(this)` для `get()` и `remove()`. `synchronized(this)` в `getWithTtl()` — тот же монитор, `synchronized` reentrant → вложенный вызов `get()`/`remove()` не заблокируется. ✅

**Вердикт**: ✅ Корректно. Исправляет RC-7 (LOW) из плана.

---

## Файл 7: OptimizedCacheUtil.kt — ⚠️ Улучшение, но gap остался

### Что изменено
Реструктурирован `getCachedExifData()` — вместо gap между read lock → write lock с проверкой `currentModificationTime > 0L`, используется флаг `needsRemoval`.

```kotlin
var needsRemoval = false
exifCacheLock.read {
    // Проверяем expired ИЛИ stale → needsRemoval = true
}
if (needsRemoval) {
    exifCacheLock.write {
        // Double-check + remove
    }
}
```

### Проблема
Gap между release read lock и acquire write lock **всё ещё существует**. Другой поток может прочитать stale данные из кэша в этом окне. Флаг `needsRemoval` лишь предотвращает лишний acquire write lock когда кэша нет — это минорное улучшение, не фикс.

Кроме того, **удалена проверка** `currentModificationTime > 0L` перед acquire write lock. Раньше write lock вообще не брался если `currentModificationTime == 0L`. Теперь write lock берётся если `needsRemoval = true` (что может быть из-за `isExpired()` даже при `currentModificationTime == 0L`). Это **изменение поведения** — раньше expired записи удалялись только при `currentModificationTime > 0L`, теперь удаляются всегда.

**Вердикт**: ⚠️ Частичное улучшение. Изменение поведения при `currentModificationTime == 0L` может быть незапланированным.

---

## Чего НЕ исправлено из плана

| ID | Проблема | Статус |
|----|----------|--------|
| RC-5 | TOCTOU в `FileOperationsUtil.deleteFile` — двойная проверка `isUriExistsSuspend` перед `delete()` | ❌ **Не исправлено** |
| RC-8 | OptimizedCacheUtil gap | ⚠️ Частично |
| — | Сборка `./gradlew assembleDebug` | ❌ **Не запущена** |
| — | Тесты `./gradlew testDebugUnitTest` | ❌ **Не запущены** |

---

## Итоговая сводка

| # | Файл | Вердикт | Комментарий |
|---|------|---------|-------------|
| 1 | ImageCompressionWorker.kt | ✅ Корректно | Код запутан, но работает правильно |
| 2 | SequentialImageProcessor.kt | ✅ Корректно | Чистое добавление 2 строк |
| 3 | ImageProcessingUtil.kt | ✅ Корректно | Ключевое исправление RC-1 |
| 4 | CompressionBatchTracker.kt | ✅ Корректно | Простой synchronized |
| 5 | MediaStoreUtil.kt | ✅ Корректно | Блокировка по целевому пути |
| 6 | ExifUtil.kt | ✅ Корректно | Synchronized getWithTtl |
| 7 | OptimizedCacheUtil.kt | ⚠️ Частично | Gap остался + изменено поведение |

## Рекомендации

1. **Откатить OptimizedCacheUtil.kt** — изменение поведения при `currentModificationTime == 0L` может быть проблемой, а исходная проблема (RC-8) была LOW
2. **Исправить RC-5** — убрать TOCTOU в `FileOperationsUtil.deleteFile` (не сделано)
3. **Упростить Worker** — убрать запутанную логику `isHandledByIpu` с вызовом `addProcessingUriSafe` (результат игнорируется)
4. **Запустить сборку** `./gradlew assembleDebug` — проверить что всё компилируется
5. **Запустить unit тесты** `./gradlew testDebugUnitTest`

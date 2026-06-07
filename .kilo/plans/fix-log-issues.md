# План: Исправление проблем из анализа логов

## Проблема 1: Дублирование EXIF-проверок при сканировании

### Диагноз

В логах (~12% URI) `ImageProcessingChecker.isProcessingRequired()` вызывается дважды для одного URI из параллельных потоков (GalleryScanUtil + MainViewModel). Оба вызова проходят через раздельные `getCachedExifData()` → `cacheExifData()`, но между read и write нет атомарности — оба потока получают `null` из кэша и делают независимый EXIF-запрос.

**Следствие**: 2× EXIF-запроса, 2× `cacheExifData()`, 2× "EXIF-данные закэшированы" в логах.

### Решение

В `ImageProcessingChecker.isProcessingRequired()` (строки 244-258) заменить раздельные вызовы `getCachedExifData()` + `cacheExifData()` на `getOrComputeExifData()`, который уже реализует double-check locking в `OptimizedCacheUtil`.

**Файл**: `ImageProcessingChecker.kt`

```kotlin
// БЫЛО:
val cachedExifData = OptimizedCacheUtil.getCachedExifData(uri, modificationTimestamp)
val (isCompressed, quality, compressionTimestamp) = if (cachedExifData != null) {
    PerformanceMonitor.recordCacheHit("EXIF")
    Triple(cachedExifData.isCompressed, cachedExifData.quality, cachedExifData.compressionTimestamp)
} else {
    PerformanceMonitor.recordCacheMiss("EXIF")
    PerformanceMonitor.measureExifCheck {
        val exifResult = ExifUtil.getCompressionMarker(context, uri)
        OptimizedCacheUtil.cacheExifData(uri, exifResult.first, exifResult.second, exifResult.third, modificationTimestamp)
        exifResult
    }
}

// СТАНЕТ:
val exifData = OptimizedCacheUtil.getOrComputeExifData(uri, modificationTimestamp) {
    PerformanceMonitor.recordCacheMiss("EXIF")
    PerformanceMonitor.measureExifCheck {
        val exifResult = ExifUtil.getCompressionMarker(context, uri)
        CachedExifData(exifResult.first, exifResult.second, exifResult.third, modificationTimestamp)
    }
}
if (exifData != null) {
    PerformanceMonitor.recordCacheHit("EXIF")
}
val isCompressed = exifData?.isCompressed ?: false
val quality = exifData?.quality ?: -1
val compressionTimestamp = exifData?.compressionTimestamp ?: 0L
```

**Примечание**: `getOrComputeExifData` уже логирует "EXIF-данные закэшированы" через `cacheExifData` — нет, он не логирует. Нужно либо убрать лог из `cacheExifData` и добавить в `getOrComputeExifData`, либо оставить как есть. Лучше перенести лог в `getOrComputeExifData` после `put`.

---

## Проблема 2: MediaStoreObserver планирует обработку для own-изменений

### Диагноз

Когда приложение сжимает файл и записывает результат через MediaStore, ContentObserver срабатывает **немедленно**. На этот момент URI ещё НЕ в ignore list (`setIgnorePeriod()` вызывается только после завершения сжатия). Observer планирует обработку через 10 сек, что ведёт к:
- 10 сек delay + ненужный пробуждение
- EXIF-запрос (`getCompressionMarker`) в delayJob (строка 115-117)
- Проверка "свежий маркер" (строка 118) — спасает от повторного сжатия, но ресурсы уже потрачены

### Решение

Добавить в `MediaStoreObserver.onChange()` раннюю проверку `uriProcessingTracker.isProcessing(uri)` — если URI сейчас обрабатывается приложением, пропустить планирование.

**Файл**: `MediaStoreObserver.kt`, метод `onChange()`, после строки 63

```kotlin
// После проверки shouldIgnore:
if (uriProcessingTracker.isProcessing(it)) {
    LogUtil.processDebug("MediaStoreObserver: URI $it пропущен, так как сейчас обрабатывается приложением.")
    return
}
```

Это подавит ~100 ненужных планирований при массовой обработке (по одному на каждый сжатый файл).

---

## Порядок выполнения

1. Исправить `ImageProcessingChecker.kt` — заменить `getCachedExifData` + `cacheExifData` на `getOrComputeExifData`
2. Исправить `MediaStoreObserver.kt` — добавить проверку `isProcessing()` в `onChange()`
3. Сборка `./gradlew assembleDebug` для проверки компиляции

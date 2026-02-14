# Примеры оптимизации: Collections

## Unnecessary Filtering

**Before:**
```kotlin
val photos = getAllPhotos()
val compressed = photos.filter { it.isCompressed }
val large = photos.filter { it.size > 1_000_000 }
// Two iterations
```

**After:**
```kotlin
val (compressed, others) = getAllPhotos().partition { it.isCompressed }
val large = compressed.filter { it.size > 1_000_000 }
// Single iteration for partition
```

**Почему:** `partition` делает одну итерацию вместо двух `filter`.

---

## Inefficient String Concatenation

**Before:**
```kotlin
fun buildPhotoInfo(photo: Photo): String {
    var result = ""
    result += "Name: " + photo.name + "\n"
    result += "Size: " + photo.size + "\n"
    result += "Date: " + photo.date + "\n"
    return result  // Creates many String objects
}
```

**After:**
```kotlin
fun buildPhotoInfo(photo: Photo): String {
    return buildString {
        appendLine("Name: ${photo.name}")
        appendLine("Size: ${photo.size}")
        appendLine("Date: ${photo.date}")
    }
}
```

**Почему:** `buildString` использует StringBuilder, создаёт только 1 объект.

---

## Repeated Collection Operations

**Before:**
```kotlin
val validPhotos = photos
    .filter { it.size > 0 }
    .filter { it.name.isNotBlank() }
    .filter { it.date != null }
// Three iterations
```

**After:**
```kotlin
val validPhotos = photos.filter { photo ->
    photo.size > 0 && photo.name.isNotBlank() && photo.date != null
}
// Single iteration
```

**Почему:** Объединение условий в один `filter` уменьшает итерации.

---

## Unnecessary Collection Copy

**Before:**
```kotlin
fun processPhotos(photos: List<Photo>): List<Result> {
    val copy = photos.toList()  // Unnecessary copy!
    return copy.map { process(it) }
}
```

**After:**
```kotlin
fun processPhotos(photos: List<Photo>): List<Result> {
    return photos.map { process(it) }  // No copy needed
}
```

**Почему:** `toList()` создаёт новую копию без необходимости.

---

## Inefficient Map Lookup

**Before:**
```kotlin
data class PhotoKey(val albumId: Long, val photoId: Long)

fun findPhoto(map: Map<PhotoKey, Photo>, albumId: Long, photoId: Long): Photo? {
    return map.keys.find { it.albumId == albumId && it.photoId == photoId }?.let { map[it] }
    // O(n) lookup!
}
```

**After:**
```kotlin
data class PhotoKey(val albumId: Long, val photoId: Long) {
    override fun equals(other: Any?) = other is PhotoKey &&
        albumId == other.albumId && photoId == other.photoId

    override fun hashCode() = 31 * albumId.hashCode() + photoId.hashCode()
}

fun findPhoto(map: Map<PhotoKey, Photo>, albumId: Long, photoId: Long): Photo? {
    return map[PhotoKey(albumId, photoId)]
    // O(1) lookup!
}
```

**Почему:** Правильные `equals`/`hashCode` дают O(1) lookup вместо O(n).

---

## Sequence vs Collection for Large Data

**Before:**
```kotlin
fun processLargeFile(file: File): List<String> {
    return file.readLines()  // Loads entire file into memory!
        .filter { it.isNotBlank() }
        .map { it.trim() }
        .take(100)
}
```

**After:**
```kotlin
fun processLargeFile(file: File): List<String> {
    return file.useLines { lines ->  // Lazy, doesn't load all
        lines.asSequence()
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .take(100)
            .toList()
    }
}
```

**Почему:** Sequence (lazy) обрабатывает элементы по одному, уменьшая память.

---

## Inefficient GroupBy

**Before:**
```kotlin
val grouped = photos.groupBy { it.albumId }
val totalSize = grouped.values.sumOf { albumPhotos ->
    albumPhotos.sumOf { it.size }
}
// Creates intermediate collections
```

**After:**
```kotlin
val totalSize = photos.groupingBy { it.albumId }
    .foldTo(0L) { acc, photo ->
        acc + photo.size
    }
    .values.sum()
// No intermediate collections
```

**Почему:** `groupingBy` + `foldTo` избегают создания промежуточных коллекций.

---

## Repeated Sort Operation

**Before:**
```kotlin
val sortedByName = photos.sortedBy { it.name }
val sortedBySize = photos.sortedBy { it.size }
val sortedByDate = photos.sortedBy { it.date }
// Three iterations
```

**After:**
```kotlin
val sortedByName = photos.sortedBy { it.name }
val sortedBySize = sortedByName.sortedBy { it.size }  // Chain sorts
val sortedByDate = sortedBySize.sortedBy { it.date }
// Still creates intermediate lists, but at least reusable
```

**Или лучше:**
```kotlin
fun comparePhotos(a: Photo, b: Photo): Int {
    val nameCmp = a.name.compareTo(b.name)
    if (nameCmp != 0) return nameCmp

    val sizeCmp = a.size.compareTo(b.size)
    if (sizeCmp != 0) return sizeCmp

    return a.date.compareTo(b.date)
}

val sorted = photos.sortedWith(::comparePhotos)
// One sort, multiple criteria
```

**Почему:** Один `sortedWith` с компаратором быстрее чем множественные сортировки.

# Примеры оптимизации: Performance

## Synchronous File Read on UI

**Before:**
```kotlin
class MainActivity : AppCompatActivity() {
    private fun loadPhotos() {
        val files = File(path).listFiles()  // BLOCKS MAIN THREAD!
        processFiles(files)
    }
}
```

**After:**
```kotlin
class MainActivity : AppCompatActivity() {
    private fun loadPhotos() {
        lifecycleScope.launch(Dispatchers.IO) {
            val files = File(path).listFiles()
            withContext(Dispatchers.Main) {
                processFiles(files)
            }
        }
    }
}
```

**Почему:** File I/O на IO dispatcher освобождает UI thread.

---

## Heavy Image Decoding on Main

**Before:**
```kotlin
class PhotoProcessor {
    fun processImage(uri: Uri): Bitmap {
        val stream = contentResolver.openInputStream(uri)
        return BitmapFactory.decodeStream(stream)  // May block!
    }
}
```

**After:**
```kotlin
class PhotoProcessor {
    suspend fun processImage(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val stream = contentResolver.openInputStream(uri)
        BitmapFactory.decodeStream(stream)
    }
}
```

**Почему:** Декодирование изображений - тяжёлая операция, должна быть в фоне.

---

## OutOfMemoryError Prevention

**Before:**
```kotlin
fun decodeImage(path: String): Bitmap {
    return BitmapFactory.decodeFile(path)  // May OOM on large images
}
```

**After:**
```kotlin
fun decodeImage(path: String): Bitmap? {
    return BitmapFactory.Options().run {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, this)

        inSampleSize = calculateInSampleSize(this, 1024, 1024)
        inJustDecodeBounds = false

        BitmapFactory.decodeFile(path, this)
    }
}

private fun calculateInSampleSize(
    options: BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int
): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2

        while (halfHeight / inSampleSize >= reqHeight
               && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}
```

**Почему:** `inSampleSize` уменьшает размер декодированного изображения, предотвращая OOM.

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

## Nested Loops Optimization

**Before:**
```kotlin
fun findDuplicates(photos: List<Photo>): List<Pair<Photo, Photo>> {
    val duplicates = mutableListOf<Pair<Photo, Photo>>()
    for (i in photos.indices) {
        for (j in i + 1 until photos.size) {
            if (photos[i].hash == photos[j].hash) {
                duplicates.add(photos[i] to photos[j])
            }
        }
    }
    return duplicates
}
// O(n²) complexity
```

**After:**
```kotlin
fun findDuplicates(photos: List<Photo>): List<Pair<Photo, Photo>> {
    val hashMap = HashMap<String, MutableList<Photo>>()

    for (photo in photos) {
        hashMap.getOrPut(photo.hash) { mutableListOf() }.add(photo)
    }

    return hashMap.values
        .filter { it.size > 1 }
        .flatMap { list ->
            list.flatMap { a -> list.map { b -> a to b } }
        }
}
// O(n) complexity
```

**Почему:** HashMap вместо вложенных циклов даёт линейную сложность.

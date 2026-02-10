# Примеры оптимизации Android кода

## Memory Leaks

### Context Leak in Singleton

**Before:**
```kotlin
object ImageManager {
    private lateinit var context: Context

    fun init(ctx: Context) {
        context = ctx  // LEAK! Holds Activity reference
    }
}
```

**After:**
```kotlin
object ImageManager {
    private lateinit var appContext: Context

    fun init(ctx: Context) {
        appContext = ctx.applicationContext  // Safe
    }
}
```

### CoroutineScope Leak in Fragment

**Before:**
```kotlin
class PhotoListFragment : Fragment() {
    private val scope = CoroutineScope(Dispatchers.Main)  // LEAK!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        scope.launch {
            // Never cancelled when fragment is destroyed
        }
    }
}
```

**After:**
```kotlin
class PhotoListFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Automatically cancelled when fragment view is destroyed
        }
    }
}
```

## Main Thread Blocking

### Synchronous File Read on UI

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

### Heavy Image Decoding

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

## Image Processing

### OutOfMemoryError Prevention

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

### Proper Bitmap Recycling

**Before:**
```kotlin
fun compressAndSave(bitmap: Bitmap, output: File) {
    bitmap.compress(CompressFormat.JPEG, 85, FileOutputStream(output))
    // Bitmap not recycled!
}
```

**After:**
```kotlin
fun compressAndSave(bitmap: Bitmap, output: File) {
    try {
        FileOutputStream(output).use { out ->
            bitmap.compress(CompressFormat.JPEG, 85, out)
        }
    } finally {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}
```

## Coroutines

### Wrong Dispatcher for I/O

**Before:**
```kotlin
class PhotoRepository {
    suspend fun loadPhotos(): List<Photo> = withContext(Dispatchers.Main) {
        // Database query on Main dispatcher!
        photoDao.getAll()
    }
}
```

**After:**
```kotlin
class PhotoRepository {
    suspend fun loadPhotos(): List<Photo> = withContext(Dispatchers.IO) {
        photoDao.getAll()  // Database on IO dispatcher
    }
}
```

### Structured Concurrency

**Before:**
```kotlin
class CompressViewModel : ViewModel() {
    private val jobs = mutableListOf<Job>()  // Manual management

    fun compressPhotos(uris: List<Uri>) {
        uris.forEach { uri ->
            jobs.add(viewModelScope.launch {
                compress(uri)
            })
        }
    }

    override fun onCleared() {
        jobs.forEach { it.cancel() }  // Easy to forget!
    }
}
```

**After:**
```kotlin
class CompressViewModel : ViewModel() {
    fun compressPhotos(uris: List<Uri>) {
        viewModelScope.launch {
            // Structured: all children cancelled when parent is cancelled
            uris.map { uri ->
                async { compress(uri) }
            }.awaitAll()
        }
    }
    // viewModelScope automatically cancelled onCleared()
}
```

## Database

### N+1 Query Problem

**Before:**
```kotlin
@Dao
interface PhotoDao {
    @Query("SELECT * FROM albums")
    suspend fun getAlbums(): List<Album>

    @Query("SELECT * FROM photos WHERE albumId = :albumId")
    suspend fun getPhotosForAlbum(albumId: Long): List<Photo>
}

// Usage (N+1 queries):
suspend fun loadAlbumsWithPhotos(): List<AlbumWithPhotos> {
    return albumDao.getAlbums().map { album ->
        AlbumWithPhotos(album, photoDao.getPhotosForAlbum(album.id))
    }
}
```

**After:**
```kotlin
@Dao
interface AlbumDao {
    @Transaction
    @Query("SELECT * FROM albums")
    suspend fun getAlbumsWithPhotos(): List<AlbumWithPhotos>
}

@Transaction
data class AlbumWithPhotos(
    @Embedded val album: Album,
    @Relation(parentColumn = "id", entityColumn = "albumId")
    val photos: List<Photo>
}

// Usage (1 query):
suspend fun loadAlbumsWithPhotos() = albumDao.getAlbumsWithPhotos()
```

### Missing Transaction

**Before:**
```kotlin
suspend fun updatePhoto(photo: Photo) {
    photoDao.update(photo)          // Query 1
    photoDao.updateThumbnail(photo) // Query 2
    // Not atomic!
}
```

**After:**
```kotlin
@Transaction
suspend fun updatePhoto(photo: Photo) {
    photoDao.update(photo)
    photoDao.updateThumbnail(photo)
    // Atomic transaction
}
```

## UI Performance

### Missing DiffUtil

**Before:**
```kotlin
class PhotoAdapter : RecyclerView.Adapter<PhotoViewHolder>() {
    fun updateItems(newItems: List<Photo>) {
        items = newItems
        notifyDataSetChanged()  // Inefficient!
    }
}
```

**After:**
```kotlin
class PhotoAdapter : RecyclerView.Adapter<PhotoViewHolder>() {
    fun updateItems(newItems: List<Photo>) {
        val diff = PhotoDiffCallback(items, newItems)
        val result = DiffUtil.calculateDiff(diff)
        items = newItems
        result.dispatchUpdatesTo(this)
    }
}

class PhotoDiffCallback(
    private val old: List<Photo>,
    private val new: List<Photo>
) : DiffUtil.Callback() {
    override fun getOldListSize() = old.size
    override fun getNewListSize() = new.size
    override fun areItemsTheSame(oldPos: Int, newPos: Int) =
        old[oldPos].id == new[newPos].id
    override fun areContentsTheSame(oldPos: Int, newPos: Int) =
        old[oldPos] == new[newPos]
}
```

## Collections

### Inefficient String Concatenation

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

### Unnecessary Filtering

**Before:**
```kotlin
val photos = getAllPhotos()
val compressed = photos.filter { it.isCompressed }
val large = photos.filter { it.size > 1_000_000 }
// Two iterations
```

**After:**
```kotlin
val (compressed, large) = getAllPhotos()
    .partition { it.isCompressed }
val large = compressed.filter { it.size > 1_000_000 }
// Single iteration for partition
```

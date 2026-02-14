# Примеры оптимизации: Memory Leaks

## Context Leak in Singleton

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

**Почему:** `applicationContext` привязан к Application lifecycle, а не Activity.

---

## CoroutineScope Leak in Fragment

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

**Почему:** `lifecycleScope` автоматически отменяется при уничтожении lifecycle.

---

## Listener Leak in Activity

**Before:**
```kotlin
object PhotoDownloader {
    private val listeners = mutableListOf<DownloadListener>()

    fun addListener(listener: DownloadListener) {
        listeners.add(listener)  // LEAK! Never removed
    }
}

class MainActivity : AppCompatActivity(), DownloadListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        PhotoDownloader.addListener(this)  // Holds Activity reference
    }
}
```

**After:**
```kotlin
class MainActivity : AppCompatActivity(), DownloadListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        PhotoDownloader.addListener(this)
    }

    override fun onDestroy() {
        PhotoDownloader.removeListener(this)  // Clean up
        super.onDestroy()
    }
}
```

**Почему:** Явное удаление listener в lifecycle методе предотвращает утечку.

---

## Bitmap Memory Leak

**Before:**
```kotlin
class ImageProcessor {
    private val bitmapCache = mutableMapOf<String, Bitmap>()

    fun processImage(key: String, bitmap: Bitmap) {
        bitmapCache[key] = bitmap  // LEAK! Bitmaps never recycled
    }
}
```

**After:**
```kotlin
class ImageProcessor {
    private val bitmapCache = LruCache<String, Bitmap>(20 * 1024 * 1024) {  // 20MB

        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue.isRecycled.not()) {
                oldValue.recycle()
            }
        }
    }

    fun processImage(key: String, bitmap: Bitmap) {
        bitmapCache.put(key, bitmap)  // Auto-recycled on eviction
    }
}
```

**Почему:** LruCache автоматически управляет памятью и recycle'ит Bitmap'ы.

---

## Static Reference Leak

**Before:**
```kotlin
companion object {
    private var instance: MainActivity? = null  // LEAK!
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this  // Holds reference after destroy
    }
}
```

**After:**
```kotlin
// Use WeakReference if you really need it
companion object {
    private var instance: WeakReference<MainActivity>? = null

    fun setInstance(activity: MainActivity) {
        instance = WeakReference(activity)
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setInstance(this)
    }
}
```

**Почему:** WeakReference позволяет GC очистить объект когда он больше не нужен.

**Или лучше:** Используй ViewModel для хранения данных, не саму Activity.

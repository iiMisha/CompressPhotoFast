# Примеры оптимизации: Coroutines

## Wrong Dispatcher for I/O

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

**Почему:** Database операции блокируют, должны быть на IO dispatcher.

---

## Structured Concurrency

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

**Почему:** Structured concurrency гарантирует отмену всех детей при отмене родителя.

---

## Blocking Calls in Coroutines

**Before:**
```kotlin
suspend fun loadPhoto(path: String): ByteArray {
    Thread.sleep(1000)  // BLOCKS coroutine thread!
    return File(path).readBytes()
}
```

**After:**
```kotlin
suspend fun loadPhoto(path: String): ByteArray = withContext(Dispatchers.IO) {
    delay(1000)  // Non-blocking
    File(path).readBytes()
}
```

**Почему:** `delay` не блокирует поток, `Thread.sleep` - блокирует.

---

## Missing Exception Handling

**Before:**
```kotlin
fun compressPhoto(uri: Uri) {
    viewModelScope.launch {
        val result = compressUseCase(uri)
        _result.value = result
        // Uncaught exception crashes app!
    }
}
```

**After:**
```kotlin
fun compressPhoto(uri: Uri) {
    viewModelScope.launch {
        try {
            val result = compressUseCase(uri)
            _result.value = result
        } catch (e: CancellationException) {
            throw e  // Don't swallow cancellation
        } catch (e: Exception) {
            _error.value = e
            // Show error to user
        }
    }
}
```

**Почему:** Явная обработка ошибок предотвращает краш и позволяет показать ошибку пользователю.

---

## CoroutineScope Leak in Class

**Before:**
```kotlin
class PhotoProcessor {
    private val scope = CoroutineScope(Dispatchers.Default)  // LEAK!

    fun process(uri: Uri) {
        scope.launch {
            // Never cancelled!
        }
    }
}
```

**After:**
```kotlin
class PhotoProcessor(
    private val externalScope: CoroutineScope  // Inject from outside
) {
    fun process(uri: Uri) {
        externalScope.launch {
            // Managed by external scope
        }
    }

    fun destroy() {
        // No cleanup needed - external scope manages lifecycle
    }
}

// Usage:
class MyViewModel : ViewModel() {
    private val processor = PhotoProcessor(viewModelScope)

    override fun onCleared() {
        processor.destroy()
        super.onCleared()
    }
}
```

**Почему:** Внешний scope управляет lifecycle, не создаём собственный.

---

## Sequential vs Parallel Execution

**Before:**
```kotlin
suspend fun compressAll(uris: List<Uri>): List<Result> {
    return uris.map { uri ->
        compress(uri)  // Sequential, one by one
    }
}
```

**After:**
```kotlin
suspend fun compressAll(uris: List<Uri>): List<Result> = coroutineScope {
    uris.map { uri ->
        async { compress(uri) }  // Parallel
    }.awaitAll()
}
```

**Почему:** `async` + `awaitAll()` запускает все задачи параллельно, ускоряет выполнение.

---

## Context Switching Overhead

**Before:**
```kotlin
suspend fun process(uri: Uri) {
    withContext(Dispatchers.IO) { readMetadata(uri) }
    withContext(Dispatchers.IO) { validatePhoto(uri) }
    withContext(Dispatchers.IO) { compressPhoto(uri) }
    // 3 context switches
}
```

**After:**
```kotlin
suspend fun process(uri: Uri) = withContext(Dispatchers.IO) {
    readMetadata(uri)
    validatePhoto(uri)
    compressPhoto(uri)
    // 1 context switch
}
```

**Почему:** Один `withContext` вместо множества уменьшает overhead.

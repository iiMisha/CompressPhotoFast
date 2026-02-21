# Паттерны качества кода

## Code Style для CompressPhotoFast

### 1. Coroutines (КРИТИЧНО)

```kotlin
// ❌ НЕПРАВИЛЬНО
GlobalScope.launch {
    // операция
}

Handler().post {
    // операция
}

// ✅ ПРАВИЛЬНО
viewModelScope.launch {
    // операция
}

withContext(Dispatchers.IO) {
    // операция
}

lifecycleScope.launch {
    // операция
}
```

**Поиск:** `Grep("GlobalScope|Handler\\(\\)\\.post")`

### 2. Bitmap обработка (КРИТИЧНО)

```kotlin
// ❌ НЕПРАВИЛЬНО - нет recycle
val bitmap = BitmapFactory.decodeStream(inputStream)
// использование bitmap
// bitmap не освобождён!

// ✅ ПРАВИЛЬНО - с recycle
var bitmap: Bitmap? = null
try {
    bitmap = BitmapFactory.decodeStream(inputStream)
    // использование bitmap
} finally {
    bitmap?.recycle()
}

// ✅ ПРАВИЛЬНО - inSampleSize для экономии памяти
val options = BitmapFactory.Options().apply {
    inSampleSize = 4
    inPreferredConfig = Bitmap.Config.RGB_565
}
```

**Поиск:** `Grep("BitmapFactory\\.decode|Bitmap\\.createBitmap")`

### 3. Memory Leaks

```kotlin
// ❌ НЕПРАВИЛЬНО - leak через Context
object Singleton {
    var context: Context? = null  // leak!
}

// ❌ НЕПРАВИЛЬНО - leak через listener
class SomeClass {
    fun setup() {
        someView.addOnClickListener { 
            // не удаляется при destroy
        }
    }
}

// ✅ ПРАВИЛЬНО - weak reference или cleanup
class SomeClass {
    private var listener: View.OnClickListener? = null
    
    fun setup() {
        listener = View.OnClickListener { }
        someView.addOnClickListener(listener)
    }
    
    fun destroy() {
        someView.removeOnClickListener(listener)
        listener = null
    }
}
```

**Поиск:** `Grep("object.*\\{|var.*Context|addOnClickListener")`

### 4. Dispatchers

```kotlin
// ❌ НЕПРАВИЛЬНО - блокировка Main thread
fun loadData() {
    val data = heavyOperation()  // на Main thread!
}

// ✅ ПРАВИЛЬНО
suspend fun loadData() = withContext(Dispatchers.IO) {
    heavyOperation()
}

// ❌ НЕПРАВИЛЬНО - неправильный dispatcher
withContext(Dispatchers.Main) {
    file.writeText()  // I/O на Main!
}

// ✅ ПРАВИЛЬНО
withContext(Dispatchers.IO) {
    file.writeText()
}
```

**Поиск:** `Grep("Dispatchers\\.Main.*write|Dispatchers\\.Main.*read")`

### 5. Null Safety

```kotlin
// ❌ НЕПРАВИЛЬНО - force unwrap
val value = maybeNull!!

// ❌ НЕПРАВИЛЬНО - игнорирование null
val value = maybeNull ?: return  // без логирования

// ✅ ПРАВИЛЬНО
val value = maybeNull ?: run {
    LogUtil.warning(uri, "Operation", "Value is null")
    return
}

// ✅ ПРАВИЛЬНО - safe call
val result = maybeNull?.let { process(it) }
```

**Поиск:** `Grep("!!")` - много force unwrap может быть проблемой

### 6. Resource Management

```kotlin
// ❌ НЕПРАВИЛЬНО - нет закрытия
val stream = context.contentResolver.openInputStream(uri)
stream.read()

// ✅ ПРАВИЛЬНО - use{} для автоматического закрытия
context.contentResolver.openInputStream(uri)?.use { stream ->
    stream.read()
}

// ✅ ПРАВИЛЬНО - use{} для Cursor
context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
    // работа с cursor
}
```

**Поиск:** `Grep("openInputStream|openOutputStream|query\\(")`

## Категории проблем

| Категория | Severity | Примеры |
|-----------|----------|---------|
| **Memory** | Critical | Bitmap leaks, Context leaks, Listener leaks |
| **Threading** | High | Main thread blocking, Wrong dispatcher |
| **Resources** | High | Unclosed streams, Unclosed cursors |
| **Null Safety** | Medium | Force unwrap, Silent null handling |
| **Code Style** | Low | Naming, Formatting |

## Поиск через Grep

```bash
# Memory issues
Grep("GlobalScope")
Grep("Handler\\(\\)\\.post")
Grep("var.*Context\\?")

# Bitmap issues
Grep("BitmapFactory\\.decode")
Grep("!!")  # force unwrap

# Resource issues
Grep("openInputStream.*\\{")
Grep("query\\(.*\\{")

# Dispatcher issues
Grep("Dispatchers\\.Main")
```

## Отчёт о качестве

Для каждой проблемы указывать:
1. **Category** - категория проблемы
2. **Severity** - критичность
3. **Location** - файл и строка
4. **Problem** - описание
5. **Solution** - рекомендация
6. **Code** - пример исправления

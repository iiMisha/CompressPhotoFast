# Search Patterns для Android Optimization

**Table of Contents:**
- [Memory Leaks Patterns](#memory-leaks-patterns)
  - [Context Leaks](#context-leaks)
  - [CoroutineScope Leaks](#coroutinescope-leaks)
  - [Listener Leaks](#listener-leaks)
- [Main Thread Blocking Patterns](#main-thread-blocking-patterns)
  - [Direct File I/O on Main](#direct-file-io-on-main)
  - [Database on Main](#database-on-main)
  - [Heavy Computation on Main](#heavy-computation-on-main)
- [Image Processing Patterns](#image-processing-patterns)
  - [Bitmap Operations](#bitmap-operations)
  - [Compression](#compression)
  - [Image Loading](#image-loading)
- [Coroutine Patterns](#coroutine-patterns)
  - [Wrong Dispatcher](#wrong-dispatcher)
  - [Blocking Calls in Coroutines](#blocking-calls-in-coroutines)
  - [Missing Structured Concurrency](#missing-structured-concurrency)
- [Database Patterns](#database-patterns)
  - [N+1 Problem](#n1-problem)
  - [Missing Indexes](#missing-indexes)
  - [Missing Transactions](#missing-transactions)
- [UI Performance Patterns](#ui-performance-patterns)
  - [RecyclerView Issues](#recyclerview-issues)
  - [Layout Issues](#layout-issues)
  - [Overdraw Candidates](#overdraw-candidates)

---

## Memory Leaks Patterns

### Context Leaks

**Что искать:** Activity или Context удерживается после уничтожения (lateinit var, статические ссылки).

**Grep patterns:**
```regex
# Non-application Context held beyond scope
Grep: "Context.*lateinit|val.*Context.*=" in **/*.kt
Grep: "activity.*context|fragment.*context" in **/*.kt
```

**Как интерпретировать:**
- Если найден `lateinit var context: Context` без `applicationContext` - возможна утечка Activity
- Проверь: context используется в singleton/object? Используй `applicationContext`

### CoroutineScope Leaks

**Что искать:** GlobalScope или собственные CoroutineScope без привязки к lifecycle.

**Grep patterns:**
```regex
# GlobalScope usage (potential leak)
Grep: "GlobalScope\." in **/*.kt

# CoroutineScope without proper lifecycle
Grep: "CoroutineScope\(.*\)" in **/*.kt

# Uncleared jobs in ViewModels
Grep: "private.*Job|private.*CoroutineScope" in **/*ViewModel.kt
```

**Как интерпретировать:**
- `GlobalScope` - почти всегда утечка (живёт вечно)
- Собственный `CoroutineScope` во ViewModel/Fragment - должен отменяться в `onCleared()`/`onDestroyView()`
- Используй `lifecycleScope`, `viewModelScope`, `viewLifecycleOwner.lifecycleScope`

### Listener Leaks

**Что искать:** Статические listeners или забытые `remove*Listener`.

**Grep patterns:**
```regex
# Static listeners
Grep: "companion.*object.*Listener|object.*Listener" in **/*.kt

# Uncleared listeners
Grep: "addOn.*Listener|setOn.*Listener" in **/*.kt
# Check for missing removeOn/removeListener
```

**Как интерпретировать:**
- Статический listener с ссылкой на Activity/Fragment - утечка
- Найди пары: `addListener` → `removeListener`
- Проверь onDestroy/onPause/onStopView - вызывается ли `remove*Listener`?

---

## Main Thread Blocking Patterns

### Direct File I/O on Main

**Что искать:** Чтение/запись файлов напрямую в Activity/Fragment без корутин.

**Grep patterns:**
```regex
Grep: "\.read.*\(|\.write.*\(|File\(.*\)" in **/*Activity.kt
Grep: "\.read.*\(|\.write.*\(|File\(.*\)" in **/*Fragment.kt
```

**Как интерпретировать:**
- Найденный код выполняется на UI thread → блокировка интерфейса
- Перенеси в `lifecycleScope.launch(Dispatchers.IO)`
- Используй suspend функции с `withContext(Dispatchers.IO)`

### Database on Main

**Что искать:** SQL запросы без корутин в Activity/Fragment.

**Grep patterns:**
```regex
Grep: "\.query\(|\.insert\(|\.update\(|\.delete\(" in **/*.kt
# Check if called from Activity/Fragment without coroutine
```

**Как интерпретировать:**
- DAO/Room операции без suspend - блокируют UI
- Все Room операции должны быть suspend или возвращать Flow/LiveDataSource
- Используй `withContext(Dispatchers.IO)` для синхронных операций

### Heavy Computation on Main

**Что искать:** Вложенные циклы, декодирование изображений на UI thread.

**Grep patterns:**
```regex
Grep: "for.*\{.*for" in **/*.kt  # Nested loops
Grep: "BitmapFactory\.decode" in **/*.kt
Grep: "compress\(.*Quality\)" in **/*.kt
```

**Как интерпретировать:**
- Вложенные циклы (>2 уровней) в Activity/Fragment → вынеси в IO dispatcher
- `BitmapFactory.decode*` на main thread → ANR на больших изображениях
- Сжатие изображений → всегда в фоне

---

## Image Processing Patterns

### Bitmap Operations

**Что искать:** Декодирование, создание Bitmap без проверки размера.

**Grep patterns:**
```regex
Grep: "BitmapFactory\.decode" in **/*.kt
Grep: "decode.*Resource|decode.*File|decode.*Stream" in **/*.kt
Grep: "Bitmap\.create|\.copy\(" in **/*.kt
```

**Как интерпретировать:**
- `decode*` без `inSampleSize` → возможен OOM
- Отсутствует `inJustDecodeBounds` проверка → неизвестный размер изображения
- Используй `inSampleSize` для уменьшения размера декодированного изображения
- Большой Bitmap → вовремя recycle() или использую RGB_565

### Compression

**Что искать:** Сжатие изображений без оптимизаций.

**Grep patterns:**
```regex
Grep: "\.compress\(" in **/*.kt
Grep: "CompressFormat|JPEG|PNG" in **/*.kt
```

**Как интерпретировать:**
- JPEG с качеством 100 → избыточный размер
- PNG для фотографий → используй JPEG
- Проверь: вызывается ли `bitmap.recycle()` после сжатия?
- Используй `Bitmap.Config.RGB_565` если не нужна alpha channel

### Image Loading

**Что искать:** Ручная загрузка изображений без библиотек (Coil, Glide).

**Grep patterns:**
```regex
Grep: "load.*url|load.*path|load.*uri" in **/*.kt
Grep: "into\(.*ImageView\)" in **/*.kt
```

**Как интерпретировать:**
- Ручная загрузка через `BitmapFactory` + потоки → используй Coil/Glide
- Отсутствие кэширования → избыточные декодирования
- Отсутствие placeholder → мигание при загрузке

---

## Coroutine Patterns

### Wrong Dispatcher

**Что искать:** I/O операции на Main dispatcher, UI операции на IO.

**Grep patterns:**
```regex
Grep: "launch.*Dispatchers\.Main" in **/*.kt
# Check if file I/O or database inside

Grep: "withContext\(Dispatchers\.IO\)" in **/*.kt
# Check if UI operations inside
```

**Как интерпретировать:**
- File I/O в `Dispatchers.Main` блокирует UI
- Database в `Dispatchers.Main` → jank
- View operations в `Dispatchers.IO` → crash (Only original thread may touch views)
- Используй правильный dispatcher для задачи

### Blocking Calls in Coroutines

**Что искать:** `Thread.sleep`, `.wait()`, блокирующее чтение в корутине.

**Grep patterns:**
```regex
Grep: "Thread\.sleep|\.wait\(\)" in **/*.kt
Grep: "\.readBytes\(\)|\.readText\(\)" in **/*.kt
```

**Как интерпретировать:**
- `Thread.sleep` в корутине → блокирует поток dispatcher'а
- `readBytes()/readText()` → используй suspend варианты или IO dispatcher
- Замени на `delay()` для корутин

### Missing Structured Concurrency

**Что искать:** Запуск корутин без родительского scope.

**Grep patterns:**
```regex
Grep: "launch\s*{" in **/*.kt
# Check if parent scope is properly managed
```

**Как интерпретировать:**
- `launch { }` без viewModelScope/lifecycleScope → утечка или неуправляемая корутина
- `Job` хранится в поле → ручное управление (используя structured concurrency)
- Используй `async/await` для параллельных задач внутри одного scope

---

## Database Patterns

### N+1 Problem

**Что искать:** Циклы с SQL запросами внутри.

**Grep patterns:**
```regex
Grep: "@Query.*SELECT.*FROM" in **/dao/*.kt
Grep: "for.*\{.*dao\." in **/*.kt
```

**Как интерпретировать:**
- 1 запрос для списка + N запросов в цикле → N+1 проблема
- Используй `@Relation` для загрузки связанных данных одним запросом
- Или JOIN в одном запросе

### Missing Indexes

**Что искать:** Entity без `@Index` на часто запрашиваемых полях.

**Grep patterns:**
```regex
Grep: "@Entity|@Table" in **/entity/*.kt
# Check @ColumnInfo for indexed fields
```

**Как интерпретировать:**
- Поля в WHERE, JOIN, ORDER BY без индекса → медленные запросы
- Добавь `@Index(value = ["fieldName"])`
- Проверь EXPLAIN QUERY PLAN в SQLite

### Missing Transactions

**Что искать:** Несколько операций подряд без `@Transaction`.

**Grep patterns:**
```regex
Grep: "dao\.insert.*;.*dao\.insert|dao\.update.*;.*dao\.update" in **/*.kt
# Multiple operations without @Transaction
```

**Как интерпретировать:**
- Несколько INSERT/UPDATE подряд → каждая операция отдельная транзакция
- Оберни в `@Transaction` для атомарности
- Или используй Room transaction автоматически

---

## UI Performance Patterns

### RecyclerView Issues

**Что искать:** Отсутствие ViewHolder, `notifyDataSetChanged`.

**Grep patterns:**
```regex
Grep: "RecyclerView\.Adapter" in **/*.kt
# Check for ViewHolder pattern

Grep: "notifyDataSetChanged\(\)" in **/*.kt
# Should use DiffUtil
```

**Как интерпретировать:**
- Отсутствие ViewHolder → пересоздание views → jank
- `notifyDataSetChanged()` → перерисовка всего списка → используй DiffUtil
- Проверь: `setHasStableIds(true)` если ID известен

### Layout Issues

**Что искать:** Глубокая вложенность layouts, `findViewById`.

**Grep patterns:**
```regex
Glob("**/res/layout/*.xml")
# Check for deep nesting (6+ levels)

Grep: "findViewById" in **/*.kt
# Should use ViewBinding/data binding
```

**Как интерпретировать:**
- Вложенность >6 уровней → медленная отрисовка
- Используй `<merge>` или ConstraintLayout для уплощения
- `findViewById` → мигрируй на ViewBinding/DataBinding

### Overdraw Candidates

**Что искать:** Избыточные фоны, наложение views.

**Grep patterns:**
```regex
Grep: "setBackground.*Drawable|setBackgroundResource" in **/*.kt
# Check for redundant backgrounds
```

**Как интерпретировать:**
- Фон у родителя + фон у ребенка → overdrawing
- Проверь через Developer Options → "Show overdraw areas"
- Убирай избыточные фоны, используй `android:background="@null"`

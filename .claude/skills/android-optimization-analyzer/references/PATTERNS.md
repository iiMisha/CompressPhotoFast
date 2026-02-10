# Search Patterns для Android Optimization

## Memory Leaks Patterns

### Context Leaks
```regex
# Non-application Context held beyond scope
Grep: "Context.*lateinit|val.*Context.*=" in **/*.kt
Grep: "activity.*context|fragment.*context" in **/*.kt
```

### CoroutineScope Leaks
```regex
# GlobalScope usage (potential leak)
Grep: "GlobalScope\." in **/*.kt

# CoroutineScope without proper lifecycle
Grep: "CoroutineScope\(.*\)" in **/*.kt

# Uncleared jobs in ViewModels
Grep: "private.*Job|private.*CoroutineScope" in **/*ViewModel.kt
```

### Listener Leaks
```regex
# Static listeners
Grep: "companion.*object.*Listener|object.*Listener" in **/*.kt

# Uncleared listeners
Grep: "addOn.*Listener|setOn.*Listener" in **/*.kt
# Check for missing removeOn/removeListener
```

## Main Thread Blocking Patterns

### Direct File I/O on Main
```regex
Grep: "\.read.*\(|\.write.*\(|File\(.*\)" in **/*Activity.kt
Grep: "\.read.*\(|\.write.*\(|File\(.*\)" in **/*Fragment.kt
```

### Database on Main
```regex
Grep: "\.query\(|\.insert\(|\.update\(|\.delete\(" in **/*.kt
# Check if called from Activity/Fragment without coroutine
```

### Heavy Computation on Main
```regex
Grep: "for.*\{.*for" in **/*.kt  # Nested loops
Grep: "BitmapFactory\.decode" in **/*.kt
Grep: "compress\(.*Quality\)" in **/*.kt
```

## Image Processing Patterns

### Bitmap Operations
```regex
Grep: "BitmapFactory\.decode" in **/*.kt
Grep: "decode.*Resource|decode.*File|decode.*Stream" in **/*.kt
Grep: "Bitmap\.create|\.copy\(" in **/*.kt
```

### Compression
```regex
Grep: "\.compress\(" in **/*.kt
Grep: "CompressFormat|JPEG|PNG" in **/*.kt
```

### Image Loading
```regex
Grep: "load.*url|load.*path|load.*uri" in **/*.kt
Grep: "into\(.*ImageView\)" in **/*.kt
```

## Coroutine Patterns

### Wrong Dispatcher
```regex
Grep: "launch.*Dispatchers\.Main" in **/*.kt
# Check if file I/O or database inside

Grep: "withContext\(Dispatchers\.IO\)" in **/*.kt
# Check if UI operations inside
```

### Blocking Calls in Coroutines
```regex
Grep: "Thread\.sleep|\.wait\(\)" in **/*.kt
Grep: "\.readBytes\(\)|\.readText\(\)" in **/*.kt
```

### Missing Structured Concurrency
```regex
Grep: "launch\s*{" in **/*.kt
# Check if parent scope is properly managed
```

## Database Patterns

### N+1 Problem
```regex
Grep: "@Query.*SELECT.*FROM" in **/dao/*.kt
Grep: "for.*\{.*dao\." in **/*.kt
```

### Missing Indexes
```regex
Grep: "@Entity|@Table" in **/entity/*.kt
# Check @ColumnInfo for indexed fields
```

### Missing Transactions
```regex
Grep: "dao\.insert.*;.*dao\.insert|dao\.update.*;.*dao\.update" in **/*.kt
# Multiple operations without @Transaction
```

## UI Performance Patterns

### RecyclerView Issues
```regex
Grep: "RecyclerView\.Adapter" in **/*.kt
# Check for ViewHolder pattern

Grep: "notifyDataSetChanged\(\)" in **/*.kt
# Should use DiffUtil
```

### Layout Issues
```regex
Glob("**/res/layout/*.xml")
# Check for deep nesting (6+ levels)

Grep: "findViewById" in **/*.kt
# Should use ViewBinding/data binding
```

### Overdraw Candidates
```regex
Grep: "setBackground.*getDrawable|setBackgroundResource" in **/*.kt
# Check for redundant backgrounds
```

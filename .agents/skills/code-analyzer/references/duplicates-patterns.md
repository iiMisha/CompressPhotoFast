# Паттерны поиска дубликатов

## Типичные дубликаты в Android/Kotlin

### 1. Повторяющиеся try-catch блоки

```kotlin
// Дубликат 1
try {
    val result = someOperation()
    handleResult(result)
} catch (e: IOException) {
    LogUtil.error(uri, "Operation", e)
    return null
}

// Дубликат 2 (тот же паттерн в другом месте)
try {
    val data = anotherOperation()
    processData(data)
} catch (e: IOException) {
    LogUtil.error(uri, "Operation", e)
    return null
}
```

**Решение:** Вынести в общую функцию `runSafely()` или extension function.

### 2. URI операции

```kotlin
// Дубликат 1
val cursor = context.contentResolver.query(uri, projection, null, null, null)
cursor?.use {
    if (it.moveToFirst()) {
        val index = it.getColumnIndex(column)
        if (index >= 0) {
            return it.getString(index)
        }
    }
}

// Дубликат 2 (тот же паттерн)
val cursor = context.contentResolver.query(anotherUri, projection, null, null, null)
cursor?.use {
    if (it.moveToFirst()) {
        val index = it.getColumnIndex(anotherColumn)
        if (index >= 0) {
            return it.getString(index)
        }
    }
}
```

**Решение:** Создать `MediaStoreUtil.querySingleValue()`.

### 3. Bitmap обработка

```kotlin
// Дубликат 1
var bitmap: Bitmap? = null
try {
    bitmap = BitmapFactory.decodeStream(inputStream, null, options)
    // обработка
} finally {
    bitmap?.recycle()
}

// Дубликат 2
var bitmap: Bitmap? = null
try {
    bitmap = BitmapFactory.decodeFile(path, options)
    // обработка
} finally {
    bitmap?.recycle()
}
```

**Решение:** Создать `BitmapUtils.withBitmap()` extension.

### 4. Coroutines паттерны

```kotlin
// Дубликат 1
withContext(Dispatchers.IO) {
    try {
        // операция
    } catch (e: Exception) {
        LogUtil.error(null, "Tag", e)
        null
    }
}

// Дубликат 2
withContext(Dispatchers.IO) {
    try {
        // другая операция
    } catch (e: Exception) {
        LogUtil.error(null, "Tag", e)
        null
    }
}
```

**Решение:** Создать `runCatchingIO()` extension.

## Поиск дубликатов через Grep

```bash
# Поиск похожих try-catch блоков
Grep("try \\{[\\s\\S]*?catch.*IOException")

# Поиск ContentResolver.query паттернов
Grep("contentResolver\\.query")

# Поиск bitmap recycle паттернов
Grep("bitmap\\?\\.recycle\\(\\)")

# Поиск withContext(Dispatchers.IO)
Grep("withContext\\(Dispatchers\\.IO\\)")
```

## Методы обнаружения

### Структурный анализ
1. Сравнивать AST-подобные структуры блоков кода
2. Игнорировать имена переменных и литералы
3. Учитывать порядок операций

### Эвристические правила
1. Блоки >5 строк с >80% совпадением = дубликат
2. Функции с одинаковой сигнатурой и похожим телом
3. Повторяющиеся последовательности вызовов

### Ложные срабатывания
- Логически разные операции с похожей структурой
- Паттерны, обусловленные API (например, Cursor.use{})
- Стандартные идиомы языка

## Метрики дублирования

| Уровень | Сходство | Действие |
|---------|----------|----------|
| High | >90% | Обязательно рефакторить |
| Medium | 70-90% | Рассмотреть рефакторинг |
| Low | 50-70% | Возможно дублирование логики |

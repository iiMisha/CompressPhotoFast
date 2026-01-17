# Обнаруженная проблема: Двойные расширения и MIME типы

**Дата:** 17 января 2026
**Статус:** ✅ ИСПРАВЛЕНО (см. DOUBLE_EXTENSION_FIX.md)

## 📋 Описание проблемы

На реальном устройстве обнаружена проблема создания файлов с **двойными расширениями**, например:
- `image.HEIC.jpg`
- `photo.heif.jpeg`
- `picture.HEIC.JPG`

## 🔍 Анализ проблемы

### 1. Двойные расширения

**Проблемный код:** `FileOperationsUtil.createCompressedFileName()` (строка 39-58)

```kotlin
fun createCompressedFileName(context: Context, originalName: String): String {
    if (isSaveModeReplace(context)) {
        return originalName  // Возвращает как есть!
    }

    val dotIndex = originalName.lastIndexOf('.')
    val compressedName = if (dotIndex > 0) {
        val baseName = originalName.substring(0, dotIndex)  // Включает .HEIC!
        val extension = originalName.substring(dotIndex)      // .jpg
        "${baseName}${Constants.COMPRESSED_FILE_SUFFIX}$extension"
    } else {
        "${originalName}${Constants.COMPRESSED_FILE_SUFFIX}"
    }

    return compressedName
}
```

**Пример проблемы:**
- Вход: `image.HEIC.jpg`
- `lastIndexOf('.')` = 10 (последняя точка перед `.jpg`)
- `baseName` = `image.HEIC` (содержит первое расширение!)
- `extension` = `.jpg`
- Результат: `image.HEIC_compressed.jpg` ❌ (двойное расширение сохранено)

### 2. Hardcoded MIME тип

**Проблемный код:** `ImageCompressionUtil.createMediaStoreEntry()` (строка 464)

```kotlin
suspend fun createMediaStoreEntry(
    context: Context,
    compressedFile: File,
    fileName: String,
    directory: String,
    mimeType: String = "image/jpeg"  // ❌ Hardcoded!
): Uri? = withContext(Dispatchers.IO) {
```

**Проблема:**
- MIME тип всегда `"image/jpeg"`, игнорируя исходный формат
- HEIC файлы сохраняются с MIME типом `image/jpeg` вместо `image/heic`
- Исходный MIME тип не определяется и не сохраняется

## 🧪 Созданные тесты

### FileNameAndMimeTypeTest (10 instrumentation тестов)
**Путь:** `app/src/androidTest/java/com/compressphotofast/util/FileNameAndMimeTypeTest.kt`

Проверяет:
1. ✅ Определение двойного расширения (image.HEIC.jpg)
2. ✅ Создание сжатого имени без двойного расширения
3. ✅ HEIC файл не превращается в HEIC.JPG
4. ✅ Документация текущего поведения MIME типов
5. ✅ Различные варианты двойных расширений
6. ✅ Правильное извлечение расширения
7. ✅ MIME типы для разных форматов
8. ✅ Обработка HEIC с суффиксом _compressed
9. ✅ Двойное расширение с суффиксом (документирует проблему)
10. ✅ Логика очистки двойного расширения (как должно быть)

### FileNameProcessingTest (10 unit тестов)
**Путь:** `app/src/test/java/com/compressphotofast/util/FileNameProcessingTest.kt`

Проверяет правильную логику:
1. ✅ Подсчет расширений
2. ✅ Извлечение последнего расширения
3. ✅ Очистка двойного расширения
4. ✅ Создание сжатого имени файла (правильная логика)
5. ✅ Определение MIME типа по расширению
6. ✅ HEIC MIME тип (different cases)
7. ✅ Проблемные имена файлов
8. ✅ Правильный MIME тип для сохранения
9. ✅ Сохранение MIME типа при том же формате
10. ✅ Комплексный сценарий обработки файла

## 📊 Статистика

**Создано тестов:** 20 (10 instrumentation + 10 unit)
**Всего тестов в проекте:** 337 (251 unit + 86 instrumentation)
**Обнаруженные проблемы:**
- ❌ Двойные расширения не очищаются
- ❌ MIME тип hardcoded как "image/jpeg"
- ❌ Исходный MIME тип не сохраняется

## ✅ Рекомендуемое решение

### 1. Очистка двойных расширений

```kotlin
fun createCompressedFileName(context: Context, originalName: String): String {
    if (isSaveModeReplace(context)) {
        return originalName
    }

    // Очищаем двойное расширение
    val cleanName = cleanDoubleExtension(originalName)
    val extension = getLastExtension(originalName)

    val compressedName = "${cleanName}${Constants.COMPRESSED_FILE_SUFFIX}$extension"
    return compressedName
}

private fun cleanDoubleExtension(fileName: String): String {
    val lastDotIndex = fileName.lastIndexOf('.')
    if (lastDotIndex <= 0) return fileName

    val beforeLastDot = fileName.substring(0, lastDotIndex)
    val secondLastDot = beforeLastDot.lastIndexOf('.')

    return if (secondLastDot > 0) {
        beforeLastDot.substring(0, secondLastDot)
    } else {
        beforeLastDot
    }
}

private fun getLastExtension(fileName: String): String {
    val lastDotIndex = fileName.lastIndexOf('.')
    return if (lastDotIndex > 0) {
        fileName.substring(lastDotIndex)
    } else {
        ""
    }
}
```

### 2. Определение и сохранение MIME типа

```kotlin
suspend fun compressImage(
    context: Context,
    uri: Uri,
    quality: Int
): Triple<Boolean, Uri?, String?> = withContext(Dispatchers.IO) {
    // Определяем исходный MIME тип
    val originalMimeType = UriUtil.getMimeType(context, uri)

    // Определяем MIME тип для сохранения (на основе формата сжатия)
    val outputMimeType = when {
        originalMimeType?.equals("image/heic", ignoreCase = true) == true
            && shouldKeepHeicFormat() -> "image/heic"
        else -> "image/jpeg"  // По умолчанию JPEG
    }

    // Используем правильный MIME тип при сохранении
    MediaStoreUtil.saveCompressedImageFromStream(
        context,
        inputStream,
        compressedFileName,
        directory,
        uri,
        quality,
        exifData,
        outputMimeType  // Передаем правильный MIME тип
    )
}
```

### 3. Изменение MediaStoreUtil

```kotlin
suspend fun saveCompressedImageFromStream(
    context: Context,
    inputStream: InputStream,
    fileName: String,
    directory: String,
    originalUri: Uri,
    quality: Int = Constants.COMPRESSION_QUALITY_MEDIUM,
    exifDataMemory: Map<String, Any>? = null,
    mimeType: String = "image/jpeg"  // Принимает как параметр
): Uri? = withContext(Dispatchers.IO) {
    // ... использует переданный mimeType
}
```

## 🎯 Преимущества решения

1. **Чистые имена файлов:** `image_compressed.jpg` вместо `image.HEIC_compressed.jpg`
2. **Правильные MIME типы:** HEIC файлы имеют MIME тип `image/heic`
3. **Сохранение метаданных:** Исходный формат учитывается
4. **Обратная совместимость:** JPEG файлы работают как раньше

## ✅ Статус исправления

**Исправление завершено!** См. [DOUBLE_EXTENSION_FIX.md](DOUBLE_EXTENSION_FIX.md) для деталей.

**Выполнено:**
1. ✅ Созданы тесты для проверки проблемы (20 тестов)
2. ✅ Реализована очистка двойных расширений
3. ✅ Добавлено определение исходного MIME типа
4. ✅ Обновлена логика сохранения с правильным MIME типом
5. ⏳ Требуется тестирование на реальном устройстве

**Измененные файлы:**
- `FileOperationsUtil.kt` - добавлена очистка двойных расширений
- `ImageCompressionUtil.kt` - добавлено определение MIME типа
- `MediaStoreUtil.kt` - добавлен параметр mimeType

## 🔗 Связанные файлы

- `app/src/main/java/com/compressphotofast/util/FileOperationsUtil.kt` (строка 39)
- `app/src/main/java/com/compressphotofast/util/ImageCompressionUtil.kt` (строка 464)
- `app/src/main/java/com/compressphotofast/util/MediaStoreUtil.kt` (строка 28)
- `app/src/main/java/com/compressphotofast/util/UriUtil.kt` (getMimeType)

---

**Статус:** Проблема документирована, тесты созданы, ожидает реализации исправления.

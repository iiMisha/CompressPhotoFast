package com.compressphotofast.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Кэшированные Field объекты для рефлексии ByteArrayOutputStream.
 * Инициализируются лениво при первом использовании.
 */
private object StreamReflectionCache {
    val bufField: java.lang.reflect.Field by lazy {
        ByteArrayOutputStream::class.java.getDeclaredField("buf").apply {
            isAccessible = true
        }
    }

    val countField: java.lang.reflect.Field by lazy {
        ByteArrayOutputStream::class.java.getDeclaredField("count").apply {
            isAccessible = true
        }
    }
}

/**
 * Создаёт ByteArrayInputStream, который использует внутренний буфер ByteArrayOutputStream
 * напрямую через рефлексию, избегая лишнего копирования данных.
 *
 * Важно: после создания ByteArrayInputStream НЕ следует модифицировать исходный
 * ByteArrayOutputStream, так как это повлияет на InputStream.
 *
 * Пример использования:
 * ```kotlin
 * val outputStream = ByteArrayOutputStream()
 * // ... записываем данные ...
 *
 * // ❌ Создаёт копию 2-10 MB
 * val inputStream1 = ByteArrayInputStream(outputStream.toByteArray())
 *
 * // ✅ Использует буфер напрямую через рефлексию
 * val inputStream2 = outputStream.toInputStream()
 * ```
 *
 * @return ByteArrayInputStream, использующий тот же буфер памяти что и исходный ByteArrayOutputStream
 */
fun ByteArrayOutputStream.toInputStream(): ByteArrayInputStream {
    // Используем кэшированные Field объекты для оптимизации производительности
    val buffer = StreamReflectionCache.bufField.get(this) as ByteArray
    val count = StreamReflectionCache.countField.getInt(this)

    return ByteArrayInputStream(buffer, 0, count)
}

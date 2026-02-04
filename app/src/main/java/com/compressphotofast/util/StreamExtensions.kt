package com.compressphotofast.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Создаёт ByteArrayInputStream, который использует внутренний буфер ByteArrayOutputStream
 * напрямую через рефлексию, избегая лишнего копирования данных.
 *
 * Важно: после создания ByteArrayInputStream НЕ следует модифицировать原始ный
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
    // Используем рефлексию для доступа к protected полям
    val bufField = ByteArrayOutputStream::class.java.getDeclaredField("buf")
    bufField.isAccessible = true
    val buffer = bufField.get(this) as ByteArray

    val countField = ByteArrayOutputStream::class.java.getDeclaredField("count")
    countField.isAccessible = true
    val count = countField.getInt(this)

    return ByteArrayInputStream(buffer, 0, count)
}

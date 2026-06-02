package com.compressphotofast.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Создаёт ByteArrayInputStream из содержимого ByteArrayOutputStream.
 *
 * Использует безопасное копирование через toByteArray() вместо рефлексии.
 * Это гарантирует, что InputStream владеет своей копией данных и не зависит
 * от состояния исходного ByteArrayOutputStream.
 *
 * При массовой обработке изображений рефлексионный zero-copy подход приводил
 * к повреждению данных из-за shared buffer mutation.
 *
 * Пример использования:
 * ```kotlin
 * val outputStream = ByteArrayOutputStream()
 * // ... записываем данные ...
 *
 * val inputStream = outputStream.toInputStream()
 * // inputStream владеет своей копией — безопасно даже после закрытия outputStream
 * ```
 *
 * @return ByteArrayInputStream с независимой копией данных
 */
fun ByteArrayOutputStream.toInputStream(): ByteArrayInputStream {
    return ByteArrayInputStream(this.toByteArray())
}

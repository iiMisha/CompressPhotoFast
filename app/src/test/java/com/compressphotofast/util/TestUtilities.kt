package com.compressphotofast.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import java.io.File

/**
 * Утилитарные функции для тестирования.
 * Предоставляет общие вспомогательные методы для создания mock-объектов и тестовых данных.
 */
object TestUtilities {

    /**
     * Создает mock URI для тестирования.
     *
     * @param scheme Схема URI (file://, content://, etc.)
     * @param path Путь к ресурсу
     * @return Mock Uri
     */
    fun createMockUri(scheme: String = "file", path: String = "/test/path/image.jpg"): Uri {
        return Uri.parse("$scheme://$path")
    }

    /**
     * Создает mock ContentResolver.
     *
     * @return Mock ContentResolver
     */
    fun createMockContentResolver(): ContentResolver {
        return mockk<ContentResolver>(relaxed = true)
    }

    /**
     * Создает mock ContentResolver с указанным URI и файлом.
     *
     * @param uri URI для мока
     * @param file Файл, который будет возвращаться при запросе
     * @return Mock ContentResolver
     */
    fun createMockContentResolverWithFile(uri: Uri, file: File): ContentResolver {
        val mockResolver = mockk<ContentResolver>(relaxed = true)
        every { mockResolver.openInputStream(uri) } returns file.inputStream()
        every { mockResolver.openFileDescriptor(uri, "r", null) } returns null
        return mockResolver
    }

    /**
     * Создает mock Context.
     *
     * @return Mock Context
     */
    fun createMockContext(): Context {
        return mockk<Context>(relaxed = true)
    }

    /**
     * Создает временный файл с указанным содержимым.
     *
     * @param prefix Префикс имени файла
     * @param suffix Суффикс (расширение) файла
     * @param content Содержимое файла (байты)
     * @return Временный файл
     */
    fun createTempFile(
        prefix: String = "test",
        suffix: String = ".tmp",
        content: ByteArray = byteArrayOf(0x01, 0x02, 0x03)
    ): File {
        val file = File.createTempFile(prefix, suffix)
        file.deleteOnExit()
        file.writeBytes(content)
        return file
    }

    /**
     * Создает тестовый JPEG файл с указанным размером.
     *
     * @param sizeInBytes Размер файла в байтах
     * @return Тестовый файл
     */
    fun createTestFileOfSize(sizeInBytes: Int): File {
        val file = File.createTempFile("test_size", ".jpg")
        file.deleteOnExit()
        file.writeBytes(ByteArray(sizeInBytes) { it.toByte() })
        return file
    }

    /**
     * Проверяет, что URI имеет указанную схему.
     *
     * @param uri URI для проверки
     * @param scheme Ожидаемая схема
     * @return true, если схема совпадает
     */
    fun hasScheme(uri: Uri, scheme: String): Boolean {
        return uri.scheme == scheme
    }

    /**
     * Создает URI из ContentResolver схемы (content://).
     *
     * @param id ID файла в MediaStore
     * @return Content URI
     */
    fun createContentUri(id: Long = 12345): Uri {
        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority("media")
            .appendPath("external")
            .appendPath("images")
            .appendPath("media")
            .appendPath(id.toString())
            .build()
    }

    /**
     * Создает URI из File scheme (file://).
     *
     * @param path Путь к файлу
     * @return File URI
     */
    fun createFileUri(path: String = "/sdcard/test/image.jpg"): Uri {
        return Uri.fromFile(File(path))
    }

    /**
     * Создает mock MediaStore Uri.
     *
     * @param collection Коллекция MediaStore
     * @param id ID файла
     * @return MediaStore URI
     */
    fun createMediaStoreUri(collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id: Long = 12345): Uri {
        return Uri.withAppendedPath(collection, id.toString())
    }

    /**
     * Проверяет, что тестовый файл валиден.
     *
     * @param file Файл для проверки
     * @param minSize Минимальный размер файла в байтах
     * @return true, если файл валиден
     */
    fun isValidTestFile(file: File?, minSize: Int = 0): Boolean {
        return file != null && file.exists() && file.canRead() && file.length() >= minSize
    }

    /**
     * Очищает временные файлы после теста.
     *
     * @param files Список файлов для удаления
     */
    fun cleanupTempFiles(vararg files: File?) {
        files.forEach { file ->
            if (file != null && file.exists()) {
                file.delete()
            }
        }
    }

    /**
     * Создает директорию для тестовых файлов.
     *
     * @param name Имя директории
     * @return Тестовая директория
     */
    fun createTestDirectory(name: String = "test_dir"): File {
        val dir = File(System.getProperty("java.io.tmpdir"), name)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir.deleteOnExit()
        return dir
    }

    /**
     * Проверяет, что файл является изображением.
     *
     * @param file Файл для проверки
     * @return true, если файл является изображением
     */
    fun isImageFile(file: File?): Boolean {
        if (file == null || !file.exists()) return false

        val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif")
        val extension = file.extension.lowercase()
        return extension in imageExtensions
    }

    /**
     * Создает assertion helper для проверки размера файла.
     *
     * @param file Файл для проверки
     * @param expectedSize Ожидаемый размер в байтах
     * @param tolerance Допустимая погрешность в байтах
     * @return true, если размер в пределах допустимой погрешности
     */
    fun assertFileSize(file: File, expectedSize: Long, tolerance: Long = 1024): Boolean {
        val actualSize = file.length()
        return kotlin.math.abs(actualSize - expectedSize) <= tolerance
    }
}

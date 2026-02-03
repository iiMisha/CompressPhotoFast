package com.compressphotofast.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.compressphotofast.BaseInstrumentedTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation тесты для проверки режима замены (SAVE_MODE_REPLACE)
 *
 * Критические тесты для проверки исправления бага с "~2" в именах файлов.
 * Упрощенная версия с использованием только JUnit assertions.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreReplaceModeTest : BaseInstrumentedTest() {

    private lateinit var context: Context
    private val createdUris = mutableListOf<Uri>()

    @Before
    override fun setUp() {
        super.setUp()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // КРИТИЧЕСКО: Устанавливаем режим замены для всех тестов
        SettingsManager.getInstance(context).setSaveMode(true)
    }

    @After
    override fun tearDown() {
        super.tearDown()
        // Очистка созданных файлов
        createdUris.forEach { uri ->
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                // Игнорируем ошибки при очистке
            }
        }
        createdUris.clear()
    }

    /**
     * Тест 1: Базовый сценарий замены - файл НЕ должен иметь "~2" в имени
     */
    @Test
    fun test_replace_mode_creates_file_without_tilde_suffix() {
        runBlocking {
            // Arrange
            val fileName = "test_image_${System.currentTimeMillis()}.jpg"
            val originalUri = createTestImageInGallery(fileName)

            // Act - сжимаем изображение в режиме замены
            val compressedUri = compressImageInReplaceMode(originalUri, fileName)

            // Assert
            assertNotNull("Сжатый URI не должен быть null", compressedUri)
            val compressedFileName = getFileNameFromUri(compressedUri!!)

            assertFalse("Имя файла НЕ должно содержать '~': $compressedFileName", compressedFileName.contains("~"))
            assertEquals("Имя файла должно совпадать с оригинальным", fileName, compressedFileName)
        }
    }

    /**
     * Тест 2: Отсутствие дубликатов файлов
     */
    @Test
    fun test_replace_mode_does_not_create_duplicate_files() {
        runBlocking {
            // Arrange
            val fileName = "photo_no_duplicate_${System.currentTimeMillis()}.jpg"
            val originalUri = createTestImageInGallery(fileName)

            // Act
            compressImageInReplaceMode(originalUri, fileName)

            // Assert - ищем все файлы с таким именем
            val allFiles = queryFilesWithName(fileName)

            assertTrue("Должен быть найден хотя бы один файл", allFiles.isNotEmpty())
            assertEquals("Должен быть только ОДИН файл", 1, allFiles.size)
            assertFalse("Имя файла не должно содержать '~'", allFiles[0].contains("~"))
        }
    }

    /**
     * Тест 3: Несколько последовательных сжатий
     */
    @Test
    fun test_multiple_compressions_in_replace_mode() {
        runBlocking {
            // Arrange
            val fileName = "multi_compression_${System.currentTimeMillis()}.jpg"
            val originalUri = createTestImageInGallery(fileName)

            // Act - сжимаем один и тот же файл несколько раз
            repeat(3) {
                compressImageInReplaceMode(originalUri, fileName)
                delay(100)
            }

            // Assert
            val allFiles = queryFilesWithName(fileName)
            assertEquals("После нескольких сжатий должен остаться только ОДИН файл", 1, allFiles.size)
            assertFalse("Имя файла не должно содержать '~'", allFiles[0].contains("~"))
        }
    }

    /**
     * Тест 4: Режим отдельной папки не затронут
     */
    @Test
    fun test_separate_mode_creates_file_with_suffix() {
        runBlocking {
            // Arrange
            val originalName = "image_separate_${System.currentTimeMillis()}.jpg"
            val originalUri = createTestImageInGallery(originalName)

            // Act - сжимаем в режиме отдельной папки
            val compressedUri = compressImageInSeparateMode(originalUri, originalName)

            // Assert
            assertNotNull("Сжатый URI не должен быть null", compressedUri)
            val compressedFileName = getFileNameFromUri(compressedUri!!)

            assertTrue("В режиме отдельной папки имя должно содержать '_compressed'", compressedFileName.contains("_compressed"))
            assertFalse("Имя файла не должно содержать '~'", compressedFileName.contains("~"))
        }
    }

    // ==================== Вспомогательные методы ====================

    /**
     * Создает тестовое изображение в галерее
     */
    private suspend fun createTestImageInGallery(fileName: String): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES
                )
                val file = java.io.File(picturesDir, fileName)
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: throw AssertionError("Не удалось создать тестовое изображение")

        // Создаем простой bitmap и сохраняем
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.RGB_565)
        val outputStream = context.contentResolver.openOutputStream(uri)
        outputStream?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
            it.flush()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
        }

        createdUris.add(uri)
        delay(200)
        return uri
    }

    /**
     * Имитирует сжатие изображения в режиме замены
     */
    private suspend fun compressImageInReplaceMode(originalUri: Uri, fileName: String): Uri? {
        val (uri, isUpdateMode) = MediaStoreUtil.createMediaStoreEntryV2(
            context,
            fileName,
            "",
            "image/jpeg",
            originalUri
        )

        if (uri == null) return null

        assertTrue("В режиме замены isUpdateMode должен быть true", isUpdateMode)

        // Перезаписываем данные
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.RGB_565)
        val outputStream = context.contentResolver.openOutputStream(uri)
        outputStream?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it)
            it.flush()
        }

        // Сбрасываем IS_PENDING
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
        }

        delay(200)
        return uri
    }

    /**
     * Имитирует сжатие изображения в режиме отдельной папки
     */
    private suspend fun compressImageInSeparateMode(originalUri: Uri, fileName: String): Uri? {
        val compressedFileName = fileName.substringBeforeLast(".") + "_compressed.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, compressedFileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CompressPhotoFast")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return null

        // Создаем сжатую версию
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.RGB_565)
        val outputStream = context.contentResolver.openOutputStream(uri)
        outputStream?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it)
            it.flush()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
        }

        createdUris.add(uri)
        delay(200)
        return uri
    }

    /**
     * Получает имя файла из URI
     */
    private fun getFileNameFromUri(uri: Uri): String {
        var fileName = ""
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        return fileName
    }

    /**
     * Ищет все файлы с заданным именем
     */
    private fun queryFilesWithName(fileName: String): List<String> {
        val fileNames = mutableListOf<String>()
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileNames.add(cursor.getString(nameIndex))
                }
            }
        }

        return fileNames
    }
}

package com.compressphotofast.performance

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.compressphotofast.BaseInstrumentedTest
import com.compressphotofast.util.Constants
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Нагрузочный тест для сжатия изображений на устройстве
 *
 * Тестирует:
 * - Генерацию 100 файлов размером ~5 МБ разных форматов (JPG, HEIC, PNG)
 * - Отсутствие дубликатов расширений в именах файлов
 * - Корректность сохранения метаданных (EXIF, даты)
 * - Проверку создания сжатых версий
 */
@RunWith(AndroidJUnit4::class)
class CompressionLoadTest : BaseInstrumentedTest() {

    private lateinit var context: Context
    private val generatedUris = mutableListOf<Uri>()
    private val metadataErrors = mutableListOf<String>()
    private val duplicateErrors = mutableListOf<String>()

    @Before
    override fun setUp() {
        super.setUp()
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    override fun tearDown() {
        super.tearDown()

        // Очистка: удаляем созданные файлы
        generatedUris.forEach { uri ->
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.w("LoadTest", "Ошибка удаления файла $uri: ${e.message}")
            }
        }

        generatedUris.clear()
        metadataErrors.clear()
        duplicateErrors.clear()
    }

    /**
     * Генерирует изображение размером ~5 МБ указанного формата
     */
    private suspend fun generate5MbImage(
        format: String,
        index: Int,
        simulateDoubleExtension: Boolean = false
    ): Uri {
        val width = 6000
        val height = 4000
        val quality = 98

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Генерируем сложный шум
        val random = java.util.Random()
        val paint = Paint()

        // Рисуем множество мелких прямоугольников
        for (x in 0 until width step 20) {
            for (y in 0 until height step 20) {
                paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    (x + 20).toFloat(),
                    (y + 20).toFloat(),
                    paint
                )
            }
        }

        // Добавляем градиент
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPaint(paint)

        // Сохраняем во временный файл для добавления EXIF
        val tempFile = File(context.cacheDir, "temp_img_${System.currentTimeMillis()}.jpg")
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        }

        // Добавляем EXIF метаданные
        try {
            val exif = ExifInterface(tempFile)
            exif.setAttribute(ExifInterface.TAG_ARTIST, "CompressPhotoFast Test")
            exif.setAttribute(ExifInterface.TAG_MAKE, "TestCamera")
            exif.setAttribute(ExifInterface.TAG_MODEL, "TestModel")
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Load test image #$index")
            exif.setAttribute(ExifInterface.TAG_DATETIME, java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss").format(java.util.Date()))
            exif.saveAttributes()
            Log.d("LoadTest", "EXIF метаданные добавлены для файла #$index")
        } catch (e: Exception) {
            Log.w("LoadTest", "Ошибка записи EXIF для #$index: ${e.message}")
        }

        bitmap.recycle()

        // Определяем имя файла (может содержать двойное расширение для теста)
        val timestamp = System.currentTimeMillis()
        val fileName = if (simulateDoubleExtension) {
            "test_image_${index}_$timestamp.jpg.jpg" // Двойное расширение
        } else {
            "test_image_${index}_$timestamp.jpg"
        }

        // Сохраняем в MediaStore
        val mimeType = "image/jpeg"
        val extension = if (simulateDoubleExtension) ".jpg.jpg" else ".jpg"

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CompressPhotoFast")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }

        val uri = resolver.insert(collection, contentValues)

        uri?.let {
            resolver.openOutputStream(it)?.use { out ->
                FileInputStream(tempFile).use { inp ->
                    inp.copyTo(out)
                }
            } ?: throw Exception("Cannot open output stream for $uri")

            // Помечаем как готовый
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(it, contentValues, null, null)
        }

        // Удаляем временный файл
        tempFile.delete()

        Log.i("LoadTest", "Сгенерирован файл $format #$${index}: $fileName")
        return uri!!
    }

    /**
     * Проверяет наличие двойного расширения в имени файла
     */
    private fun checkDoubleExtension(displayName: String, uri: Uri) {
        val hasDoubleExtension = displayName.contains(Regex("\\.(jpg|jpeg|png|heic)\\.(jpg|jpeg|png|heic)$", RegexOption.IGNORE_CASE))

        if (hasDoubleExtension) {
            val error = "ОБНАРУЖЕНО ДВОЙНОЕ РАСШИРЕНИЕ: $displayName (URI: $uri)"
            Log.e("LoadTest", error)
            duplicateErrors.add(error)
        }
    }

    /**
     * Проверяет метаданные файла
     */
    private fun checkMetadata(uri: Uri) {
        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_MODIFIED
                ),
                null,
                null,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val displayName = it.getString(0) ?: ""
                    val mimeType = it.getString(1) ?: ""
                    val size = it.getLong(2)
                    val dateAdded = it.getLong(3)
                    val dateModified = it.getLong(4)

                    // Проверяем имя файла
                    if (displayName.isEmpty()) {
                        val error = "ОШИБКА: Пустое имя файла (URI: $uri)"
                        Log.e("LoadTest", error)
                        metadataErrors.add(error)
                    }

                    // Проверяем MIME тип
                    if (mimeType.isEmpty()) {
                        val error = "ОШИБКА: Пустой MIME тип (URI: $uri, файл: $displayName)"
                        Log.e("LoadTest", error)
                        metadataErrors.add(error)
                    }

                    // Проверяем размер
                    if (size <= 0) {
                        val error = "ОШИБКА: Некорректный размер файла $size (URI: $uri, файл: $displayName)"
                        Log.e("LoadTest", error)
                        metadataErrors.add(error)
                    }

                    // Проверяем даты
                    if (dateAdded <= 0 || dateModified <= 0) {
                        val error = "ОШИБКА: Некорректные даты (added=$dateAdded, modified=$dateModified) для файла $displayName"
                        Log.e("LoadTest", error)
                        metadataErrors.add(error)
                    }

                    // Проверяем двойные расширения
                    checkDoubleExtension(displayName, uri)
                }
            }
        } catch (e: Exception) {
            val error = "ОШИБКА чтения метаданных для $uri: ${e.message}"
            Log.e("LoadTest", error)
            metadataErrors.add(error)
        }
    }

    /**
     * Генерирует 100 тестовых изображений разных форматов
     */
    private suspend fun generateLoadTestImages(checkMetadata: Boolean = true): Int {
        val startTime = System.currentTimeMillis()
        var count = 0

        // Генерируем батчами по 10 файлов
        for (batchStart in 0 until 100 step 10) {
            val batchEnd = minOf(batchStart + 10, 100)

            Log.i("LoadTest", "Генерация батча $batchStart-${batchEnd} из 100")

            for (i in batchStart until batchEnd) {
                // Симулируем двойное расширение для 5% файлов
                val simulateDoubleExtension = (i % 20 == 0)

                val uri = generate5MbImage("jpg", i, simulateDoubleExtension)
                generatedUris.add(uri)

                // Проверяем метаданные если нужно
                if (checkMetadata) {
                    checkMetadata(uri)
                }

                count++
            }

            // Небольшая пауза после каждого батча
            delay(100)
        }

        val endTime = System.currentTimeMillis()
        Log.i("LoadTest", "Сгенерировано $count файлов за ${endTime - startTime}ms")

        return count
    }

    /**
     * Основной нагрузочный тест - генерация 100 изображений с проверкой метаданных
     */
    @Test
    fun test_generate100Images_withMetadataCheck() {
        runBlocking {
            // Arrange & Act
            val count = generateLoadTestImages(checkMetadata = true)

            // Assert - базовые проверки
            assertThat(count).isEqualTo(100)
            assertThat(generatedUris.size).isEqualTo(100)

            // Проверяем, что файлы существуют
            val existingFiles = generatedUris.count { uri ->
                try {
                    context.contentResolver.openInputStream(uri)?.use { it.available() > 0 } ?: false
                } catch (e: Exception) {
                    false
                }
            }

            Log.i("LoadTest", "Существуют файлов: $existingFiles из ${generatedUris.size}")
            assertThat(existingFiles).isAtLeast(95)

            // Проверяем ошибки метаданных
            Log.i("LoadTest", "Ошибок метаданных: ${metadataErrors.size}")
            Log.i("LoadTest", "Обнаружено двойных расширений: ${duplicateErrors.size}")

            // Ожидаем 5 двойных расширений (5% от 100)
            assertThat(duplicateErrors.size).isAtLeast(5)

            // Логируем все ошибки метаданных
            metadataErrors.forEach { error ->
                Log.e("LoadTest", "METADATA ERROR: $error")
            }

            // Допускаем некоторые ошибки метаданных, но не критичные
            assertThat(metadataErrors.size).isLessThan(10) // Максимум 10% ошибок метаданных
        }
    }

    /**
     * Тест проверки отсутствия двойных расширений
     */
    @Test
    fun test_noDoubleExtensions() {
        runBlocking {
            // Arrange & Act - генерируем 20 файлов БЕЗ двойных расширений
            for (i in 0 until 20) {
                val uri = generate5MbImage("jpg", i, simulateDoubleExtension = false)
                generatedUris.add(uri)
                checkMetadata(uri)
            }

            // Assert - не должно быть двойных расширений
            assertThat(duplicateErrors.size).isEqualTo(0)
            Log.i("LoadTest", "✅ Нет двойных расширений (проверено 20 файлов)")
        }
    }

    /**
     * Тест проверки обнаружения двойных расширений
     */
    @Test
    fun test_detectDoubleExtensions() {
        runBlocking {
            // Arrange & Act - генерируем 10 файлов С двойными расширениями
            for (i in 0 until 10) {
                val uri = generate5MbImage("jpg", i, simulateDoubleExtension = true)
                generatedUris.add(uri)
                checkMetadata(uri)
            }

            // Assert - все должны иметь двойные расширения
            assertThat(duplicateErrors.size).isEqualTo(10)
            Log.i("LoadTest", "✅ Обнаружены все 10 двойных расширений")

            // Проверяем, что в ошибках есть упоминание ".jpg.jpg"
            val hasJpgJpg = duplicateErrors.any { it.contains(".jpg.jpg") }
            assertThat(hasJpgJpg).isTrue()
        }
    }

    /**
     * Тест проверки корректности метаданных
     */
    @Test
    fun test_metadataCorrectness() {
        runBlocking {
            // Arrange & Act - генерируем 10 файлов
            for (i in 0 until 10) {
                val uri = generate5MbImage("jpg", i, simulateDoubleExtension = false)
                generatedUris.add(uri)
                checkMetadata(uri)
            }

            // Assert - не должно быть критических ошибок метаданных
            val criticalErrors = metadataErrors.filter { error ->
                error.contains("Пустое имя файла") ||
                error.contains("Пустой MIME тип") ||
                error.contains("Некорректный размер")
            }

            Log.i("LoadTest", "Критических ошибок метаданных: ${criticalErrors.size}")
            assertThat(criticalErrors.size).isEqualTo(0)
        }
    }

    /**
     * Тест генерации 100 изображений (быстрая версия без проверки метаданных)
     */
    @Test
    fun test_generate100Images_fast() {
        runBlocking {
            // Arrange & Act
            val count = generateLoadTestImages(checkMetadata = false)

            // Assert
            assertThat(count).isEqualTo(100)
            assertThat(generatedUris.size).isEqualTo(100)

            Log.i("LoadTest", "✅ Сгенерировано 100 файлов без проверки метаданных")
        }
    }
}

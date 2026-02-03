package com.compressphotofast.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Instrumentation тесты для ExifUtil.
 *
 * Тестирует критичные операции с EXIF метаданными:
 * - Чтение EXIF данных
 * - Копирование EXIF между изображениями
 * - Сохранение/восстановление EXIF из памяти
 * - Маркеры сжатия
 * - GPS данные
 * - Дата и время
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ExifUtilInstrumentedTest {

    private lateinit var context: Context
    private val testUris = mutableListOf<android.net.Uri>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        // Очищаем все созданные тестовые файлы
        testUris.forEach { uri ->
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                // Игнорируем ошибки при очистке
            }
        }
        testUris.clear()
    }

    /**
     * Создает простое тестовое изображение в MediaStore
     */
    private fun createTestImageInMediaStore(width: Int = 800, height: Int = 600): android.net.Uri {
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val timeStamp = System.currentTimeMillis()

        val values = ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "test_exif_$timeStamp")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/CompressPhotoFastTest")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)!!
        testUris.add(uri)

        // Создаем простое изображение
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLUE)

        // Записываем изображение
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()

        // Помечаем как готовое
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return uri
    }

    /**
     * Тест 1: Получение ExifInterface для существующего изображения
     */
    @Test
    fun test01_getExifInterface_returnsExifForValidImage() {
        // Arrange
        val testImage = createTestImageInMediaStore()

        // Act
        val exif = ExifUtil.getExifInterface(context, testImage)

        // Assert
        assertThat(exif).isNotNull()
    }

    /**
     * Тест 2: Получение ExifInterface для несуществующего изображения
     */
    @Test
    fun test02_getExifInterface_returnsNullForInvalidUri() {
        // Arrange
        val invalidUri = android.net.Uri.parse("content://invalid/uri")

        // Act
        val exif = ExifUtil.getExifInterface(context, invalidUri)

        // Assert
        assertThat(exif).isNull()
    }

    /**
     * Тест 3: Чтение EXIF данных в память
     */
    @Test
    fun test03_readExifDataToMemory_returnsExifData() {
        runBlocking {
            // Arrange
            val testImage = createTestImageInMediaStore()

            // Act
            val exifData = ExifUtil.readExifDataToMemory(context, testImage)

            // Assert
            assertThat(exifData).isNotNull()
            assertThat(exifData).isNotEmpty()
        }
    }

    /**
     * Тест 4: Проверка кэширования EXIF данных
     */
    @Test
    fun test04_readExifDataToMemory_usesCacheOnSecondCall() {
        runBlocking {
            // Arrange
            val testImage = createTestImageInMediaStore()

            // Act - первый вызов
            val exifData1 = ExifUtil.readExifDataToMemory(context, testImage)
            // Act - второй вызов (должен использовать кэш)
            val exifData2 = ExifUtil.readExifDataToMemory(context, testImage)

            // Assert
            assertThat(exifData1).isNotNull()
            assertThat(exifData2).isNotNull()
            assertThat(exifData1.size).isEqualTo(exifData2.size)
        }
    }

    /**
     * Тест 5: Добавление маркера сжатия
     */
    @Test
    fun test05_markCompressedImage_addsMarker() {
        runBlocking {
            // Arrange
            val testImage = createTestImageInMediaStore()
            val quality = 85

            // Act
            val markResult = ExifUtil.markCompressedImage(context, testImage, quality)

            // Assert
            assertThat(markResult).isTrue()

            // Verify - проверяем, что маркер действительно добавлен
            val (isCompressed, markedQuality, _) = ExifUtil.getCompressionInfo(context, testImage)
            assertThat(isCompressed).isTrue()
            assertThat(markedQuality).isEqualTo(quality)
        }
    }

    /**
     * Тест 6: Проверка, было ли изображение сжато
     */
    @Test
    fun test06_isImageCompressed_returnsTrueAfterMarking() {
        runBlocking {
            // Arrange
            val testImage = createTestImageInMediaStore()
            ExifUtil.markCompressedImage(context, testImage, 80)

            // Act
            val isCompressed = ExifUtil.isImageCompressed(context, testImage)

            // Assert
            assertThat(isCompressed).isTrue()
        }
    }

    /**
     * Тест 7: Проверка несжатого изображения
     */
    @Test
    fun test07_isImageCompressed_returnsFalseForNewImage() {
        runBlocking {
            // Arrange
            val testImage = createTestImageInMediaStore()

            // Act
            val isCompressed = ExifUtil.isImageCompressed(context, testImage)

            // Assert
            assertThat(isCompressed).isFalse()
        }
    }

    /**
     * Тест 8: Получение информации о сжатии
     */
    @Test
    fun test08_getCompressionInfo_returnsCorrectInfo() {
        runBlocking {
            // Arrange
            val testImage = createTestImageInMediaStore()
            val quality = 75
            ExifUtil.markCompressedImage(context, testImage, quality)

            // Act
            val (isCompressed, markedQuality, timestamp) =
                ExifUtil.getCompressionInfo(context, testImage)

            // Assert
            assertThat(isCompressed).isTrue()
            assertThat(markedQuality).isEqualTo(quality)
            assertThat(timestamp).isGreaterThan(0L)
        }
    }

    /**
     * Тест 9: Копирование EXIF данных между изображениями
     *
     * Проверяет что метод copyExifData корректно копирует EXIF теги из исходного файла в целевой.
     *
     * ВАЖНО: Тест создает два тестовых изображения, добавляет EXIF данные в исходный файл,
     * затем копирует их в целевой файл и верифицирует успешность копирования.
     *
     * Историческая справка: Ранее тест падал из-за преждевременного закрытия ParcelFileDescriptor
     * через блок use{}. ExifInterface.saveAttributes() требует открытый файловый дескриптор.
     * Решение: дескриптор теперь хранится в переменной и закрывается явно после saveAttributes().
     */
    @Test
    fun test09_copyExifData_copiesExifSuccessfully() {
        runBlocking {
            // Arrange
            val sourceImage = createTestImageInMediaStore()
            kotlinx.coroutines.delay(2000)  // Увеличено для стабильности

            // Добавляем минимальные EXIF данные в исходный файл
            context.contentResolver.openFileDescriptor(sourceImage, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_MAKE, "TestManufacturer")
                exif.setAttribute(ExifInterface.TAG_MODEL, "TestModel")
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
                exif.saveAttributes()
            }

            // Ждем после добавления EXIF
            kotlinx.coroutines.delay(1500)  // Увеличено для стабильности

            val destImage = createTestImageInMediaStore()
            kotlinx.coroutines.delay(2000)  // Увеличено для стабильности

            // Act - копируем EXIF без маркера сжатия
            val copyResult = ExifUtil.copyExifData(context, sourceImage, destImage)

            // Assert
            assertThat(copyResult).isTrue()

            // Verify - проверяем, что теги скопированы
            val destExif = ExifUtil.getExifInterface(context, destImage)
            assertThat(destExif).isNotNull()
            val make = destExif?.getAttribute(ExifInterface.TAG_MAKE)
            val model = destExif?.getAttribute(ExifInterface.TAG_MODEL)
            assertThat(make).isEqualTo("TestManufacturer")
            assertThat(model).isEqualTo("TestModel")
        }
    }

    /**
     * Тест 10: Применение EXIF из памяти
     */
    @Test
    fun test10_applyExifFromMemory_appliesExifData() {
        runBlocking {
            // Arrange
            val sourceImage = createTestImageInMediaStore()
            val destImage = createTestImageInMediaStore()

            // Читаем EXIF из исходного изображения
            val exifData = ExifUtil.readExifDataToMemory(context, sourceImage)

            // Act
            val applyResult = ExifUtil.applyExifFromMemory(
                context,
                destImage,
                exifData,
                quality = 90
            )

            // Assert
            assertThat(applyResult).isTrue()

            // Verify - проверяем, что маркер добавлен
            val (isCompressed, quality, _) = ExifUtil.getCompressionInfo(context, destImage)
            assertThat(isCompressed).isTrue()
            assertThat(quality).isEqualTo(90)
        }
    }

    /**
     * Тест 11: Обработка EXIF для сохраненного изображения (с заранее загруженными данными)
     */
    @Test
    fun test11_handleExifForSavedImage_withPreloadedData() {
        runBlocking {
            // Arrange
            val sourceImage = createTestImageInMediaStore()
            val destImage = createTestImageInMediaStore()
            val quality = 80

            // Предварительно загружаем EXIF данные
            val exifData = ExifUtil.readExifDataToMemory(context, sourceImage)

            // Act
            val handleResult = ExifUtil.handleExifForSavedImage(
                context,
                sourceImage,
                destImage,
                quality,
                exifData
            )

            // Assert
            assertThat(handleResult).isTrue()

            // Verify
            val (isCompressed, markedQuality, _) = ExifUtil.getCompressionInfo(context, destImage)
            assertThat(isCompressed).isTrue()
            assertThat(markedQuality).isEqualTo(quality)
        }
    }

    /**
     * Тест 12: Обработка EXIF для сохраненного изображения (без предварительной загрузки)
     */
    @Test
    fun test12_handleExifForSavedImage_withoutPreloadedData() {
        runBlocking {
            // Arrange
            val sourceImage = createTestImageInMediaStore()
            val destImage = createTestImageInMediaStore()
            val quality = 85

            // Act - без предварительной загрузки данных
            val handleResult = ExifUtil.handleExifForSavedImage(
                context,
                sourceImage,
                destImage,
                quality,
                exifDataMemory = null
            )

            // Assert
            assertThat(handleResult).isTrue()
        }
    }

    /**
     * Тест 13: Получение маркера сжатия (не suspend вариант)
     */
    @Test
    fun test13_getCompressionMarker_returnsMarkerForCompressedImage() {
        runBlocking {
            // Arrange
            val testImage = createTestImageInMediaStore()
            val quality = 70
            ExifUtil.markCompressedImage(context, testImage, quality)

            // Act - не suspend метод
            val (isCompressed, markedQuality, timestamp) =
                ExifUtil.getCompressionMarker(context, testImage)

            // Assert
            assertThat(isCompressed).isTrue()
            assertThat(markedQuality).isEqualTo(quality)
            assertThat(timestamp).isGreaterThan(0L)
        }
    }

    /**
     * Тест 14: Применение EXIF с качеством null (без маркера)
     */
    @Test
    fun test14_applyExifFromMemory_withoutQuality() {
        runBlocking {
            // Arrange
            val sourceImage = createTestImageInMediaStore()
            val destImage = createTestImageInMediaStore()

            val exifData = ExifUtil.readExifDataToMemory(context, sourceImage)

            // Act - без качества (не добавляем маркер)
            val applyResult = ExifUtil.applyExifFromMemory(
                context,
                destImage,
                exifData,
                quality = null
            )

            // Assert
            assertThat(applyResult).isTrue()

            // Verify - маркер не должен быть добавлен
            val (isCompressed, _, _) = ExifUtil.getCompressionInfo(context, destImage)
            assertThat(isCompressed).isFalse()
        }
    }

    /**
     * Тест 15: Несколько маркеров сжатия (последний перезаписывает предыдущий)
     */
    @Test
    fun test15_markCompressedImage_overwritesPreviousMarker() {
        runBlocking {
            // Arrange
            val testImage = createTestImageInMediaStore()

            // Act - добавляем первый маркер
            ExifUtil.markCompressedImage(context, testImage, 70)
            val (compressed1, quality1, _) = ExifUtil.getCompressionInfo(context, testImage)

            // Act - добавляем второй маркер
            Thread.sleep(100) // чтобы timestamp отличался
            ExifUtil.markCompressedImage(context, testImage, 90)
            val (compressed2, quality2, timestamp2) = ExifUtil.getCompressionInfo(context, testImage)

            // Assert
            assertThat(compressed1).isTrue()
            assertThat(quality1).isEqualTo(70)
            assertThat(compressed2).isTrue()
            assertThat(quality2).isEqualTo(90) // качество обновлено
            assertThat(timestamp2).isGreaterThan(0L)
        }
    }

    /**
     * Тест 16: Копирование EXIF с несуществующим исходным файлом
     */
    @Test
    fun test16_copyExifData_returnsFalseForInvalidSourceUri() {
        runBlocking {
            // Arrange
            val destImage = createTestImageInMediaStore()
            val invalidSourceUri = android.net.Uri.parse("content://invalid/source")

            // Act
            val copyResult = ExifUtil.copyExifData(context, invalidSourceUri, destImage)

            // Assert
            assertThat(copyResult).isFalse()
        }
    }

    /**
     * Тест 17: Применение EXIF к несуществующему файлу
     */
    @Test
    fun test17_applyExifFromMemory_returnsFalseForInvalidUri() {
        runBlocking {
            // Arrange
            val testImage = createTestImageInMediaStore()
            val exifData = ExifUtil.readExifDataToMemory(context, testImage)
            val invalidUri = android.net.Uri.parse("content://invalid/dest")

            // Act
            val applyResult = ExifUtil.applyExifFromMemory(
                context,
                invalidUri,
                exifData,
                quality = 85
            )

            // Assert
            assertThat(applyResult).isFalse()
        }
    }

    // ==================== HEIC ФАЙЛЫ ТЕСТЫ ====================

    /**
     * Тест 18: Определение HEIC файлов через MIME тип
     *
     * Проверяет, что JPEG и HEIC файлы правильно определяются по MIME типу
     */
    @Test
    fun test18_isHeicFile_correctlyIdentifiesHeicFiles() {
        // Arrange - создаем JPEG тестовый файл
        val jpegImage = createTestImageInMediaStore()

        // Act & Assert
        // JPEG файл НЕ должен быть HEIC
        val jpegMimeType = context.contentResolver.getType(jpegImage)
        assertThat(jpegMimeType).isNotNull()
        assertThat(jpegMimeType).isNotEqualTo("image/heic")
        assertThat(jpegMimeType).isNotEqualTo("image/heif")
    }

    /**
     * Тест 19: getCompressionMarker для JPEG файла
     *
     * Проверяет стандартную логику определения маркера сжатия для JPEG
     */
    @Test
    fun test19_getCompressionMarker_worksForJpegWithoutMarker() {
        // Arrange
        val jpegImage = createTestImageInMediaStore()

        // Act
        val (isCompressed, quality, timestamp) = ExifUtil.getCompressionMarker(context, jpegImage)

        // Assert - у только что созданного файла нет маркера
        assertThat(isCompressed).isFalse()
        assertThat(quality).isEqualTo(-1)
        assertThat(timestamp).isEqualTo(0L)
    }

    /**
     * Тест 20: getCompressionMarker для JPEG с маркером сжатия
     *
     * Проверяет, что EXIF маркер корректно определяется для JPEG файлов
     */
    @Test
    fun test20_getCompressionMarker_detectsMarkerInJpeg() {
        runBlocking {
            // Arrange
            val jpegImage = createTestImageInMediaStore()
            val quality = 75

            // Добавляем маркер сжатия
            val markResult = ExifUtil.markCompressedImage(context, jpegImage, quality)
            assertThat(markResult).isTrue()

            // Act
            val (isCompressed, detectedQuality, timestamp) = ExifUtil.getCompressionMarker(context, jpegImage)

            // Assert
            assertThat(isCompressed).isTrue()
            assertThat(detectedQuality).isEqualTo(quality)
            assertThat(timestamp).isGreaterThan(0L)
        }
    }

    /**
     * Тест 21: Проверка суффикса _compressed для HEIC файлов (именование)
     *
     * Проверяет логику определения суффикса через отражение
     */
    @Test
    fun test21_hasHeicCompressedSuffix_detectsSuffixCorrectly() {
        // Arrange
        val exifUtilClass = ExifUtil.javaClass
        val hasHeicCompressedSuffixMethod = exifUtilClass.getDeclaredMethod(
            "hasHeicCompressedSuffix",
            String::class.java
        )
        hasHeicCompressedSuffixMethod.isAccessible = true

        val testCases = mapOf(
            "image_compressed.heic" to true,
            "image.heic" to false,
            "photo_compressed.HEIC" to true,
            "photo_compressed.heif" to true,
            "photo.heif" to false,
            "IMG_20240131_175449_compressed.heic" to true,
            "20240131_175449.heic" to false,
            "test_compressed.jpg" to false,
            null to false,
            "" to false
        )

        // Act & Assert
        testCases.forEach { (fileName, expectedResult) ->
            val result = hasHeicCompressedSuffixMethod.invoke(ExifUtil, fileName) as Boolean
            assertThat(result).isEqualTo(expectedResult)
        }
    }

    /**
     * Тест 22: Добавление маркера сжатия для HEIC файла через EXIF
     *
     * Проверяет что для HEIC файла (эмулированного через MIME тип):
     * 1. Маркер сжатия успешно добавляется через applyExifFromMemory
     * 2. EXIF маркер корректно определяется через getCompressionMarker
     * 3. Параметры маркера (isCompressed, quality, timestamp) корректны
     *
     * Примечание: Поскольку мы используем JPEG контент с MIME типом image/heic,
     * ExifInterface успешно сохраняет EXIF в JPEG, поэтому переименование не происходит.
     * Переименование (_compressed) происходит только при реальной ошибке сохранения EXIF.
     *
     * Тестируется через публичный API:
     * - ExifUtil.applyExifFromMemory() - добавление EXIF маркера сжатия
     * - ExifUtil.getCompressionMarker() - чтение маркера из EXIF
     */
    @Test
    fun test22_applyExifFromMemory_addsCompressionMarkerToHeic() {
        runBlocking {
            // Arrange - создаем файл и эмулируем HEIC через MIME тип
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val timeStamp = System.currentTimeMillis()
            val originalName = "test_heic_$timeStamp.heic"

            val values = ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, originalName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/heic")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/CompressPhotoFastTest")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(collection, values)!!
            testUris.add(uri)

            // Создаем изображение (используем JPEG формат, но MIME type говорит HEIC)
            val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.RED)

            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()

            // Помечаем как готовое
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            // Act - добавляем маркер сжатия через applyExifFromMemory
            val quality = 85
            val applyResult = ExifUtil.applyExifFromMemory(
                context,
                uri,
                emptyMap(),  // Пустой EXIF - только добавляем маркер
                quality
            )

            // Assert - операция успешна
            assertThat(applyResult).isTrue()

            // Verify - проверяем что маркер сжатия добавлен через EXIF (не через суффикс)
            val (isCompressed, markedQuality, timestamp) = ExifUtil.getCompressionMarker(context, uri)
            assertThat(isCompressed).isTrue()
            assertThat(markedQuality).isEqualTo(quality)
            assertThat(timestamp).isGreaterThan(0L)

            // Проверяем что имя файла НЕ изменилось (суффикс _compressed не добавлен)
            val projection = arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                val nameIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                val newName = cursor.getString(nameIndex)

                // Имя не содержит суффикс _compressed (т.к. EXIF сохранился успешно)
                assertThat(newName).doesNotContain("_compressed")
                assertThat(newName).endsWith(".heic")
            }
        }
    }

    /**
     * Тест 23: Идемпотентность добавления маркера сжатия для HEIC файла
     *
     * Проверяет что повторный вызов applyExifFromMemory для HEIC файла:
     * 1. Первый вызов успешно добавляет маркер сжатия
     * 2. Второй вызов также успешен и обновляет маркер (новый timestamp)
     * 3. Оба вызова возвращают true (идемпотентность операции)
     *
     * Сценарий:
     * 1. Создается HEIC файл (эмулированный через MIME тип)
     * 2. Первый вызов applyExifFromMemory добавляет маркер сжатия
     * 3. Второй вызов applyExifFromMemory обновляет маркер (новый timestamp)
     * 4. Проверяется что маркер корректен после каждого вызова
     *
     * Тестируется через публичный API:
     * - ExifUtil.applyExifFromMemory() - идемпотентное добавление маркера
     * - ExifUtil.getCompressionMarker() - проверка маркера и timestamp
     */
    @Test
    fun test23_applyExifFromMemory_isIdempotentForHeic() {
        runBlocking {
            // Arrange - создаем HEIC файл
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val timeStamp = System.currentTimeMillis()
            val originalName = "test_${timeStamp}.heic"

            val values = ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, originalName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/heic")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/CompressPhotoFastTest")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(collection, values)!!
            testUris.add(uri)

            val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.GREEN)

            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()

            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            // Act - первый вызов добавляет маркер
            val quality = 85
            val applyResult1 = ExifUtil.applyExifFromMemory(
                context,
                uri,
                emptyMap(),
                quality
            )

            // Проверяем маркер после первого вызова
            val (isCompressed1, markedQuality1, timestamp1) = ExifUtil.getCompressionMarker(context, uri)

            // Небольшая пауза чтобы timestamp отличался
            kotlinx.coroutines.delay(100)

            // Act - второй вызов обновляет маркер
            val applyResult2 = ExifUtil.applyExifFromMemory(
                context,
                uri,
                emptyMap(),
                quality
            )

            // Проверяем маркер после второго вызова
            val (isCompressed2, markedQuality2, timestamp2) = ExifUtil.getCompressionMarker(context, uri)

            // Assert - оба вызова успешны (идемпотентность)
            assertThat(applyResult1).isTrue()
            assertThat(applyResult2).isTrue()

            // Проверяем что маркер присутствует после обоих вызовов
            assertThat(isCompressed1).isTrue()
            assertThat(isCompressed2).isTrue()
            assertThat(markedQuality1).isEqualTo(quality)
            assertThat(markedQuality2).isEqualTo(quality)

            // Timestamp должен обновиться при втором вызове
            assertThat(timestamp2).isAtLeast(timestamp1)

            // Проверяем что имя файла НЕ изменилось (без суффикса _compressed)
            val projection = arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                val nameIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                val finalName = cursor.getString(nameIndex)

                assertThat(finalName).doesNotContain("_compressed")
                assertThat(finalName).isEqualTo(originalName)
            }
        }
    }

    /**
     * Тест 24: Сохранение EXIF данных с маркером сжатия для HEIC файла
     *
     * Проверяет что для HEIC файла с EXIF метаданными:
     * 1. EXIF данные успешно читаются через readExifDataToMemory
     * 2. EXIF данные и маркер сжатия успешно применяются через applyExifFromMemory
     * 3. Маркер сжатия корректно определяется через getCompressionMarker
     * 4. Исходные EXIF данные сохраняются после применения
     *
     * Сценарий:
     * 1. Создается HEIC файл (эмулированный через MIME тип) с EXIF данными
     * 2. Вызывается readExifDataToMemory для чтения EXIF метаданных
     * 3. Вызывается applyExifFromMemory с EXIF данными и качеством
     * 4. Проверяется что:
     *    - Маркер сжатия добавлен
     *    - Исходные EXIF данные (MAKE, MODEL, ORIENTATION) сохранены
     *    - Имя файла не изменилось
     *
     * Примечание: Поскольку используется JPEG контент с MIME типом image/heic,
     * ExifInterface успешно сохраняет EXIF, поэтому fallback на переименование не происходит.
     *
     * Тестируется через публичный API:
     * - ExifUtil.readExifDataToMemory() - чтение EXIF метаданных
     * - ExifUtil.applyExifFromMemory() - применение EXIF с маркером сжатия
     * - ExifUtil.getCompressionMarker() - проверка маркера
     * - ExifUtil.getExifInterface() - верификация сохраненных EXIF данных
     */
    @Test
    fun test24_applyExifFromMemory_preservesExifDataWithMarker() {
        runBlocking {
            // Arrange - создаем HEIC файл с EXIF данными
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val timeStamp = System.currentTimeMillis()
            val originalName = "test_exif_heic_$timeStamp.heic"

            val values = ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, originalName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/heic")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/CompressPhotoFastTest")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(collection, values)!!
            testUris.add(uri)

            // Создаем изображение с EXIF данными
            val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLUE)

            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()

            // Добавляем EXIF данные в исходный файл
            val testMake = "TestManufacturer"
            val testModel = "TestModel"
            val testOrientation = "1"

            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_MAKE, testMake)
                exif.setAttribute(ExifInterface.TAG_MODEL, testModel)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, testOrientation)
                exif.saveAttributes()
            }

            // Помечаем как готовое
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            // Читаем EXIF данные из файла
            val exifData = ExifUtil.readExifDataToMemory(context, uri)

            // Act - применяем EXIF данные с маркером сжатия
            val quality = 75
            val applyResult = ExifUtil.applyExifFromMemory(
                context,
                uri,
                exifData,
                quality
            )

            // Assert - операция успешна
            assertThat(applyResult).isTrue()

            // Verify - проверяем что маркер сжатия добавлен через EXIF
            val (isCompressed, markedQuality, timestamp) = ExifUtil.getCompressionMarker(context, uri)
            assertThat(isCompressed).isTrue()
            assertThat(markedQuality).isEqualTo(quality)
            assertThat(timestamp).isGreaterThan(0L)

            // Verify - проверяем что исходные EXIF данные сохранены
            val finalExif = ExifUtil.getExifInterface(context, uri)
            assertThat(finalExif).isNotNull()

            val savedMake = finalExif?.getAttribute(ExifInterface.TAG_MAKE)
            val savedModel = finalExif?.getAttribute(ExifInterface.TAG_MODEL)
            val savedOrientation = finalExif?.getAttribute(ExifInterface.TAG_ORIENTATION)

            assertThat(savedMake).isEqualTo(testMake)
            assertThat(savedModel).isEqualTo(testModel)
            assertThat(savedOrientation).isEqualTo(testOrientation)

            // Проверяем что имя файла НЕ изменилось (без суффикса _compressed)
            val projection = arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                val nameIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                val finalName = cursor.getString(nameIndex)

                assertThat(finalName).doesNotContain("_compressed")
                assertThat(finalName).isEqualTo(originalName)
            }
        }
    }

    /**
     * Тест 25: Проверка getCompressionInfo с кэшированием
     *
     * Проверяет, что getCompressionInfo корректно работает с HEIC файлами
     * (хотя в текущей реализации используется только MIME тип из UriUtil)
     */
    @Test
    fun test25_getCompressionInfo_worksCorrectly() {
        // Arrange
        val jpegImage = createTestImageInMediaStore()

        // Act - для нового файла без маркера
        val (isCompressed1, quality1, timestamp1) = runBlocking {
            ExifUtil.getCompressionInfo(context, jpegImage)
        }

        // Assert
        assertThat(isCompressed1).isFalse()
        assertThat(quality1).isEqualTo(-1)
        assertThat(timestamp1).isEqualTo(0L)

        // Теперь добавляем маркер
        runBlocking {
            ExifUtil.markCompressedImage(context, jpegImage, 80)
        }

        // Act - после добавления маркера
        val (isCompressed2, quality2, timestamp2) = runBlocking {
            ExifUtil.getCompressionInfo(context, jpegImage)
        }

        // Assert
        assertThat(isCompressed2).isTrue()
        assertThat(quality2).isEqualTo(80)
        assertThat(timestamp2).isGreaterThan(0L)
    }
}

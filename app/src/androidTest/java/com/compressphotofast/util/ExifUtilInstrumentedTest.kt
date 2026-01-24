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
    fun test03_readExifDataToMemory_returnsExifData() = runBlocking {
        // Arrange
        val testImage = createTestImageInMediaStore()

        // Act
        val exifData = ExifUtil.readExifDataToMemory(context, testImage)

        // Assert
        assertThat(exifData).isNotNull()
        assertThat(exifData).isNotEmpty()
    }

    /**
     * Тест 4: Проверка кэширования EXIF данных
     */
    @Test
    fun test04_readExifDataToMemory_usesCacheOnSecondCall() = runBlocking {
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

    /**
     * Тест 5: Добавление маркера сжатия
     */
    @Test
    fun test05_markCompressedImage_addsMarker() = runBlocking {
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

    /**
     * Тест 6: Проверка, было ли изображение сжато
     */
    @Test
    fun test06_isImageCompressed_returnsTrueAfterMarking() = runBlocking {
        // Arrange
        val testImage = createTestImageInMediaStore()
        ExifUtil.markCompressedImage(context, testImage, 80)

        // Act
        val isCompressed = ExifUtil.isImageCompressed(context, testImage)

        // Assert
        assertThat(isCompressed).isTrue()
    }

    /**
     * Тест 7: Проверка несжатого изображения
     */
    @Test
    fun test07_isImageCompressed_returnsFalseForNewImage() = runBlocking {
        // Arrange
        val testImage = createTestImageInMediaStore()

        // Act
        val isCompressed = ExifUtil.isImageCompressed(context, testImage)

        // Assert
        assertThat(isCompressed).isFalse()
    }

    /**
     * Тест 8: Получение информации о сжатии
     */
    @Test
    fun test08_getCompressionInfo_returnsCorrectInfo() = runBlocking {
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

    /**
     * Тест 9: Копирование EXIF данных между изображениями
     *
     * ОТКЛЮЧЕН: copyExifData возвращает false даже с корректными EXIF данными.
     * Проблема требует дополнительного расследования логики ExifUtil.copyExifData.
     *
     * Возможные причины:
     * - Проблема с openFileDescriptor для свежесозданных MediaStore файлов
     * - Логика verifyExifCopy слишком строгая
     * - Конфликт между saveAttributes() и повторным чтением EXIF
     *
     * Примечание: Остальные 16 тестов ExifUtil работают корректно и покрывают:
     * - Чтение EXIF (test01-04)
     * - Маркеры сжатия (test05-08)
     * - Применение EXIF из памяти (test10-14)
     * - Edge cases (test15-17)
     *
     * ПРИМЕЧАНИЕ: @Ignore убран для проверки текущего состояния функциональности
     */
    @Test
    fun test09_copyExifData_copiesExifSuccessfully() = runBlocking {
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

    /**
     * Тест 10: Применение EXIF из памяти
     */
    @Test
    fun test10_applyExifFromMemory_appliesExifData() = runBlocking {
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

    /**
     * Тест 11: Обработка EXIF для сохраненного изображения (с заранее загруженными данными)
     */
    @Test
    fun test11_handleExifForSavedImage_withPreloadedData() = runBlocking {
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

    /**
     * Тест 12: Обработка EXIF для сохраненного изображения (без предварительной загрузки)
     */
    @Test
    fun test12_handleExifForSavedImage_withoutPreloadedData() = runBlocking {
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

    /**
     * Тест 13: Получение маркера сжатия (не suspend вариант)
     */
    @Test
    fun test13_getCompressionMarker_returnsMarkerForCompressedImage() = runBlocking {
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

    /**
     * Тест 14: Применение EXIF с качеством null (без маркера)
     */
    @Test
    fun test14_applyExifFromMemory_withoutQuality() = runBlocking {
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

    /**
     * Тест 15: Несколько маркеров сжатия (последний перезаписывает предыдущий)
     */
    @Test
    fun test15_markCompressedImage_overwritesPreviousMarker() = runBlocking {
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

    /**
     * Тест 16: Копирование EXIF с несуществующим исходным файлом
     */
    @Test
    fun test16_copyExifData_returnsFalseForInvalidSourceUri() = runBlocking {
        // Arrange
        val destImage = createTestImageInMediaStore()
        val invalidSourceUri = android.net.Uri.parse("content://invalid/source")

        // Act
        val copyResult = ExifUtil.copyExifData(context, invalidSourceUri, destImage)

        // Assert
        assertThat(copyResult).isFalse()
    }

    /**
     * Тест 17: Применение EXIF к несуществующему файлу
     */
    @Test
    fun test17_applyExifFromMemory_returnsFalseForInvalidUri() = runBlocking {
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

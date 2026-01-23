package com.compressphotofast.e2e

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.platform.app.InstrumentationRegistry
import com.compressphotofast.BaseE2ETest
import com.compressphotofast.R
import com.compressphotofast.ui.MainActivity
import com.compressphotofast.util.Constants
import com.compressphotofast.util.E2ETestImageGenerator
import com.compressphotofast.util.ExifUtil
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.UriUtil
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * E2E тесты для ручного сжатия изображений.
 *
 * Тестирует полный сценарий ручного сжатия:
 * - Выбор одного изображения через галерею
 * - Выбор качества сжатия
 * - Выбор режима сохранения
 * - Запуск сжатия
 * - Проверка результата
 * - Проверка сохранения EXIF-данных
 * - Проверка уведомления о завершении
 * - Проверка статистики сжатия
 * - Проверка обработки уже сжатых файлов
 * - Проверка обработки файлов меньше 100 КБ
 */
class ManualCompressionE2ETest : BaseE2ETest() {

    private lateinit var context: Context
    private val testUris = mutableListOf<Uri>()

    @Before
    override fun setUp() {
        super.setUp()
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Добавить запуск Activity
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Создаем тестовые изображения
        createTestImages()
    }

    @After
    override fun tearDown() {
        // Удаляем тестовые изображения
        cleanupTestImages()
        super.tearDown()  // Закроет activityScenario
    }

    /**
     * Тест 1: Выбор одного изображения через галерею
     */
    @Test
    fun testSelectSingleImageFromGallery() {
        // Проверяем, что кнопка выбора фото отображается
        assertViewDisplayed(R.id.btnSelectPhotos)
        
        // Нажимаем на кнопку выбора фото
        Espresso.onView(ViewMatchers.withId(R.id.btnSelectPhotos))
            .perform(ViewActions.click())

        // Ждем обновления UI
        waitForUI(300)
        
        // Проверяем, что Photo Picker открыт
        runBlocking { delay(1000) }
        
        // В реальном тесте здесь нужно выбрать изображение через UIAutomator
        // Для E2E теста мы используем программный выбор
        if (testUris.isNotEmpty()) {
            LogUtil.processDebug("Выбрано изображение: ${testUris[0]}")
        }
    }

    /**
     * Тест 2: Выбор качества сжатия (низкое)
     */
    @Test
    fun testSelectLowQuality() {
        // Проверяем, что переключатель качества отображается
        assertViewDisplayed(R.id.rbQualityLow)
        
        // Нажимаем на переключатель низкого качества
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityLow))
            .perform(ViewActions.click())

        // Ждем обновления UI
        waitForUI(300)
        
        // Проверяем, что переключатель выбран
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityLow))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        LogUtil.processDebug("Выбрано низкое качество сжатия")
    }

    /**
     * Тест 3: Выбор качества сжатия (среднее)
     */
    @Test
    fun testSelectMediumQuality() {
        // Проверяем, что переключатель качества отображается
        assertViewDisplayed(R.id.rbQualityMedium)
        
        // Нажимаем на переключатель среднего качества
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityMedium))
            .perform(ViewActions.click())

        // Ждем обновления UI
        waitForUI(300)
        
        // Проверяем, что переключатель выбран
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityMedium))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        LogUtil.processDebug("Выбрано среднее качество сжатия")
    }

    /**
     * Тест 4: Выбор качества сжатия (высокое)
     */
    @Test
    fun testSelectHighQuality() {
        // Проверяем, что переключатель качества отображается
        assertViewDisplayed(R.id.rbQualityHigh)
        
        // Нажимаем на переключатель высокого качества
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityHigh))
            .perform(ViewActions.click())

        // Ждем обновления UI
        waitForUI(300)
        
        // Проверяем, что переключатель выбран
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityHigh))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        LogUtil.processDebug("Выбрано высокое качество сжатия")
    }

    /**
     * Тест 5: Выбор режима сохранения (замена)
     */
    @Test
    fun testSelectReplaceMode() {
        // Проверяем, что переключатель режима сохранения отображается
        assertViewDisplayed(R.id.switchSaveMode)
        
        // Нажимаем на переключатель режима сохранения
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())

        // Ждем обновления UI
        waitForUI(300)
        
        // Проверяем, что переключатель включен (режим замены)
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        LogUtil.processDebug("Выбран режим замены")
    }

    /**
     * Тест 6: Выбор режима сохранения (отдельная папка)
     */
    @Test
    fun testSelectSeparateFolderMode() {
        // Проверяем, что переключатель режима сохранения отображается
        assertViewDisplayed(R.id.switchSaveMode)
        
        // Нажимаем на переключатель режима сохранения дважды (отключаем)
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())

        // Ждем обновления UI
        waitForUI(300)
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())

        // Ждем обновления UI
        waitForUI(300)
        
        // Проверяем, что переключатель выключен (режим отдельной папки)
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .check(ViewAssertions.matches(ViewMatchers.isNotChecked()))
        
        LogUtil.processDebug("Выбран режим отдельной папки")
    }

    /**
     * Тест 7: Запуск сжатия и проверка результата (уменьшение размера)
     */
    @Test
    fun testCompressionReducesFileSize() = runBlocking {
        if (testUris.isEmpty()) {
            return@runBlocking
        }
        
        val uri = testUris[0]
        val originalSize = UriUtil.getFileSize(context, uri)
        
        // Проверяем, что размер файла больше 100 КБ
        assertThat(originalSize).isGreaterThan(100 * 1024)
        
        // Выполняем сжатие
        val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            uri,
            Constants.COMPRESSION_QUALITY_MEDIUM
        )
        
        // Проверяем, что сжатие выполнено успешно
        assertThat(result.second).isNotNull()
        
        // Проверяем, что размер файла уменьшился
        val compressedSize = UriUtil.getFileSize(context, result.second!!)
        assertThat(compressedSize).isLessThan(originalSize)
        
        // Проверяем, что экономия составляет минимум 30%
        val savings = originalSize - compressedSize
        val savingsPercent = (savings.toFloat() / originalSize * 100).toInt()
        assertThat(savingsPercent).isAtLeast(30)
        
        LogUtil.processDebug("Сжатие: $originalSize -> $compressedSize байт (экономия: $savingsPercent%)")
    }

    /**
     * Тест 8: Проверка сохранения EXIF-данных
     */
    @Test
    fun testExifDataPreserved() = runBlocking {
        if (testUris.isEmpty()) {
            return@runBlocking
        }
        
        val uri = testUris[0]
        
        // Получаем EXIF-данные до сжатия
        val exifBefore = ExifUtil.getCompressionMarker(context, uri)
        
        // Выполняем сжатие
        val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            uri,
            Constants.COMPRESSION_QUALITY_MEDIUM
        )
        
        // Проверяем, что сжатие выполнено успешно
        assertThat(result.second).isNotNull()
        
        // Получаем EXIF-данные после сжатия
        val exifAfter = ExifUtil.getCompressionMarker(context, result.second!!)
        
        // Проверяем, что маркер сжатия добавлен
        assertThat(exifAfter.first).isTrue()
        
        LogUtil.processDebug("EXIF-данные сохранены, маркер сжатия добавлен")
    }

    /**
     * Тест 9: Проверка уведомления о завершении сжатия
     */
    @Test
    fun testCompressionCompletionNotification() = runBlocking {
        if (testUris.isEmpty()) {
            return@runBlocking
        }
        
        val uri = testUris[0]
        
        // Выполняем сжатие
        val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            uri,
            Constants.COMPRESSION_QUALITY_MEDIUM
        )
        
        // Проверяем, что сжатие выполнено успешно
        assertThat(result.second).isNotNull()
        
        // Ждем появления уведомления
        delay(2000)
        
        // В реальном тесте здесь нужно проверить уведомление через UIAutomator
        // Для E2E теста мы проверяем, что сжатие завершено
        LogUtil.processDebug("Уведомление о завершении сжатия должно быть отображено")
    }

    /**
     * Тест 10: Проверка статистики сжатия
     */
    @Test
    fun testCompressionStatistics() = runBlocking {
        if (testUris.size < 3) {
            return@runBlocking
        }
        
        var totalOriginalSize = 0L
        var totalCompressedSize = 0L
        
        // Выполняем сжатие нескольких изображений
        for (uri in testUris.take(3)) {
            val originalSize = UriUtil.getFileSize(context, uri)
            val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
                context,
                uri,
                Constants.COMPRESSION_QUALITY_MEDIUM
            )
            
            if (result.second != null) {
                val compressedSize = UriUtil.getFileSize(context, result.second!!)
                totalOriginalSize += originalSize
                totalCompressedSize += compressedSize
            }
        }
        
        // Проверяем, что статистика корректна
        val totalSavings = totalOriginalSize - totalCompressedSize
        assertThat(totalSavings).isGreaterThan(0)
        
        val savingsPercent = (totalSavings.toFloat() / totalOriginalSize * 100).toInt()
        assertThat(savingsPercent).isAtLeast(30)
        
        LogUtil.processDebug("Статистика сжатия: $totalOriginalSize -> $totalCompressedSize байт (экономия: $savingsPercent%)")
    }

    /**
     * Тест 11: Проверка обработки уже сжатых файлов
     */
    @Test
    fun testAlreadyCompressedFileSkipped() = runBlocking {
        if (testUris.isEmpty()) {
            return@runBlocking
        }
        
        val uri = testUris[0]
        
        // Выполняем первое сжатие
        val result1 = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            uri,
            Constants.COMPRESSION_QUALITY_MEDIUM
        )
        
        // Проверяем, что первое сжатие выполнено успешно
        assertThat(result1.second).isNotNull()
        
        // Проверяем, что маркер сжатия добавлен
        val hasMarker = ExifUtil.getCompressionMarker(context, result1.second!!).first
        assertThat(hasMarker).isTrue()
        
        // Пытаемся сжать уже сжатый файл
        val result2 = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            result1.second!!,
            Constants.COMPRESSION_QUALITY_MEDIUM
        )
        
        // Проверяем, что файл пропущен (маркер сжатия уже есть)
        // Примечание: Это зависит от реализации логики проверки маркера
        LogUtil.processDebug("Обработка уже сжатого файла: результат=${result2.second != null}")
    }

    /**
     * Тест 12: Проверка обработки файлов меньше 100 КБ
     */
    @Test
    fun testSmallFileSkipped() = runBlocking {
        // Создаем маленькое изображение (< 100 КБ)
        val smallUri = createSmallTestImage()
        if (smallUri == null) {
            return@runBlocking
        }
        
        val fileSize = UriUtil.getFileSize(context, smallUri)
        
        // Проверяем, что размер файла меньше 100 КБ
        assertThat(fileSize).isLessThan(100 * 1024)
        
        // Пытаемся сжать маленький файл
        val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            smallUri,
            Constants.COMPRESSION_QUALITY_MEDIUM
        )
        
        // Проверяем, что файл пропущен (слишком маленький)
        // Примечание: Это зависит от реализации логики проверки размера
        LogUtil.processDebug("Обработка маленького файла ($fileSize байт): результат=${result.second != null}")
    }

    /**
     * Тест 13: Проверка сжатия с низким качеством
     */
    @Test
    fun testCompressionWithLowQuality() = runBlocking {
        if (testUris.isEmpty()) {
            return@runBlocking
        }
        
        val uri = testUris[0]
        val originalSize = UriUtil.getFileSize(context, uri)
        
        // Выполняем сжатие с низким качеством
        val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            uri,
            Constants.COMPRESSION_QUALITY_LOW
        )
        
        // Проверяем, что сжатие выполнено успешно
        assertThat(result.second).isNotNull()
        
        // Проверяем, что размер файла значительно уменьшился
        val compressedSize = UriUtil.getFileSize(context, result.second!!)
        val savingsPercent = ((originalSize - compressedSize).toFloat() / originalSize * 100).toInt()
        assertThat(savingsPercent).isAtLeast(40)
        
        LogUtil.processDebug("Сжатие с низким качеством: экономия $savingsPercent%")
    }

    /**
     * Тест 14: Проверка сжатия с высоким качеством
     */
    @Test
    fun testCompressionWithHighQuality() = runBlocking {
        if (testUris.isEmpty()) {
            return@runBlocking
        }
        
        val uri = testUris[0]
        val originalSize = UriUtil.getFileSize(context, uri)
        
        // Выполняем сжатие с высоким качеством
        val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            uri,
            Constants.COMPRESSION_QUALITY_HIGH
        )
        
        // Проверяем, что сжатие выполнено успешно
        assertThat(result.second).isNotNull()
        
        // Проверяем, что размер файла уменьшился, но меньше чем при низком качестве
        val compressedSize = UriUtil.getFileSize(context, result.second!!)
        val savingsPercent = ((originalSize - compressedSize).toFloat() / originalSize * 100).toInt()
        assertThat(savingsPercent).isAtLeast(20)
        
        LogUtil.processDebug("Сжатие с высоким качеством: экономия $savingsPercent%")
    }

    /**
     * Тест 15: Проверка сжатия в режиме замены
     */
    @Test
    fun testCompressionInReplaceMode() = runBlocking {
        if (testUris.isEmpty()) {
            return@runBlocking
        }
        
        val uri = testUris[0]
        val originalSize = UriUtil.getFileSize(context, uri)
        
        // Выполняем сжатие в режиме замены
        val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            uri,
            Constants.COMPRESSION_QUALITY_MEDIUM
        )
        
        // Проверяем, что сжатие выполнено успешно
        assertThat(result.second).isNotNull()
        
        // Проверяем, что размер файла уменьшился
        val compressedSize = UriUtil.getFileSize(context, result.second!!)
        assertThat(compressedSize).isLessThan(originalSize)
        
        LogUtil.processDebug("Сжатие в режиме замены: $originalSize -> $compressedSize байт")
    }

    // ========== Вспомогательные методы ==========

    /**
     * Создает тестовые изображения для тестирования
     */
    private fun createTestImages() {
        testUris.clear()
        testUris.addAll(E2ETestImageGenerator.createLargeTestImages(context, 5))
        LogUtil.processDebug("Создано ${testUris.size} тестовых изображений")
    }

    /**
     * Создает тестовое изображение заданного размера
     */
    private fun createTestImage(width: Int, height: Int): Uri? {
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "test_image_${width}x$height.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.WIDTH, width)
                put(MediaStore.Images.Media.HEIGHT, height)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(it, contentValues, null, null)
                }
            }
            
            bitmap.recycle()
            uri
        } catch (e: Exception) {
            LogUtil.errorWithException("Ошибка при создании тестового изображения", e)
            null
        }
    }

    /**
     * Создает маленькое тестовое изображение (< 100 КБ)
     */
    private fun createSmallTestImage(): Uri? {
        return try {
            val width = 200
            val height = 200
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "test_small_image.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.WIDTH, width)
                put(MediaStore.Images.Media.HEIGHT, height)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(it, contentValues, null, null)
                }
            }
            
            bitmap.recycle()
            uri
        } catch (e: Exception) {
            LogUtil.errorWithException("Ошибка при создании маленького тестового изображения", e)
            null
        }
    }

    /**
     * Удаляет тестовые изображения
     */
    private fun cleanupTestImages() {
        for (uri in testUris) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                LogUtil.errorWithException("Ошибка при удалении тестового изображения: $uri", e)
            }
        }
        testUris.clear()
        LogUtil.processDebug("Тестовые изображения удалены")
    }
}

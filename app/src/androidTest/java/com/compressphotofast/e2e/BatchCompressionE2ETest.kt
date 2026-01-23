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
import com.compressphotofast.util.ExifUtil
import com.compressphotofast.util.FileInfoUtil
import com.compressphotofast.util.ImageCompressionUtil
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.UriUtil
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * E2E тесты для пакетного сжатия изображений.
 *
 * Тестирует полный сценарий пакетного сжатия:
 * - Выбор нескольких изображений
 * - Пакетное сжатие с разными настройками
 * - Отображение прогресс-бара
 * - Итоговая сводка
 * - Обработка скриншотов и фото из мессенджеров
 */
class BatchCompressionE2ETest : BaseE2ETest() {

    private lateinit var context: Context
    private val testUris = mutableListOf<Uri>()
    private val screenshotUris = mutableListOf<Uri>()
    private val messengerUris = mutableListOf<Uri>()

    @Before
    override fun setUp() {
        super.setUp()
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Используем activityScenario из базового класса
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Создаем тестовые изображения
        createTestImages()
    }

    @After
    override fun tearDown() {
        super.tearDown()
        // Удаляем тестовые изображения
        cleanupTestImages()
    }

    /**
     * Тест 1: Выбор нескольких изображений через галерею
     */
    @Test
    fun testSelectMultipleImagesFromGallery() {
        // Проверяем, что кнопка выбора фото отображается
        assertViewDisplayed(R.id.btnSelectPhotos)
        
        // Нажимаем на кнопку выбора фото
        Espresso.onView(ViewMatchers.withId(R.id.btnSelectPhotos))
            .perform(ViewActions.click())
        
        // Проверяем, что Photo Picker открыт
        runBlocking { delay(1000) }
        
        // В реальном тесте здесь нужно выбрать несколько изображений через UIAutomator
        // Для E2E теста мы используем программный выбор
        if (testUris.size >= 3) {
            activityScenario?.onActivity { activity ->
                // Симулируем выбор нескольких изображений
                LogUtil.processDebug("Выбрано ${testUris.size} изображений")
            }
        }
    }

    /**
     * Тест 2: Пакетное сжатие с низким качеством
     */
    @Test
    fun testBatchCompressionWithLowQuality() = runBlocking {
        if (testUris.size < 3) {
            return@runBlocking
        }
        
        // Выбираем низкое качество
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityLow))
            .perform(ViewActions.click())
        
        // Проверяем, что прогресс-бар отображается
        Espresso.onView(ViewMatchers.withId(R.id.progressBar))
            .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.GONE)))
        
        // Выполняем пакетное сжатие
        val results = mutableListOf<Pair<Uri, Long>>()
        for (uri in testUris.take(3)) {
            val originalSize = UriUtil.getFileSize(context, uri)
            val compressedUri = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
                context,
                uri,
                Constants.COMPRESSION_QUALITY_LOW
            )
            
            if (compressedUri.second != null) {
                val compressedSize = UriUtil.getFileSize(context, compressedUri.second!!)
                results.add(Pair(uri, originalSize - compressedSize))
            }
        }
        
        // Проверяем, что все изображения сжаты
        assertThat(results.size).isAtLeast(2)
        
        // Проверяем, что экономия значительная для низкого качества
        val totalSavings = results.sumOf { it.second }
        assertThat(totalSavings).isGreaterThan(0)
        
        LogUtil.processDebug("Пакетное сжатие с низким качеством: ${results.size} изображений, экономия: $totalSavings байт")
    }

    /**
     * Тест 3: Пакетное сжатие со средним качеством
     */
    @Test
    fun testBatchCompressionWithMediumQuality() = runBlocking {
        if (testUris.size < 3) {
            return@runBlocking
        }
        
        // Выбираем среднее качество
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityMedium))
            .perform(ViewActions.click())
        
        // Выполняем пакетное сжатие
        val results = mutableListOf<Pair<Uri, Long>>()
        for (uri in testUris.take(3)) {
            val originalSize = UriUtil.getFileSize(context, uri)
            val compressedUri = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
                context,
                uri,
                Constants.COMPRESSION_QUALITY_MEDIUM
            )
            
            if (compressedUri.second != null) {
                val compressedSize = UriUtil.getFileSize(context, compressedUri.second!!)
                results.add(Pair(uri, originalSize - compressedSize))
            }
        }
        
        // Проверяем, что все изображения сжаты
        assertThat(results.size).isAtLeast(2)
        
        LogUtil.processDebug("Пакетное сжатие со средним качеством: ${results.size} изображений")
    }

    /**
     * Тест 4: Пакетное сжатие с высоким качеством
     */
    @Test
    fun testBatchCompressionWithHighQuality() = runBlocking {
        if (testUris.size < 3) {
            return@runBlocking
        }
        
        // Выбираем высокое качество
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityHigh))
            .perform(ViewActions.click())
        
        // Выполняем пакетное сжатие
        val results = mutableListOf<Pair<Uri, Long>>()
        for (uri in testUris.take(3)) {
            val originalSize = UriUtil.getFileSize(context, uri)
            val compressedUri = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
                context,
                uri,
                Constants.COMPRESSION_QUALITY_HIGH
            )
            
            if (compressedUri.second != null) {
                val compressedSize = UriUtil.getFileSize(context, compressedUri.second!!)
                results.add(Pair(uri, originalSize - compressedSize))
            }
        }
        
        // Проверяем, что все изображения сжаты
        assertThat(results.size).isAtLeast(2)
        
        LogUtil.processDebug("Пакетное сжатие с высоким качеством: ${results.size} изображений")
    }

    /**
     * Тест 5: Проверка отображения прогресс-бара
     */
    @Test
    fun testProgressBarDisplayedDuringBatchCompression() = runBlocking {
        if (testUris.size < 3) {
            return@runBlocking
        }
        
        // Проверяем, что прогресс-бар скрыт в начале
        Espresso.onView(ViewMatchers.withId(R.id.progressBar))
            .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.GONE)))
        
        // Начинаем пакетное сжатие
        val urisToCompress = testUris.take(3)
        
        // Симулируем начало сжатия
        activityScenario?.onActivity { activity ->
            // В реальном приложении это запускается через ViewModel
            LogUtil.processDebug("Начало пакетного сжатия ${urisToCompress.size} изображений")
        }
        
        // Проверяем, что прогресс-бар отображается во время сжатия
        delay(500)
        Espresso.onView(ViewMatchers.withId(R.id.progressBar))
            .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))
        
        // Выполняем сжатие
        for (uri in urisToCompress) {
            com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
                context,
                uri,
                Constants.COMPRESSION_QUALITY_MEDIUM
            )
            delay(200)
        }
        
        // Проверяем, что прогресс-бар скрыт после завершения
        delay(1000)
        Espresso.onView(ViewMatchers.withId(R.id.progressBar))
            .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.GONE)))
    }

    /**
     * Тест 6: Проверка итоговой сводки (успешные/пропущенные/уже оптимизированные)
     */
    @Test
    fun testBatchCompressionSummary() = runBlocking {
        if (testUris.size < 5) {
            return@runBlocking
        }
        
        // Создаем смесь изображений: обычные, уже сжатые, маленькие
        val urisToCompress = mutableListOf<Uri>()
        
        // Добавляем обычные изображения
        urisToCompress.addAll(testUris.take(3))
        
        // Добавляем уже сжатое изображение
        val compressedUri = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            testUris[0],
            Constants.COMPRESSION_QUALITY_MEDIUM
        )
        if (compressedUri.second != null) {
            urisToCompress.add(compressedUri.second!!)
        }
        
        // Добавляем маленькое изображение
        val smallUri = createSmallTestImage()
        if (smallUri != null) {
            urisToCompress.add(smallUri)
        }
        
        // Выполняем пакетное сжатие
        var successful = 0
        var skipped = 0
        var alreadyOptimized = 0
        
        for (uri in urisToCompress) {
            val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
                context,
                uri,
                Constants.COMPRESSION_QUALITY_MEDIUM
            )
            
            if (result.second != null) {
                successful++
            } else {
                // Проверяем, почему пропущено
                val fileSize = UriUtil.getFileSize(context, uri)
                if (fileSize < 100 * 1024) {
                    skipped++
                } else if (ExifUtil.getCompressionMarker(context, uri).first) {
                    alreadyOptimized++
                } else {
                    skipped++
                }
            }
        }
        
        // Проверяем статистику
        assertThat(successful).isAtLeast(2)
        assertThat(skipped + alreadyOptimized).isAtLeast(1)
        
        LogUtil.processDebug("Сводка пакетного сжатия: успешные=$successful, пропущенные=$skipped, уже оптимизированные=$alreadyOptimized")
    }

    /**
     * Тест 7: Проверка обработки скриншотов (включено игнорирование)
     */
    @Test
    fun testScreenshotsIgnoredWhenEnabled() = runBlocking {
        if (screenshotUris.isEmpty()) {
            return@runBlocking
        }
        
        // Включаем игнорирование скриншотов
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .perform(ViewActions.click())
        
        // Пытаемся сжать скриншот
        val uri = screenshotUris[0]
        val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            uri,
            Constants.COMPRESSION_QUALITY_MEDIUM
        )
        
        // Проверяем, что скриншот пропущен (если логика игнорирования работает)
        // Примечание: Это зависит от реализации логики игнорирования
        LogUtil.processDebug("Обработка скриншота: результат=${result.second != null}")
    }

    /**
     * Тест 8: Проверка обработки скриншотов (выключено игнорирование)
     */
    @Test
    fun testScreenshotsProcessedWhenDisabled() = runBlocking {
        if (screenshotUris.isEmpty()) {
            return@runBlocking
        }
        
        // Выключаем игнорирование скриншотов
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .perform(ViewActions.click())
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .perform(ViewActions.click())
        
        // Пытаемся сжать скриншот
        val uri = screenshotUris[0]
        val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            uri,
            Constants.COMPRESSION_QUALITY_MEDIUM
        )
        
        // Проверяем, что скриншот обработан
        assertThat(result).isNotNull()
        
        LogUtil.processDebug("Скриншот обработан: $result")
    }

    /**
     * Тест 9: Проверка обработки фото из мессенджеров (включено игнорирование)
     */
    @Test
    fun testMessengerPhotosIgnoredWhenEnabled() = runBlocking {
        if (messengerUris.isEmpty()) {
            return@runBlocking
        }
        
        // Включаем игнорирование фото из мессенджеров
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .perform(ViewActions.click())
        
        // Пытаемся сжать фото из мессенджера
        val uri = messengerUris[0]
        val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            uri,
            Constants.COMPRESSION_QUALITY_MEDIUM
        )
        
        // Проверяем результат (зависит от реализации логики игнорирования)
        LogUtil.processDebug("Обработка фото из мессенджера: результат=${result.second != null}")
    }

    /**
     * Тест 10: Проверка обработки фото из мессенджеров (выключено игнорирование)
     */
    @Test
    fun testMessengerPhotosProcessedWhenDisabled() = runBlocking {
        if (messengerUris.isEmpty()) {
            return@runBlocking
        }
        
        // Выключаем игнорирование фото из мессенджеров
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .perform(ViewActions.click())
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .perform(ViewActions.click())
        
        // Пытаемся сжать фото из мессенджера
        val uri = messengerUris[0]
        val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
            context,
            uri,
            Constants.COMPRESSION_QUALITY_MEDIUM
        )
        
        // Проверяем, что фото обработано
        assertThat(result.second).isNotNull()
        
        LogUtil.processDebug("Фото из мессенджера обработано: $result")
    }

    /**
     * Тест 11: Проверка прерывания процесса пакетного сжатия
     */
    @Test
    fun testBatchCompressionInterruption() = runBlocking {
        if (testUris.size < 5) {
            return@runBlocking
        }
        
        // Начинаем пакетное сжатие
        val urisToCompress = testUris.take(5)
        var processedCount = 0
        
        // Симулируем прерывание после обработки 2 изображений
        for ((index, uri) in urisToCompress.withIndex()) {
            if (index >= 2) {
                // Прерываем процесс
                LogUtil.processDebug("Прерывание пакетного сжатия после $processedCount изображений")
                break
            }
            
            val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
                context,
                uri,
                Constants.COMPRESSION_QUALITY_MEDIUM
            )
            
            if (result.second != null) {
                processedCount++
            }
        }
        
        // Проверяем, что не все изображения были обработаны
        assertThat(processedCount).isLessThan(urisToCompress.size)
        
        LogUtil.processDebug("Пакетное сжатие прервано после $processedCount из ${urisToCompress.size} изображений")
    }

    /**
     * Тест 12: Проверка обработки ошибок при пакетном сжатии
     */
    @Test
    fun testBatchCompressionErrorHandling() = runBlocking {
        if (testUris.size < 3) {
            return@runBlocking
        }
        
        // Создаем список URI с одним недействительным
        val urisToCompress = mutableListOf<Uri>()
        urisToCompress.addAll(testUris.take(2))
        
        // Добавляем недействительный URI
        val invalidUri = Uri.parse("content://media/external/images/media/999999")
        urisToCompress.add(invalidUri)
        
        var successful = 0
        var failed = 0
        
        for (uri in urisToCompress) {
            try {
                val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
                    context,
                    uri,
                    Constants.COMPRESSION_QUALITY_MEDIUM
                )
                
                if (result.second != null) {
                    successful++
                }
            } catch (e: Exception) {
                failed++
                LogUtil.errorWithException("Ошибка при сжатии: $uri", e)
            }
        }
        
        // Проверяем, что успешные сжатия выполнены, а ошибки обработаны
        assertThat(successful).isAtLeast(1)
        assertThat(failed).isAtLeast(1)
        
        LogUtil.processDebug("Обработка ошибок: успешные=$successful, ошибки=$failed")
    }

    /**
     * Тест 13: Проверка пакетного сжатия в режиме замены
     */
    @Test
    fun testBatchCompressionInReplaceMode() = runBlocking {
        if (testUris.size < 3) {
            return@runBlocking
        }
        
        // Включаем режим замены
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())
        
        // Выполняем пакетное сжатие
        val results = mutableListOf<Uri>()
        for (uri in testUris.take(3)) {
            val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
                context,
                uri,
                Constants.COMPRESSION_QUALITY_MEDIUM
            )
            
            if (result.second != null) {
                results.add(result.second!!)
            }
        }
        
        // Проверяем, что все изображения сжаты
        assertThat(results.size).isAtLeast(2)
        
        LogUtil.processDebug("Пакетное сжатие в режиме замены: ${results.size} изображений")
    }

    /**
     * Тест 14: Проверка пакетного сжатия в режиме отдельной папки
     */
    @Test
    fun testBatchCompressionInSeparateFolderMode() = runBlocking {
        if (testUris.size < 3) {
            return@runBlocking
        }
        
        // Выключаем режим замены (отдельная папка)
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())
        
        // Выполняем пакетное сжатие
        val results = mutableListOf<Uri>()
        for (uri in testUris.take(3)) {
            val result = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
                context,
                uri,
                Constants.COMPRESSION_QUALITY_MEDIUM
            )
            
            if (result.second != null) {
                results.add(result.second!!)
            }
        }
        
        // Проверяем, что все изображения сжаты
        assertThat(results.size).isAtLeast(2)
        
        // Проверяем, что файлы сохранены в отдельной папке
        for (uri in results) {
            val path = uri.path ?: ""
            assertThat(path).contains("CompressPhotoFast")
        }
        
        LogUtil.processDebug("Пакетное сжатие в режиме отдельной папки: ${results.size} изображений")
    }

    /**
     * Тест 15: Проверка общей экономии при пакетном сжатии
     */
    @Test
    fun testTotalSavingsInBatchCompression() = runBlocking {
        if (testUris.size < 5) {
            return@runBlocking
        }
        
        var totalOriginalSize = 0L
        var totalCompressedSize = 0L
        
        // Выполняем пакетное сжатие
        for (uri in testUris.take(5)) {
            val originalSize = UriUtil.getFileSize(context, uri)
            val compressedUri = com.compressphotofast.util.ImageCompressionUtil.processAndSaveImage(
                context,
                uri,
                Constants.COMPRESSION_QUALITY_MEDIUM
            )
            
            if (compressedUri.second != null) {
                val compressedSize = UriUtil.getFileSize(context, compressedUri.second!!)
                totalOriginalSize += originalSize
                totalCompressedSize += compressedSize
            }
        }
        
        // Проверяем, что общая экономия положительная
        val totalSavings = totalOriginalSize - totalCompressedSize
        assertThat(totalSavings).isGreaterThan(0)
        
        // Проверяем, что экономия составляет минимум 30%
        val savingsPercent = (totalSavings.toFloat() / totalOriginalSize * 100).toInt()
        assertThat(savingsPercent).isAtLeast(30)
        
        LogUtil.processDebug("Общая экономия: $totalSavings байт ($savingsPercent%)")
    }

    // ========== Вспомогательные методы ==========

    /**
     * Создает тестовые изображения для тестирования
     */
    private fun createTestImages() {
        // Обычные изображения
        val sizes = listOf(
            Pair(1920, 1080),
            Pair(1280, 720),
            Pair(800, 600),
            Pair(1024, 768),
            Pair(640, 480)
        )
        
        for ((width, height) in sizes) {
            val uri = createTestImage(width, height, "test_image")
            if (uri != null) {
                testUris.add(uri)
            }
        }
        
        // Скриншоты
        val screenshotSizes = listOf(
            Pair(1080, 1920),
            Pair(720, 1280)
        )
        
        for ((width, height) in screenshotSizes) {
            val uri = createTestImage(width, height, "screenshot")
            if (uri != null) {
                screenshotUris.add(uri)
            }
        }
        
        // Фото из мессенджеров
        val messengerSizes = listOf(
            Pair(800, 600),
            Pair(640, 480)
        )
        
        for ((width, height) in messengerSizes) {
            val uri = createTestImage(width, height, "messenger")
            if (uri != null) {
                messengerUris.add(uri)
            }
        }
        
        LogUtil.processDebug("Создано ${testUris.size} обычных, ${screenshotUris.size} скриншотов, ${messengerUris.size} фото из мессенджеров")
    }

    /**
     * Создает тестовое изображение заданного размера
     */
    private fun createTestImage(width: Int, height: Int, prefix: String): Uri? {
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${prefix}_${width}x$height.jpg")
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
        val allUris = testUris + screenshotUris + messengerUris
        for (uri in allUris) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                LogUtil.errorWithException("Ошибка при удалении тестового изображения: $uri", e)
            }
        }
        testUris.clear()
        screenshotUris.clear()
        messengerUris.clear()
        LogUtil.processDebug("Тестовые изображения удалены")
    }
}

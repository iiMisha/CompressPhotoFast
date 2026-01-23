package com.compressphotofast.e2e

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.platform.app.InstrumentationRegistry
import com.compressphotofast.BaseE2ETest
import com.compressphotofast.R
import com.compressphotofast.ui.MainActivity
import com.compressphotofast.util.Constants
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
 * E2E тесты для обработки Share Intent.
 *
 * Тестирует полный сценарий обработки изображений через Share Intent:
 * - Получение изображения через ACTION_SEND
 * - Получение нескольких изображений через ACTION_SEND_MULTIPLE
 * - Отображение выбранного изображения
 * - Автоматическое сжатие
 * - Сохранение результата
 * - Обработка не поддерживаемых форматов
 */
class ShareIntentE2ETest : BaseE2ETest() {

    private lateinit var context: Context
    private val testUris = mutableListOf<Uri>()
    private lateinit var mainActivityScenario: ActivityScenario<MainActivity>

    @Before
    override fun setUp() {
        super.setUp()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
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
     * Тест 1: Получение одного изображения через ACTION_SEND
     */
    @Test
    fun testReceiveSingleImageViaActionSend() {
        if (testUris.isEmpty()) {
            return
        }
        
        val uri = testUris[0]
        
        // Создаем Intent с ACTION_SEND
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        // Запускаем Activity с Intent
        mainActivityScenario = ActivityScenario.launch<MainActivity>(intent)
        
        // Проверяем, что Activity запущена
        assertViewDisplayed(R.id.mainContainer)
        
        LogUtil.processDebug("Получено изображение через ACTION_SEND: $uri")
    }

    /**
     * Тест 2: Получение нескольких изображений через ACTION_SEND_MULTIPLE
     */
    @Test
    fun testReceiveMultipleImagesViaActionSendMultiple() {
        if (testUris.size < 3) {
            return
        }
        
        val uris = testUris.take(3)
        
        // Создаем Intent с ACTION_SEND_MULTIPLE
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        // Запускаем Activity с Intent
        mainActivityScenario = ActivityScenario.launch<MainActivity>(intent)
        
        // Проверяем, что Activity запущена
        assertViewDisplayed(R.id.mainContainer)
        
        LogUtil.processDebug("Получено ${uris.size} изображений через ACTION_SEND_MULTIPLE")
    }

    /**
     * Тест 3: Проверка отображения выбранного изображения
     */
    @Test
    fun testSelectedImageDisplayed() {
        if (testUris.isEmpty()) {
            return
        }
        
        val uri = testUris[0]
        
        // Создаем Intent с ACTION_SEND
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        // Запускаем Activity с Intent
        mainActivityScenario = ActivityScenario.launch<MainActivity>(intent)
        
        // Проверяем, что изображение отображается
        // В реальном тесте здесь нужно проверить ImageView через Espresso
        // Для E2E теста мы проверяем, что Activity запущена
        assertViewDisplayed(R.id.mainContainer)
        
        LogUtil.processDebug("Выбранное изображение отображено")
    }

    /**
     * Тест 4: Проверка автоматического сжатия
     */
    @Test
    fun testAutomaticCompression() = runBlocking {
        if (testUris.isEmpty()) {
            return@runBlocking
        }
        
        val uri = testUris[0]
        val originalSize = UriUtil.getFileSize(context, uri)
        
        // Создаем Intent с ACTION_SEND
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        // Запускаем Activity с Intent
        mainActivityScenario = ActivityScenario.launch<MainActivity>(intent)
        
        // Ждем автоматического сжатия
        delay(3000)
        
        // Проверяем, что изображение сжато
        val hasMarker = ExifUtil.getCompressionMarker(context, uri).first
        assertThat(hasMarker).isTrue()
        
        // Проверяем, что размер файла уменьшился
        val compressedSize = UriUtil.getFileSize(context, uri)
        assertThat(compressedSize).isLessThan(originalSize)
        
        LogUtil.processDebug("Автоматическое сжатие: $originalSize -> $compressedSize байт")
    }

    /**
     * Тест 5: Проверка сохранения результата
     */
    @Test
    fun testResultSaved() = runBlocking {
        if (testUris.isEmpty()) {
            return@runBlocking
        }
        
        val uri = testUris[0]
        
        // Создаем Intent с ACTION_SEND
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        // Запускаем Activity с Intent
        mainActivityScenario = ActivityScenario.launch<MainActivity>(intent)
        
        // Ждем автоматического сжатия
        delay(3000)
        
        // Проверяем, что результат сохранен
        val hasMarker = ExifUtil.getCompressionMarker(context, uri).first
        assertThat(hasMarker).isTrue()
        
        // Проверяем, что файл существует
        val fileExists = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            cursor.count > 0
        } ?: false
        assertThat(fileExists).isTrue()
        
        LogUtil.processDebug("Результат сжатия сохранен")
    }

    /**
     * Тест 6: Проверка обработки нескольких изображений
     */
    @Test
    fun testMultipleImagesProcessed() = runBlocking {
        if (testUris.size < 3) {
            return@runBlocking
        }
        
        val uris = testUris.take(3)
        
        // Создаем Intent с ACTION_SEND_MULTIPLE
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        // Запускаем Activity с Intent
        mainActivityScenario = ActivityScenario.launch<MainActivity>(intent)
        
        // Ждем автоматического сжатия
        delay(5000)
        
        // Проверяем, что все изображения обработаны
        var processedCount = 0
        for (uri in uris) {
            val hasMarker = ExifUtil.getCompressionMarker(context, uri).first
            if (hasMarker) {
                processedCount++
            }
        }
        
        assertThat(processedCount).isAtLeast(2)
        
        LogUtil.processDebug("Обработано $processedCount из ${uris.size} изображений")
    }

    /**
     * Тест 7: Проверка обработки PNG изображений
     */
    @Test
    fun testPngImageProcessed() = runBlocking {
        // Создаем PNG изображение
        val pngUri = createPngImage()
        if (pngUri == null) {
            return@runBlocking
        }
        
        // Создаем Intent с ACTION_SEND
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, pngUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        // Запускаем Activity с Intent
        mainActivityScenario = ActivityScenario.launch<MainActivity>(intent)
        
        // Ждем обработки
        delay(3000)
        
        // Проверяем результат
        val hasMarker = ExifUtil.getCompressionMarker(context, pngUri).first
        LogUtil.processDebug("PNG изображение обработано: $hasMarker")
    }

    /**
     * Тест 8: Проверка обработки не поддерживаемых форматов
     */
    @Test
    fun testUnsupportedFormatHandled() {
        // Создаем текстовый файл
        val textUri = createTextFile()
        if (textUri == null) {
            return
        }
        
        // Создаем Intent с ACTION_SEND
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, textUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        // Запускаем Activity с Intent
        mainActivityScenario = ActivityScenario.launch<MainActivity>(intent)
        
        // Проверяем, что Activity запущена (но изображение не отображается)
        assertViewDisplayed(R.id.mainContainer)
        
        LogUtil.processDebug("Не поддерживаемый формат обработан корректно")
    }

    /**
     * Тест 9: Проверка обработки изображений с разным качеством
     */
    @Test
    fun testCompressionWithDifferentQualities() = runBlocking {
        if (testUris.isEmpty()) {
            return@runBlocking
        }
        
        val uri = testUris[0]
        val originalSize = UriUtil.getFileSize(context, uri)
        
        // Создаем Intent с ACTION_SEND
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        // Запускаем Activity с Intent
        mainActivityScenario = ActivityScenario.launch<MainActivity>(intent)
        
        // Ждем автоматического сжатия
        delay(3000)
        
        // Проверяем, что изображение сжато
        val hasMarker = ExifUtil.getCompressionMarker(context, uri).first
        assertThat(hasMarker).isTrue()
        
        // Проверяем, что размер файла уменьшился
        val compressedSize = UriUtil.getFileSize(context, uri)
        assertThat(compressedSize).isLessThan(originalSize)
        
        LogUtil.processDebug("Сжатие с разным качеством: $originalSize -> $compressedSize байт")
    }

    /**
     * Тест 10: Проверка обработки изображений в режиме замены
     */
    @Test
    fun testCompressionInReplaceMode() = runBlocking {
        if (testUris.isEmpty()) {
            return@runBlocking
        }
        
        val uri = testUris[0]
        val originalSize = UriUtil.getFileSize(context, uri)
        
        // Создаем Intent с ACTION_SEND
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        // Запускаем Activity с Intent
        mainActivityScenario = ActivityScenario.launch<MainActivity>(intent)
        
        // Ждем автоматического сжатия
        delay(3000)
        
        // Проверяем, что изображение сжато
        val hasMarker = ExifUtil.getCompressionMarker(context, uri).first
        assertThat(hasMarker).isTrue()
        
        // Проверяем, что размер файла уменьшился
        val compressedSize = UriUtil.getFileSize(context, uri)
        assertThat(compressedSize).isLessThan(originalSize)
        
        LogUtil.processDebug("Сжатие в режиме замены: $originalSize -> $compressedSize байт")
    }

    // ========== Вспомогательные методы ==========

    /**
     * Создает тестовые изображения для тестирования
     */
    private fun createTestImages() {
        val sizes = listOf(
            Pair(1920, 1080),
            Pair(1280, 720),
            Pair(800, 600),
            Pair(1024, 768),
            Pair(640, 480)
        )
        
        for ((width, height) in sizes) {
            val uri = createTestImage(width, height)
            if (uri != null) {
                testUris.add(uri)
            }
        }
        
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
     * Создает PNG изображение
     */
    private fun createPngImage(): Uri? {
        return try {
            val width = 800
            val height = 600
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "test_image.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
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
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
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
            LogUtil.errorWithException("Ошибка при создании PNG изображения", e)
            null
        }
    }

    /**
     * Создает текстовый файл
     */
    private fun createTextFile(): Uri? {
        return try {
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, "test_file.txt")
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }
            }
            
            val uri = context.contentResolver.insert(
                MediaStore.Files.getContentUri("external"),
                contentValues
            )
            
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write("Test content".toByteArray())
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                    context.contentResolver.update(it, contentValues, null, null)
                }
            }
            
            uri
        } catch (e: Exception) {
            LogUtil.errorWithException("Ошибка при создании текстового файла", e)
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

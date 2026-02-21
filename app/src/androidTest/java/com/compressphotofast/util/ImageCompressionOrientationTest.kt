package com.compressphotofast.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ImageCompressionOrientationTest {

    private lateinit var targetContext: Context
    private lateinit var testContext: Context
    private lateinit var tempDir: File
    private val testFiles = mutableListOf<File>()

    @Before
    fun setup() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        testContext = InstrumentationRegistry.getInstrumentation().context
        tempDir = targetContext.cacheDir.resolve("orientation_e2e_test").apply { mkdirs() }
    }

    @After
    fun cleanup() {
        testFiles.forEach { it.delete() }
        tempDir.deleteRecursively()
    }

    private fun copyAssetToTempFile(assetPath: String): File {
        val tempFile = tempDir.resolve(assetPath.substringAfterLast("/"))
        testContext.assets.open(assetPath).use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        testFiles.add(tempFile)
        return tempFile
    }

    @Test
    fun compressImage_with90DegreeOrientation_producesCorrectlyRotatedBitmap() = runBlocking {
        val inputFile = copyAssetToTempFile("orientation/test_rotate_90.jpg")
        val inputUri = Uri.fromFile(inputFile)

        val originalBitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
        val originalWidth = originalBitmap.width
        val originalHeight = originalBitmap.height
        originalBitmap.recycle()

        val outputStream: ByteArrayOutputStream = ImageCompressionUtil.compressImageToStream(targetContext, inputUri, 85)!!
        assertNotNull("Сжатие должно быть успешным", outputStream)
        assertTrue("Результат не пустой", outputStream.size() > 0)

        val outputFile = tempDir.resolve("output_rotate_90.jpg")
        outputFile.outputStream().use { outputStream.writeTo(it) }

        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertNotNull("Bitmap должен быть создан", outputBitmap)

        assertEquals("Ширина и высота должны поменяться местами после поворота на 90°",
            originalHeight, outputBitmap!!.width)
        assertEquals("Ширина и высота должны поменяться местами после поворота на 90°",
            originalWidth, outputBitmap.height)

        outputStream.close()
    }

    @Test
    fun compressImage_with180DegreeOrientation_producesCorrectlyRotatedBitmap() = runBlocking {
        val inputFile = copyAssetToTempFile("orientation/test_rotate_180.jpg")
        val inputUri = Uri.fromFile(inputFile)

        val originalBitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
        val originalWidth = originalBitmap.width
        val originalHeight = originalBitmap.height
        originalBitmap.recycle()

        val outputStream: ByteArrayOutputStream = ImageCompressionUtil.compressImageToStream(targetContext, inputUri, 85)!!
        assertNotNull("Сжатие должно быть успешным", outputStream)
        assertTrue("Результат не пустой", outputStream.size() > 0)

        val outputFile = tempDir.resolve("output_rotate_180.jpg")
        outputFile.outputStream().use { outputStream.writeTo(it) }

        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertNotNull("Bitmap должен быть создан", outputBitmap)

        assertEquals("Размеры не должны измениться после поворота на 180°",
            originalWidth, outputBitmap!!.width)
        assertEquals("Размеры не должны измениться после поворота на 180°",
            originalHeight, outputBitmap.height)

        outputStream.close()
    }

    @Test
    fun compressImage_with270DegreeOrientation_producesCorrectlyRotatedBitmap() = runBlocking {
        val inputFile = copyAssetToTempFile("orientation/test_rotate_270.jpg")
        val inputUri = Uri.fromFile(inputFile)

        val originalBitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
        val originalWidth = originalBitmap.width
        val originalHeight = originalBitmap.height
        originalBitmap.recycle()

        val outputStream: ByteArrayOutputStream = ImageCompressionUtil.compressImageToStream(targetContext, inputUri, 85)!!
        assertNotNull("Сжатие должно быть успешным", outputStream)
        assertTrue("Результат не пустой", outputStream.size() > 0)

        val outputFile = tempDir.resolve("output_rotate_270.jpg")
        outputFile.outputStream().use { outputStream.writeTo(it) }

        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertNotNull("Bitmap должен быть создан", outputBitmap)

        assertEquals("Ширина и высота должны поменяться местами после поворота на 270°",
            originalHeight, outputBitmap!!.width)
        assertEquals("Ширина и высота должны поменяться местами после поворота на 270°",
            originalWidth, outputBitmap.height)

        outputStream.close()
    }

    @Test
    fun compressImage_withFlipHorizontal_producesCorrectlyFlippedBitmap() = runBlocking {
        val inputFile = copyAssetToTempFile("orientation/test_flip_horizontal.jpg")
        val inputUri = Uri.fromFile(inputFile)

        val originalBitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
        val originalWidth = originalBitmap.width
        val originalHeight = originalBitmap.height
        originalBitmap.recycle()

        val outputStream: ByteArrayOutputStream = ImageCompressionUtil.compressImageToStream(targetContext, inputUri, 85)!!
        assertNotNull("Сжатие должно быть успешным", outputStream)
        assertTrue("Результат не пустой", outputStream.size() > 0)

        val outputFile = tempDir.resolve("output_flip_h.jpg")
        outputFile.outputStream().use { outputStream.writeTo(it) }

        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertNotNull("Bitmap должен быть создан", outputBitmap)

        assertEquals("Размеры не должны измениться после flip horizontal",
            originalWidth, outputBitmap!!.width)
        assertEquals("Размеры не должны измениться после flip horizontal",
            originalHeight, outputBitmap.height)

        outputStream.close()
    }

    @Test
    fun compressImage_withFlipVertical_producesCorrectlyFlippedBitmap() = runBlocking {
        val inputFile = copyAssetToTempFile("orientation/test_flip_vertical.jpg")
        val inputUri = Uri.fromFile(inputFile)

        val originalBitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
        val originalWidth = originalBitmap.width
        val originalHeight = originalBitmap.height
        originalBitmap.recycle()

        val outputStream: ByteArrayOutputStream = ImageCompressionUtil.compressImageToStream(targetContext, inputUri, 85)!!
        assertNotNull("Сжатие должно быть успешным", outputStream)
        assertTrue("Результат не пустой", outputStream.size() > 0)

        val outputFile = tempDir.resolve("output_flip_v.jpg")
        outputFile.outputStream().use { outputStream.writeTo(it) }

        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertNotNull("Bitmap должен быть создан", outputBitmap)

        assertEquals("Размеры не должны измениться после flip vertical",
            originalWidth, outputBitmap!!.width)
        assertEquals("Размеры не должны измениться после flip vertical",
            originalHeight, outputBitmap.height)

        outputStream.close()
    }

    @Test
    fun compressImage_normalOrientation_remainsUnchanged() = runBlocking {
        val inputFile = copyAssetToTempFile("orientation/test_normal.jpg")
        val inputUri = Uri.fromFile(inputFile)

        val originalBitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
        val originalWidth = originalBitmap.width
        val originalHeight = originalBitmap.height
        originalBitmap.recycle()

        val outputStream: ByteArrayOutputStream = ImageCompressionUtil.compressImageToStream(targetContext, inputUri, 85)!!
        assertNotNull("Сжатие должно быть успешным", outputStream)
        assertTrue("Результат не пустой", outputStream.size() > 0)

        val outputFile = tempDir.resolve("output_normal.jpg")
        outputFile.outputStream().use { outputStream.writeTo(it) }

        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertEquals("Ширина не должна измениться", originalWidth, outputBitmap!!.width)
        assertEquals("Высота не должна измениться", originalHeight, outputBitmap.height)

        outputStream.close()
    }

    @Test
    fun compressImage_transposeOrientation_producesCorrectlyTransformedBitmap() = runBlocking {
        val inputFile = copyAssetToTempFile("orientation/test_transpose.jpg")
        val inputUri = Uri.fromFile(inputFile)

        val originalBitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
        val originalWidth = originalBitmap.width
        val originalHeight = originalBitmap.height
        originalBitmap.recycle()

        val outputStream: ByteArrayOutputStream = ImageCompressionUtil.compressImageToStream(targetContext, inputUri, 85)!!
        assertNotNull("Сжатие должно быть успешным", outputStream)
        assertTrue("Результат не пустой", outputStream.size() > 0)

        val outputFile = tempDir.resolve("output_transpose.jpg")
        outputFile.outputStream().use { outputStream.writeTo(it) }

        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertNotNull("Bitmap должен быть создан", outputBitmap)

        assertEquals("Ширина и высота должны поменяться местами после transpose (90° поворот)",
            originalHeight, outputBitmap!!.width)
        assertEquals("Ширина и высота должны поменяться местами после transpose (90° поворот)",
            originalWidth, outputBitmap.height)

        outputStream.close()
    }

    @Test
    fun compressImage_transverseOrientation_producesCorrectlyTransformedBitmap() = runBlocking {
        val inputFile = copyAssetToTempFile("orientation/test_transverse.jpg")
        val inputUri = Uri.fromFile(inputFile)

        val originalBitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
        val originalWidth = originalBitmap.width
        val originalHeight = originalBitmap.height
        originalBitmap.recycle()

        val outputStream: ByteArrayOutputStream = ImageCompressionUtil.compressImageToStream(targetContext, inputUri, 85)!!
        assertNotNull("Сжатие должно быть успешным", outputStream)
        assertTrue("Результат не пустой", outputStream.size() > 0)

        val outputFile = tempDir.resolve("output_transverse.jpg")
        outputFile.outputStream().use { outputStream.writeTo(it) }

        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertNotNull("Bitmap должен быть создан", outputBitmap)

        assertEquals("Ширина и высота должны поменяться местами после transverse (270° поворот)",
            originalHeight, outputBitmap!!.width)
        assertEquals("Ширина и высота должны поменяться местами после transverse (270° поворот)",
            originalWidth, outputBitmap.height)

        outputStream.close()
    }

    // ==================== HEIC Orientation Tests ====================

    /**
     * Декодирует HEIC файл в Bitmap используя ImageDecoder
     * BitmapFactory не поддерживает HEIC напрямую
     */
    private fun decodeHeicToBitmap(file: File): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source)
        } else {
            null // HEIC поддерживается только с API 28+
        }
    }

    @Test
    fun compressImage_heicWith90DegreeOrientation_producesCorrectlyRotatedBitmap() = runBlocking {
        val inputFile = copyAssetToTempFile("orientation/heic/test_rotate_90.heic")
        val inputUri = Uri.fromFile(inputFile)

        val originalBitmap = decodeHeicToBitmap(inputFile)
        assertNotNull("Оригинальный HEIC bitmap должен быть создан", originalBitmap)
        val originalWidth = originalBitmap!!.width
        val originalHeight = originalBitmap.height
        originalBitmap.recycle()

        val outputStream: ByteArrayOutputStream = ImageCompressionUtil.compressImageToStream(targetContext, inputUri, 85)!!
        assertNotNull("Сжатие должно быть успешным", outputStream)
        assertTrue("Результат не пустой", outputStream.size() > 0)

        val outputFile = tempDir.resolve("output_heic_rotate_90.jpg")
        outputFile.outputStream().use { outputStream.writeTo(it) }

        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertNotNull("Bitmap должен быть создан", outputBitmap)

        // ImageDecoder автоматически применяет EXIF orientation для HEIC
        // поэтому выходные размеры должны совпадать с исходными (уже повёрнутыми)
        assertEquals("Ширина HEIC с orientation=90 должна сохраниться (ImageDecoder уже применил поворот)",
            originalWidth, outputBitmap!!.width)
        assertEquals("Высота HEIC с orientation=90 должна сохраниться (ImageDecoder уже применил поворот)",
            originalHeight, outputBitmap.height)

        outputStream.close()
    }

    @Test
    fun compressImage_heicWith180DegreeOrientation_producesCorrectlyRotatedBitmap() = runBlocking {
        val inputFile = copyAssetToTempFile("orientation/heic/test_rotate_180.heic")
        val inputUri = Uri.fromFile(inputFile)

        val originalBitmap = decodeHeicToBitmap(inputFile)
        assertNotNull("Оригинальный HEIC bitmap должен быть создан", originalBitmap)
        val originalWidth = originalBitmap!!.width
        val originalHeight = originalBitmap.height
        originalBitmap.recycle()

        val outputStream: ByteArrayOutputStream = ImageCompressionUtil.compressImageToStream(targetContext, inputUri, 85)!!
        assertNotNull("Сжатие должно быть успешным", outputStream)
        assertTrue("Результат не пустой", outputStream.size() > 0)

        val outputFile = tempDir.resolve("output_heic_rotate_180.jpg")
        outputFile.outputStream().use { outputStream.writeTo(it) }

        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertNotNull("Bitmap должен быть создан", outputBitmap)

        assertEquals("Размеры не должны измениться для HEIC с orientation=180",
            originalWidth, outputBitmap!!.width)
        assertEquals("Размеры не должны измениться для HEIC с orientation=180",
            originalHeight, outputBitmap.height)

        outputStream.close()
    }

    @Test
    fun compressImage_heicWith270DegreeOrientation_producesCorrectlyRotatedBitmap() = runBlocking {
        val inputFile = copyAssetToTempFile("orientation/heic/test_rotate_270.heic")
        val inputUri = Uri.fromFile(inputFile)

        val originalBitmap = decodeHeicToBitmap(inputFile)
        assertNotNull("Оригинальный HEIC bitmap должен быть создан", originalBitmap)
        val originalWidth = originalBitmap!!.width
        val originalHeight = originalBitmap.height
        originalBitmap.recycle()

        val outputStream: ByteArrayOutputStream = ImageCompressionUtil.compressImageToStream(targetContext, inputUri, 85)!!
        assertNotNull("Сжатие должно быть успешным", outputStream)
        assertTrue("Результат не пустой", outputStream.size() > 0)

        val outputFile = tempDir.resolve("output_heic_rotate_270.jpg")
        outputFile.outputStream().use { outputStream.writeTo(it) }

        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertNotNull("Bitmap должен быть создан", outputBitmap)

        // ImageDecoder автоматически применяет EXIF orientation для HEIC
        assertEquals("Ширина HEIC с orientation=270 должна сохраниться (ImageDecoder уже применил поворот)",
            originalWidth, outputBitmap!!.width)
        assertEquals("Высота HEIC с orientation=270 должна сохраниться (ImageDecoder уже применил поворот)",
            originalHeight, outputBitmap.height)

        outputStream.close()
    }

    @Test
    fun compressImage_heicNormalOrientation_remainsUnchanged() = runBlocking {
        val inputFile = copyAssetToTempFile("orientation/heic/test_normal.heic")
        val inputUri = Uri.fromFile(inputFile)

        val originalBitmap = decodeHeicToBitmap(inputFile)
        assertNotNull("Оригинальный HEIC bitmap должен быть создан", originalBitmap)
        val originalWidth = originalBitmap!!.width
        val originalHeight = originalBitmap.height
        originalBitmap.recycle()

        val outputStream: ByteArrayOutputStream = ImageCompressionUtil.compressImageToStream(targetContext, inputUri, 85)!!
        assertNotNull("Сжатие должно быть успешным", outputStream)
        assertTrue("Результат не пустой", outputStream.size() > 0)

        val outputFile = tempDir.resolve("output_heic_normal.jpg")
        outputFile.outputStream().use { outputStream.writeTo(it) }

        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertNotNull("Bitmap должен быть создан", outputBitmap)

        assertEquals("Ширина HEIC не должна измениться", originalWidth, outputBitmap!!.width)
        assertEquals("Высота HEIC не должна измениться", originalHeight, outputBitmap.height)

        outputStream.close()
    }
}

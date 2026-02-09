package com.compressphotofast.util

import com.compressphotofast.BaseUnitTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import android.content.Context

/**
 * Unit тесты для FileOperationsUtil
 *
 * Тестируют методы утилиты:
 * - Проверка валидности размера файла (без контекста)
 * - Форматирование размера файла (без контекста)
 * - Сокращение длинных имен файлов (без контекста)
 * - createCompressedFileName с моком Context (двойные расширения)
 *
 * Примечание: Некоторые методы требуют интеграционного тестирования с Robolectric или Hilt:
 * - deleteFile (реальная работа с MediaStore)
 * - createTempImageFile (файловая система)
 * - findCompressedVersionByOriginalName (MediaStore queries)
 * - isScreenshot (UriUtil моки)
 */
class FileOperationsUtilTest : BaseUnitTest() {

    private lateinit var mockContext: Context
    private lateinit var mockSettingsManager: SettingsManager

    @Before
    override fun setUp() {
        super.setUp()
        mockContext = mockk()
        mockSettingsManager = mockk()

        // Мокаем SettingsManager.getInstance
        mockkStatic(SettingsManager::class)
        every { SettingsManager.getInstance(mockContext) } returns mockSettingsManager
    }

    @After
    override fun tearDown() {
        super.tearDown()
    }

    // ==================== createCompressedFileName (двойные расширения) ====================
    // ПРИМЕЧАНИЕ: Тесты для createCompressedFileName перемещены в instrumentation тесты
    // из-за необходимости реального Android Context для SettingsManager.
    // См. FileOperationsInstrumentedTest.kt в androidTest папке.

    // ==================== isFileSizeValid ====================
    
    @Test
    fun `isFileSizeValid returns true for valid size`() {
        // Arrange
        val validSize = 100 * 1024L // 100 KB (больше MIN_FILE_SIZE = 50 KB)
        
        // Act
        val result = FileOperationsUtil.isFileSizeValid(validSize)
        
        // Assert
        assertTrue(result)
    }
    
    @Test
    fun `isFileSizeValid returns false for size below minimum`() {
        // Arrange
        val tooSmallSize = 10L
        
        // Act
        val result = FileOperationsUtil.isFileSizeValid(tooSmallSize)
        
        // Assert
        assertFalse(result)
    }
    
    @Test
    fun `isFileSizeValid returns false for size above maximum`() {
        // Arrange
        val tooLargeSize = 200L * 1024 * 1024 // 200 MB
        
        // Act
        val result = FileOperationsUtil.isFileSizeValid(tooLargeSize)
        
        // Assert
        assertFalse(result)
    }
    
    @Test
    fun `isFileSizeValid returns false for minimum boundary`() {
        // Arrange
        val minSize = Constants.MIN_FILE_SIZE - 1
        
        // Act
        val result = FileOperationsUtil.isFileSizeValid(minSize)
        
        // Assert
        assertFalse(result)
    }
    
    @Test
    fun `isFileSizeValid returns true for minimum boundary`() {
        // Arrange
        val minSize = Constants.MIN_FILE_SIZE
        
        // Act
        val result = FileOperationsUtil.isFileSizeValid(minSize)
        
        // Assert
        assertTrue(result)
    }
    
    @Test
    fun `isFileSizeValid returns true for maximum boundary`() {
        // Arrange
        val maxSize = Constants.MAX_FILE_SIZE
        
        // Act
        val result = FileOperationsUtil.isFileSizeValid(maxSize)
        
        // Assert
        assertTrue(result)
    }
    
    @Test
    fun `isFileSizeValid returns false for maximum boundary`() {
        // Arrange
        val maxSize = Constants.MAX_FILE_SIZE + 1
        
        // Act
        val result = FileOperationsUtil.isFileSizeValid(maxSize)
        
        // Assert
        assertFalse(result)
    }
    
    @Test
    fun `isFileSizeValid returns false for zero size`() {
        // Arrange
        val zeroSize = 0L
        
        // Act
        val result = FileOperationsUtil.isFileSizeValid(zeroSize)
        
        // Assert
        assertFalse(result)
    }
    
    @Test
    fun `isFileSizeValid returns false for negative size`() {
        // Arrange
        val negativeSize = -1L
        
        // Act
        val result = FileOperationsUtil.isFileSizeValid(negativeSize)
        
        // Assert
        assertFalse(result)
    }
    
    // ==================== formatFileSize ====================
    
    @Test
    fun `formatFileSize returns 0 B for zero size`() {
        // Arrange
        val size = 0L
        
        // Act
        val result = FileOperationsUtil.formatFileSize(size)
        
        // Assert
        assertEquals("0 B", result)
    }
    
    @Test
    fun `formatFileSize returns 0 B for negative size`() {
        // Arrange
        val size = -1L
        
        // Act
        val result = FileOperationsUtil.formatFileSize(size)
        
        // Assert
        assertEquals("0 B", result)
    }
    
    @Test
    fun `formatFileSize returns bytes for small size`() {
        // Arrange
        val size = 512L
        
        // Act
        val result = FileOperationsUtil.formatFileSize(size)
        
        // Assert
        assertEquals("512 B", result)
    }
    
    @Test
    fun `formatFileSize returns KB for kilobyte size`() {
        // Arrange
        val size = 1024L
        
        // Act
        val result = FileOperationsUtil.formatFileSize(size)
        
        // Assert
        assertEquals("1 KB", result)
    }
    
    @Test
    fun `formatFileSize returns MB for megabyte size`() {
        // Arrange
        val size = 1024L * 1024
        
        // Act
        val result = FileOperationsUtil.formatFileSize(size)
        
        // Assert
        assertEquals("1 MB", result)
    }
    
    @Test
    fun `formatFileSize returns GB for gigabyte size`() {
        // Arrange
        val size = 1024L * 1024 * 1024
        
        // Act
        val result = FileOperationsUtil.formatFileSize(size)
        
        // Assert
        assertEquals("1 GB", result)
    }
    
    @Test
    fun `formatFileSize returns TB for terabyte size`() {
        // Arrange
        val size = 1024L * 1024 * 1024 * 1024
        
        // Act
        val result = FileOperationsUtil.formatFileSize(size)
        
        // Assert
        assertEquals("1 TB", result)
    }
    
    @Test
    fun `formatFileSize formats with decimal for non-power-of-two sizes`() {
        // Arrange
        val size = 1536L // 1.5 KB
        
        // Act
        val result = FileOperationsUtil.formatFileSize(size)
        
        // Assert
        assertEquals("1.5 KB", result)
    }
    
    @Test
    fun `formatFileSize uses separator for large numbers`() {
        // Arrange
        val size = 1234567890L
        
        // Act
        val result = FileOperationsUtil.formatFileSize(size)
        
        // Assert - проверяем, что результат содержит разделитель (запятую или точку)
        assertTrue(result.contains(",") || result.contains("."))
    }
    
    // ==================== truncateFileName ====================
    
    @Test
    fun `truncateFileName returns original name when shorter than max length`() {
        // Arrange
        val fileName = "photo.jpg"
        val maxLength = 25
        
        // Act
        val result = FileOperationsUtil.truncateFileName(fileName, maxLength)
        
        // Assert
        assertEquals(fileName, result)
    }
    
    @Test
    fun `truncateFileName returns original name when equal to max length`() {
        // Arrange
        val fileName = "photo_with_25_chars.jpg"
        val maxLength = 25
        
        // Act
        val result = FileOperationsUtil.truncateFileName(fileName, maxLength)
        
        // Assert
        assertEquals(fileName, result)
    }
    
    @Test
    fun `truncateFileName truncates long file name`() {
        // Arrange
        val fileName = "very_long_file_name_that_exceeds_maximum_length.jpg"
        val maxLength = 25
        
        // Act
        val result = FileOperationsUtil.truncateFileName(fileName, maxLength)
        
        // Assert - проверяем, что имя сокращено и содержит "..."
        assertTrue(result.length < fileName.length)
        assertTrue(result.contains("..."))
    }
    
    @Test
    fun `truncateFileName uses default max length`() {
        // Arrange
        val fileName = "very_long_file_name_that_exceeds_maximum_length.jpg"
        
        // Act
        val result = FileOperationsUtil.truncateFileName(fileName)
        
        // Assert - проверяем, что имя сокращено и содержит "..."
        assertTrue(result.length < fileName.length)
        assertTrue(result.contains("..."))
    }
    
    @Test
    fun `truncateFileName preserves file extension`() {
        // Arrange
        val fileName = "very_long_file_name_that_exceeds_maximum_length.jpg"
        val maxLength = 25
        
        // Act
        val result = FileOperationsUtil.truncateFileName(fileName, maxLength)
        
        // Assert
        assertTrue(result.endsWith(".jpg"))
    }
    
    @Test
    fun `truncateFileName handles file without extension`() {
        // Arrange
        val fileName = "very_long_file_name_without_extension"
        val maxLength = 25
        
        // Act
        val result = FileOperationsUtil.truncateFileName(fileName, maxLength)
        
        // Assert - проверяем, что имя сокращено и содержит "..."
        assertTrue(result.length < fileName.length)
        assertTrue(result.contains("..."))
    }
    
    @Test
    fun `truncateFileName handles very short max length`() {
        // Arrange
        val fileName = "very_long_file_name.jpg"
        val maxLength = 10
        
        // Act
        val result = FileOperationsUtil.truncateFileName(fileName, maxLength)
        
        // Assert - для очень короткого maxLength длина результата равна maxLength
        assertEquals(maxLength, result.length)
        assertTrue(result.contains("..."))
    }
    
    @Test
    fun `truncateFileName handles empty string`() {
        // Arrange
        val fileName = ""
        val maxLength = 25
        
        // Act
        val result = FileOperationsUtil.truncateFileName(fileName, maxLength)
        
        // Assert
        assertEquals(fileName, result)
    }
    
    @Test
    fun `truncateFileName handles single character`() {
        // Arrange
        val fileName = "a"
        val maxLength = 25
        
        // Act
        val result = FileOperationsUtil.truncateFileName(fileName, maxLength)
        
        // Assert
        assertEquals(fileName, result)
    }
}

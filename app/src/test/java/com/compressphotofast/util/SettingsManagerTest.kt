package com.compressphotofast.util

import android.content.Context
import android.content.SharedPreferences
import com.compressphotofast.BaseUnitTest
import com.compressphotofast.ui.CompressionPreset
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit тесты для SettingsManager
 * 
 * Тестируют все публичные методы менеджера настроек:
 * - Автоматическое сжатие
 * - Режим сохранения
 * - Качество сжатия
 * - Предустановки сжатия
 * - Отложенные запросы на удаление
 * - Первый запуск
 * - Разрешения
 * - Обработка скриншотов
 * - Игнорирование фото из мессенджеров
 */
class SettingsManagerTest : BaseUnitTest() {
    
    private lateinit var settingsManager: SettingsManager
    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    
    @Before
    override fun setUp() {
        super.setUp()
        mockSharedPreferences = mockk()
        mockEditor = mockk(relaxUnitFun = true)
        settingsManager = SettingsManager(mockSharedPreferences)
    }
    
    @After
    override fun tearDown() {
        super.tearDown()
    }
    
    // ==================== Автоматическое сжатие ====================
    
    @Test
    fun `isAutoCompressionEnabled returns false when not set`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, false) } returns false
        
        // Act
        val result = settingsManager.isAutoCompressionEnabled()
        
        // Assert
        assertFalse(result)
    }
    
    @Test
    fun `isAutoCompressionEnabled returns true when enabled`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, false) } returns true
        
        // Act
        val result = settingsManager.isAutoCompressionEnabled()
        
        // Assert
        assertTrue(result)
    }
    
    @Test
    fun `setAutoCompression enables auto compression when true`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(Constants.PREF_AUTO_COMPRESSION, true) } returns mockEditor
        
        // Act
        settingsManager.setAutoCompression(true)
        
        // Assert
        verify { mockEditor.putBoolean(Constants.PREF_AUTO_COMPRESSION, true) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `setAutoCompression disables auto compression when false`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(Constants.PREF_AUTO_COMPRESSION, false) } returns mockEditor
        
        // Act
        settingsManager.setAutoCompression(false)
        
        // Assert
        verify { mockEditor.putBoolean(Constants.PREF_AUTO_COMPRESSION, false) }
        verify { mockEditor.apply() }
    }
    
    // ==================== Режим сохранения ====================
    
    @Test
    fun `isSaveModeReplace returns false when not set`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_SAVE_MODE, false) } returns false
        
        // Act
        val result = settingsManager.isSaveModeReplace()
        
        // Assert
        assertFalse(result)
    }
    
    @Test
    fun `isSaveModeReplace returns true when replace mode is set`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_SAVE_MODE, false) } returns true
        
        // Act
        val result = settingsManager.isSaveModeReplace()
        
        // Assert
        assertTrue(result)
    }
    
    @Test
    fun `setSaveMode sets replace mode when true`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(Constants.PREF_SAVE_MODE, true) } returns mockEditor
        
        // Act
        settingsManager.setSaveMode(true)
        
        // Assert
        verify { mockEditor.putBoolean(Constants.PREF_SAVE_MODE, true) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `setSaveMode sets separate mode when false`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(Constants.PREF_SAVE_MODE, false) } returns mockEditor
        
        // Act
        settingsManager.setSaveMode(false)
        
        // Assert
        verify { mockEditor.putBoolean(Constants.PREF_SAVE_MODE, false) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `getSaveMode returns REPLACE when replace mode is enabled`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_SAVE_MODE, false) } returns true
        
        // Act
        val result = settingsManager.getSaveMode()
        
        // Assert
        assertEquals(Constants.SAVE_MODE_REPLACE, result)
    }
    
    @Test
    fun `getSaveMode returns SEPARATE when replace mode is disabled`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_SAVE_MODE, false) } returns false
        
        // Act
        val result = settingsManager.getSaveMode()
        
        // Assert
        assertEquals(Constants.SAVE_MODE_SEPARATE, result)
    }
    
    // ==================== Качество сжатия ====================
    
    @Test
    fun `getCompressionQuality returns MEDIUM when not set`() {
        // Arrange
        every { 
            mockSharedPreferences.getInt(
                Constants.PREF_COMPRESSION_QUALITY, 
                Constants.COMPRESSION_QUALITY_MEDIUM
            ) 
        } returns Constants.COMPRESSION_QUALITY_MEDIUM
        
        // Act
        val result = settingsManager.getCompressionQuality()
        
        // Assert
        assertEquals(Constants.COMPRESSION_QUALITY_MEDIUM, result)
    }
    
    @Test
    fun `getCompressionQuality returns LOW when set to LOW`() {
        // Arrange
        every { 
            mockSharedPreferences.getInt(
                Constants.PREF_COMPRESSION_QUALITY, 
                Constants.COMPRESSION_QUALITY_MEDIUM
            ) 
        } returns Constants.COMPRESSION_QUALITY_LOW
        
        // Act
        val result = settingsManager.getCompressionQuality()
        
        // Assert
        assertEquals(Constants.COMPRESSION_QUALITY_LOW, result)
    }
    
    @Test
    fun `getCompressionQuality returns HIGH when set to HIGH`() {
        // Arrange
        every { 
            mockSharedPreferences.getInt(
                Constants.PREF_COMPRESSION_QUALITY, 
                Constants.COMPRESSION_QUALITY_MEDIUM
            ) 
        } returns Constants.COMPRESSION_QUALITY_HIGH
        
        // Act
        val result = settingsManager.getCompressionQuality()
        
        // Assert
        assertEquals(Constants.COMPRESSION_QUALITY_HIGH, result)
    }
    
    @Test
    fun `setCompressionQuality sets LOW quality`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_LOW) } returns mockEditor
        
        // Act
        settingsManager.setCompressionQuality(Constants.COMPRESSION_QUALITY_LOW)
        
        // Assert
        verify { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_LOW) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `setCompressionQuality sets MEDIUM quality`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_MEDIUM) } returns mockEditor
        
        // Act
        settingsManager.setCompressionQuality(Constants.COMPRESSION_QUALITY_MEDIUM)
        
        // Assert
        verify { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_MEDIUM) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `setCompressionQuality sets HIGH quality`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_HIGH) } returns mockEditor
        
        // Act
        settingsManager.setCompressionQuality(Constants.COMPRESSION_QUALITY_HIGH)
        
        // Assert
        verify { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_HIGH) }
        verify { mockEditor.apply() }
    }
    
    // ==================== Предустановки сжатия ====================
    
    @Test
    fun `setCompressionPreset sets LOW quality for LOW preset`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_LOW) } returns mockEditor
        
        // Act
        settingsManager.setCompressionPreset(CompressionPreset.LOW)
        
        // Assert
        verify { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_LOW) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `setCompressionPreset sets MEDIUM quality for MEDIUM preset`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_MEDIUM) } returns mockEditor
        
        // Act
        settingsManager.setCompressionPreset(CompressionPreset.MEDIUM)
        
        // Assert
        verify { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_MEDIUM) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `setCompressionPreset sets HIGH quality for HIGH preset`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_HIGH) } returns mockEditor
        
        // Act
        settingsManager.setCompressionPreset(CompressionPreset.HIGH)
        
        // Assert
        verify { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_HIGH) }
        verify { mockEditor.apply() }
    }
    
    // ==================== Отложенные запросы на удаление ====================
    
    @Test
    fun `savePendingDeleteUri adds URI to pending list`() {
        // Arrange
        val testUri = "content://media/external/images/media/123"
        val existingUris = mutableSetOf<String>()
        val newUris = mutableSetOf(testUri)
        every { mockSharedPreferences.getStringSet(Constants.PREF_PENDING_DELETE_URIS, any()) } returns existingUris
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putStringSet(Constants.PREF_PENDING_DELETE_URIS, newUris) } returns mockEditor
        
        // Act
        settingsManager.savePendingDeleteUri(testUri)
        
        // Assert
        verify { mockEditor.putStringSet(Constants.PREF_PENDING_DELETE_URIS, newUris) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `savePendingDeleteUri appends URI to existing list`() {
        // Arrange
        val testUri = "content://media/external/images/media/456"
        val existingUris = mutableSetOf("content://media/external/images/media/123")
        val newUris = mutableSetOf("content://media/external/images/media/123", testUri)
        every { mockSharedPreferences.getStringSet(Constants.PREF_PENDING_DELETE_URIS, any()) } returns existingUris
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putStringSet(Constants.PREF_PENDING_DELETE_URIS, newUris) } returns mockEditor
        
        // Act
        settingsManager.savePendingDeleteUri(testUri)
        
        // Assert
        verify { mockEditor.putStringSet(Constants.PREF_PENDING_DELETE_URIS, newUris) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `savePendingDeleteUri handles null existing URIs`() {
        // Arrange
        val testUri = "content://media/external/images/media/789"
        val newUris = mutableSetOf(testUri)
        every { mockSharedPreferences.getStringSet(Constants.PREF_PENDING_DELETE_URIS, any()) } returns null
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putStringSet(Constants.PREF_PENDING_DELETE_URIS, newUris) } returns mockEditor
        
        // Act
        settingsManager.savePendingDeleteUri(testUri)
        
        // Assert
        verify { mockEditor.putStringSet(Constants.PREF_PENDING_DELETE_URIS, newUris) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `getAndRemoveFirstPendingDeleteUri returns null when list is empty`() {
        // Arrange
        every { mockSharedPreferences.getStringSet(Constants.PREF_PENDING_DELETE_URIS, null) } returns null
        
        // Act
        val result = settingsManager.getAndRemoveFirstPendingDeleteUri()
        
        // Assert
        assertNull(result)
    }
    
    @Test
    fun `getAndRemoveFirstPendingDeleteUri returns null when list is empty set`() {
        // Arrange
        every { mockSharedPreferences.getStringSet(Constants.PREF_PENDING_DELETE_URIS, null) } returns emptySet()
        
        // Act
        val result = settingsManager.getAndRemoveFirstPendingDeleteUri()
        
        // Assert
        assertNull(result)
    }
    
    @Test
    fun `getAndRemoveFirstPendingDeleteUri returns first URI and removes it`() {
        // Arrange
        val firstUri = "content://media/external/images/media/123"
        val secondUri = "content://media/external/images/media/456"
        val uris = mutableSetOf(firstUri, secondUri)
        val remainingUris = mutableSetOf(secondUri)
        every { mockSharedPreferences.getStringSet(Constants.PREF_PENDING_DELETE_URIS, null) } returns uris
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putStringSet(Constants.PREF_PENDING_DELETE_URIS, remainingUris) } returns mockEditor
        
        // Act
        val result = settingsManager.getAndRemoveFirstPendingDeleteUri()
        
        // Assert
        assertEquals(firstUri, result)
        verify { mockEditor.putStringSet(Constants.PREF_PENDING_DELETE_URIS, remainingUris) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `getPendingDeleteUris returns empty set when not set`() {
        // Arrange
        every { mockSharedPreferences.getStringSet(Constants.PREF_PENDING_DELETE_URIS, emptySet()) } returns null
        
        // Act
        val result = settingsManager.getPendingDeleteUris()
        
        // Assert
        assertTrue(result.isEmpty())
    }
    
    @Test
    fun `getPendingDeleteUris returns all pending URIs`() {
        // Arrange
        val uris = mutableSetOf(
            "content://media/external/images/media/123",
            "content://media/external/images/media/456",
            "content://media/external/images/media/789"
        )
        every { mockSharedPreferences.getStringSet(Constants.PREF_PENDING_DELETE_URIS, emptySet()) } returns uris
        
        // Act
        val result = settingsManager.getPendingDeleteUris()
        
        // Assert
        assertEquals(uris, result)
    }
    
    // ==================== Первый запуск ====================
    
    @Test
    fun `isFirstLaunch returns true when not set`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_FIRST_LAUNCH, true) } returns true
        
        // Act
        val result = settingsManager.isFirstLaunch()
        
        // Assert
        assertTrue(result)
    }
    
    @Test
    fun `isFirstLaunch returns false when already launched`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_FIRST_LAUNCH, true) } returns false
        
        // Act
        val result = settingsManager.isFirstLaunch()
        
        // Assert
        assertFalse(result)
    }
    
    @Test
    fun `setFirstLaunch sets to false after first launch`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(Constants.PREF_FIRST_LAUNCH, false) } returns mockEditor
        
        // Act
        settingsManager.setFirstLaunch(false)
        
        // Assert
        verify { mockEditor.putBoolean(Constants.PREF_FIRST_LAUNCH, false) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `setFirstLaunch can be reset to true`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(Constants.PREF_FIRST_LAUNCH, true) } returns mockEditor
        
        // Act
        settingsManager.setFirstLaunch(true)
        
        // Assert
        verify { mockEditor.putBoolean(Constants.PREF_FIRST_LAUNCH, true) }
        verify { mockEditor.apply() }
    }
    
    // ==================== Разрешение на удаление ====================
    
    @Test
    fun `isDeletePermissionRequested returns false when not set`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, false) } returns false
        
        // Act
        val result = settingsManager.isDeletePermissionRequested()
        
        // Assert
        assertFalse(result)
    }
    
    @Test
    fun `isDeletePermissionRequested returns true when requested`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, false) } returns true
        
        // Act
        val result = settingsManager.isDeletePermissionRequested()
        
        // Assert
        assertTrue(result)
    }
    
    @Test
    fun `setDeletePermissionRequested marks as requested`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, true) } returns mockEditor
        
        // Act
        settingsManager.setDeletePermissionRequested(true)
        
        // Assert
        verify { mockEditor.putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, true) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `setDeletePermissionRequested can be reset to false`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, false) } returns mockEditor
        
        // Act
        settingsManager.setDeletePermissionRequested(false)
        
        // Assert
        verify { mockEditor.putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, false) }
        verify { mockEditor.apply() }
    }
    
    // ==================== Обработка скриншотов ====================
    
    @Test
    fun `shouldProcessScreenshots returns true when not set`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_PROCESS_SCREENSHOTS, true) } returns true
        
        // Act
        val result = settingsManager.shouldProcessScreenshots()
        
        // Assert
        assertTrue(result)
    }
    
    @Test
    fun `shouldProcessScreenshots returns true when enabled`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_PROCESS_SCREENSHOTS, true) } returns true
        
        // Act
        val result = settingsManager.shouldProcessScreenshots()
        
        // Assert
        assertTrue(result)
    }
    
    @Test
    fun `shouldProcessScreenshots returns false when disabled`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_PROCESS_SCREENSHOTS, true) } returns false
        
        // Act
        val result = settingsManager.shouldProcessScreenshots()
        
        // Assert
        assertFalse(result)
    }
    
    @Test
    fun `setProcessScreenshots enables screenshot processing`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(Constants.PREF_PROCESS_SCREENSHOTS, true) } returns mockEditor
        
        // Act
        settingsManager.setProcessScreenshots(true)
        
        // Assert
        verify { mockEditor.putBoolean(Constants.PREF_PROCESS_SCREENSHOTS, true) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `setProcessScreenshots disables screenshot processing`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(Constants.PREF_PROCESS_SCREENSHOTS, false) } returns mockEditor
        
        // Act
        settingsManager.setProcessScreenshots(false)
        
        // Assert
        verify { mockEditor.putBoolean(Constants.PREF_PROCESS_SCREENSHOTS, false) }
        verify { mockEditor.apply() }
    }
    
    // ==================== Игнорирование фото из мессенджеров ====================
    
    @Test
    fun `shouldIgnoreMessengerPhotos returns true when not set`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, true) } returns true
        
        // Act
        val result = settingsManager.shouldIgnoreMessengerPhotos()
        
        // Assert
        assertTrue(result)
    }
    
    @Test
    fun `shouldIgnoreMessengerPhotos returns true when enabled`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, true) } returns true
        
        // Act
        val result = settingsManager.shouldIgnoreMessengerPhotos()
        
        // Assert
        assertTrue(result)
    }
    
    @Test
    fun `shouldIgnoreMessengerPhotos returns false when disabled`() {
        // Arrange
        every { mockSharedPreferences.getBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, true) } returns false
        
        // Act
        val result = settingsManager.shouldIgnoreMessengerPhotos()
        
        // Assert
        assertFalse(result)
    }
    
    @Test
    fun `setIgnoreMessengerPhotos enables ignoring`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, true) } returns mockEditor
        
        // Act
        settingsManager.setIgnoreMessengerPhotos(true)
        
        // Assert
        verify { mockEditor.putBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, true) }
        verify { mockEditor.apply() }
    }
    
    @Test
    fun `setIgnoreMessengerPhotos disables ignoring`() {
        // Arrange
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, false) } returns mockEditor
        
        // Act
        settingsManager.setIgnoreMessengerPhotos(false)
        
        // Assert
        verify { mockEditor.putBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, false) }
        verify { mockEditor.apply() }
    }
    
    // ==================== Статический метод getInstance ====================
    
    @Test
    fun `getInstance creates SettingsManager with correct SharedPreferences`() {
        // Arrange
        val mockContext = mockk<Context>()
        val mockPrefs = mockk<SharedPreferences>()
        every { mockContext.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE) } returns mockPrefs
        
        // Act
        val result = SettingsManager.getInstance(mockContext)
        
        // Assert
        assertNotNull(result)
        verify { mockContext.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE) }
    }
}

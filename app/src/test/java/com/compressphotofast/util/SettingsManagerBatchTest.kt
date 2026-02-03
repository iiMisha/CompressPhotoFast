package com.compressphotofast.util

import android.content.SharedPreferences
import com.compressphotofast.ui.CompressionPreset
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test

/**
 * Unit тесты для пакетных операций обновления настроек
 */
class SettingsManagerBatchTest {

    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)

        mockSharedPreferences = mockk()
        mockEditor = mockk()

        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.apply() } just Runs

        settingsManager = SettingsManager(mockSharedPreferences)
    }

    @Test
    fun `batchUpdate should apply multiple settings in one transaction`() = runTest {
        // When
        settingsManager.batchUpdate {
            setAutoCompression(true)
            setCompressionQuality(85)
            setSaveMode(false)
            setIgnoreMessengerPhotos(false)
        }

        // Then
        verify { mockEditor.putBoolean(Constants.PREF_AUTO_COMPRESSION, true) }
        verify { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, 85) }
        verify { mockEditor.putBoolean(Constants.PREF_SAVE_MODE, false) }
        verify { mockEditor.putBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, false) }
        verify(exactly = 1) { mockEditor.apply() }
    }

    @Test
    fun `batchUpdate should apply settings with CompressionPreset`() = runTest {
        // When
        settingsManager.batchUpdate {
            setCompressionPreset(CompressionPreset.HIGH)
        }

        // Then
        verify { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_HIGH) }
        verify(exactly = 1) { mockEditor.apply() }
    }

    @Test
    fun `batchUpdate with LOW preset should set correct quality`() = runTest {
        // When
        settingsManager.batchUpdate {
            setCompressionPreset(CompressionPreset.LOW)
        }

        // Then
        verify { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_LOW) }
        verify(exactly = 1) { mockEditor.apply() }
    }

    @Test
    fun `batchUpdate with MEDIUM preset should set correct quality`() = runTest {
        // When
        settingsManager.batchUpdate {
            setCompressionPreset(CompressionPreset.MEDIUM)
        }

        // Then
        verify { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, Constants.COMPRESSION_QUALITY_MEDIUM) }
        verify(exactly = 1) { mockEditor.apply() }
    }

    @Test
    fun `batchUpdate should update all boolean settings`() = runTest {
        // When
        settingsManager.batchUpdate {
            setAutoCompression(true)
            setSaveMode(true)
            setProcessScreenshots(false)
            setIgnoreMessengerPhotos(true)
            setShowCompressionToast(true)
            setFirstLaunch(false)
            setDeletePermissionRequested(true)
        }

        // Then
        verify { mockEditor.putBoolean(Constants.PREF_AUTO_COMPRESSION, true) }
        verify { mockEditor.putBoolean(Constants.PREF_SAVE_MODE, true) }
        verify { mockEditor.putBoolean(Constants.PREF_PROCESS_SCREENSHOTS, false) }
        verify { mockEditor.putBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, true) }
        verify { mockEditor.putBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, true) }
        verify { mockEditor.putBoolean(Constants.PREF_FIRST_LAUNCH, false) }
        verify { mockEditor.putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, true) }
        verify(exactly = 1) { mockEditor.apply() }
    }

    @Test
    fun `batchUpdate should handle empty update block`() = runTest {
        // When
        settingsManager.batchUpdate {
            // Empty block
        }

        // Then
        verify(exactly = 1) { mockEditor.apply() }
    }

    @Test
    fun `batchUpdate should only call apply once for multiple settings`() = runTest {
        // When
        settingsManager.batchUpdate {
            setAutoCompression(true)
            setCompressionQuality(75)
            setSaveMode(false)
            setCompressionPreset(CompressionPreset.MEDIUM)
            setProcessScreenshots(true)
            setIgnoreMessengerPhotos(false)
            setShowCompressionToast(false)
            setFirstLaunch(true)
            setDeletePermissionRequested(false)
        }

        // Then - verify that apply was called exactly once
        verify(exactly = 1) { mockEditor.apply() }

        // And verify all settings were put into editor
        verify(exactly = 1) { mockEditor.putBoolean(Constants.PREF_AUTO_COMPRESSION, true) }
        verify(atLeast = 1) { mockEditor.putInt(Constants.PREF_COMPRESSION_QUALITY, any()) }
        verify(exactly = 1) { mockEditor.putBoolean(Constants.PREF_SAVE_MODE, false) }
        verify(exactly = 1) { mockEditor.putBoolean(Constants.PREF_PROCESS_SCREENSHOTS, true) }
        verify(exactly = 1) { mockEditor.putBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, false) }
        verify(exactly = 1) { mockEditor.putBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, false) }
        verify(exactly = 1) { mockEditor.putBoolean(Constants.PREF_FIRST_LAUNCH, true) }
        verify(exactly = 1) { mockEditor.putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, false) }
    }
}

package com.compressphotofast.util

import android.content.SharedPreferences
import com.compressphotofast.BaseUnitTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit тесты для функциональности отключения Toast сообщений о сжатии
 */
class SettingsManagerToastTest : BaseUnitTest() {

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

    @Test
    fun `shouldShowCompressionToast returns false by default`() {
        every { mockSharedPreferences.getBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, false) } returns false

        val result = settingsManager.shouldShowCompressionToast()

        assertFalse("Toast должен быть отключен по умолчанию", result)
        verify { mockSharedPreferences.getBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, false) }
    }

    @Test
    fun `setShowCompressionToast true saves preference`() {
        every { mockEditor.putBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, true) } returns mockEditor
        every { mockSharedPreferences.edit() } returns mockEditor

        settingsManager.setShowCompressionToast(true)

        verify { mockEditor.putBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, true) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `setShowCompressionToast false saves preference`() {
        every { mockEditor.putBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, false) } returns mockEditor
        every { mockSharedPreferences.edit() } returns mockEditor

        settingsManager.setShowCompressionToast(false)

        verify { mockEditor.putBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, false) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `shouldShowCompressionToast returns true when enabled`() {
        every { mockSharedPreferences.getBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, false) } returns true

        val result = settingsManager.shouldShowCompressionToast()

        assertTrue("Toast должен быть включен", result)
    }

    @Test
    fun `multiple setShowCompressionToast calls work correctly`() {
        every { mockEditor.putBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, any()) } returns mockEditor
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockSharedPreferences.getBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, false) } returns false andThen true andThen false andThen true

        // Проверяем состояние по умолчанию
        assertFalse("По умолчанию Toast отключен", settingsManager.shouldShowCompressionToast())

        // Включаем
        settingsManager.setShowCompressionToast(true)

        // Проверяем, что включен
        assertTrue("После включения Toast должен быть true", settingsManager.shouldShowCompressionToast())

        // Выключаем
        settingsManager.setShowCompressionToast(false)

        // Проверяем, что выключен
        assertFalse("После выключения Toast должен быть false", settingsManager.shouldShowCompressionToast())

        verify(atLeast = 2) { mockEditor.putBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, any()) }
    }
}

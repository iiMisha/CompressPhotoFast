package com.compressphotofast.e2e

import android.content.Context
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
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.SettingsManager
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * E2E тесты для управления настройками приложения.
 * 
 * Тестирует полный сценарий управления настройками:
 * - Изменение качества сжатия
 * - Изменение режима сохранения
 * - Включение/выключение авто-сжатия
 * - Включение/выключение обработки скриншотов
 * - Включение/выключение игнорирования фото из мессенджеров
 * - Сохранение настроек
 * - Восстановление настроек после перезапуска
 * - Сброс настроек
 */
@HiltAndroidTest
class SettingsE2ETest : BaseE2ETest() {

    private lateinit var context: Context
    private lateinit var settingsManager: SettingsManager
    private lateinit var mainActivityScenario: ActivityScenario<MainActivity>

    @Before
    override fun setUp() {
        super.setUp()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        settingsManager = SettingsManager.getInstance(context)
        mainActivityScenario = ActivityScenario.launch(MainActivity::class.java)
        
        // Сбрасываем настройки перед каждым тестом
        resetSettings()
    }

    @After
    override fun tearDown() {
        super.tearDown()
        // Сбрасываем настройки после каждого теста
        resetSettings()
    }

    /**
     * Тест 1: Изменение качества сжатия на низкое
     */
    @Test
    fun testChangeQualityToLow() {
        // Проверяем, что переключатель качества отображается
        assertViewDisplayed(R.id.rbQualityLow)
        
        // Выбираем низкое качество
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityLow))
            .perform(ViewActions.click())
        
        // Проверяем, что переключатель выбран
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityLow))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        // Проверяем, что настройка сохранена
        val quality = settingsManager.getCompressionQuality()
        assertThat(quality).isEqualTo(Constants.COMPRESSION_QUALITY_LOW)
        
        LogUtil.processDebug("Качество сжатия изменено на низкое: $quality")
    }

    /**
     * Тест 2: Изменение качества сжатия на среднее
     */
    @Test
    fun testChangeQualityToMedium() {
        // Выбираем среднее качество
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityMedium))
            .perform(ViewActions.click())
        
        // Проверяем, что переключатель выбран
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityMedium))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        // Проверяем, что настройка сохранена
        val quality = settingsManager.getCompressionQuality()
        assertThat(quality).isEqualTo(Constants.COMPRESSION_QUALITY_MEDIUM)
        
        LogUtil.processDebug("Качество сжатия изменено на среднее: $quality")
    }

    /**
     * Тест 3: Изменение качества сжатия на высокое
     */
    @Test
    fun testChangeQualityToHigh() {
        // Выбираем высокое качество
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityHigh))
            .perform(ViewActions.click())
        
        // Проверяем, что переключатель выбран
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityHigh))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        // Проверяем, что настройка сохранена
        val quality = settingsManager.getCompressionQuality()
        assertThat(quality).isEqualTo(Constants.COMPRESSION_QUALITY_HIGH)
        
        LogUtil.processDebug("Качество сжатия изменено на высокое: $quality")
    }

    /**
     * Тест 4: Изменение режима сохранения на замену
     */
    @Test
    fun testChangeSaveModeToReplace() {
        // Проверяем, что переключатель режима сохранения отображается
        assertViewDisplayed(R.id.switchSaveMode)
        
        // Включаем режим замены
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())
        
        // Проверяем, что переключатель включен
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        // Проверяем, что настройка сохранена
        val saveMode = settingsManager.getSaveMode()
        assertThat(saveMode).isEqualTo(Constants.SAVE_MODE_REPLACE)
        
        LogUtil.processDebug("Режим сохранения изменен на замену: $saveMode")
    }

    /**
     * Тест 5: Изменение режима сохранения на отдельную папку
     */
    @Test
    fun testChangeSaveModeToSeparate() {
        // Выключаем режим замены (отдельная папка)
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())
        
        // Проверяем, что переключатель выключен
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .check(ViewAssertions.matches(ViewMatchers.isNotChecked()))
        
        // Проверяем, что настройка сохранена
        val saveMode = settingsManager.getSaveMode()
        assertThat(saveMode).isEqualTo(Constants.SAVE_MODE_SEPARATE)
        
        LogUtil.processDebug("Режим сохранения изменен на отдельную папку: $saveMode")
    }

    /**
     * Тест 6: Включение авто-сжатия
     */
    @Test
    fun testEnableAutoCompression() {
        // Проверяем, что переключатель авто-сжатия отображается
        assertViewDisplayed(R.id.switchAutoCompression)
        
        // Проверяем, что переключатель выключен
        Espresso.onView(ViewMatchers.withId(R.id.switchAutoCompression))
            .check(ViewAssertions.matches(ViewMatchers.isNotChecked()))
        
        // Включаем авто-сжатие
        Espresso.onView(ViewMatchers.withId(R.id.switchAutoCompression))
            .perform(ViewActions.click())
        
        // Проверяем, что переключатель включен
        Espresso.onView(ViewMatchers.withId(R.id.switchAutoCompression))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        // Проверяем, что настройка сохранена
        val autoCompressionEnabled = settingsManager.isAutoCompressionEnabled()
        assertThat(autoCompressionEnabled).isTrue()
        
        LogUtil.processDebug("Авто-сжатие включено")
    }

    /**
     * Тест 7: Выключение авто-сжатия
     */
    @Test
    fun testDisableAutoCompression() {
        // Включаем авто-сжатие
        Espresso.onView(ViewMatchers.withId(R.id.switchAutoCompression))
            .perform(ViewActions.click())
        
        // Проверяем, что переключатель включен
        Espresso.onView(ViewMatchers.withId(R.id.switchAutoCompression))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        // Выключаем авто-сжатие
        Espresso.onView(ViewMatchers.withId(R.id.switchAutoCompression))
            .perform(ViewActions.click())
        
        // Проверяем, что переключатель выключен
        Espresso.onView(ViewMatchers.withId(R.id.switchAutoCompression))
            .check(ViewAssertions.matches(ViewMatchers.isNotChecked()))
        
        // Проверяем, что настройка сохранена
        val autoCompressionEnabled = settingsManager.isAutoCompressionEnabled()
        assertThat(autoCompressionEnabled).isFalse()
        
        LogUtil.processDebug("Авто-сжатие выключено")
    }

    /**
     * Тест 8: Включение игнорирования фото из мессенджеров
     */
    @Test
    fun testEnableIgnoreMessengerPhotos() {
        // Проверяем, что переключатель игнорирования фото из мессенджеров отображается
        assertViewDisplayed(R.id.switchIgnoreMessengerPhotos)
        
        // Проверяем, что переключатель выключен
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .check(ViewAssertions.matches(ViewMatchers.isNotChecked()))
        
        // Включаем игнорирование фото из мессенджеров
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .perform(ViewActions.click())
        
        // Проверяем, что переключатель включен
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        // Проверяем, что настройка сохранена
        val ignoreMessengerPhotos = settingsManager.shouldIgnoreMessengerPhotos()
        assertThat(ignoreMessengerPhotos).isTrue()
        
        LogUtil.processDebug("Игнорирование фото из мессенджеров включено")
    }

    /**
     * Тест 9: Выключение игнорирования фото из мессенджеров
     */
    @Test
    fun testDisableIgnoreMessengerPhotos() {
        // Включаем игнорирование фото из мессенджеров
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .perform(ViewActions.click())
        
        // Проверяем, что переключатель включен
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        // Выключаем игнорирование фото из мессенджеров
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .perform(ViewActions.click())
        
        // Проверяем, что переключатель выключен
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .check(ViewAssertions.matches(ViewMatchers.isNotChecked()))
        
        // Проверяем, что настройка сохранена
        val ignoreMessengerPhotos = settingsManager.shouldIgnoreMessengerPhotos()
        assertThat(ignoreMessengerPhotos).isFalse()
        
        LogUtil.processDebug("Игнорирование фото из мессенджеров выключено")
    }

    /**
     * Тест 10: Проверка сохранения настроек
     */
    @Test
    fun testSettingsPersisted() {
        // Изменяем несколько настроек
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityHigh))
            .perform(ViewActions.click())
        
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())
        
        Espresso.onView(ViewMatchers.withId(R.id.switchAutoCompression))
            .perform(ViewActions.click())
        
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .perform(ViewActions.click())
        
        // Проверяем, что настройки сохранены
        val quality = settingsManager.getCompressionQuality()
        val saveMode = settingsManager.getSaveMode()
        val autoCompressionEnabled = settingsManager.isAutoCompressionEnabled()
        val ignoreMessengerPhotos = settingsManager.shouldIgnoreMessengerPhotos()
        
        assertThat(quality).isEqualTo(Constants.COMPRESSION_QUALITY_HIGH)
        assertThat(saveMode).isEqualTo(Constants.SAVE_MODE_REPLACE)
        assertThat(autoCompressionEnabled).isTrue()
        assertThat(ignoreMessengerPhotos).isTrue()
        
        LogUtil.processDebug("Настройки сохранены: качество=$quality, режим=$saveMode, авто=$autoCompressionEnabled, игнор=$ignoreMessengerPhotos")
    }

    /**
     * Тест 11: Проверка восстановления настроек после перезапуска
     */
    @Test
    fun testSettingsRestoredAfterRestart() {
        // Изменяем настройки
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityMedium))
            .perform(ViewActions.click())
        
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())
        
        Espresso.onView(ViewMatchers.withId(R.id.switchAutoCompression))
            .perform(ViewActions.click())
        
        // Перезапускаем Activity
        mainActivityScenario.close()
        mainActivityScenario = ActivityScenario.launch(MainActivity::class.java)
        
        // Проверяем, что настройки восстановлены
        val quality = settingsManager.getCompressionQuality()
        val saveMode = settingsManager.getSaveMode()
        val autoCompressionEnabled = settingsManager.isAutoCompressionEnabled()
        
        assertThat(quality).isEqualTo(Constants.COMPRESSION_QUALITY_MEDIUM)
        assertThat(saveMode).isEqualTo(Constants.SAVE_MODE_REPLACE)
        assertThat(autoCompressionEnabled).isTrue()
        
        // Проверяем, что UI отображает правильные настройки
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityMedium))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        Espresso.onView(ViewMatchers.withId(R.id.switchAutoCompression))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        LogUtil.processDebug("Настройки восстановлены после перезапуска")
    }

    /**
     * Тест 12: Проверка сброса настроек
     */
    @Test
    fun testResetSettings() {
        // Изменяем настройки
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityHigh))
            .perform(ViewActions.click())
        
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())
        
        Espresso.onView(ViewMatchers.withId(R.id.switchAutoCompression))
            .perform(ViewActions.click())
        
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .perform(ViewActions.click())
        
        // Сбрасываем настройки
        resetSettings()
        
        // Перезапускаем Activity
        mainActivityScenario.close()
        mainActivityScenario = ActivityScenario.launch(MainActivity::class.java)
        
        // Проверяем, что настройки сброшены
        val quality = settingsManager.getCompressionQuality()
        val saveMode = settingsManager.getSaveMode()
        val autoCompressionEnabled = settingsManager.isAutoCompressionEnabled()
        val ignoreMessengerPhotos = settingsManager.shouldIgnoreMessengerPhotos()
        
        assertThat(quality).isEqualTo(Constants.COMPRESSION_QUALITY_MEDIUM) // Значение по умолчанию
        assertThat(saveMode).isEqualTo(Constants.SAVE_MODE_SEPARATE) // Значение по умолчанию
        assertThat(autoCompressionEnabled).isFalse() // Значение по умолчанию
        assertThat(ignoreMessengerPhotos).isFalse() // Значение по умолчанию
        
        LogUtil.processDebug("Настройки сброшены")
    }

    /**
     * Тест 13: Проверка переключения между качествами
     */
    @Test
    fun testSwitchBetweenQualities() {
        // Низкое качество
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityLow))
            .perform(ViewActions.click())
        var quality = settingsManager.getCompressionQuality()
        assertThat(quality).isEqualTo(Constants.COMPRESSION_QUALITY_LOW)
        
        // Среднее качество
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityMedium))
            .perform(ViewActions.click())
        quality = settingsManager.getCompressionQuality()
        assertThat(quality).isEqualTo(Constants.COMPRESSION_QUALITY_MEDIUM)
        
        // Высокое качество
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityHigh))
            .perform(ViewActions.click())
        quality = settingsManager.getCompressionQuality()
        assertThat(quality).isEqualTo(Constants.COMPRESSION_QUALITY_HIGH)
        
        // Возврат к среднему качеству
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityMedium))
            .perform(ViewActions.click())
        quality = settingsManager.getCompressionQuality()
        assertThat(quality).isEqualTo(Constants.COMPRESSION_QUALITY_MEDIUM)
        
        LogUtil.processDebug("Переключение между качествами работает корректно")
    }

    /**
     * Тест 14: Проверка переключения между режимами сохранения
     */
    @Test
    fun testSwitchBetweenSaveModes() {
        // Режим замены
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())
        var saveMode = settingsManager.getSaveMode()
        assertThat(saveMode).isEqualTo(Constants.SAVE_MODE_REPLACE)
        
        // Режим отдельной папки
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())
        saveMode = settingsManager.getSaveMode()
        assertThat(saveMode).isEqualTo(Constants.SAVE_MODE_SEPARATE)
        
        // Возврат к режиму замены
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())
        saveMode = settingsManager.getSaveMode()
        assertThat(saveMode).isEqualTo(Constants.SAVE_MODE_REPLACE)
        
        LogUtil.processDebug("Переключение между режимами сохранения работает корректно")
    }

    /**
     * Тест 15: Проверка всех настроек одновременно
     */
    @Test
    fun testAllSettingsTogether() {
        // Изменяем все настройки
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityLow))
            .perform(ViewActions.click())
        
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .perform(ViewActions.click())
        
        Espresso.onView(ViewMatchers.withId(R.id.switchAutoCompression))
            .perform(ViewActions.click())
        
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .perform(ViewActions.click())
        
        // Проверяем все настройки
        val quality = settingsManager.getCompressionQuality()
        val saveMode = settingsManager.getSaveMode()
        val autoCompressionEnabled = settingsManager.isAutoCompressionEnabled()
        val ignoreMessengerPhotos = settingsManager.shouldIgnoreMessengerPhotos()
        
        assertThat(quality).isEqualTo(Constants.COMPRESSION_QUALITY_LOW)
        assertThat(saveMode).isEqualTo(Constants.SAVE_MODE_REPLACE)
        assertThat(autoCompressionEnabled).isTrue()
        assertThat(ignoreMessengerPhotos).isTrue()
        
        // Проверяем UI
        Espresso.onView(ViewMatchers.withId(R.id.rbQualityLow))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        Espresso.onView(ViewMatchers.withId(R.id.switchSaveMode))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        Espresso.onView(ViewMatchers.withId(R.id.switchAutoCompression))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        Espresso.onView(ViewMatchers.withId(R.id.switchIgnoreMessengerPhotos))
            .check(ViewAssertions.matches(ViewMatchers.isChecked()))
        
        LogUtil.processDebug("Все настройки работают корректно")
    }

    // ========== Вспомогательные методы ==========

    /**
     * Сбрасывает настройки к значениям по умолчанию
     */
    private fun resetSettings() {
        try {
            settingsManager.setCompressionQuality(Constants.COMPRESSION_QUALITY_MEDIUM)
            settingsManager.setSaveMode(false) // false = SAVE_MODE_SEPARATE
            settingsManager.setAutoCompression(false)
            settingsManager.setIgnoreMessengerPhotos(false)
            LogUtil.processDebug("Настройки сброшены к значениям по умолчанию")
        } catch (e: Exception) {
            LogUtil.errorWithException("Ошибка при сбросе настроек", e)
        }
    }
}

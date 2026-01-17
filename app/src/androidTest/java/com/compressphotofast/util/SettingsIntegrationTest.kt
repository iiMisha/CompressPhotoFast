package com.compressphotofast.util

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.compressphotofast.BaseInstrumentedTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation тесты для интеграции с SharedPreferences
 *
 * Тестируют сохранение и чтение настроек приложения
 */
@RunWith(AndroidJUnit4::class)
class SettingsIntegrationTest : BaseInstrumentedTest() {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        sharedPreferences = context.getSharedPreferences(
            Constants.PREF_FILE_NAME,
            Context.MODE_PRIVATE
        )
        editor = sharedPreferences.edit()
    }

    /**
     * Тест 1: Проверка сохранения и чтения настройки автосжатия
     */
    @Test
    fun test_autoCompressionSettings_saveAndRead() {
        // Тестируем сохранение true
        editor.putBoolean(Constants.PREF_AUTO_COMPRESSION, true)
        val result1 = editor.commit()

        org.junit.Assert.assertTrue("Настройка должна сохраниться", result1)
        org.junit.Assert.assertEquals(
            "Значение должно быть true",
            true,
            sharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, false)
        )

        // Тестируем сохранение false
        editor.putBoolean(Constants.PREF_AUTO_COMPRESSION, false)
        val result2 = editor.commit()

        org.junit.Assert.assertTrue("Настройка должна сохраниться", result2)
        org.junit.Assert.assertEquals(
            "Значение должно быть false",
            false,
            sharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, true)
        )
    }

    /**
     * Тест 2: Проверка сохранения и чтения качества сжатия
     */
    @Test
    fun test_compressionQuality_saveAndRead() {
        // Тестируем сохранение качества (80)
        editor.putInt(Constants.PREF_COMPRESSION_QUALITY, 80)
        val result1 = editor.commit()

        org.junit.Assert.assertTrue("Настройка должна сохраниться", result1)
        org.junit.Assert.assertEquals(
            "Значение должно быть 80",
            80,
            sharedPreferences.getInt(Constants.PREF_COMPRESSION_QUALITY, -1)
        )

        // Тестируем сохранение качества (60)
        editor.putInt(Constants.PREF_COMPRESSION_QUALITY, 60)
        val result2 = editor.commit()

        org.junit.Assert.assertTrue("Настройка должна сохраниться", result2)
        org.junit.Assert.assertEquals(
            "Значение должно быть 60",
            60,
            sharedPreferences.getInt(Constants.PREF_COMPRESSION_QUALITY, -1)
        )
    }

    /**
     * Тест 3: Проверка сохранения и чтения режима сохранения
     */
    @Test
    fun test_saveMode_saveAndRead() {
        // Тестируем сохранение true
        editor.putBoolean(Constants.PREF_SAVE_MODE, true)
        val result = editor.commit()

        org.junit.Assert.assertTrue("Настройка должна сохраниться", result)
        org.junit.Assert.assertEquals(
            "Значение должно быть true",
            true,
            sharedPreferences.getBoolean(Constants.PREF_SAVE_MODE, false)
        )
    }

    /**
     * Тест 4: Проверка сохранения и чтения настройки игнорирования мессенджеров
     */
    @Test
    fun test_ignoreMessengerPhotos_saveAndRead() {
        // Тестируем сохранение true
        editor.putBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, true)
        val result = editor.commit()

        org.junit.Assert.assertTrue("Настройка должна сохраниться", result)
        org.junit.Assert.assertEquals(
            "Значение должно быть true",
            true,
            sharedPreferences.getBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, false)
        )

        // Тестируем сохранение false
        editor.putBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, false)
        val result2 = editor.commit()

        org.junit.Assert.assertTrue("Настройка должна сохраниться", result2)
        org.junit.Assert.assertEquals(
            "Значение должно быть false",
            false,
            sharedPreferences.getBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, true)
        )
    }

    /**
     * Тест 5: Проверка сохранения и чтения настройки обработки скриншотов
     */
    @Test
    fun test_processScreenshots_saveAndRead() {
        // Тестируем сохранение true
        editor.putBoolean(Constants.PREF_PROCESS_SCREENSHOTS, true)
        val result = editor.commit()

        org.junit.Assert.assertTrue("Настройка должна сохраниться", result)
        org.junit.Assert.assertEquals(
            "Значение должно быть true",
            true,
            sharedPreferences.getBoolean(Constants.PREF_PROCESS_SCREENSHOTS, false)
        )
    }

    /**
     * Тест 6: Проверка сохранения и чтения первой загрузки
     */
    @Test
    fun test_firstLaunch_saveAndRead() {
        // Тестируем сохранение false (приложение уже запускалось)
        editor.putBoolean(Constants.PREF_FIRST_LAUNCH, false)
        val result = editor.commit()

        org.junit.Assert.assertTrue("Настройка должна сохраниться", result)
        org.junit.Assert.assertEquals(
            "Значение должно быть false",
            false,
            sharedPreferences.getBoolean(Constants.PREF_FIRST_LAUNCH, true)
        )
    }

    /**
     * Тест 7: Проверка сохранения нескольких настроек одновременно
     */
    @Test
    fun test_multipleSettings_saveAndRead() {
        // Сохраняем несколько настроек
        editor.putBoolean(Constants.PREF_AUTO_COMPRESSION, true)
        editor.putInt(Constants.PREF_COMPRESSION_QUALITY, 70)
        editor.putBoolean(Constants.PREF_SAVE_MODE, false)
        editor.putBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, true)
        val result = editor.commit()

        org.junit.Assert.assertTrue("Все настройки должны сохраниться", result)

        // Проверяем все сохраненные значения
        org.junit.Assert.assertEquals(
            "AUTO_COMPRESSION должно быть true",
            true,
            sharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, false)
        )
        org.junit.Assert.assertEquals(
            "COMPRESSION_QUALITY должно быть 70",
            70,
            sharedPreferences.getInt(Constants.PREF_COMPRESSION_QUALITY, -1)
        )
        org.junit.Assert.assertEquals(
            "SAVE_MODE должно быть false",
            false,
            sharedPreferences.getBoolean(Constants.PREF_SAVE_MODE, true)
        )
        org.junit.Assert.assertEquals(
            "IGNORE_MESSENGER_PHOTOS должно быть true",
            true,
            sharedPreferences.getBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, false)
        )
    }

    /**
     * Тест 8: Проверка удаления настроек
     */
    @Test
    fun test_clearSettings() {
        // Сохраняем настройку
        editor.putBoolean(Constants.PREF_AUTO_COMPRESSION, true)
        editor.commit()

        org.junit.Assert.assertTrue(
            "Перед удалением настройка должна быть true",
            sharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, false)
        )

        // Удаляем настройку
        editor.remove(Constants.PREF_AUTO_COMPRESSION)
        editor.commit()

        // Проверяем, что значение вернулось к дефолтному
        org.junit.Assert.assertFalse(
            "После удаления настройка должна быть false (дефолтное)",
            sharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, false)
        )
    }

    /**
     * Тест 9: Проверка сохранения пресета сжатия
     */
    @Test
    fun test_compressionPreset_saveAndRead() {
        // Тестируем сохранение пресета
        editor.putString(Constants.PREF_COMPRESSION_PRESET, "medium")
        val result = editor.commit()

        org.junit.Assert.assertTrue("Пресет должен сохраниться", result)
        org.junit.Assert.assertEquals(
            "Пресет должен быть 'medium'",
            "medium",
            sharedPreferences.getString(Constants.PREF_COMPRESSION_PRESET, "")
        )
    }

    /**
     * Тест 10: Проверка счетчика запросов разрешений
     */
    @Test
    fun test_permissionRequestCount_increment() {
        // Очищаем счетчик
        editor.putInt(Constants.PREF_PERMISSION_REQUEST_COUNT, 0)
        editor.commit()

        // Читаем текущее значение
        val count1 = sharedPreferences.getInt(Constants.PREF_PERMISSION_REQUEST_COUNT, 0)
        org.junit.Assert.assertEquals("Начальное значение должно быть 0", 0, count1)

        // Увеличиваем счетчик
        editor.putInt(Constants.PREF_PERMISSION_REQUEST_COUNT, count1 + 1)
        editor.commit()

        // Проверяем увеличение
        val count2 = sharedPreferences.getInt(Constants.PREF_PERMISSION_REQUEST_COUNT, 0)
        org.junit.Assert.assertEquals("Значение должно быть 1", 1, count2)
    }

    /**
     * Очистка после тестов
     */
    @After
    fun cleanup() {
        // Восстанавливаем дефолтные значения
        editor.putBoolean(Constants.PREF_AUTO_COMPRESSION, false)
        editor.putInt(Constants.PREF_COMPRESSION_QUALITY, 80)
        editor.putBoolean(Constants.PREF_SAVE_MODE, false)
        editor.putBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, false)
        editor.putBoolean(Constants.PREF_PROCESS_SCREENSHOTS, false)
        editor.putInt(Constants.PREF_PERMISSION_REQUEST_COUNT, 0)
        editor.commit()
    }
}

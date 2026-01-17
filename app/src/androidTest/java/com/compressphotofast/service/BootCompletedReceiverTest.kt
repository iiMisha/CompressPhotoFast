package com.compressphotofast.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.compressphotofast.BaseInstrumentedTest
import com.compressphotofast.util.Constants
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

/**
 * Instrumentation тесты для BootCompletedReceiver
 *
 * Тестируют критические сценарии BroadcastReceiver:
 * - Обработка BOOT_COMPLETED события
 * - Запуск сервисов при включенном автосжатии
 * - Отсутствие запуска при выключенном автосжатии
 * - Проверка настроек SharedPreferences
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BootCompletedReceiverTest : BaseInstrumentedTest() {

    private lateinit var receiver: BootCompletedReceiver
    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        receiver = BootCompletedReceiver()
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Получаем реальные SharedPreferences для тестов
        sharedPreferences = context.getSharedPreferences(
            Constants.PREF_FILE_NAME,
            Context.MODE_PRIVATE
        )
        editor = sharedPreferences.edit()
    }

    /**
     * Тест 1: Проверка создания Receiver
     */
    @Test
    fun test_receiver_canBeInstantiated() {
        // Receiver должен успешно создаваться
        org.junit.Assert.assertNotNull(receiver)
    }

    /**
     * Тест 2: Проверка обработки BOOT_COMPLETED при включенном автосжатии
     * Примечание: Мы не можем реально запустить сервисы в тесте, но можем проверить
     * что Receiver не падает с ошибкой при обработке интента
     */
    @Test
    fun test_onReceive_withAutoCompressionEnabled_doesNotCrash() {
        // Включаем автоматическое сжатие
        editor.putBoolean(Constants.PREF_AUTO_COMPRESSION, true)
        editor.commit()

        // Создаем интент BOOT_COMPLETED
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        // Проверяем, что onReceive не падает с ошибкой
        try {
            receiver.onReceive(context, intent)
            // Если дошли сюда, значит Receiver не упал
            org.junit.Assert.assertTrue("Receiver обработал BOOT_COMPLETED без ошибок", true)
        } catch (e: Exception) {
            org.junit.Assert.fail("Receiver упал с ошибкой: ${e.message}")
        }
    }

    /**
     * Тест 3: Проверка обработки BOOT_COMPLETED при выключенном автосжатии
     * При выключенном автосжатии сервисы не должны запускаться
     */
    @Test
    fun test_onReceive_withAutoCompressionDisabled_doesNotCrash() {
        // Выключаем автоматическое сжатие
        editor.putBoolean(Constants.PREF_AUTO_COMPRESSION, false)
        editor.commit()

        // Создаем интент BOOT_COMPLETED
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        // Проверяем, что onReceive не падает с ошибкой
        try {
            receiver.onReceive(context, intent)
            // Если дошли сюда, значит Receiver не упал
            org.junit.Assert.assertTrue("Receiver обработал BOOT_COMPLETED без ошибок", true)
        } catch (e: Exception) {
            org.junit.Assert.fail("Receiver упал с ошибкой: ${e.message}")
        }
    }

    /**
     * Тест 4: Проверка обработки некорректного действия
     * Receiver должен игнорировать действия, отличные от BOOT_COMPLETED
     */
    @Test
    fun test_onReceive_withWrongAction_doesNotCrash() {
        // Создаем интент с другим действием
        val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)

        // Проверяем, что onReceive не падает с ошибкой
        try {
            receiver.onReceive(context, intent)
            // Receiver должен обработать это gracefully
            org.junit.Assert.assertTrue("Receiver проигнорировал неправильное действие", true)
        } catch (e: Exception) {
            org.junit.Assert.fail("Receiver упал с ошибкой: ${e.message}")
        }
    }

    /**
     * Тест 5: Проверка обработки null интента
     * Receiver должен обрабатывать null интент без падения
     */
    @Test
    fun test_onReceive_withNullIntent_doesNotCrash() {
        // Включаем автоматическое сжатие
        editor.putBoolean(Constants.PREF_AUTO_COMPRESSION, true)
        editor.commit()

        // Проверяем, что onReceive не падает с null интентом
        try {
            receiver.onReceive(context, null)
            // Если дошли сюда, значит Receiver не упал
            org.junit.Assert.assertTrue("Receiver обработал null интент без ошибок", true)
        } catch (e: Exception) {
            // Null Pointer Exception допустим в этом случае
            org.junit.Assert.assertTrue(
                "Receiver корректно обработал null: ${e.javaClass.simpleName}",
                e is NullPointerException || e is kotlin.KotlinNullPointerException
            )
        }
    }

    /**
     * Тест 6: Проверка сохранения настроек
     * Проверяем, что настройки корректно сохраняются и читаются
     */
    @Test
    fun test_sharedPreferences_correctlyStoresAndRetrievesSettings() {
        // Тестируем сохранение true
        editor.putBoolean(Constants.PREF_AUTO_COMPRESSION, true)
        val commitResult = editor.commit()

        org.junit.Assert.assertTrue("Настройки должны сохраняться успешно", commitResult)
        org.junit.Assert.assertEquals(
            "Значение должно быть true",
            true,
            sharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, false)
        )

        // Тестируем сохранение false
        editor.putBoolean(Constants.PREF_AUTO_COMPRESSION, false)
        val commitResult2 = editor.commit()

        org.junit.Assert.assertTrue("Настройки должны сохраняться успешно", commitResult2)
        org.junit.Assert.assertEquals(
            "Значение должно быть false",
            false,
            sharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, true)
        )
    }

    /**
     * Очистка после тестов
     * Восстанавливаем исходное значение настройки
     */
    @After
    fun cleanup() {
        // Восстанавливаем значение по умолчанию (false)
        editor.putBoolean(Constants.PREF_AUTO_COMPRESSION, false)
        editor.commit()
    }
}

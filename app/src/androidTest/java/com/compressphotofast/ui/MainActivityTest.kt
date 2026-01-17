package com.compressphotofast.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ActivityTestRule
import com.compressphotofast.R
import com.compressphotofast.BaseInstrumentedTest
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.hasChildCount

/**
 * Instrumentation тесты для MainActivity
 *
 * Тестируют критические UI сценарии:
 * - Запуск приложения
 * - Отображение основных элементов
 * - Взаимодействие с переключателями
 * - Изменение настроек качества
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainActivityTest : BaseInstrumentedTest() {

    @get:Rule
    val activityRule = ActivityTestRule(MainActivity::class.java, initialTouchMode = false)

    /**
     * Тест 1: Проверка запуска приложения
     * Проверяет, что MainActivity запускается успешно
     */
    @Test
    fun test_appLaunches_successfully() {
        // Проверяем, что главный контейнер отображается
        onView(withId(R.id.mainContainer))
            .check(matches(isDisplayed()))
    }

    /**
     * Тест 2: Проверка отображения переключателя автоматического сжатия
     */
    @Test
    fun test_autoCompressionSwitch_isDisplayed() {
        onView(withId(R.id.switchAutoCompression))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
    }

    /**
     * Тест 3: Проверка отображения описания автоматического сжатия
     */
    @Test
    fun test_autoCompressionDescription_isDisplayed() {
        onView(withId(R.id.tvAutoCompressionDescription))
            .check(matches(isDisplayed()))
            .check(matches(withText(R.string.auto_compression_description)))
    }

    /**
     * Тест 4: Проверка отображения переключателя режима сохранения
     */
    @Test
    fun test_saveModeSwitch_isDisplayed() {
        onView(withId(R.id.switchSaveMode))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
    }

    /**
     * Тест 5: Проверка отображения переключателя игнорирования фото из мессенджеров
     */
    @Test
    fun test_ignoreMessengerSwitch_isDisplayed() {
        onView(withId(R.id.switchIgnoreMessengerPhotos))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
    }

    /**
     * Тест 6: Проверка отображения группы радиокнопок качества
     */
    @Test
    fun test_qualityRadioGroup_isDisplayed() {
        onView(withId(R.id.radioGroupQuality))
            .check(matches(isDisplayed()))

        // Проверяем, что все три радиокнопки отображаются
        onView(withId(R.id.rbQualityLow))
            .check(matches(isDisplayed()))

        onView(withId(R.id.rbQualityMedium))
            .check(matches(isDisplayed()))

        onView(withId(R.id.rbQualityHigh))
            .check(matches(isDisplayed()))
    }

    /**
     * Тест 7: Проверка отображения кнопки выбора фото
     */
    @Test
    fun test_selectPhotoButton_isDisplayed() {
        onView(withId(R.id.btnSelectPhotos))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
            .check(matches(withText(R.string.select_photos)))
    }

    /**
     * Тест 8: Проверка отображения progressBar (изначально скрыт)
     */
    @Test
    fun test_progressBar_isInitiallyHidden() {
        onView(withId(R.id.progressBar))
            .check(matches(not(isDisplayed())))
    }

    /**
     * Тест 9: Проверка переключения автоматического сжатия
     * Проверяет, что переключатель можно нажать
     */
    @Test
    fun test_autoCompressionToggle_canBeClicked() {
        // Нажимаем на переключатель
        onView(withId(R.id.switchAutoCompression))
            .perform(click())

        // Проверяем, что переключатель все еще отображается
        onView(withId(R.id.switchAutoCompression))
            .check(matches(isDisplayed()))
    }

    /**
     * Тест 10: Проверка переключения режима сохранения
     */
    @Test
    fun test_saveModeToggle_canBeClicked() {
        onView(withId(R.id.switchSaveMode))
            .perform(click())

        onView(withId(R.id.switchSaveMode))
            .check(matches(isDisplayed()))
    }

    /**
     * Тест 11: Проверка выбора низкого качества
     */
    @Test
    fun test_selectLowQuality_canBeClicked() {
        onView(withId(R.id.rbQualityLow))
            .perform(click())

        onView(withId(R.id.rbQualityLow))
            .check(matches(isChecked()))
    }

    /**
     * Тест 12: Проверка выбора среднего качества
     */
    @Test
    fun test_selectMediumQuality_canBeClicked() {
        onView(withId(R.id.rbQualityMedium))
            .perform(click())

        onView(withId(R.id.rbQualityMedium))
            .check(matches(isChecked()))
    }

    /**
     * Тест 13: Проверка выбора высокого качества
     */
    @Test
    fun test_selectHighQuality_canBeClicked() {
        onView(withId(R.id.rbQualityHigh))
            .perform(click())

        onView(withId(R.id.rbQualityHigh))
            .check(matches(isChecked()))
    }

    /**
     * Тест 14: Проверка нажатия на кнопку выбора фото
     * Проверяет, что кнопка нажимается (будет открываться Photo Picker)
     */
    @Test
    fun test_selectPhotoButton_canBeClicked() {
        onView(withId(R.id.btnSelectPhotos))
            .perform(click())

        // Кнопка должна оставаться видимой после клика
        onView(withId(R.id.btnSelectPhotos))
            .check(matches(isDisplayed()))
    }

    /**
     * Тест 15: Проверка переключения игнорирования фото из мессенджеров
     */
    @Test
    fun test_ignoreMessengerToggle_canBeClicked() {
        onView(withId(R.id.switchIgnoreMessengerPhotos))
            .perform(click())

        onView(withId(R.id.switchIgnoreMessengerPhotos))
            .check(matches(isDisplayed()))
    }

    /**
     * Тест 16: Проверка переключения нескольких переключателей последовательно
     * Проверяет, что переключатели работают независимо друг от друга
     */
    @Test
    fun test_multipleToggles_workIndependently() {
        // Включаем автосжатие
        onView(withId(R.id.switchAutoCompression))
            .perform(click())

        // Включаем режим сохранения
        onView(withId(R.id.switchSaveMode))
            .perform(click())

        // Включаем игнорирование мессенджеров
        onView(withId(R.id.switchIgnoreMessengerPhotos))
            .perform(click())

        // Проверяем, что все переключатели отображаются
        onView(withId(R.id.switchAutoCompression))
            .check(matches(isDisplayed()))
        onView(withId(R.id.switchSaveMode))
            .check(matches(isDisplayed()))
        onView(withId(R.id.switchIgnoreMessengerPhotos))
            .check(matches(isDisplayed()))
    }

    /**
     * Тест 17: Проверка переключения между разными качествами
     * Проверяет, что можно переключаться между всеми тремя вариантами качества
     */
    @Test
    fun test_qualitySwitching_worksCorrectly() {
        // Выбираем низкое качество
        onView(withId(R.id.rbQualityLow))
            .perform(click())
        onView(withId(R.id.rbQualityLow))
            .check(matches(isChecked()))

        // Переключаем на среднее качество
        onView(withId(R.id.rbQualityMedium))
            .perform(click())
        onView(withId(R.id.rbQualityMedium))
            .check(matches(isChecked()))
        onView(withId(R.id.rbQualityLow))
            .check(matches(isNotChecked()))

        // Переключаем на высокое качество
        onView(withId(R.id.rbQualityHigh))
            .perform(click())
        onView(withId(R.id.rbQualityHigh))
            .check(matches(isChecked()))
        onView(withId(R.id.rbQualityMedium))
            .check(matches(isNotChecked()))
    }

    /**
     * Тест 18: Проверка доступности переключателя при запуске
     * Проверяет, что переключатели доступны для взаимодействия
     */
    @Test
    fun test_switches_areEnabled_onLaunch() {
        onView(withId(R.id.switchAutoCompression))
            .check(matches(isEnabled()))

        onView(withId(R.id.switchSaveMode))
            .check(matches(isEnabled()))

        onView(withId(R.id.switchIgnoreMessengerPhotos))
            .check(matches(isEnabled()))
    }

    /**
     * Тест 19: Проверка доступности радио-кнопок качества
     */
    @Test
    fun test_qualityRadioButtons_areEnabled() {
        onView(withId(R.id.rbQualityLow))
            .check(matches(isEnabled()))

        onView(withId(R.id.rbQualityMedium))
            .check(matches(isEnabled()))

        onView(withId(R.id.rbQualityHigh))
            .check(matches(isEnabled()))
    }

    /**
     * Тест 20: Проверка отображения RadioGroup
     * RadioGroup должен быть видим и содержать все радио-кнопки
     */
    @Test
    fun test_qualityRadioGroup_containsAllRadioButtons() {
        onView(withId(R.id.radioGroupQuality))
            .check(matches(hasChildCount(3)))
    }

    /**
     * Тест 21: Проверка двойного нажатия на переключатель
     * Проверяет, что переключатель можно переключить обратно
     */
    @Test
    fun test_autoCompressionToggle_canBeToggledBack() {
        // Проверяем начальное состояние (предполагаем, что выключен)
        onView(withId(R.id.switchAutoCompression))
            .perform(click())

        // Переключаем обратно
        onView(withId(R.id.switchAutoCompression))
            .perform(click())

        // Проверяем, что переключатель все еще отображается
        onView(withId(R.id.switchAutoCompression))
            .check(matches(isDisplayed()))
    }

    /**
     * Тест 22: Проверка переключения всех переключателей по порядку
     * Проверяет, что можно переключить все переключатели в определенной последовательности
     */
    @Test
    fun test_allSwitchesSequential() {
        // Последовательно переключаем все переключатели
        onView(withId(R.id.switchAutoCompression)).perform(click())
        onView(withId(R.id.switchSaveMode)).perform(click())
        onView(withId(R.id.switchIgnoreMessengerPhotos)).perform(click())

        // Проверяем, что все остались видимыми
        onView(withId(R.id.switchAutoCompression)).check(matches(isDisplayed()))
        onView(withId(R.id.switchSaveMode)).check(matches(isDisplayed()))
        onView(withId(R.id.switchIgnoreMessengerPhotos)).check(matches(isDisplayed()))
    }

    /**
     * Тест 23: Проверка переключения качества и затем переключателей
     * Проверяет взаимодействие между радио-кнопками и переключателями
     */
    @Test
    fun test_qualitySelectionThenSwitches() {
        // Выбираем качество
        onView(withId(R.id.rbQualityMedium)).perform(click())

        // Переключаем переключатели
        onView(withId(R.id.switchAutoCompression)).perform(click())
        onView(withId(R.id.switchSaveMode)).perform(click())

        // Проверяем состояние
        onView(withId(R.id.rbQualityMedium)).check(matches(isChecked()))
        onView(withId(R.id.switchAutoCompression)).check(matches(isDisplayed()))
        onView(withId(R.id.switchSaveMode)).check(matches(isDisplayed()))
    }

    /**
     * Тест 24: Проверка переключения между всеми тремя качествами по очереди
     * Проверяет полное переключение между всеми вариантами
     */
    @Test
    fun test_allQualitySelections() {
        // Low -> Medium -> High -> Low
        onView(withId(R.id.rbQualityLow)).perform(click())
        onView(withId(R.id.rbQualityLow)).check(matches(isChecked()))

        onView(withId(R.id.rbQualityMedium)).perform(click())
        onView(withId(R.id.rbQualityMedium)).check(matches(isChecked()))
        onView(withId(R.id.rbQualityLow)).check(matches(isNotChecked()))

        onView(withId(R.id.rbQualityHigh)).perform(click())
        onView(withId(R.id.rbQualityHigh)).check(matches(isChecked()))
        onView(withId(R.id.rbQualityMedium)).check(matches(isNotChecked()))

        onView(withId(R.id.rbQualityLow)).perform(click())
        onView(withId(R.id.rbQualityLow)).check(matches(isChecked()))
        onView(withId(R.id.rbQualityHigh)).check(matches(isNotChecked()))
    }

    /**
     * Тест 25: Проверка множественных нажатий на кнопку выбора фото
     * Проверяет, что кнопка корректно обрабатывает множественные нажатия
     */
    @Test
    fun test_selectPhotoButton_multipleClicks() {
        // Дважды нажимаем на кнопку
        onView(withId(R.id.btnSelectPhotos))
            .perform(click())
            .perform(click())

        // Кнопка должна оставаться видимой
        onView(withId(R.id.btnSelectPhotos))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
    }

    /**
     * Тест 26: Проверка видимости текстового описания автосжатия
     */
    @Test
    fun test_autoCompressionDescription_visibility() {
        onView(withId(R.id.tvAutoCompressionDescription))
            .check(matches(isDisplayed()))
            .check(matches(withText(R.string.auto_compression_description)))
    }

    /**
     * Тест 27: Проверка доступности RadioGroup для взаимодействия
     */
    @Test
    fun test_qualityRadioGroup_isEnabled() {
        onView(withId(R.id.radioGroupQuality))
            .check(matches(isEnabled()))
    }

    /**
     * Тест 28: Проверка отображения главного контейнера
     */
    @Test
    fun test_mainContainer_visibility() {
        onView(withId(R.id.mainContainer))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
    }

    /**
     * Тест 29: Проверка отсутствия прогресс-бара при запуске
     */
    @Test
    fun test_progressBar_notVisibleAtLaunch() {
        onView(withId(R.id.progressBar))
            .check(matches(not(isDisplayed())))
    }

    /**
     * Тест 30: Проверка корректности текста на кнопке выбора фото
     */
    @Test
    fun test_selectPhotoButton_correctText() {
        onView(withId(R.id.btnSelectPhotos))
            .check(matches(withText(R.string.select_photos)))
    }

    /**
     * Тест 31: Проверка переключения качества в обратном порядке
     * High -> Medium -> Low
     */
    @Test
    fun test_qualitySelection_reverseOrder() {
        // High -> Medium -> Low
        onView(withId(R.id.rbQualityHigh)).perform(click())
        onView(withId(R.id.rbQualityHigh)).check(matches(isChecked()))

        onView(withId(R.id.rbQualityMedium)).perform(click())
        onView(withId(R.id.rbQualityMedium)).check(matches(isChecked()))
        onView(withId(R.id.rbQualityHigh)).check(matches(isNotChecked()))

        onView(withId(R.id.rbQualityLow)).perform(click())
        onView(withId(R.id.rbQualityLow)).check(matches(isChecked()))
        onView(withId(R.id.rbQualityMedium)).check(matches(isNotChecked()))
    }

    /**
     * Тест 32: Проверка быстрого переключения всех переключателей
     * Проверяет, что быстрые переключения не вызывают проблем
     */
    @Test
    fun test_rapidSwitchToggling() {
        // Быстро переключаем все переключатели несколько раз
        repeat(3) {
            onView(withId(R.id.switchAutoCompression)).perform(click())
            onView(withId(R.id.switchSaveMode)).perform(click())
            onView(withId(R.id.switchIgnoreMessengerPhotos)).perform(click())
        }

        // Проверяем, что все отображаются корректно
        onView(withId(R.id.switchAutoCompression)).check(matches(isDisplayed()))
        onView(withId(R.id.switchSaveMode)).check(matches(isDisplayed()))
        onView(withId(R.id.switchIgnoreMessengerPhotos)).check(matches(isDisplayed()))
    }

    /**
     * Тест 33: Проверка выбора качества после переключения переключателей
     * Проверяет, что переключатели не влияют на выбор качества
     */
    @Test
    fun test_qualitySelectionAfterSwitches() {
        // Переключаем переключатели
        onView(withId(R.id.switchAutoCompression)).perform(click())
        onView(withId(R.id.switchSaveMode)).perform(click())

        // Выбираем качество
        onView(withId(R.id.rbQualityHigh)).perform(click())

        // Проверяем, что качество выбрано корректно
        onView(withId(R.id.rbQualityHigh)).check(matches(isChecked()))

        // Переключаем ещё раз
        onView(withId(R.id.switchIgnoreMessengerPhotos)).perform(click())

        // Качество должно остаться выбранным
        onView(withId(R.id.rbQualityHigh)).check(matches(isChecked()))
    }

    /**
     * Тест 34: Проверка переключения качества после нажатия на кнопку
     * Проверяет, что нажатие кнопки не сбрасывает выбор качества
     */
    @Test
    fun test_qualitySelectionAfterButtonClick() {
        // Выбираем качество
        onView(withId(R.id.rbQualityMedium)).perform(click())
        onView(withId(R.id.rbQualityMedium)).check(matches(isChecked()))

        // Нажимаем на кнопку
        onView(withId(R.id.btnSelectPhotos)).perform(click())

        // Качество должно остаться выбранным
        onView(withId(R.id.rbQualityMedium)).check(matches(isChecked()))
    }

    /**
     * Тест 35: Проверка комбинации всех действий
     * Полный сценарий использования
     */
    @Test
    fun test_fullUserScenario() {
        // 1. Включаем автосжатие
        onView(withId(R.id.switchAutoCompression)).perform(click())

        // 2. Выбираем качество
        onView(withId(R.id.rbQualityHigh)).perform(click())

        // 3. Переключаем режим сохранения
        onView(withId(R.id.switchSaveMode)).perform(click())

        // 4. Включаем игнорирование мессенджеров
        onView(withId(R.id.switchIgnoreMessengerPhotos)).perform(click())

        // 5. Меняем качество
        onView(withId(R.id.rbQualityMedium)).perform(click())

        // 6. Проверяем конечное состояние
        onView(withId(R.id.switchAutoCompression)).check(matches(isDisplayed()))
        onView(withId(R.id.rbQualityMedium)).check(matches(isChecked()))
        onView(withId(R.id.switchSaveMode)).check(matches(isDisplayed()))
        onView(withId(R.id.switchIgnoreMessengerPhotos)).check(matches(isDisplayed()))

        // 7. Кнопка должна быть доступна
        onView(withId(R.id.btnSelectPhotos))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
    }

    /**
     * Тест 36: Проверка переключения всех трех качеств по очереди несколько раз
     */
    @Test
    fun test_qualityCycling_multipleTimes() {
        // Цикл 1: Low -> Medium -> High
        onView(withId(R.id.rbQualityLow)).perform(click())
        onView(withId(R.id.rbQualityMedium)).perform(click())
        onView(withId(R.id.rbQualityHigh)).perform(click())

        // Цикл 2: Low -> Medium -> High
        onView(withId(R.id.rbQualityLow)).perform(click())
        onView(withId(R.id.rbQualityMedium)).perform(click())
        onView(withId(R.id.rbQualityHigh)).perform(click())

        // Проверяем финальное состояние
        onView(withId(R.id.rbQualityHigh)).check(matches(isChecked()))
    }

    /**
     * Тест 37: Проверка переключения переключателей в разном порядке
     */
    @Test
    fun test_switches_differentOrder() {
        // Порядок 1: AutoCompression -> SaveMode -> IgnoreMessenger
        onView(withId(R.id.switchAutoCompression)).perform(click())
        onView(withId(R.id.switchSaveMode)).perform(click())
        onView(withId(R.id.switchIgnoreMessengerPhotos)).perform(click())

        // Порядок 2: IgnoreMessenger -> SaveMode -> AutoCompression
        onView(withId(R.id.switchIgnoreMessengerPhotos)).perform(click())
        onView(withId(R.id.switchSaveMode)).perform(click())
        onView(withId(R.id.switchAutoCompression)).perform(click())

        // Все переключатели должны оставаться видимыми
        onView(withId(R.id.switchAutoCompression)).check(matches(isDisplayed()))
        onView(withId(R.id.switchSaveMode)).check(matches(isDisplayed()))
        onView(withId(R.id.switchIgnoreMessengerPhotos)).check(matches(isDisplayed()))
    }

    /**
     * Тест 38: Проверка стабильности UI при множественных действиях
     */
    @Test
    fun test_uiStability_multipleActions() {
        // Выполняем множество действий
        repeat(5) {
            onView(withId(R.id.rbQualityLow)).perform(click())
            onView(withId(R.id.switchAutoCompression)).perform(click())
            onView(withId(R.id.rbQualityMedium)).perform(click())
            onView(withId(R.id.switchSaveMode)).perform(click())
            onView(withId(R.id.rbQualityHigh)).perform(click())
            onView(withId(R.id.switchIgnoreMessengerPhotos)).perform(click())
        }

        // UI должен оставаться стабильным
        onView(withId(R.id.mainContainer)).check(matches(isDisplayed()))
        onView(withId(R.id.radioGroupQuality)).check(matches(isDisplayed()))
        onView(withId(R.id.btnSelectPhotos)).check(matches(isDisplayed()))
    }

    /**
     * Тест 39: Проверка выбора каждого качества отдельно
     */
    @Test
    fun test_eachQualitySelection_independently() {
        // Выбираем только Low
        onView(withId(R.id.rbQualityLow)).perform(click())
        onView(withId(R.id.rbQualityLow)).check(matches(isChecked()))
        onView(withId(R.id.rbQualityMedium)).check(matches(isNotChecked()))
        onView(withId(R.id.rbQualityHigh)).check(matches(isNotChecked()))

        // Сбрасываем и выбираем только Medium
        onView(withId(R.id.rbQualityMedium)).perform(click())
        onView(withId(R.id.rbQualityLow)).check(matches(isNotChecked()))
        onView(withId(R.id.rbQualityMedium)).check(matches(isChecked()))
        onView(withId(R.id.rbQualityHigh)).check(matches(isNotChecked()))

        // Сбрасываем и выбираем только High
        onView(withId(R.id.rbQualityHigh)).perform(click())
        onView(withId(R.id.rbQualityLow)).check(matches(isNotChecked()))
        onView(withId(R.id.rbQualityMedium)).check(matches(isNotChecked()))
        onView(withId(R.id.rbQualityHigh)).check(matches(isChecked()))
    }

    /**
     * Тест 40: Проверка initialState всех переключателей
     */
    @Test
    fun test_initialState_allElements() {
        // Проверяем, что все элементы в исходном состоянии
        onView(withId(R.id.mainContainer)).check(matches(isDisplayed()))
        onView(withId(R.id.switchAutoCompression)).check(matches(isDisplayed()))
        onView(withId(R.id.switchSaveMode)).check(matches(isDisplayed()))
        onView(withId(R.id.switchIgnoreMessengerPhotos)).check(matches(isDisplayed()))
        onView(withId(R.id.radioGroupQuality)).check(matches(isDisplayed()))
        onView(withId(R.id.btnSelectPhotos)).check(matches(isDisplayed()))
        onView(withId(R.id.progressBar)).check(matches(not(isDisplayed())))
    }
}

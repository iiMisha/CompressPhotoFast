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
}

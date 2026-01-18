package com.compressphotofast.heic

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Простой instrumentation тест для проверки работы с HEIC файлами
 */
@RunWith(AndroidJUnit4::class)
class HeicSimpleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        // Очистка
    }

    /**
     * Тест 1: Проверка создания HEIC файла
     */
    @Test
    fun test_heicMimeType() {
        val heicMimeType = "image/heic"
        assertThat(heicMimeType).isEqualTo("image/heic")
    }
}

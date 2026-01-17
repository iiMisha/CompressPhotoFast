package com.compressphotofast.util

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.compressphotofast.BaseInstrumentedTest
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation тесты для проверки работы с URI
 *
 * Тестируют создание и парсинг URI
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class UriIntegrationTest : BaseInstrumentedTest() {

    /**
     * Тест 1: Проверка создания URI из строки
     */
    @Test
    fun test_uriFromString() {
        val uriString = "content://media/external/images/media/123"
        val uri = Uri.parse(uriString)

        org.junit.Assert.assertNotNull("URI не должен быть null", uri)
        org.junit.Assert.assertEquals(
            "Строка URI должна совпадать",
            uriString,
            uri.toString()
        )
    }

    /**
     * Тест 2: Проверка схемы URI
     */
    @Test
    fun test_uriScheme() {
        val contentUri = Uri.parse("content://media/external/images/media/123")
        val fileUri = Uri.parse("file:///sdcard/test.jpg")

        org.junit.Assert.assertEquals(
            "Схема должна быть 'content'",
            "content",
            contentUri.scheme
        )

        org.junit.Assert.assertEquals(
            "Схема должна быть 'file'",
            "file",
            fileUri.scheme
        )
    }

    /**
     * Тест 3: Проверка получения пути URI
     */
    @Test
    fun test_uriPath() {
        val uri = Uri.parse("content://media/external/images/media/123")
        val path = uri.path

        org.junit.Assert.assertNotNull("Путь не должен быть null", path)
        org.junit.Assert.assertTrue(
            "Путь не должен быть пустым",
            path!!.isNotEmpty()
        )
    }

    /**
     * Тест 4: Проверка получения lastPathSegment
     */
    @Test
    fun test_uriLastPathSegment() {
        val uri = Uri.parse("content://media/external/images/media/123")
        val lastSegment = uri.lastPathSegment

        org.junit.Assert.assertNotNull("LastPathSegment не должен быть null", lastSegment)
        org.junit.Assert.assertEquals(
            "LastPathSegment должен быть '123'",
            "123",
            lastSegment
        )
    }

    /**
     * Тест 5: Проверка создания URI для файла
     */
    @Test
    fun test_fileUriCreation() {
        val fileName = "test_image.jpg"
        val fileUri = Uri.parse("file:///sdcard/$fileName")

        org.junit.Assert.assertTrue(
            "URI должен содержать имя файла",
            fileUri.toString().contains(fileName)
        )
    }

    /**
     * Тест 6: Проверка URI с query параметрами
     */
    @Test
    fun test_uriWithQuery() {
        val uriString = "content://media/external/images/media?limit=10"
        val uri = Uri.parse(uriString)

        org.junit.Assert.assertEquals(
            "Query параметр должен быть 'limit=10'",
            "limit=10",
            uri.query
        )
    }

    /**
     * Тест 7: Проверка пустого URI
     */
    @Test
    fun test_emptyUri() {
        val emptyUri = Uri.parse("")

        org.junit.Assert.assertNotNull("Даже пустой URI не должен быть null", emptyUri)
        org.junit.Assert.assertEquals(
            "Пустой URI должен иметь пустую строку",
            "",
            emptyUri.toString()
        )
    }

    /**
     * Тест 8: Проверка сравнения URI
     */
    @Test
    fun test_uriEquality() {
        val uriString1 = "content://media/external/images/media/123"
        val uriString2 = "content://media/external/images/media/123"

        val uri1 = Uri.parse(uriString1)
        val uri2 = Uri.parse(uriString2)

        org.junit.Assert.assertEquals(
            "URI должны быть равны",
            uri1,
            uri2
        )
    }

    /**
     * Тест 9: Проверка получения authority
     */
    @Test
    fun test_uriAuthority() {
        val uri = Uri.parse("content://media/external/images/media/123")

        org.junit.Assert.assertEquals(
            "Authority должен быть 'media'",
            "media",
            uri.authority
        )
    }

    /**
     * Тест 10: Проверка создания URI с несколькими сегментами пути
     */
    @Test
    fun test_uriWithMultiplePathSegments() {
        val uri = Uri.parse("content://media/external/images/media/123")

        val pathSegments = uri.pathSegments
        org.junit.Assert.assertTrue(
            "Должно быть несколько сегментов пути",
            pathSegments.size >= 3
        )

        org.junit.Assert.assertEquals(
            "Первый сегмент должен быть 'external'",
            "external",
            pathSegments[0]
        )
    }
}

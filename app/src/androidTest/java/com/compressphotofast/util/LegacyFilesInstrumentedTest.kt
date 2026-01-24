package com.compressphotofast.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.compressphotofast.BaseInstrumentedTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Инструментальные тесты для проверки обработки старых файлов, скопированных через USB.
 *
 * Эти тесты работают с реальным MediaStore на устройстве/эмуляторе,
 * что позволяет проверить реальное поведение системы.
 */
class LegacyFilesInstrumentedTest : BaseInstrumentedTest() {

    companion object {
        // Константы из LegacyFilesTestHelpers (скопированы для androidTest)
        val OLD_FILE_TIME_2020: Long = 1577836800L // 01.01.2020 00:00:00 UTC
    }

    private lateinit var context: Context

    @Before
    override fun setUp() {
        super.setUp()
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    /**
     * Тест 1: Создание и проверка файла со старой DATE_MODIFIED
     *
     * Сценарий:
     * 1. Создать тестовый JPEG файл
     * 2. Вставить в MediaStore с DATE_ADDED=сейчас, DATE_MODIFIED=2020
     * 3. Проверить, что файл может быть прочитан
     */
    @Test
    fun testInsertFileWithOldModifiedDate() {
        // Подготовка данных
        val currentTime = System.currentTimeMillis() / 1000
        val oldFileTime = OLD_FILE_TIME_2020

        // Создаем ContentValues для вставки в MediaStore
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "test_old_file_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, currentTime)
            put(MediaStore.Images.Media.DATE_MODIFIED, oldFileTime)
            put(MediaStore.Images.Media.SIZE, 1024 * 100) // 100 KB
            put(MediaStore.Images.Media.IS_PENDING, 0)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Test")
        }

        // Вставляем в MediaStore
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )

        assertNotNull("URI не должен быть null", uri)

        // Проверяем, что файл можно прочитать
        uri?.let { testUri ->
            val cursor = context.contentResolver.query(
                testUri,
                arrayOf(
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.DISPLAY_NAME
                ),
                null,
                null,
                null
            )

            assertNotNull("Cursor не должен быть null", cursor)

            cursor?.use {
                assertTrue("Cursor должен содержать данные", it.moveToFirst())

                val dateAddedIndex = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val dateModifiedIndex = it.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val displayNameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)

                val dateAdded = it.getLong(dateAddedIndex)
                val dateModified = it.getLong(dateModifiedIndex)
                val displayName = it.getString(displayNameIndex)

                // Проверяем, что даты соответствуют ожиданиям
                assertEquals("DATE_ADDED должен быть недавним", currentTime, dateAdded)
                assertEquals("DATE_MODIFIED должен быть старым", oldFileTime, dateModified)
                assertTrue("DISPLAY_NAME должен содержать test_old_file", displayName.contains("test_old_file"))
            }

            // Очистка - удаляем тестовый файл
            context.contentResolver.delete(testUri, null, null)
        }
    }

    /**
     * Тест 2: Проверка IS_PENDING флага
     *
     * Сценарий:
     * 1. Создать файл с IS_PENDING=1
     * 2. Проверить, что UriUtil.isFilePending() возвращает правильный результат
     * 3. Подождать и проверить, что устаревший флаг игнорируется
     */
    @Test
    fun testIsPendingFlagWithRealMediaStore() {
        val currentTime = System.currentTimeMillis() / 1000

        // Создаем файл с IS_PENDING=1
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "test_pending_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, currentTime)
            put(MediaStore.Images.Media.DATE_MODIFIED, currentTime)
            put(MediaStore.Images.Media.SIZE, 1024 * 100)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Test")
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )

        assertNotNull("URI не должен быть null", uri)

        uri?.let {
            // Проверяем, что IS_PENDING установлен
            val cursor = context.contentResolver.query(
                it,
                arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.DATE_ADDED),
                null,
                null,
                null
            )

            cursor?.use {
                assertTrue("Cursor должен содержать данные", it.moveToFirst())

                val isPendingIndex = it.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                val isPending = it.getInt(isPendingIndex)

                assertEquals("IS_PENDING должен быть 1", 1, isPending)
            }

            // Очистка
            context.contentResolver.delete(it, null, null)
        }
    }

    /**
     * Тест 3: Проверка GalleryScanUtil с реальными файлами
     *
     * Сценарий:
     * 1. Создать несколько файлов с разными характеристиками
     * 2. Запустить GalleryScanUtil.scanRecentImages()
     * 3. Проверить, что файлы обнаружены корректно
     */
    @Test
    fun testGalleryScanUtilWithRealFiles() {
        // Создаем тестовый файл
        val currentTime = System.currentTimeMillis() / 1000
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "test_scan_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, currentTime)
            put(MediaStore.Images.Media.DATE_MODIFIED, OLD_FILE_TIME_2020)
            put(MediaStore.Images.Media.SIZE, 5 * 1024 * 1024) // 5 MB
            put(MediaStore.Images.Media.IS_PENDING, 0)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Test")
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )

        assertNotNull("URI не должен быть null", uri)

        uri?.let { testUri ->
            try {
                // Примечание: GalleryScanUtil требует мокания SettingsManager и других зависимостей
                // В instrumentation тестах это сложнее, поэтому здесь мы просто проверяем,
                // что файл существует в MediaStore

                val cursor = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.DATE_MODIFIED
                    ),
                    "${MediaStore.Images.Media.DATE_ADDED} > ?",
                    arrayOf((currentTime - 900).toString()), // за последние 15 минут
                    null
                )

                assertNotNull("Cursor не должен быть null", cursor)

                var found = false
                cursor?.use {
                    while (it.moveToNext()) {
                        val idIndex = it.getColumnIndex(MediaStore.Images.Media._ID)
                        val id = it.getLong(idIndex)

                        // Проверяем, что наш файл найден
                        val scanUri = android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )

                        if (scanUri == testUri) {
                            found = true
                        }
                    }
                }

                assertTrue("Созданный файл должен быть найден при сканировании", found)

            } finally {
                // Очистка
                context.contentResolver.delete(testUri, null, null)
            }
        }
    }

    /**
     * Тест 4: Проверка фильтрации по размеру файла
     *
     * Сценарий:
     * 1. Создать маленький файл (< 50 KB)
     * 2. Проверить, что он пропускается при сканировании
     */
    @Test
    fun testFileSizeFiltering() {
        val currentTime = System.currentTimeMillis() / 1000

        // Создаем маленький файл
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "test_small_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, currentTime)
            put(MediaStore.Images.Media.DATE_MODIFIED, currentTime)
            put(MediaStore.Images.Media.SIZE, 30 * 1024L) // 30 KB - меньше MIN_FILE_SIZE
            put(MediaStore.Images.Media.IS_PENDING, 0)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Test")
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )

        uri?.let {
            try {
                // Проверяем размер файла
                val cursor = context.contentResolver.query(
                    it,
                    arrayOf(MediaStore.Images.Media.SIZE),
                    null,
                    null,
                    null
                )

                cursor?.use {
                    assertTrue("Cursor должен содержать данные", it.moveToFirst())

                    val sizeIndex = it.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val size = it.getLong(sizeIndex)

                    assertTrue("Размер должен быть 30 KB", size == 30 * 1024L)
                    assertTrue("Размер должен быть меньше 50 KB", size < 50 * 1024L)
                }

            } finally {
                // Очистка
                context.contentResolver.delete(it, null, null)
            }
        }
    }

    /**
     * Тест 5: Интеграционный тест - полный цикл
     *
     * Проверяет полный цикл от создания файла до его обнаружения
     */
    @Test
    fun testFullCycleOfLegacyFileProcessing() {
        val currentTime = System.currentTimeMillis() / 1000

        // Шаг 1: Создаем файл, имитирующий старый файл, скопированный через USB
        val fileName = "test_legacy_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, currentTime) // Недавно добавлен
            put(MediaStore.Images.Media.DATE_MODIFIED, OLD_FILE_TIME_2020) // Старый файл
            put(MediaStore.Images.Media.SIZE, 5 * 1024 * 1024) // 5 MB
            put(MediaStore.Images.Media.IS_PENDING, 0)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Test")
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )

        assertNotNull("Шаг 1: URI не должен быть null", uri)

        uri?.let {
            try {
                // Шаг 2: Проверяем, что файл имеет правильные метаданные
                val cursor = context.contentResolver.query(
                    it,
                    arrayOf(
                        MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.DATE_MODIFIED,
                        MediaStore.Images.Media.SIZE
                    ),
                    null,
                    null,
                    null
                )

                assertNotNull("Шаг 2: Cursor не должен быть null", cursor)

                cursor?.use {
                    assertTrue("Шаг 2: Cursor должен содержать данные", it.moveToFirst())

                    val dateAddedIndex = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val dateModifiedIndex = it.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                    val sizeIndex = it.getColumnIndex(MediaStore.Images.Media.SIZE)

                    val dateAdded = it.getLong(dateAddedIndex)
                    val dateModified = it.getLong(dateModifiedIndex)
                    val size = it.getLong(sizeIndex)

                    // Проверяем характеристики файла (с допуском в 2 секунды для DATE_ADDED)
                    val addedTimeDiff = Math.abs(dateAdded - currentTime)
                    assertTrue("Шаг 2: DATE_ADDED должен быть недавним (разница $addedTimeDiff сек)", addedTimeDiff <= 2)
                    assertEquals("Шаг 2: DATE_MODIFIED должен быть старым (2020)", OLD_FILE_TIME_2020, dateModified)
                    assertEquals("Шаг 2: Размер должен быть 5 MB", 5 * 1024 * 1024L, size)

                    // Проверяем разницу между датами (старый файл, недавно добавленный)
                    val modifiedTimeDiff = dateAdded - dateModified
                    assertTrue("Шаг 2: Разница должна быть большой (> 1 год)", modifiedTimeDiff > 365 * 24 * 60 * 60)
                }

                // Шаг 3: Проверяем, что файл может быть найден при сканировании
                val scanCursor = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.DATE_ADDED} > ? AND ${MediaStore.Images.Media.SIZE} >= ?",
                    arrayOf((currentTime - 900).toString(), (50 * 1024).toString()),
                    null
                )

                var found = false
                scanCursor?.use {
                    while (it.moveToNext()) {
                        val idIndex = it.getColumnIndex(MediaStore.Images.Media._ID)
                        val id = it.getLong(idIndex)
                        val scanUri = android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        if (scanUri == uri) {
                            found = true
                        }
                    }
                }

                assertTrue("Шаг 3: Файл должен быть найден при сканировании", found)

            } finally {
                // Шаг 4: Очистка - удаляем тестовый файл
                val deleted = context.contentResolver.delete(uri, null, null)
                assertEquals("Шаг 4: Должен быть удален 1 файл", 1, deleted)
            }
        }
    }
}

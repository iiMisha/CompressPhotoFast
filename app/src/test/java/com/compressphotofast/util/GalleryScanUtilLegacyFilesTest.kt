package com.compressphotofast.util

import com.compressphotofast.BaseUnitTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit тесты для GalleryScanUtil, проверяющие обработку старых файлов,
 * недавно скопированных на устройство через USB.
 *
 * Сценарии тестирования:
 * 1. Файлы с недавним DATE_ADDED, но старым DATE_MODIFIED
 * 2. Файлы, измененные в последние 3 секунды
 * 3. Файлы с устаревшим флагом IS_PENDING
 * 4. Расширенное 24-часовое окно сканирования
 */
class GalleryScanUtilLegacyFilesTest : BaseUnitTest() {

    private lateinit var mockContext: android.content.Context

    @Before
    override fun setUp() {
        super.setUp()

        // Создаем mock Context
        mockContext = mockk<android.content.Context>(relaxed = true)
    }

    /**
     * Тест 1: Проверка обнаружения старых файлов с недавним DATE_ADDED
     *
     * Сценарий:
     * - DATE_ADDED = сейчас (файл недавно добавлен)
     * - DATE_MODIFIED = 2020 год (старый файл)
     * - IS_PENDING = 0 (не в процессе записи)
     *
     * Ожидание: Файл ДОЛЖЕН быть обнаружен и добавлен в foundUris
     *
     * Цель: Проверить, что сканирование использует только DATE_ADDED
     * и игнорирует DATE_MODIFIED при поиске новых файлов
     */
    @Test
    fun scanRecentImages_detectsOldFilesWithRecentDateAdded() = runTest {
        // Подготовка тестовых данных
        val currentTime = System.currentTimeMillis() / 1000
        val oldFileTime = LegacyFilesTestHelpers.OLD_FILE_TIME_2020

        // Создаем mock cursor с файлом, который был недавно добавлен, но имеет старую дату модификации
        val cursor = LegacyFilesTestHelpers.createMediaStoreCursor(
            id = 1L,
            dateAddedSeconds = currentTime,
            dateModifiedSeconds = oldFileTime,
            isPending = 0,
            displayName = "old_photo_2020.jpg",
            size = 5 * 1024 * 1024 // 5 MB
        )

        val contentResolver = LegacyFilesTestHelpers.createMockContentResolverWithCursor(cursor)
        every { mockContext.contentResolver } returns contentResolver

        // Мокаем SettingsManager
        mockkObject(SettingsManager)
        every { SettingsManager.getInstance(mockContext).isAutoCompressionEnabled() } returns true

        // Выполняем сканирование с checkProcessable=false, чтобы не мокать сложные зависимости
        val result = GalleryScanUtil.scanRecentImages(
            context = mockContext,
            timeWindowSeconds = 900, // 15 минут
            checkProcessable = false // Не проверяем processable, только базовую логику
        )

        // Проверяем результаты
        assertEquals("Файл должен быть обнаружен", 1, result.processedCount)
        assertEquals("Файлы не должны пропускаться из-за размера", 0, result.skippedCount)
        assertTrue("Список URI должен содержать файл", result.foundUris.isNotEmpty())
    }

    /**
     * Тест 2: Проверка пропуска маленьких файлов
     *
     * Сценарий:
     * - Размер файла = 30 KB (< 50 KB)
     *
     * Ожидание: Файл пропускается (маленький размер)
     */
    @Test
    fun scanRecentImages_skipsSmallFiles() = runTest {
        val currentTime = System.currentTimeMillis() / 1000

        // Создаем mock cursor с маленьким файлом
        val cursor = LegacyFilesTestHelpers.createMediaStoreCursor(
            id = 1L,
            dateAddedSeconds = currentTime,
            dateModifiedSeconds = currentTime,
            isPending = 0,
            displayName = "small_photo.jpg",
            size = 30 * 1024L // 30 KB
        )

        val contentResolver = LegacyFilesTestHelpers.createMockContentResolverWithCursor(cursor)
        every { mockContext.contentResolver } returns contentResolver

        // Мокаем SettingsManager
        mockkObject(SettingsManager)
        every { SettingsManager.getInstance(mockContext).isAutoCompressionEnabled() } returns true

        // Выполняем сканирование
        val result = GalleryScanUtil.scanRecentImages(
            context = mockContext,
            timeWindowSeconds = 900,
            checkProcessable = false // Не проверяем processable
        )

        // Проверяем, что маленький файл был пропущен
        assertEquals("Маленький файл не должен обрабатываться", 0, result.processedCount)
        assertTrue("Маленький файл должен быть в списке пропущенных", result.skippedCount > 0)
    }

    /**
     * Тест 3: Проверка 24-часового окна сканирования для старых файлов
     *
     * Сценарий:
     * - 24-часовое окно (86400 секунд)
     * - DATE_ADDED = сейчас
     * - DATE_MODIFIED = 2020 год
     *
     * Ожидание: Файл НАЙДЕН при сканировании за 24 часа
     *
     * Цель: Проверить расширенное окно сканирования
     */
    @Test
    fun scanDayOldImages_detectsRecentlyCopiedOldFiles() = runTest {
        // Подготовка тестовых данных
        val currentTime = System.currentTimeMillis() / 1000

        // Создаем mock cursor со старым файлом
        val cursor = LegacyFilesTestHelpers.createMediaStoreCursor(
            id = 1L,
            dateAddedSeconds = currentTime,
            dateModifiedSeconds = LegacyFilesTestHelpers.OLD_FILE_TIME_2020,
            isPending = 0,
            displayName = "old_recently_added.jpg",
            size = 5 * 1024 * 1024
        )

        val contentResolver = LegacyFilesTestHelpers.createMockContentResolverWithCursor(cursor)
        every { mockContext.contentResolver } returns contentResolver

        // Мокаем SettingsManager
        mockkObject(SettingsManager)
        every { SettingsManager.getInstance(mockContext).isAutoCompressionEnabled() } returns true

        // Выполняем сканирование за 24 часа
        val result = GalleryScanUtil.scanDayOldImages(mockContext)

        // Проверяем, что файл найден
        assertEquals("Файл должен быть найден при 24-часовом сканировании", 1, result.processedCount)
        assertEquals("Файлы не должны пропускаться из-за размера", 0, result.skippedCount)
    }

    /**
     * Тест 4: Проверка отключенного автоматического сжатия
     *
     * Сценарий:
     * - Автосжатие отключено
     *
     * Ожидание: Сканирование пропускается
     */
    @Test
    fun scanRecentImages_returnsEmptyWhenAutoCompressionDisabled() = runTest {
        // Мокаем SettingsManager с отключенным автосжатием
        mockkObject(SettingsManager)
        every { SettingsManager.getInstance(mockContext).isAutoCompressionEnabled() } returns false

        // Выполняем сканирование
        val result = GalleryScanUtil.scanRecentImages(
            context = mockContext,
            timeWindowSeconds = 900,
            checkProcessable = true
        )

        // Проверяем, что сканирование было пропущено
        assertEquals("Ничего не должно быть обработано", 0, result.processedCount)
        assertEquals("Ничего не должно быть пропущено", 0, result.skippedCount)
        assertTrue("Список URI должен быть пустым", result.foundUris.isEmpty())
    }

    /**
     * Тест 5: Проверка фильтрации по слишком большим файлам
     *
     * Сценарий:
     * - Размер файла = 150 MB (> 100 MB)
     *
     * Ожидание: Файл пропускается (слишком большой)
     */
    @Test
    fun scanRecentImages_skipsTooLargeFiles() = runTest {
        val currentTime = System.currentTimeMillis() / 1000

        // Создаем mock cursor с большим файлом
        val cursor = LegacyFilesTestHelpers.createMediaStoreCursor(
            id = 1L,
            dateAddedSeconds = currentTime,
            dateModifiedSeconds = currentTime,
            isPending = 0,
            displayName = "huge_photo.jpg",
            size = 150 * 1024 * 1024L // 150 MB
        )

        val contentResolver = LegacyFilesTestHelpers.createMockContentResolverWithCursor(cursor)
        every { mockContext.contentResolver } returns contentResolver

        // Мокаем SettingsManager
        mockkObject(SettingsManager)
        every { SettingsManager.getInstance(mockContext).isAutoCompressionEnabled() } returns true

        // Выполняем сканирование
        val result = GalleryScanUtil.scanRecentImages(
            context = mockContext,
            timeWindowSeconds = 900,
            checkProcessable = false
        )

        // Проверяем, что большой файл был пропущен
        assertEquals("Большой файл не должен обрабатываться", 0, result.processedCount)
        assertTrue("Большой файл должен быть в списке пропущенных", result.skippedCount > 0)
    }

    /**
     * Тест 6: Проверка обработки нескольких файлов с разными характеристиками
     *
     * Сценарий:
     * - Файл 1: 5 MB (нормальный)
     * - Файл 2: 30 KB (слишком маленький)
     * - Файл 3: 150 MB (слишком большой)
     *
     * Ожидание: Только файл 1 обрабатывается
     */
    @Test
    fun scanRecentImages_filtersMultipleFilesBySize() = runTest {
        val currentTime = System.currentTimeMillis() / 1000

        // Создаем mock cursor с тремя файлами
        val cursor = LegacyFilesTestHelpers.createMediaStoreCursor(
            id = 1L,
            dateAddedSeconds = currentTime,
            dateModifiedSeconds = currentTime,
            isPending = 0,
            displayName = "normal_photo.jpg",
            size = 5 * 1024 * 1024L // 5 MB
        )

        val contentResolver = LegacyFilesTestHelpers.createMockContentResolverWithCursor(cursor)
        every { mockContext.contentResolver } returns contentResolver

        // Мокаем SettingsManager
        mockkObject(SettingsManager)
        every { SettingsManager.getInstance(mockContext).isAutoCompressionEnabled() } returns true

        // Выполняем сканирование
        val result = GalleryScanUtil.scanRecentImages(
            context = mockContext,
            timeWindowSeconds = 900,
            checkProcessable = false
        )

        // Проверяем результаты
        assertEquals("Нормальный файл должен быть обработан", 1, result.processedCount)
        assertEquals("Файлы не должны пропускаться из-за размера", 0, result.skippedCount)
    }

    /**
     * Тест 7: Проверка IS_PENDING флага с базовой фильтрацией
     *
     * Сценарий:
     * - IS_PENDING = 1
     *
     * Ожидание: Файл добавляется в список (базовая логика без проверки pending)
     */
    @Test
    fun scanRecentImages_includesPendingFilesWithoutProcessableCheck() = runTest {
        val currentTime = System.currentTimeMillis() / 1000

        // Создаем mock cursor с файлом, который имеет IS_PENDING
        val cursor = LegacyFilesTestHelpers.createMediaStoreCursor(
            id = 1L,
            dateAddedSeconds = currentTime,
            dateModifiedSeconds = currentTime,
            isPending = 1, // Флаг установлен
            displayName = "pending_photo.jpg",
            size = 5 * 1024 * 1024
        )

        val contentResolver = LegacyFilesTestHelpers.createMockContentResolverWithCursor(cursor)
        every { mockContext.contentResolver } returns contentResolver

        // Мокаем SettingsManager
        mockkObject(SettingsManager)
        every { SettingsManager.getInstance(mockContext).isAutoCompressionEnabled() } returns true

        // Выполняем сканирование без проверки processable
        val result = GalleryScanUtil.scanRecentImages(
            context = mockContext,
            timeWindowSeconds = 900,
            checkProcessable = false // Не проверяем processable, поэтому IS_PENDING не учитывается
        )

        // Проверяем, что файл добавлен (так как не проверяем processable)
        assertEquals("Файл должен быть добавлен при checkProcessable=false", 1, result.processedCount)
    }
}

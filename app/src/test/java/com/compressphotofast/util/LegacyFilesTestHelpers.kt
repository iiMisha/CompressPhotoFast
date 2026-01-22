package com.compressphotofast.util

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import java.util.Calendar

/**
 * Вспомогательный объект для создания mock данных при тестировании обработки старых файлов.
 *
 * Предоставляет функции для создания MediaStore cursor с специфическими характеристиками:
 * - Старые файлы с недавним DATE_ADDED (скопированные через USB)
 * - Файлы с флагом IS_PENDING
 * - Файлы с различными комбинациями DATE_ADDED и DATE_MODIFIED
 *
 * Это позволяет тестировать сценарии, когда старые файлы копируются на устройство
 * и должны быть обработаны автоматическим сжатием.
 */
object LegacyFilesTestHelpers {

    /**
     * Константа для текущего времени в секундах (для DATE_ADDED)
     */
    val CURRENT_TIME_SECONDS: Long = System.currentTimeMillis() / 1000

    /**
     * Константа для времени файла из 2020 года (для DATE_MODIFIED старых файлов)
     * 01.01.2020 00:00:00 UTC
     */
    val OLD_FILE_TIME_2020: Long = 1577836800L

    /**
     * Константа для времени файла из 2021 года
     * 01.01.2021 00:00:00 UTC
     */
    val OLD_FILE_TIME_2021: Long = 1609459200L

    /**
     * Создает MatrixCursor с данными MediaStore для тестового файла.
     *
     * @param id ID файла в MediaStore
     * @param dateAddedSeconds Время добавления файла (DATE_ADDED) в секундах
     * @param dateModifiedSeconds Время модификации файла (DATE_MODIFIED) в секундах
     * @param isPending Флаг IS_PENDING (0 или 1)
     * @param displayName Имя файла
     * @param size Размер файла в байтах
     * @return MatrixCursor с одной строкой данных
     */
    fun createMediaStoreCursor(
        id: Long = 1L,
        dateAddedSeconds: Long = CURRENT_TIME_SECONDS,
        dateModifiedSeconds: Long = OLD_FILE_TIME_2020,
        isPending: Int = 0,
        displayName: String = "old_photo_2020.jpg",
        size: Long = 5 * 1024 * 1024 // 5 MB
    ): MatrixCursor {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.IS_PENDING,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.RELATIVE_PATH
        )

        val cursor = MatrixCursor(projection)
        cursor.addRow(arrayOf(
            id,                           // _ID
            dateAddedSeconds,             // DATE_ADDED
            dateModifiedSeconds,          // DATE_MODIFIED
            isPending,                    // IS_PENDING
            displayName,                  // DISPLAY_NAME
            size,                         // SIZE
            "image/jpeg",                 // MIME_TYPE
            "Pictures/"                   // RELATIVE_PATH
        ))

        return cursor
    }

    /**
     * Создает mock ContentResolver с MediaStore query result.
     *
     * @param cursor Cursor, который будет возвращаться при query()
     * @return Mock ContentResolver
     */
    fun createMockContentResolverWithCursor(cursor: MatrixCursor): ContentResolver {
        val mockResolver = mockk<ContentResolver>(relaxed = true)

        // Используем relaxed = true, поэтому нам нужно указать только то, что возвращает cursor
        every {
            mockResolver.query(
                any<Uri>(),
                any(),
                any(),
                any(),
                any()
            )
        } returns cursor

        return mockResolver
    }

    /**
     * Создает mock ContentResolver с несколькими файлами в MediaStore.
     *
     * @param cursors Список курсоров для разных query вызовов
     * @return Mock ContentResolver
     */
    fun createMockContentResolverWithMultipleCursors(vararg cursors: MatrixCursor): ContentResolver {
        val mockResolver = mockk<ContentResolver>(relaxed = true)

        // Используем первый cursor для всех вызовов
        every {
            mockResolver.query(
                any<Uri>(),
                any(),
                any(),
                any(),
                any()
            )
        } returns cursors.first()

        return mockResolver
    }

    /**
     * Создает mock Context с настроенным ContentResolver.
     *
     * @param contentResolver Mock ContentResolver
     * @return Mock Context
     */
    fun createMockContextWithContentResolver(contentResolver: ContentResolver): Context {
        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.contentResolver } returns contentResolver
        return mockContext
    }

    /**
     * Создает готовый к использованию mock Context с MediaStore cursor для старого файла.
     *
     * @param dateAddedSeconds Время добавления файла (по умолчанию: сейчас)
     * @param dateModifiedSeconds Время модификации файла (по умолчанию: 2020 год)
     * @param isPending Флаг IS_PENDING (по умолчанию: 0)
     * @return Mock Context с настроенным ContentResolver
     */
    fun createMockContextForLegacyFile(
        dateAddedSeconds: Long = CURRENT_TIME_SECONDS,
        dateModifiedSeconds: Long = OLD_FILE_TIME_2020,
        isPending: Int = 0
    ): Context {
        val cursor = createMediaStoreCursor(
            dateAddedSeconds = dateAddedSeconds,
            dateModifiedSeconds = dateModifiedSeconds,
            isPending = isPending
        )
        val contentResolver = createMockContentResolverWithCursor(cursor)
        return createMockContextWithContentResolver(contentResolver)
    }

    /**
     * Создает тестовый URI для MediaStore изображения.
     *
     * @param id ID файла в MediaStore
     * @return content:// URI для изображения
     */
    fun createMediaStoreUri(id: Long = 1L): Uri {
        // Используем mock Uri для Robolectric тестов
        val mockUri = mockk<Uri>()
        every { mockUri.toString() } returns "content://media/external/images/media/$id"
        every { mockUri.scheme } returns "content"
        every { mockUri.authority } returns "media"
        every { mockUri.path } returns "/external/images/media/$id"
        every { mockUri.lastPathSegment } returns id.toString()
        return mockUri
    }

    /**
     * Вычисляет возраст файла в секундах относительно текущего времени.
     *
     * @param dateAddedSeconds DATE_ADDED в секундах
     * @return Возраст файла в секундах
     */
    fun calculateFileAge(dateAddedSeconds: Long): Long {
        return CURRENT_TIME_SECONDS - dateAddedSeconds
    }

    /**
     * Создает Calendar объект для указанного времени в секундах.
     *
     * @param timeSeconds Время в секундах с 1970-01-01
     * @return Calendar объект
     */
    fun createDateFromSeconds(timeSeconds: Long): Calendar {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timeSeconds * 1000
        return calendar
    }

    /**
     * Формирует человекочитаемое описание времени файла для логов.
     *
     * @param timeSeconds Время в секундах
     * @param label Метка для описания (например, "DATE_ADDED")
     * @return Строка с описанием
     */
    fun formatTimeDescription(timeSeconds: Long, label: String): String {
        val calendar = createDateFromSeconds(timeSeconds)
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return "$label: $year-$month-$day ($timeSeconds)"
    }
}

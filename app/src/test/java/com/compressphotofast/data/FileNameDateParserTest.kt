package com.compressphotofast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * Корпус шаблонов [FileNameDateParser]: epoch-префиксы, цифровые basename,
 * компактные и разделённые дата-время, только дата; негативные случаи.
 * Точное время проверяется в локальной таймзоне (как в парсере).
 */
class FileNameDateParserTest {

    private fun msOf(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    @Test
    fun `epoch с известным префиксом`() {
        assertEquals(1698741123456L, FileNameDateParser.parseDateMs("mmexport1698741123456.jpg"))
        assertEquals(1698741123456L, FileNameDateParser.parseDateMs("wx_camera_1698741123456.jpg"))
        assertEquals(1698741123456L, FileNameDateParser.parseDateMs("Snapchat-1698741123456.jpg"))
    }

    @Test
    fun `basename целиком из цифр - 13 цифр ms, 10 цифр секунды`() {
        assertEquals(1698741123456L, FileNameDateParser.parseDateMs("1698741123456.jpg"))
        assertEquals(1698741123000L, FileNameDateParser.parseDateMs("1698741123.jpg"))
    }

    @Test
    fun `компактные дата-время`() {
        val expected = msOf(2025, 1, 1, 12, 34, 56)
        assertEquals(expected, FileNameDateParser.parseDateMs("IMG_20250101_123456.jpg"))
        assertEquals(expected, FileNameDateParser.parseDateMs("20250101_123456.jpg"))
        assertEquals(expected, FileNameDateParser.parseDateMs("Screenshot_20250101-123456_CompressPhotoFast.jpg"))
        assertEquals(expected, FileNameDateParser.parseDateMs("PANO_20250101_123456.jpg"))
        assertEquals(expected, FileNameDateParser.parseDateMs("MVIMG_20250101_123456.jpg"))
        assertEquals(expected, FileNameDateParser.parseDateMs("IMG_20250101_123456_BURST001.jpg"))
        assertEquals(expected, FileNameDateParser.parseDateMs("IMG_20250101_123456_compressed.jpg"))
        assertEquals(expected, FileNameDateParser.parseDateMs("20250101123456.jpg"))
        assertEquals(expected, FileNameDateParser.parseDateMs("20250101T123456.jpg"))
    }

    @Test
    fun `разделённые дата-время, разделители компонентов независимы, мс-хвост игнорируется`() {
        assertEquals(
            msOf(2025, 1, 2, 3, 4, 5),
            FileNameDateParser.parseDateMs("signal-2025-01-02-03-04-05-123.jpg")
        )
        assertEquals(
            msOf(2025, 1, 1, 12, 30, 45),
            FileNameDateParser.parseDateMs("photo_2025-01-01_12-30-45.jpg")
        )
        assertEquals(
            msOf(2025, 1, 1, 12, 30, 45),
            FileNameDateParser.parseDateMs("photo_2025-01-01_12-30-45 (1).jpg")
        )
        assertEquals(
            msOf(2022, 1, 1, 12, 30, 45),
            FileNameDateParser.parseDateMs("Screenshot_2022-01-01-12-30-45-123.png")
        )
        assertEquals(
            msOf(2025, 1, 1, 12, 30),
            FileNameDateParser.parseDateMs("photo_2025-01-01_12-30.jpg")
        )
    }

    @Test
    fun `только дата - полночь`() {
        assertEquals(msOf(2025, 1, 1), FileNameDateParser.parseDateMs("IMG-20250101-WA0001.jpg"))
        assertEquals(msOf(2025, 1, 1), FileNameDateParser.parseDateMs("VID-20250101-WA0001.mp4"))
        assertEquals(msOf(2025, 1, 1), FileNameDateParser.parseDateMs("20250101.jpg"))
        assertEquals(msOf(2025, 1, 1), FileNameDateParser.parseDateMs("2025-01-01.png"))
        assertEquals(msOf(2025, 1, 1), FileNameDateParser.parseDateMs("2025.01.01.jpg"))
    }

    @Test
    fun `негативные случаи`() {
        assertNull(FileNameDateParser.parseDateMs(null))
        assertNull(FileNameDateParser.parseDateMs(""))
        assertNull(FileNameDateParser.parseDateMs("DSC01234.jpg"))
        assertNull(FileNameDateParser.parseDateMs("GOPR1234.jpg"))
        assertNull(FileNameDateParser.parseDateMs("2025-13-01.jpg"))
        assertNull(FileNameDateParser.parseDateMs("20250230.jpg"))
        assertNull(FileNameDateParser.parseDateMs("19900101.jpg"))
        assertNull(FileNameDateParser.parseDateMs("12345678.jpg"))
        assertNull(FileNameDateParser.parseDateMs("received_10161672603575608.jpg"))
        assertNull(FileNameDateParser.parseDateMs("202501011234567.jpg"))
        assertNull(FileNameDateParser.parseDateMs("202501011234.jpg"))
        assertNull(FileNameDateParser.parseDateMs("01-02-2025.jpg"))
    }
}

package com.compressphotofast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Корпус шаблонов [FileNameDateParser]: epoch-префиксы (мс/µs/нс), цифровые
 * basename, месяцы словами, компактные и разделённые дата-время (с `:` и
 * TZ-суффиксами), day-first с разделителями, только дата; негативные случаи.
 * Точное время проверяется в локальной таймзоне (как в парсере), кроме имён
 * с явным TZ-суффиксом.
 */
class FileNameDateParserTest {

    private fun msOf(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun msInZone(zone: String, year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(zone))
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    @Test
    fun `epoch с известным префиксом - ms, us, ns`() {
        assertEquals(1698741123456L, FileNameDateParser.parseDateMs("mmexport1698741123456.jpg"))
        assertEquals(1698741123456L, FileNameDateParser.parseDateMs("wx_camera_1698741123456.jpg"))
        assertEquals(1698741123456L, FileNameDateParser.parseDateMs("Snapchat-1698741123456.jpg"))
        assertEquals(1698741123456L, FileNameDateParser.parseDateMs("mmexport1698741123456000.jpg"))
        assertEquals(1698741123456L, FileNameDateParser.parseDateMs("Snapchat-1698741123456000000.jpg"))
    }

    @Test
    fun `basename целиком из цифр - 13 цифр ms, 16 цифр us, 10 цифр секунды`() {
        assertEquals(1698741123456L, FileNameDateParser.parseDateMs("1698741123456.jpg"))
        assertEquals(1698741123456L, FileNameDateParser.parseDateMs("1698741123456000.jpg"))
        assertEquals(1698741123000L, FileNameDateParser.parseDateMs("1698741123.jpg"))
    }

    @Test
    fun `месяцы словами - разные порядки и iOS-время с точкой с запятой`() {
        assertEquals(msOf(2025, 1, 1, 12, 30, 45), FileNameDateParser.parseDateMs("Photo Jan 1, 2025 at 12;30;45 JPG.jpg"))
        assertEquals(msOf(2025, 1, 1), FileNameDateParser.parseDateMs("Jan 01 2025.jpg"))
        assertEquals(msOf(2025, 1, 1), FileNameDateParser.parseDateMs("01 Jan 2025.jpg"))
        assertEquals(msOf(2025, 1, 1), FileNameDateParser.parseDateMs("2025-Jan-01.jpg"))
        assertEquals(msOf(2025, 1, 1), FileNameDateParser.parseDateMs("01Jan2025.jpg"))
        assertEquals(msOf(2025, 1, 1), FileNameDateParser.parseDateMs("photo January 01, 2025.jpg"))
        assertEquals(msOf(2025, 9, 2, 8, 15), FileNameDateParser.parseDateMs("Sep 02 2025 08.15.jpg"))
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
    fun `разделённые дата-время с двоеточиями`() {
        assertEquals(msOf(2025, 1, 1, 12, 30, 45), FileNameDateParser.parseDateMs("2025-01-01 12:30:45.jpg"))
        assertEquals(msOf(2025, 1, 1, 12, 30), FileNameDateParser.parseDateMs("2025-01-01_12:30.jpg"))
        assertEquals(msOf(2025, 1, 1, 12, 30, 45), FileNameDateParser.parseDateMs("2025-01-01_12:30:45 (1).jpg"))
    }

    @Test
    fun `таймзонные суффиксы - Z и смещения`() {
        assertEquals(
            msInZone("UTC", 2025, 1, 1, 12, 30, 45),
            FileNameDateParser.parseDateMs("2025-01-01T12:30:45Z.jpg")
        )
        assertEquals(
            msInZone("GMT+03:00", 2025, 1, 1, 12, 30, 45),
            FileNameDateParser.parseDateMs("2025-01-01 12:30:45+03:00.jpg")
        )
        assertEquals(
            msInZone("GMT-05:00", 2025, 1, 1, 12, 30, 45),
            FileNameDateParser.parseDateMs("2025-01-01 12:30:45-05:00.jpg")
        )
        assertEquals(
            msInZone("GMT+03:00", 2025, 1, 1, 12, 30, 45),
            FileNameDateParser.parseDateMs("2025-01-01 123045+0300.jpg")
        )
    }

    @Test
    fun `day-first с разделителями - гибрид DD-MM и MM-DD`() {
        assertEquals(msOf(2025, 2, 1), FileNameDateParser.parseDateMs("01-02-2025.jpg"))
        assertEquals(msOf(2025, 12, 25), FileNameDateParser.parseDateMs("12-25-2025.jpg"))
        assertEquals(msOf(2025, 2, 1), FileNameDateParser.parseDateMs("01.02.2025"))
        assertEquals(msOf(2025, 2, 1), FileNameDateParser.parseDateMs("01/02/2025"))
        assertEquals(msOf(2025, 12, 31, 10, 20, 30), FileNameDateParser.parseDateMs("31-12-2025_10-20-30.jpg"))
        assertEquals(msOf(2025, 12, 25, 12, 30, 45), FileNameDateParser.parseDateMs("25-12-2025 12:30:45"))
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
    }

    @Test
    fun `негативные случаи новых форматов`() {
        assertNull(FileNameDateParser.parseDateMs("251201.jpg"))
        assertNull(FileNameDateParser.parseDateMs("25-12-01.jpg"))
        assertNull(FileNameDateParser.parseDateMs("13-25-2025.jpg"))
        assertNull(FileNameDateParser.parseDateMs("01122025.jpg"))
        assertNull(FileNameDateParser.parseDateMs("photo 01 02 2025.jpg"))
        assertNull(FileNameDateParser.parseDateMs("1234567890123456789.jpg"))
        assertNull(FileNameDateParser.parseDateMs("IMG-1234-5678-2025.jpg"))
    }
}

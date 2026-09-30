package com.compressphotofast.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionMarkerTest {

    @Test
    fun `build и parse согласованы`() {
        val marker = CompressionMarker.build(70, 1742629672908L, 123456L, 987654L)

        assertEquals(CompressionMarkerInfo(true, 70, 1742629672908L, 123456L, 987654L), CompressionMarker.parse(marker))
    }

    @Test
    fun `заглушка фазы 1 даёт неизвестный размер`() {
        val info = CompressionMarker.parse(CompressionMarker.build(70, 1L, null, 5000L))!!

        assertNull(info.fileSize)
        assertEquals(5000L, info.originalFileSize)
    }

    @Test
    fun `старые форматы без размеров`() {
        assertEquals(
            CompressionMarkerInfo(true, 85, 1704067200000L, null, null),
            CompressionMarker.parse("CompressPhotoFast_Compressed:85:1704067200000")
        )
        assertEquals(
            CompressionMarkerInfo(true, 85, 1704067200000L, 4096L, null),
            CompressionMarker.parse("CompressPhotoFast_Compressed:85:1704067200000:4096")
        )
    }

    @Test
    fun `чужой или пустой UserComment не маркер`() {
        assertNull(CompressionMarker.parse(null))
        assertNull(CompressionMarker.parse(""))
        assertNull(CompressionMarker.parse("Some camera comment"))
        assertNull(CompressionMarker.parse("CompressPhotoFast_Compressed:85"))
    }

    @Test(expected = NumberFormatException::class)
    fun `нечисловое качество — ошибка разбора`() {
        CompressionMarker.parse("CompressPhotoFast_Compressed:abc:1")
    }

    @Test
    fun `проверка качества в маркере`() {
        val marker = CompressionMarker.build(70, 1L, null, null)
        assertTrue(CompressionMarker.hasMarkerWithQuality(marker, 70))
        assertFalse(CompressionMarker.hasMarkerWithQuality(marker, 99))
        assertFalse(CompressionMarker.hasMarkerWithQuality(null, 70))
    }
}

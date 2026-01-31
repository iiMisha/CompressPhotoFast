package com.compressphotofast.util

import android.content.ContentValues
import android.provider.MediaStore
import com.compressphotofast.BaseUnitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit тесты для MediaStoreDateUtil
 *
 * Тестирует утилиту для работы с датами в MediaStore.
 * Использует Robolectric для работы с Android API в unit тестах.
 */
@RunWith(RobolectricTestRunner::class)
class MediaStoreDateUtilTest : BaseUnitTest() {

    /**
     * Тест 1: Проверка, что setCreationTimestamp устанавливает значения в ContentValues
     */
    @Test
    fun `testSetCreationTimestamp sets values in ContentValues`() {
        // Arrange
        val values = ContentValues()
        val timestampMillis = 1704067200000L // 2024-01-01 00:00:00 UTC

        // Act
        MediaStoreDateUtil.setCreationTimestamp(values, timestampMillis)

        // Assert
        assertTrue("ContentValues should not be empty", values.size() > 0)
        assertNotNull("ContentValues should contain values", values.valueSet())
        assertFalse("ContentValues valueSet should not be empty", values.valueSet().isEmpty())
        assertEquals("Should have 2 values (DATE_ADDED and DATE_MODIFIED)", 2, values.size())
    }

    /**
     * Тест 2: Проверка преобразования миллисекунд в секунды
     */
    @Test
    fun `testSetCreationTimestamp converts milliseconds to seconds correctly`() {
        // Arrange
        val values = ContentValues()
        val timestampMillis = 1704067200000L // 2024-01-01 00:00:00 UTC
        val expectedSeconds = timestampMillis / 1000 // 1704067200

        // Act
        MediaStoreDateUtil.setCreationTimestamp(values, timestampMillis)

        // Assert
        val actualDateAdded = values.getAsLong(MediaStore.Images.Media.DATE_ADDED)
        val actualDateModified = values.getAsLong(MediaStore.Images.Media.DATE_MODIFIED)

        assertTrue("DATE_ADDED should be set", actualDateAdded != null)
        assertTrue("DATE_MODIFIED should be set", actualDateModified != null)
        assertEquals("DATE_ADDED should be in seconds", expectedSeconds, actualDateAdded)
        assertEquals("DATE_MODIFIED should be in seconds", expectedSeconds, actualDateModified)
    }

    /**
     * Тест 3: Проверка множественных вызовов
     */
    @Test
    fun `testSetCreationTimestamp can be called multiple times`() {
        // Arrange
        val values = ContentValues()
        val timestamp1 = 1704067200000L // 2024-01-01
        val timestamp2 = 1706745600000L // 2024-02-01

        // Act
        MediaStoreDateUtil.setCreationTimestamp(values, timestamp1)
        val sizeAfterFirstCall = values.size()
        val valueAfterFirstCall = values.getAsLong(MediaStore.Images.Media.DATE_ADDED)

        MediaStoreDateUtil.setCreationTimestamp(values, timestamp2)
        val sizeAfterSecondCall = values.size()
        val valueAfterSecondCall = values.getAsLong(MediaStore.Images.Media.DATE_ADDED)

        // Assert
        assertTrue("First call should set values", sizeAfterFirstCall > 0)
        assertTrue("First call should set DATE_ADDED", valueAfterFirstCall != null)
        assertEquals("First call should set 2 values", 2, sizeAfterFirstCall)
        assertEquals("First call should convert to seconds correctly", timestamp1 / 1000, valueAfterFirstCall)

        assertTrue("Second call should maintain values", sizeAfterSecondCall > 0)
        assertTrue("Second call should update DATE_ADDED", valueAfterSecondCall != null)
        assertEquals("Second call should still have 2 values", 2, sizeAfterSecondCall)
        assertEquals("Second call should update to new timestamp", timestamp2 / 1000, valueAfterSecondCall)
    }

    /**
     * Тест 4: Проверка работы с нулевым timestamp
     */
    @Test
    fun `testSetCreationTimestamp handles zero timestamp`() {
        // Arrange
        val values = ContentValues()
        val timestampMillis = 0L

        // Act
        MediaStoreDateUtil.setCreationTimestamp(values, timestampMillis)

        // Assert
        assertEquals("Should have 2 values even with timestamp 0", 2, values.size())
        val actualDateAdded = values.getAsLong(MediaStore.Images.Media.DATE_ADDED)
        val actualDateModified = values.getAsLong(MediaStore.Images.Media.DATE_MODIFIED)

        assertEquals("DATE_ADDED should be 0 for timestamp 0", 0L, actualDateAdded)
        assertEquals("DATE_MODIFIED should be 0 for timestamp 0", 0L, actualDateModified)
    }

    /**
     * Тест 5: Проверка работы с отрицательным timestamp
     */
    @Test
    fun `testSetCreationTimestamp handles negative timestamp`() {
        // Arrange
        val values = ContentValues()
        val timestampMillis = -1000L

        // Act
        MediaStoreDateUtil.setCreationTimestamp(values, timestampMillis)

        // Assert
        assertEquals("Should have 2 values even with negative timestamp", 2, values.size())
        val actualDateAdded = values.getAsLong(MediaStore.Images.Media.DATE_ADDED)
        val actualDateModified = values.getAsLong(MediaStore.Images.Media.DATE_MODIFIED)

        assertEquals("DATE_ADDED should be -1 for timestamp -1000", -1L, actualDateAdded)
        assertEquals("DATE_MODIFIED should be -1 for timestamp -1000", -1L, actualDateModified)
    }

    /**
     * Тест 6: Проверка сохранения существующих значений в ContentValues
     */
    @Test
    fun `testSetCreationTimestamp preserves existing values`() {
        // Arrange
        val values = ContentValues()
        values.put("existing_key", "existing_value")
        val timestampMillis = 1704067200000L

        // Act
        MediaStoreDateUtil.setCreationTimestamp(values, timestampMillis)

        // Assert
        assertEquals("Should preserve existing values", 3, values.size())
        assertEquals("Existing value should be preserved", "existing_value", values.getAsString("existing_key"))
        assertTrue("DATE_ADDED should be set", values.containsKey(MediaStore.Images.Media.DATE_ADDED))
        assertTrue("DATE_MODIFIED should be set", values.containsKey(MediaStore.Images.Media.DATE_MODIFIED))
    }
}

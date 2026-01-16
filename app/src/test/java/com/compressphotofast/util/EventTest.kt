package com.compressphotofast.util

import com.compressphotofast.BaseUnitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit тесты для класса Event.
 * Event используется для обертки данных, которые передаются через LiveData
 * и должны быть обработаны только один раз.
 */
class EventTest : BaseUnitTest() {

    // ========== Тесты создания события с данными ==========

    @Test
    fun `создание события с данными - содержимое сохранено`() {
        // Arrange
        val testData = "Test message"

        // Act
        val event = Event(testData)

        // Assert
        assertEquals(testData, event.peekContent())
    }

    @Test
    fun `создание события с данными - hasBeenHandled равно false`() {
        // Arrange
        val testData = "Test message"

        // Act
        val event = Event(testData)

        // Assert
        assertFalse(event.hasBeenHandled)
    }

    @Test
    fun `создание события с числовыми данными`() {
        // Arrange
        val testData = 42

        // Act
        val event = Event(testData)

        // Assert
        assertEquals(testData, event.peekContent())
    }

    @Test
    fun `создание события с объектом данных`() {
        // Arrange
        data class TestData(val id: Int, val name: String)
        val testData = TestData(1, "Test")

        // Act
        val event = Event(testData)

        // Assert
        assertEquals(testData, event.peekContent())
    }

    // ========== Тесты создания события без данных ==========

    @Test
    fun `создание события с Unit`() {
        // Arrange
        val testData = Unit

        // Act
        val event = Event(testData)

        // Assert
        assertEquals(Unit, event.peekContent())
    }

    // ========== Тесты getContentIfNotHandled ==========

    @Test
    fun `getContentIfNotHandled возвращает содержимое при первом вызове`() {
        // Arrange
        val testData = "Test message"
        val event = Event(testData)

        // Act
        val result = event.getContentIfNotHandled()

        // Assert
        assertEquals(testData, result)
    }

    @Test
    fun `getContentIfNotHandled устанавливает hasBeenHandled в true`() {
        // Arrange
        val testData = "Test message"
        val event = Event(testData)

        // Act
        event.getContentIfNotHandled()

        // Assert
        assertTrue(event.hasBeenHandled)
    }

    @Test
    fun `getContentIfNotHandled возвращает null при повторном вызове`() {
        // Arrange
        val testData = "Test message"
        val event = Event(testData)
        event.getContentIfNotHandled() // Первый вызов

        // Act
        val result = event.getContentIfNotHandled() // Второй вызов

        // Assert
        assertNull(result)
    }

    @Test
    fun `getContentIfNotHandled возвращает null после нескольких вызовов`() {
        // Arrange
        val testData = "Test message"
        val event = Event(testData)
        event.getContentIfNotHandled()
        event.getContentIfNotHandled()

        // Act
        val result = event.getContentIfNotHandled()

        // Assert
        assertNull(result)
    }

    // ========== Тесты peekContent ==========

    @Test
    fun `peekContent возвращает содержимое без изменения hasBeenHandled`() {
        // Arrange
        val testData = "Test message"
        val event = Event(testData)

        // Act
        val result = event.peekContent()

        // Assert
        assertEquals(testData, result)
        assertFalse(event.hasBeenHandled)
    }

    @Test
    fun `peekContent возвращает содержимое после getContentIfNotHandled`() {
        // Arrange
        val testData = "Test message"
        val event = Event(testData)
        event.getContentIfNotHandled()

        // Act
        val result = event.peekContent()

        // Assert
        assertEquals(testData, result)
    }

    @Test
    fun `peekContent возвращает содержимое после нескольких вызовов getContentIfNotHandled`() {
        // Arrange
        val testData = "Test message"
        val event = Event(testData)
        event.getContentIfNotHandled()
        event.getContentIfNotHandled()

        // Act
        val result = event.peekContent()

        // Assert
        assertEquals(testData, result)
    }

    // ========== Тесты комбинации методов ==========

    @Test
    fun `последовательность вызовов peekContent и getContentIfNotHandled`() {
        // Arrange
        val testData = "Test message"
        val event = Event(testData)

        // Act
        val peekResult1 = event.peekContent()
        val contentResult = event.getContentIfNotHandled()
        val peekResult2 = event.peekContent()

        // Assert
        assertEquals(testData, peekResult1)
        assertEquals(testData, contentResult)
        assertEquals(testData, peekResult2)
        assertTrue(event.hasBeenHandled)
    }

    @Test
    fun `getContentIfNotHandled возвращает null после установки hasBeenHandled`() {
        // Arrange
        val testData = "Test message"
        val event = Event(testData)
        event.getContentIfNotHandled()

        // Act
        val result = event.getContentIfNotHandled()

        // Assert
        assertNull(result)
    }

    // ========== Тесты с разными типами данных ==========

    @Test
    fun `событие с Boolean данными`() {
        // Arrange
        val testData = true
        val event = Event(testData)

        // Act
        val result = event.getContentIfNotHandled()

        // Assert
        assertEquals(testData, result)
    }

    @Test
    fun `событие со списком данных`() {
        // Arrange
        val testData = listOf(1, 2, 3)
        val event = Event(testData)

        // Act
        val result = event.getContentIfNotHandled()

        // Assert
        assertEquals(testData, result)
    }

    @Test
    fun `событие с null данными`() {
        // Arrange
        val testData: String? = null
        val event = Event(testData)

        // Act
        val result = event.getContentIfNotHandled()

        // Assert
        assertNull(result)
    }

    @Test
    fun `событие с пустой строкой`() {
        // Arrange
        val testData = ""
        val event = Event(testData)

        // Act
        val result = event.getContentIfNotHandled()

        // Assert
        assertEquals(testData, result)
    }
}

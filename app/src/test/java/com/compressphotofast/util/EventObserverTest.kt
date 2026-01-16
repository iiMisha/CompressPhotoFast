package com.compressphotofast.util

import com.compressphotofast.BaseUnitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit тесты для класса EventObserver.
 * EventObserver используется для обработки событий LiveData и гарантирует,
 * что содержимое события будет обработано только один раз.
 */
class EventObserverTest : BaseUnitTest() {

    // ========== Тесты обработки события с данными ==========

    @Test
    fun `onChanged вызывает callback с данными события`() {
        // Arrange
        val testData = "Test message"
        val event = Event(testData)
        var callbackCalled = false
        var receivedData: String? = null

        val observer = EventObserver<String> { data ->
            callbackCalled = true
            receivedData = data
        }

        // Act
        observer.onChanged(event)

        // Assert
        assertTrue(callbackCalled)
        assertEquals(testData, receivedData)
    }

    @Test
    fun `onChanged вызывает callback с числовыми данными`() {
        // Arrange
        val testData = 42
        val event = Event(testData)
        var receivedData: Int? = null

        val observer = EventObserver<Int> { data ->
            receivedData = data
        }

        // Act
        observer.onChanged(event)

        // Assert
        assertEquals(testData, receivedData)
    }

    @Test
    fun `onChanged вызывает callback с объектом данных`() {
        // Arrange
        data class TestData(val id: Int, val name: String)
        val testData = TestData(1, "Test")
        val event = Event(testData)
        var receivedData: TestData? = null

        val observer = EventObserver<TestData> { data ->
            receivedData = data
        }

        // Act
        observer.onChanged(event)

        // Assert
        assertEquals(testData, receivedData)
    }

    // ========== Тесты обработки события без данных ==========

    @Test
    fun `onChanged вызывает callback с Unit`() {
        // Arrange
        val event = Event(Unit)
        var callbackCalled = false

        val observer = EventObserver<Unit> {
            callbackCalled = true
        }

        // Act
        observer.onChanged(event)

        // Assert
        assertTrue(callbackCalled)
    }

    // ========== Тесты обработки уже обработанного события ==========

    @Test
    fun `onChanged не вызывает callback для уже обработанного события`() {
        // Arrange
        val testData = "Test message"
        val event = Event(testData)
        var callbackCount = 0

        val observer = EventObserver<String> {
            callbackCount++
        }

        // Act
        observer.onChanged(event) // Первый вызов
        observer.onChanged(event) // Второй вызов с тем же событием

        // Assert
        assertEquals(1, callbackCount)
    }

    @Test
    fun `onChanged не вызывает callback после getContentIfNotHandled`() {
        // Arrange
        val testData = "Test message"
        val event = Event(testData)
        var callbackCalled = false

        val observer = EventObserver<String> {
            callbackCalled = true
        }

        // Act
        event.getContentIfNotHandled() // Помечаем событие как обработанное
        observer.onChanged(event)

        // Assert
        assertFalse(callbackCalled)
    }

    // ========== Тесты onChanged с разными типами данных ==========

    @Test
    fun `onChanged с Boolean данными`() {
        // Arrange
        val testData = true
        val event = Event(testData)
        var receivedData: Boolean? = null

        val observer = EventObserver<Boolean> { data ->
            receivedData = data
        }

        // Act
        observer.onChanged(event)

        // Assert
        assertEquals(testData, receivedData)
    }

    @Test
    fun `onChanged со списком данных`() {
        // Arrange
        val testData = listOf(1, 2, 3)
        val event = Event(testData)
        var receivedData: List<Int>? = null

        val observer = EventObserver<List<Int>> { data ->
            receivedData = data
        }

        // Act
        observer.onChanged(event)

        // Assert
        assertEquals(testData, receivedData)
    }

    @Test
    fun `onChanged с null данными`() {
        // Arrange
        val testData: String? = null
        val event = Event(testData)
        var callbackCalled = false

        val observer = EventObserver<String?> { data ->
            callbackCalled = true
        }

        // Act
        observer.onChanged(event)

        // Assert - callback не должен вызываться для null данных
        // потому что getContentIfNotHandled() возвращает null
        assertFalse(callbackCalled)
    }

    @Test
    fun `onChanged с пустой строкой`() {
        // Arrange
        val testData = ""
        val event = Event(testData)
        var receivedData: String? = null

        val observer = EventObserver<String> { data ->
            receivedData = data
        }

        // Act
        observer.onChanged(event)

        // Assert
        assertEquals(testData, receivedData)
    }

    // ========== Тесты множественных вызовов onChanged ==========

    @Test
    fun `несколько вызовов onChanged с разными событиями`() {
        // Arrange
        val event1 = Event("First")
        val event2 = Event("Second")
        val event3 = Event("Third")
        val receivedData = mutableListOf<String>()

        val observer = EventObserver<String> { data ->
            receivedData.add(data)
        }

        // Act
        observer.onChanged(event1)
        observer.onChanged(event2)
        observer.onChanged(event3)

        // Assert
        assertEquals(3, receivedData.size)
        assertEquals("First", receivedData[0])
        assertEquals("Second", receivedData[1])
        assertEquals("Third", receivedData[2])
    }

    @Test
    fun `повторный вызов onChanged с тем же событием`() {
        // Arrange
        val event = Event("Test")
        var callbackCount = 0

        val observer = EventObserver<String> {
            callbackCount++
        }

        // Act
        observer.onChanged(event)
        observer.onChanged(event)
        observer.onChanged(event)

        // Assert
        assertEquals(1, callbackCount)
    }

    // ========== Тесты callback с побочными эффектами ==========

    @Test
    fun `callback может изменять внешнее состояние`() {
        // Arrange
        val event = Event("Test")
        var counter = 0

        val observer = EventObserver<String> {
            counter++
        }

        // Act
        observer.onChanged(event)

        // Assert
        assertEquals(1, counter)
    }

    @Test
    fun `callback может выбрасывать исключение`() {
        // Arrange
        val event = Event("Test")
        var exceptionThrown = false

        val observer = EventObserver<String> {
            throw RuntimeException("Test exception")
        }

        // Act & Assert
        try {
            observer.onChanged(event)
        } catch (e: RuntimeException) {
            exceptionThrown = true
        }

        assertTrue(exceptionThrown)
    }

    // ========== Тесты с составными данными ==========

    @Test
    fun `onChanged с составным объектом данных`() {
        // Arrange
        data class User(val id: Int, val name: String, val email: String)
        val testData = User(1, "John Doe", "john@example.com")
        val event = Event(testData)
        var receivedData: User? = null

        val observer = EventObserver<User> { data ->
            receivedData = data
        }

        // Act
        observer.onChanged(event)

        // Assert
        assertEquals(testData, receivedData)
    }

    @Test
    fun `onChanged с вложенным объектом данных`() {
        // Arrange
        data class Address(val city: String, val street: String)
        data class Person(val name: String, val address: Address)
        val testData = Person("Alice", Address("Moscow", "Main St"))
        val event = Event(testData)
        var receivedData: Person? = null

        val observer = EventObserver<Person> { data ->
            receivedData = data
        }

        // Act
        observer.onChanged(event)

        // Assert
        assertEquals(testData, receivedData)
    }
}

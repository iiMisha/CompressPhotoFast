package com.compressphotofast.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.compressphotofast.BaseInstrumentedTest
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation тесты для Event и EventObserver
 *
 * Тестируют событийную модель приложения
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EventIntegrationTest : BaseInstrumentedTest() {

    /**
     * Тест 1: Проверка создания Event с данными
     */
    @Test
    fun test_eventCreation_withData() {
        val testData = "Test Data"
        val event = Event(testData)

        org.junit.Assert.assertNotNull("Event не должен быть null", event)
        org.junit.Assert.assertEquals(
            "Данные должны совпадать",
            testData,
            event.getContentIfNotHandled()
        )
    }

    /**
     * Тест 2: Проверка getContentIfNotHandled
     */
    @Test
    fun test_event_getContentIfNotHandled() {
        val testData = "Test Data"
        val event = Event(testData)

        // Первый вызов должен вернуть данные
        val content1 = event.getContentIfNotHandled()
        org.junit.Assert.assertEquals(
            "Первый вызов должен вернуть данные",
            testData,
            content1
        )

        // Второй вызов должен вернуть null (уже обработано)
        val content2 = event.getContentIfNotHandled()
        org.junit.Assert.assertNull(
            "Второй вызов должен вернуть null",
            content2
        )
    }

    /**
     * Тест 3: Проверка peekContent
     */
    @Test
    fun test_event_peekContent() {
        val testData = "Test Data"
        val event = Event(testData)

        // peekContent должен возвращать данные без изменения статуса
        val content1 = event.peekContent()
        org.junit.Assert.assertEquals(
            "peekContent должен вернуть данные",
            testData,
            content1
        )

        // Повторный вызов peekContent должен снова вернуть данные
        val content2 = event.peekContent()
        org.junit.Assert.assertEquals(
            "Повторный peekContent должен снова вернуть данные",
            testData,
            content2
        )
    }

    /**
     * Тест 4: Проверка Event с null данными
     */
    @Test
    fun test_event_withNullData() {
        val event = Event<String?>(null)

        val content = event.getContentIfNotHandled()
        org.junit.Assert.assertNull(
            "Event должен поддерживать null данные",
            content
        )
    }

    /**
     * Тест 5: Проверка множественных вызовов getContentIfNotHandled
     */
    @Test
    fun test_event_multipleGetContentCalls() {
        val event = Event("Data")

        val result1 = event.getContentIfNotHandled()
        org.junit.Assert.assertNotNull("Первый вызов должен вернуть данные", result1)

        val result2 = event.getContentIfNotHandled()
        org.junit.Assert.assertNull("Второй вызов должен вернуть null", result2)

        val result3 = event.getContentIfNotHandled()
        org.junit.Assert.assertNull("Третий вызов также должен вернуть null", result3)
    }

    /**
     * Тест 6: Проверка Event с разными типами данных
     */
    @Test
    fun test_event_withDifferentDataTypes() {
        // String
        val stringEvent = Event("Test String")
        org.junit.Assert.assertEquals("String", stringEvent.peekContent())

        // Int
        val intEvent = Event(42)
        org.junit.Assert.assertEquals(42, intEvent.peekContent())

        // Boolean
        val boolEvent = Event(true)
        org.junit.Assert.assertEquals(true, boolEvent.peekContent())
    }

    /**
     * Тест 7: Проверка peekContent после getContentIfNotHandled
     */
    @Test
    fun test_event_peekAfterGetContent() {
        val event = Event("Data")

        // Получаем контент
        event.getContentIfNotHandled()

        // peekContent все равно должен вернуть данные
        val peeked = event.peekContent()
        org.junit.Assert.assertEquals(
            "peekContent должен вернуть данные даже после getContentIfNotHandled",
            "Data",
            peeked
        )
    }

    /**
     * Тест 8: Проверка EventObserver onChanged
     */
    @Test
    fun test_eventObserver_onChanged() {
        var receivedData: String? = null
        val observer = EventObserver<String> { data ->
            receivedData = data
        }

        val event = Event("Test Data")
        observer.onChanged(event)

        org.junit.Assert.assertEquals(
            "Observer должен получить данные",
            "Test Data",
            receivedData
        )
    }

    /**
     * Тест 9: Проверка EventObserver с null данными
     */
    @Test
    fun test_eventObserver_withNullData() {
        var callCount = 0
        val observer = EventObserver<String?> {
            callCount++
        }

        val event = Event<String?>(null)
        observer.onChanged(event)

        org.junit.Assert.assertEquals(
            "Observer должен быть вызван",
            1,
            callCount
        )
    }

    /**
     * Тест 10: Проверка множественных событий в Observer
     */
    @Test
    fun test_eventObserver_multipleEvents() {
        val receivedEvents = mutableListOf<String>()
        val observer = EventObserver<String> { data ->
            receivedEvents.add(data)
        }

        // Отправляем несколько событий
        observer.onChanged(Event("Event1"))
        observer.onChanged(Event("Event2"))
        observer.onChanged(Event("Event3"))

        org.junit.Assert.assertEquals(
            "Должны быть получены 3 события",
            3,
            receivedEvents.size
        )

        org.junit.Assert.assertEquals("Event1", receivedEvents[0])
        org.junit.Assert.assertEquals("Event2", receivedEvents[1])
        org.junit.Assert.assertEquals("Event3", receivedEvents[2])
    }
}

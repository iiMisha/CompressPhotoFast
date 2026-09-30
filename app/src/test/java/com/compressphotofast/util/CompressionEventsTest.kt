package com.compressphotofast.util

import android.net.Uri
import com.compressphotofast.BaseUnitTest
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionEventsTest : BaseUnitTest() {

    @Test
    fun `подписчик получает событие`() = runTest {
        val events = CompressionEvents()
        val uri = mockk<Uri>()
        val received = async { events.events.first() }
        yield()

        events.emit(CompressionEvents.Event.DeleteConfirmationRequired(uri))

        assertEquals(CompressionEvents.Event.DeleteConfirmationRequired(uri), received.await())
    }

    @Test
    fun `событие без подписчиков не блокирует и не сохраняется`() = runTest {
        val events = CompressionEvents()
        repeat(100) { events.emit(CompressionEvents.Event.DeleteConfirmationRequired(mockk())) }
        assertTrue(events.events.replayCache.isEmpty())
    }
}

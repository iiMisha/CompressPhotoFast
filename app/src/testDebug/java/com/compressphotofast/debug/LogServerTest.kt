package com.compressphotofast.debug

import androidx.test.core.app.ApplicationProvider
import com.compressphotofast.BaseUnitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LogServerTest : BaseUnitTest() {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    override fun setUp() {
        super.setUp()
        LogServer.save(context, LogServer.DEFAULT_URL, "")
    }

    @Test
    fun `normalizeUrl falls back to default for blank input`() {
        assertEquals(LogServer.DEFAULT_URL, LogServer.normalizeUrl(null))
        assertEquals(LogServer.DEFAULT_URL, LogServer.normalizeUrl("   "))
    }

    @Test
    fun `normalizeUrl trims spaces and trailing slashes`() {
        assertEquals("http://10.0.0.1:8093", LogServer.normalizeUrl("  http://10.0.0.1:8093// "))
    }

    @Test
    fun `isConfigured is false until token is saved`() {
        assertFalse(LogServer.isConfigured(context))
        assertEquals("Не настроен", LogServer.summary(context))

        LogServer.save(context, "http://10.0.0.1:8093/", "  abc123  ")

        assertTrue(LogServer.isConfigured(context))
        assertEquals("abc123", LogServer.getToken(context))
        assertEquals("http://10.0.0.1:8093", LogServer.getUrl(context))
        assertEquals("http://10.0.0.1:8093", LogServer.summary(context))
    }

    @Test
    fun `upload without token reports error and sends nothing`() {
        assertEquals("не задан токен", LogServer.upload(context))
    }
}

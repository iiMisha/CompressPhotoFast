package com.compressphotofast.debug

import androidx.test.core.app.ApplicationProvider
import com.compressphotofast.BaseUnitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.util.Log

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FileLogTest : BaseUnitTest() {

    @Before
    override fun setUp() {
        super.setUp()
        FileLog.init(ApplicationProvider.getApplicationContext())
        FileLog.clear()
    }

    @Test
    fun `write appends formatted line with level, tag and message`() {
        FileLog.write(Log.INFO, "CompressTag", "привет", null)

        val text = FileLog.file()!!.readText()
        assertTrue(text, Regex("""\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} I \[.*] CompressTag: привет\n""").containsMatchIn(text))
    }

    @Test
    fun `write appends stacktrace for throwable`() {
        FileLog.write(Log.ERROR, null, "сбой", IllegalStateException("boom"))

        val text = FileLog.file()!!.readText()
        assertTrue(text.contains(" E [") && text.contains("сбой"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun `clear empties the journal file`() {
        FileLog.write(Log.INFO, null, "строка", null)

        assertTrue(FileLog.clear())
        assertEquals(0L, FileLog.file()!!.length())
    }

    @Test
    fun `trim keeps tail starting from a whole line once size exceeds limit`() {
        val line = "x".repeat(199)
        repeat(3000) { i -> FileLog.write(Log.INFO, null, "$i $line", null) }

        val file = FileLog.file()!!
        assertTrue("size=${file.length()}", file.length() <= 512 * 1024L)
        val firstLine = file.readText().lineSequence().first()
        assertNotNull(firstLine)
        assertTrue("first line is not whole: $firstLine", firstLine.endsWith(line) && firstLine.contains(" I [") )
        assertTrue(file.readText().trimEnd().endsWith("2999 $line"))
    }
}

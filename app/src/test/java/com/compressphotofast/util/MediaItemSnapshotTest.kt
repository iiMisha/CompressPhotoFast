package com.compressphotofast.util

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.compressphotofast.BaseUnitTest
import com.compressphotofast.data.MediaItemSnapshot
import com.compressphotofast.data.UriUtil
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MediaItemSnapshotTest : BaseUnitTest() {

    @MockK
    lateinit var context: Context

    @MockK
    lateinit var contentResolver: ContentResolver

    private val mediaUri = Uri.parse("content://media/external/images/media/42")
    private val providerUri = Uri.parse("content://com.example.fileprovider/photos/p.jpg")

    override fun setUp() {
        super.setUp()
        every { context.contentResolver } returns contentResolver
    }

    private fun fullCursor(pending: Int = 0, dateAdded: Long = 1_000L, size: Long? = 2_000_000L) =
        MatrixCursor(MediaItemSnapshot.PROJECTION).apply {
            addRow(arrayOf<Any?>(42L, "IMG_1.jpg", "DCIM/Camera/", "image/jpeg", size, pending, dateAdded, 1_700_000_000L))
        }

    @Test
    fun `полная строка MediaStore читается одним запросом`() = runTest {
        every { contentResolver.query(mediaUri, any(), null, null, null) } returns fullCursor()

        val snapshot = MediaItemSnapshot.query(context, mediaUri)!!

        assertEquals("IMG_1.jpg", snapshot.displayName)
        assertEquals("image/jpeg", snapshot.mimeType)
        assertEquals(2_000_000L, snapshot.size)
        assertEquals("DCIM/Camera", snapshot.directory)
        assertTrue(snapshot.filePath!!.endsWith("/DCIM/Camera/IMG_1.jpg"))
        assertEquals(1_700_000_000_000L, snapshot.dateModifiedMs)
        assertTrue(snapshot.exists)
        verify(exactly = 1) { contentResolver.query(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `отсутствующая строка MediaStore — null без OpenableColumns`() = runTest {
        every { contentResolver.query(mediaUri, any(), null, null, null) } returns MatrixCursor(MediaItemSnapshot.PROJECTION)

        assertNull(MediaItemSnapshot.query(context, mediaUri))
        verify(exactly = 1) { contentResolver.query(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `сторонний провайдер без колонок MediaStore — fallback на OpenableColumns`() = runTest {
        every { contentResolver.query(providerUri, MediaItemSnapshot.PROJECTION, null, null, null) } throws
            IllegalArgumentException("unknown column")
        every { contentResolver.query(providerUri, match { it.size == 2 }, null, null, null) } returns
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any?>("p.jpg", 500_000L))
            }
        every { contentResolver.getType(providerUri) } returns "image/jpeg"

        val snapshot = MediaItemSnapshot.query(context, providerUri)!!

        assertEquals("p.jpg", snapshot.displayName)
        assertEquals(500_000L, snapshot.size)
        assertEquals("image/jpeg", snapshot.mimeType)
        assertNull(snapshot.relativePath)
        assertFalse(snapshot.isPendingRaw)
        assertTrue(snapshot.exists)
    }

    @Test
    fun `pending из снимка совпадает с isFilePending`() = runTest {
        val now = System.currentTimeMillis() / 1000
        every { contentResolver.query(mediaUri, any(), null, null, null) } answers { fullCursor(pending = 1, dateAdded = now - 10) }
        val recent = MediaItemSnapshot.query(context, mediaUri)!!
        assertFalse(recent.exists)
        assertTrue(UriUtil.isPendingEffective(context, recent))

        every { contentResolver.query(mediaUri, any(), null, null, null) } answers { fullCursor(pending = 1, dateAdded = now - 600) }
        val stale = MediaItemSnapshot.query(context, mediaUri)!!
        assertFalse(UriUtil.isPendingEffective(context, stale))
    }

    @Test
    fun `курсор скана строит снимок без дополнительных запросов`() {
        val cursor = fullCursor()
        cursor.moveToFirst()

        val snapshot = MediaItemSnapshot.fromCursor(cursor, mediaUri)

        assertEquals("IMG_1.jpg", snapshot.displayName)
        assertEquals(1_000L, snapshot.dateAddedSec)
        verify(exactly = 0) { contentResolver.query(any(), any(), any(), any(), any()) }
    }
}

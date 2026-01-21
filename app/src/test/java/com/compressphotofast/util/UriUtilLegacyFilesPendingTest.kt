package com.compressphotofast.util

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.compressphotofast.BaseUnitTest
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class UriUtilLegacyFilesPendingTest : BaseUnitTest() {

    @MockK
    lateinit var context: Context

    @MockK
    lateinit var contentResolver: ContentResolver

    private val testUri = Uri.parse("content://media/external/images/media/123")

    override fun setUp() {
        super.setUp()
        every { context.contentResolver } returns contentResolver
    }

    @Test
    fun `test isFilePending returns true when IS_PENDING is 1 and file is recent`() {
        // Arrange
        val projection = arrayOf(
            MediaStore.Images.Media.IS_PENDING,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        val cursor = MatrixCursor(projection)
        // is_pending = 1, age = 10 seconds, size = 100 KB
        cursor.addRow(arrayOf(1, currentTimeSeconds - 10, 102400L))

        every { 
            contentResolver.query(testUri, any(), null, null, null) 
        } returns cursor

        // Act
        val result = UriUtil.isFilePending(context, testUri)

        // Assert
        assertTrue("Should return true for recent pending file", result)
    }

    @Test
    fun `test isFilePending returns false when IS_PENDING is 1 but file is old`() {
        // Arrange
        val projection = arrayOf(
            MediaStore.Images.Media.IS_PENDING,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        val cursor = MatrixCursor(projection)
        // is_pending = 1, age = 120 seconds (> 60s), size = 100 KB
        cursor.addRow(arrayOf(1, currentTimeSeconds - 120, 102400L))

        every { 
            contentResolver.query(testUri, any(), null, null, null) 
        } returns cursor

        // Act
        val result = UriUtil.isFilePending(context, testUri)

        // Assert
        assertFalse("Should return false for old pending file", result)
    }

    @Test
    fun `test isFilePending returns false when IS_PENDING is 0`() {
        // Arrange
        val projection = arrayOf(
            MediaStore.Images.Media.IS_PENDING,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        val cursor = MatrixCursor(projection)
        // is_pending = 0, size = 100 KB
        cursor.addRow(arrayOf(0, currentTimeSeconds - 10, 102400L))

        every { 
            contentResolver.query(testUri, any(), null, null, null) 
        } returns cursor

        // Act
        val result = UriUtil.isFilePending(context, testUri)

        // Assert
        assertFalse("Should return false when IS_PENDING is 0", result)
    }

    @Test
    fun `test isFilePending returns false when IS_PENDING is 1 but size is NULL and file is readable`() {
        // Arrange
        val projection = arrayOf(
            MediaStore.Images.Media.IS_PENDING,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        val cursor = MatrixCursor(projection)
        // is_pending = 1, age = 10 seconds, size = NULL
        cursor.addRow(arrayOf(1, currentTimeSeconds - 10, null))

        every { 
            contentResolver.query(testUri, any(), null, null, null) 
        } returns cursor

        // Mock openInputStream to return a non-empty stream
        val mockInputStream = java.io.ByteArrayInputStream("test data".toByteArray())
        every {
            contentResolver.openInputStream(testUri)
        } returns mockInputStream

        // Act
        val result = UriUtil.isFilePending(context, testUri)

        // Assert
        assertFalse("Should return false when file is readable even if pending and size is NULL", result)
    }
}

package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.compressphotofast.BaseUnitTest
import com.compressphotofast.data.ExifUtil
import com.compressphotofast.data.MediaItemSnapshot
import com.compressphotofast.data.MediaStoreObserver
import com.compressphotofast.data.UriProcessingTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Наблюдатель MediaStore проверяет URI одним запросом [MediaItemSnapshot] без EXIF-разбора.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MediaStoreObserverTest : BaseUnitTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val tracker = mockk<UriProcessingTracker>(relaxed = true)
    private val uri = Uri.parse("content://media/external/images/media/77")
    private val notified = mutableListOf<Uri>()
    private lateinit var observer: MediaStoreObserver

    @Before
    override fun setUp() {
        super.setUp()
        mockkObject(MediaItemSnapshot.Companion, ExifUtil)
        observer = MediaStoreObserver(context, tracker) { notified += it }
    }

    @After
    override fun tearDown() {
        observer.unregister()
        unmockkAll()
        super.tearDown()
    }

    private fun snapshot(relativePath: String, name: String = "IMG_1.jpg") = MediaItemSnapshot(
        uri = uri,
        displayName = name,
        relativePath = relativePath,
        mimeType = "image/jpeg",
        size = 2_000_000L,
        isPendingRaw = false,
        dateAddedSec = System.currentTimeMillis() / 1000,
        dateModifiedSec = System.currentTimeMillis() / 1000
    )

    @Test
    fun `сжатая копия в директории приложения отсекается одним запросом без EXIF`() = runTest {
        coEvery { MediaItemSnapshot.query(any(), uri) } returns snapshot("Pictures/${Constants.APP_DIRECTORY}/")

        observer.processUri(uri)

        assertTrue(notified.isEmpty())
        coVerify(exactly = 1) { MediaItemSnapshot.query(any(), any()) }
        coVerify(exactly = 0) { ExifUtil.getCompressionMarker(any(), any()) }
    }

    @Test
    fun `новое фото передаётся обработчику`() = runTest {
        coEvery { MediaItemSnapshot.query(any(), uri) } returns snapshot("DCIM/Camera/")

        observer.processUri(uri)

        assertEquals(listOf(uri), notified)
        coVerify(exactly = 1) { MediaItemSnapshot.query(any(), any()) }
    }

    @Test
    fun `исчезнувший URI помечается недоступным`() = runTest {
        coEvery { MediaItemSnapshot.query(any(), uri) } returns null

        observer.processUri(uri)

        assertTrue(notified.isEmpty())
        verify { tracker.markUriUnavailable(uri) }
    }
}

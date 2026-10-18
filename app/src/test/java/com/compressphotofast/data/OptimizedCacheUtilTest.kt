package com.compressphotofast.data

import android.net.Uri
import com.compressphotofast.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OptimizedCacheUtilTest : BaseUnitTest() {

    private fun newUri() = Uri.parse("content://media/external/images/media/${UUID.randomUUID()}")

    @Test
    fun `повторный вызов берёт EXIF из кэша`() = runTest {
        val uri = newUri()
        var computes = 0
        val compute = suspend {
            computes++
            OptimizedCacheUtil.CachedExifData(false, -1, 0L, null, 1_000L)
        }

        OptimizedCacheUtil.getOrComputeExifData(uri, 1_000L, compute)
        OptimizedCacheUtil.getOrComputeExifData(uri, 1_000L, compute)

        assertEquals(1, computes)
    }

    @Test
    fun `инвалидация сбрасывает кэш при неизменном размере`() = runTest {
        val uri = newUri()
        var computes = 0
        val compute = suspend {
            computes++
            OptimizedCacheUtil.CachedExifData(false, -1, 0L, null, 1_000L)
        }

        OptimizedCacheUtil.getOrComputeExifData(uri, 1_000L, compute)
        OptimizedCacheUtil.invalidateExifData(uri)
        OptimizedCacheUtil.getOrComputeExifData(uri, 1_000L, compute)

        assertEquals(2, computes)
    }
}

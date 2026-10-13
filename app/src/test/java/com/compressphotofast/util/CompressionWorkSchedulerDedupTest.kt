package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.compressphotofast.data.SettingsManager
import com.compressphotofast.domain.CompressionEnqueueResult
import com.compressphotofast.domain.CompressionWorkScheduler
import com.compressphotofast.test.TestApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Повторное обнаружение URI (observer и content-trigger) не ставит вторую auto-работу. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = TestApplication::class)
class CompressionWorkSchedulerDedupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://media/external/images/media/42")

    @Before
    fun setUp() {
        SettingsManager.getInstance(context).setAutoCompression(true)
    }

    @Test
    fun `повторный auto enqueue при ожидающей работе не ставит новую`() = runBlocking {
        val workManager = mockk<WorkManager>(relaxed = true)
        val pending = mockk<WorkInfo> { every { state } returns WorkInfo.State.ENQUEUED }
        every { workManager.getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(listOf(pending))
        val scheduler = CompressionWorkScheduler(context, workManager, SettingsManager.getInstance(context))

        val result = scheduler.enqueue(uri)

        assertEquals(CompressionEnqueueResult.DURABLY_ACCEPTED, result)
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `первый auto enqueue ставит работу URI`() = runBlocking {
        val workManager = WorkManager.getInstance(context)
        val scheduler = CompressionWorkScheduler(context, workManager, SettingsManager.getInstance(context))

        val result = scheduler.enqueue(uri)

        assertEquals(CompressionEnqueueResult.DURABLY_ACCEPTED, result)
        val tag = "image_compression_v2_${CompressionWorkScheduler.digest(uri)}"
        assertEquals(1, workManager.getWorkInfosByTag(tag).get().size)
    }
}

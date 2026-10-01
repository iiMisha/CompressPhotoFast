package com.compressphotofast.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.compressphotofast.data.SettingsManager
import com.compressphotofast.test.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Разовый HISTORY-скан ставится только при catchUp; periodic — всегда. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = TestApplication::class)
class GalleryReconciliationScheduleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wm get() = WorkManager.getInstance(context)

    @Before
    fun setUp() {
        SettingsManager.getInstance(context).setAutoCompression(true)
    }

    @Test
    fun `без catchUp ставится только periodic`() {
        GalleryReconciliationWorker.schedule(context, catchUp = false)

        assertTrue(wm.getWorkInfosForUniqueWork("gallery_reconciliation_catch_up").get().isEmpty())
        assertEquals(1, wm.getWorkInfosForUniqueWork("gallery_reconciliation_periodic").get().size)
    }

    @Test
    fun `с catchUp ставится разовый HISTORY-скан`() {
        GalleryReconciliationWorker.schedule(context, catchUp = true)

        assertEquals(1, wm.getWorkInfosForUniqueWork("gallery_reconciliation_catch_up").get().size)
        assertEquals(1, wm.getWorkInfosForUniqueWork("gallery_reconciliation_periodic").get().size)
    }
}

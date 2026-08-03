package com.compressphotofast.util

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.compressphotofast.BaseUnitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationUtilTest : BaseUnitTest() {

    private lateinit var context: Context

    @Before
    override fun setUp() {
        super.setUp()
        context = RuntimeEnvironment.getApplication()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @Test
    fun `Суточная статистика публикуется бесшумно и заменяется по постоянному ID`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationUtil.createDefaultNotificationChannel(context)

        NotificationUtil.showDailyCompressionNotification(
            context,
            DailyCompressionStats(20_000L, 1, 1_000L, 600L)
        )

        val first = shadowOf(manager).getNotification(Constants.NOTIFICATION_ID_COMPRESSION_SUMMARY)
        requireNotNull(first)
        assertEquals("Сжато: 1", first.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertTrue(first.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("400"))
        assertTrue((first.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        assertEquals(NotificationCompat.PRIORITY_LOW, first.priority)
        val dailyStatsChannel = manager.getNotificationChannel(Constants.NOTIFICATION_CHANNEL_DAILY_STATS)
        requireNotNull(dailyStatsChannel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, dailyStatsChannel.importance)

        NotificationUtil.showDailyCompressionNotification(
            context,
            DailyCompressionStats(20_000L, 2, 3_000L, 1_600L)
        )

        val updated = shadowOf(manager).getNotification(Constants.NOTIFICATION_ID_COMPRESSION_SUMMARY)
        requireNotNull(updated)
        assertEquals("Сжато: 2", updated.extras.getCharSequence(Notification.EXTRA_TITLE))
    }
}

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
    fun `Статистика обновляет единое постоянное уведомление автосжатия`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationUtil.createDefaultNotificationChannel(context)
        manager.notify(10, Notification.Builder(context).setSmallIcon(android.R.drawable.ic_menu_info_details).build())

        NotificationUtil.updateBackgroundServiceNotification(
            context,
            DailyCompressionStats(20_000L, 1, 1_000L, 600L)
        )

        val first = shadowOf(manager).getNotification(Constants.NOTIFICATION_ID_BACKGROUND_SERVICE)
        requireNotNull(first)
        assertEquals("Автоматическое сжатие фотографий", first.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertTrue(first.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("400"))
        assertTrue((first.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue((first.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        assertEquals(NotificationCompat.PRIORITY_LOW, first.priority)
        assertEquals(null, shadowOf(manager).getNotification(10))
        val mainChannel = manager.getNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID)
        requireNotNull(mainChannel)

        NotificationUtil.updateBackgroundServiceNotification(
            context,
            DailyCompressionStats(20_000L, 2, 3_000L, 1_600L)
        )

        val updated = shadowOf(manager).getNotification(Constants.NOTIFICATION_ID_BACKGROUND_SERVICE)
        requireNotNull(updated)
        assertTrue(updated.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Сэкономлено"))
        assertTrue(updated.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Сжато: 2"))
    }
}

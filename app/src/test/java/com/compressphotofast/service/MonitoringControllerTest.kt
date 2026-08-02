package com.compressphotofast.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.compressphotofast.BaseUnitTest
import com.compressphotofast.util.Constants
import com.compressphotofast.util.SettingsManager
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit тесты для [MonitoringController] — единой точки управления жизненным циклом
 * постоянной службы обнаружения новых фото.
 *
 * Использует Robolectric для предоставления реального контекста: это позволяет реально
 * выполнить [SettingsManager.getInstance] и резервный Job-триггер
 * [ImageDetectionJobService] (companion-методы) без их мокирования. Возвращаемое
 * значение [MonitoringStartResult] отражает, была ли попытка запуска foreground-службы.
 *
 * Проверяют:
 *  - пропуск запуска при выключенном автосжатии (DISABLED);
 *  - запуск foreground-службы и планирование Job при включённом автосжатии (STARTED);
 *  - подавление ошибок старта foreground-службы (FAILED);
 *  - корректное отключение настройки при остановке.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MonitoringControllerTest : BaseUnitTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    override fun tearDown() {
        // Сбрасываем настройку автосжатия между тестами.
        setAutoCompression(false)
        super.tearDown()
    }

    private fun setAutoCompression(value: Boolean) {
        context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_AUTO_COMPRESSION, value)
            .commit()
    }

    @Test
    fun `startMonitoring returns DISABLED when auto compression is off`() {
        setAutoCompression(false)

        val result = MonitoringController.startMonitoring(context)

        assertEquals(MonitoringStartResult.DISABLED, result)
    }

    @Test
    fun `startMonitoring returns STARTED when auto compression is on`() {
        setAutoCompression(true)

        val result = MonitoringController.startMonitoring(context)

        assertEquals(MonitoringStartResult.STARTED, result)
    }

    @Test
    fun `startForegroundService returns FAILED when start is blocked`() {
        // Изолированный мок контекста: вызывается только startForegroundService,
        // поэтому companion-методы (нуждающиеся в реальном контексте) не затрагиваются.
        val blockedContext = mockk<Context>(relaxed = true)
        every { blockedContext.startForegroundService(any<Intent>()) } throws SecurityException("blocked")

        val result = MonitoringController.startForegroundService(blockedContext)

        assertEquals(MonitoringStartResult.FAILED, result)
    }

    @Test
    fun `stopMonitoring disables auto compression setting`() {
        setAutoCompression(true)
        assertTrue(SettingsManager.getInstance(context).isAutoCompressionEnabled())

        MonitoringController.stopMonitoring(context)

        assertFalse(
            "Автосжатие должно быть отключено после stopMonitoring",
            SettingsManager.getInstance(context).isAutoCompressionEnabled()
        )
    }
}

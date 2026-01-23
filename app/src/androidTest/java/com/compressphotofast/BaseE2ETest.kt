package com.compressphotofast

import android.Manifest
import android.os.Build
import androidx.test.rule.GrantPermissionRule
import org.junit.Before
import org.junit.Rule

/**
 * Базовый класс для E2E тестов.
 *
 * Предоставляет общую настройку для:
 * - Автоматического предоставления всех необходимых разрешений
 * - Общих утилит для E2E тестирования
 *
 * Разрешения автоматически предоставляются:
 * - Android 13+ (API 33+): READ_MEDIA_IMAGES, POST_NOTIFICATIONS, ACCESS_MEDIA_LOCATION
 * - Android 10-12 (API 29-32): READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE, POST_NOTIFICATIONS, ACCESS_MEDIA_LOCATION
 */
abstract class BaseE2ETest : BaseInstrumentedTest() {

    @get:Rule
    val grantPermissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.FOREGROUND_SERVICE
        )
    } else {
        GrantPermissionRule.grant(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.FOREGROUND_SERVICE
        )
    }

    @Before
    override fun setUp() {
        super.setUp()
    }
}
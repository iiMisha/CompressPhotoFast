package com.compressphotofast.util

import androidx.activity.result.ActivityResultLauncher

/**
 * Интерфейс для менеджера разрешений
 */
interface IPermissionsManager {
    /**
     * Типы разрешений, которыми управляет менеджер
     */
    enum class PermissionType {
        STORAGE,
        NOTIFICATIONS,
        ALL
    }
    
    /**
     * Проверяет и запрашивает все необходимые разрешения
     * @return true если все разрешения уже предоставлены
     */
    fun checkAndRequestAllPermissions(onPermissionsGranted: () -> Unit): Boolean
    
    /**
     * Запрашивает разрешения для доступа к хранилищу
     * @return true если все разрешения уже предоставлены
     */
    fun requestStoragePermissions(onPermissionsGranted: () -> Unit): Boolean
    
    /**
     * Запрашивает разрешение на отправку уведомлений (только для Android 13+)
     * @return true если разрешение уже предоставлено или не требуется
     */
    fun requestNotificationPermission(onPermissionGranted: () -> Unit): Boolean
    
    /**
     * Запрашивает остальные разрешения (кроме MANAGE_EXTERNAL_STORAGE)
     * @return true если все разрешения уже предоставлены
     */
    fun requestOtherPermissions(onPermissionsGranted: () -> Unit): Boolean
    
    /**
     * Проверка разрешения на отправку уведомлений
     */
    fun hasNotificationPermission(): Boolean
    
    /**
     * Проверка разрешений на доступ к хранилищу
     */
    fun hasStoragePermissions(): Boolean
    
    /**
     * Показывает диалог с объяснением необходимости полного доступа к файловой системе
     */
    fun showStoragePermissionDialog(onSkip: () -> Unit)
    
    /**
     * Показать диалог с объяснением необходимости разрешений
     */
    fun showPermissionExplanationDialog(
        permissionType: PermissionType,
        onRetry: () -> Unit,
        onSkip: () -> Unit
    )
    
    /**
     * Показать диалог с объяснением необходимости разрешения для уведомлений
     */
    fun showNotificationPermissionExplanation(onRetry: () -> Unit, onSkip: () -> Unit)
    
    /**
     * Обработка результата запроса разрешений
     * Должна вызываться из onRequestPermissionsResult активити
     */
    fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        onAllGranted: () -> Unit,
        onSomePermissionsDenied: () -> Unit
    )
    
    /**
     * Обработка результата активности запроса разрешений
     * Должна вызываться из onActivityResult активити
     */
    fun handleActivityResult(
        requestCode: Int,
        resultCode: Int,
        onSuccess: () -> Unit
    )
} 
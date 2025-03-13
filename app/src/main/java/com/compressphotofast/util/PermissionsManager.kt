package com.compressphotofast.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.compressphotofast.R
import timber.log.Timber

/**
 * Менеджер разрешений для централизованного управления запросами и проверками разрешений.
 * Поддерживает различные версии Android (API 30+, API 33+) и их специфические требования.
 */
class PermissionsManager(
    private val activity: AppCompatActivity
) : IPermissionsManager {
    /**
     * Типы разрешений, которыми управляет менеджер
     */
    // Используем типы из интерфейса
    private val permissionTypeStorage = IPermissionsManager.PermissionType.STORAGE
    private val permissionTypeNotifications = IPermissionsManager.PermissionType.NOTIFICATIONS
    private val permissionTypeAll = IPermissionsManager.PermissionType.ALL

    // Константы
    companion object {
        const val PERMISSION_REQUEST_CODE = 100
        const val REQUEST_MANAGE_EXTERNAL_STORAGE = 101
        
        // Ключи для SharedPreferences
        private const val PREF_PERMISSION_SKIPPED = Constants.PREF_PERMISSION_SKIPPED
        private const val PREF_PERMISSION_REQUEST_COUNT = Constants.PREF_PERMISSION_REQUEST_COUNT
        private const val PREF_NOTIFICATION_PERMISSION_SKIPPED = Constants.PREF_NOTIFICATION_PERMISSION_SKIPPED
    }

    // Получение доступа к SharedPreferences
    private val prefs = activity.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Инициализирует регистрацию обработчиков результатов для activity
     */
    fun registerPermissionHandlers(
        permissionLauncher: ActivityResultLauncher<Array<String>>,
        onAllGranted: () -> Unit,
        onPermissionDenied: () -> Unit
    ) {
        // Этот метод будет использоваться для связывания PermissionsManager с обработчиками в Activity
        // Но фактическая регистрация будет в Activity, т.к. registerForActivityResult можно вызывать только оттуда
    }

    /**
     * Проверяет и запрашивает все необходимые разрешения
     * @return true если все разрешения уже предоставлены
     */
    override fun checkAndRequestAllPermissions(onPermissionsGranted: () -> Unit): Boolean {
        // Проверяем, были ли уже предоставлены все разрешения
        if (hasStoragePermissions()) {
            Timber.d("Все разрешения уже предоставлены")
            onPermissionsGranted()
            return true
        }

        // Проверяем, был ли ранее пропущен запрос разрешений пользователем
        val permissionSkipped = prefs.getBoolean(PREF_PERMISSION_SKIPPED, false)
        val permissionRequestCount = prefs.getInt(PREF_PERMISSION_REQUEST_COUNT, 0)
        
        // Если пользователь уже 3 раза отказал или пропустил запрос, не показываем больше
        if (permissionSkipped || permissionRequestCount >= 3) {
            Timber.d("Запрос разрешений был пропущен или превышено количество попыток, не запрашиваем снова")
            onPermissionsGranted()
            return true
        }
        
        // Разрешение на управление всеми файлами (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showStoragePermissionDialog(onPermissionsGranted)
                return false
            }
        }

        // Запрашиваем остальные разрешения
        return requestOtherPermissions(onPermissionsGranted)
    }

    /**
     * Запрашивает разрешения для доступа к хранилищу
     * @return true если все разрешения уже предоставлены
     */
    override fun requestStoragePermissions(onPermissionsGranted: () -> Unit): Boolean {
        val permissions = mutableListOf<String>()

        // Для Android 13+ используем READ_MEDIA_IMAGES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } 
        // Для версий Android 12 и ниже используем старые разрешения
        else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isEmpty()) {
            Timber.d("Все необходимые разрешения для хранилища уже предоставлены")
            onPermissionsGranted()
            return true
        }

        Timber.d("Запрашиваем разрешения для хранилища: ${permissions.joinToString()}")
        incrementPermissionRequestCount()
        ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        return false
    }

    /**
     * Запрашивает разрешение на отправку уведомлений (только для Android 13+)
     * @return true если разрешение уже предоставлено или не требуется
     */
    override fun requestNotificationPermission(onPermissionGranted: () -> Unit): Boolean {
        // Для Android 13+ требуется специальное разрешение для уведомлений
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) {
                Timber.d("Запрашиваем разрешение POST_NOTIFICATIONS")
                ActivityCompat.requestPermissions(
                    activity, 
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 
                    PERMISSION_REQUEST_CODE
                )
                return false
            }
        }
        
        // Разрешение уже предоставлено или не требуется
        onPermissionGranted()
        return true
    }

    /**
     * Запрашивает остальные разрешения (кроме MANAGE_EXTERNAL_STORAGE)
     * @return true если все разрешения уже предоставлены
     */
    override fun requestOtherPermissions(onPermissionsGranted: () -> Unit): Boolean {
        val permissions = mutableListOf<String>()

        // Разрешения для Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            
            if (!hasNotificationPermission()) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                Timber.d("Запрашиваем разрешение POST_NOTIFICATIONS")
            }
        } 
        // Разрешения для Android 12 и ниже
        else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isEmpty()) {
            Timber.d("Все необходимые разрешения уже предоставлены")
            onPermissionsGranted()
            return true
        }

        Timber.d("Запрашиваем разрешения: ${permissions.joinToString()}")
        incrementPermissionRequestCount()
        ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        return false
    }

    /**
     * Увеличивает счетчик попыток запроса разрешений
     */
    private fun incrementPermissionRequestCount() {
        val permissionRequestCount = prefs.getInt(PREF_PERMISSION_REQUEST_COUNT, 0)
        prefs.edit().putInt(PREF_PERMISSION_REQUEST_COUNT, permissionRequestCount + 1).apply()
    }

    /**
     * Проверка разрешения на отправку уведомлений
     */
    override fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == 
                    PackageManager.PERMISSION_GRANTED
        }
        return true // На более старых версиях Android разрешение не требуется
    }

    /**
     * Проверка разрешений на доступ к хранилищу
     */
    override fun hasStoragePermissions(): Boolean {
        // Проверяем сохраненное состояние разрешений
        val hasStoragePermissionGranted = prefs.getBoolean("has_storage_permission_granted", false)
        if (hasStoragePermissionGranted) {
            return true
        }

        var hasPermissions = false
        
        // Для Android 11+ проверяем разрешение MANAGE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasPermissions = Environment.isExternalStorageManager()
        } 
        // Для Android 13+ проверяем READ_MEDIA_IMAGES
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermissions = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES) == 
                    PackageManager.PERMISSION_GRANTED
        } 
        // Для более старых версий проверяем READ_EXTERNAL_STORAGE и WRITE_EXTERNAL_STORAGE
        else {
            hasPermissions = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) == 
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == 
                    PackageManager.PERMISSION_GRANTED
        }

        // Сохраняем состояние разрешений
        if (hasPermissions) {
            prefs.edit().putBoolean("has_storage_permission_granted", true).apply()
        }

        return hasPermissions
    }

    /**
     * Показывает диалог с объяснением необходимости полного доступа к файловой системе
     */
    override fun showStoragePermissionDialog(onSkip: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_storage_permission_title)
            .setMessage(R.string.dialog_storage_permission_message)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse("package:${activity.applicationContext.packageName}")
                    activity.startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    activity.startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE)
                }
            }
            .setNegativeButton(R.string.dialog_skip) { _, _ ->
                // Продолжаем запрос других разрешений
                requestOtherPermissions(onSkip)
            }
            .setCancelable(false)
            .create()
            .show()
    }

    /**
     * Показать диалог с объяснением необходимости разрешений
     */
    override fun showPermissionExplanationDialog(
        permissionType: IPermissionsManager.PermissionType, 
        onRetry: () -> Unit, 
        onSkip: () -> Unit
    ) {
        // Инициализируем значения по умолчанию
        var titleResId = R.string.dialog_permission_title
        var messageResId = R.string.dialog_permission_message
        
        when (permissionType) {
            IPermissionsManager.PermissionType.STORAGE -> {
                titleResId = R.string.dialog_storage_permission_title
                messageResId = R.string.dialog_storage_permission_message
            }
            IPermissionsManager.PermissionType.NOTIFICATIONS -> {
                titleResId = R.string.dialog_notification_permission_title
                messageResId = R.string.dialog_notification_permission_message
            }
            IPermissionsManager.PermissionType.ALL -> {
                titleResId = R.string.dialog_permission_title
                messageResId = R.string.dialog_permission_message
            }
        }
        
        AlertDialog.Builder(activity)
            .setTitle(titleResId)
            .setMessage(messageResId)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> onRetry() }
            .setNegativeButton(R.string.dialog_skip) { _, _ -> 
                // Запоминаем, что пользователь решил пропустить запрос разрешений
                when (permissionType) {
                    IPermissionsManager.PermissionType.STORAGE, 
                    IPermissionsManager.PermissionType.ALL -> 
                        prefs.edit().putBoolean(PREF_PERMISSION_SKIPPED, true).apply()
                    IPermissionsManager.PermissionType.NOTIFICATIONS -> 
                        prefs.edit().putBoolean(PREF_NOTIFICATION_PERMISSION_SKIPPED, true).apply()
                }
                
                // Вызываем колбэк
                onSkip()
            }
            .create()
            .show()
    }

    /**
     * Показать диалог с объяснением необходимости разрешения для уведомлений
     */
    override fun showNotificationPermissionExplanation(onRetry: () -> Unit, onSkip: () -> Unit) {
        showPermissionExplanationDialog(IPermissionsManager.PermissionType.NOTIFICATIONS, onRetry, onSkip)
    }

    /**
     * Обработка результата запроса разрешений
     * Должна вызываться из onRequestPermissionsResult активити
     */
    override fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        onAllGranted: () -> Unit,
        onSomePermissionsDenied: () -> Unit
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Увеличиваем счетчик запросов разрешений
            val currentCount = prefs.getInt(PREF_PERMISSION_REQUEST_COUNT, 0)
            prefs.edit().putInt(PREF_PERMISSION_REQUEST_COUNT, currentCount + 1).apply()

            // Проверяем результаты
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Timber.d("Все разрешения предоставлены")
                prefs.edit()
                    .putBoolean("has_storage_permission_granted", true)
                    .putBoolean(PREF_PERMISSION_SKIPPED, false)
                    .apply()
                onAllGranted()
            } else {
                Timber.d("Не все разрешения были предоставлены")
                
                // Проверяем, было ли отклонено разрешение на уведомления
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val notificationPermissionIndex = permissions.indexOf(Manifest.permission.POST_NOTIFICATIONS)
                    if (notificationPermissionIndex != -1 && 
                            grantResults.getOrNull(notificationPermissionIndex) != PackageManager.PERMISSION_GRANTED) {
                        
                        Timber.d("Разрешение на уведомления было отклонено")
                        
                        // Проверяем, можно ли повторно показать запрос
                        if (ActivityCompat.shouldShowRequestPermissionRationale(
                                activity, Manifest.permission.POST_NOTIFICATIONS)) {
                            showNotificationPermissionExplanation(
                                onRetry = { requestNotificationPermission { onAllGranted() } },
                                onSkip = onAllGranted
                            )
                        } else {
                            Timber.d("Пользователь выбрал 'больше не спрашивать' для уведомлений")
                            prefs.edit().putBoolean(PREF_NOTIFICATION_PERMISSION_SKIPPED, true).apply()
                            onAllGranted()
                        }
                    }
                
                    // Обработка других отклоненных разрешений
                    val storagePermissionDenied = permissions.any {
                        (it == Manifest.permission.READ_MEDIA_IMAGES || 
                         it == Manifest.permission.READ_EXTERNAL_STORAGE || 
                         it == Manifest.permission.WRITE_EXTERNAL_STORAGE) &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
                    }
                    
                    if (storagePermissionDenied) {
                        Timber.d("Пользователь выбрал 'больше не спрашивать' для доступа к файлам")
                        prefs.edit().putBoolean(PREF_PERMISSION_SKIPPED, true).apply()
                        onAllGranted()
                    } else {
                        showPermissionExplanationDialog(
                            IPermissionsManager.PermissionType.ALL,
                            onRetry = { checkAndRequestAllPermissions(onAllGranted) },
                            onSkip = onAllGranted
                        )
                    }
                } else {
                    // Для более старых версий Android - проверяем STORAGE разрешения
                    val storagePermissionDenied = permissions.any {
                        (it == Manifest.permission.READ_EXTERNAL_STORAGE || 
                         it == Manifest.permission.WRITE_EXTERNAL_STORAGE) &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
                    }
                    
                    if (storagePermissionDenied) {
                        Timber.d("Пользователь выбрал 'больше не спрашивать' для доступа к файлам")
                        prefs.edit().putBoolean(PREF_PERMISSION_SKIPPED, true).apply()
                        onAllGranted()
                    } else {
                        showPermissionExplanationDialog(
                            IPermissionsManager.PermissionType.ALL,
                            onRetry = { checkAndRequestAllPermissions(onAllGranted) },
                            onSkip = onAllGranted
                        )
                    }
                }
                
                onSomePermissionsDenied()
            }
        }
    }
    
    /**
     * Обработка результата активности запроса разрешений
     * Должна вызываться из onActivityResult активити
     */
    override fun handleActivityResult(
        requestCode: Int,
        resultCode: Int,
        onSuccess: () -> Unit
    ) {
        if (requestCode == REQUEST_MANAGE_EXTERNAL_STORAGE) {
            // Проверяем, было ли предоставлено разрешение
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                Timber.d("Разрешение MANAGE_EXTERNAL_STORAGE получено")
                onSuccess()
            } else {
                Timber.d("Разрешение MANAGE_EXTERNAL_STORAGE не получено")
                // Продолжаем запрос других разрешений
                requestOtherPermissions(onSuccess)
            }
        }
    }
} 
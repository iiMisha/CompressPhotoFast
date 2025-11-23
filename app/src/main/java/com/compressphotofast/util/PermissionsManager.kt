package com.compressphotofast.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.compressphotofast.R
import com.compressphotofast.util.LogUtil

/**
 * Менеджер разрешений для централизованного управления запросами и проверками разрешений.
 * Поддерживает различные версии Android (API 30+, API 33+) и их специфические требования.
 */
class PermissionsManager(
    private val activity: AppCompatActivity
) : IPermissionsManager {

    // Константы
    companion object {
        private const val PREF_PERMISSION_SKIPPED = Constants.PREF_PERMISSION_SKIPPED
        private const val PREF_PERMISSION_REQUEST_COUNT = Constants.PREF_PERMISSION_REQUEST_COUNT
        private const val PREF_NOTIFICATION_PERMISSION_SKIPPED = Constants.PREF_NOTIFICATION_PERMISSION_SKIPPED
    }

    private var onPermissionsGrantedCallback: (() -> Unit)? = null

    // Получение доступа к SharedPreferences
    private val prefs = activity.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)

    private val requestPermissionLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            handlePermissionResult(permissions, onPermissionsGrantedCallback!!)
        }

    // ActivityResultLauncher для запуска настроек разрешений
    private val storagePermissionLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            LogUtil.processDebug("Разрешение MANAGE_EXTERNAL_STORAGE получено")
            requestOtherPermissions(onPermissionsGrantedCallback!!)
        } else {
            LogUtil.processDebug("Разрешение MANAGE_EXTERNAL_STORAGE не получено")
            requestOtherPermissions(onPermissionsGrantedCallback!!)
        }
    }

    /**
     * Проверяет и запрашивает все необходимые разрешения
     * @return true если все разрешения уже предоставлены
     */
    override fun checkAndRequestAllPermissions(onPermissionsGranted: () -> Unit): Boolean {
        this.onPermissionsGrantedCallback = onPermissionsGranted
        if (hasStoragePermissions()) {
            LogUtil.processDebug("Все разрешения уже предоставлены")
            onPermissionsGranted()
            return true
        }

        val permissionSkipped = prefs.getBoolean(PREF_PERMISSION_SKIPPED, false)
        val permissionRequestCount = prefs.getInt(PREF_PERMISSION_REQUEST_COUNT, 0)
        
        if (permissionSkipped || permissionRequestCount >= 3) {
            LogUtil.processDebug("Запрос разрешений был пропущен или превышено количество попыток, не запрашиваем снова")
            onPermissionsGranted()
            return true
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showStoragePermissionDialog(onPermissionsGranted)
                return false
            }
        }

        return requestOtherPermissions(onPermissionsGranted)
    }

    /**
     * Запрашивает разрешения для доступа к хранилищу
     * @return true если все разрешения уже предоставлены
     */
    override fun requestStoragePermissions(onPermissionsGranted: () -> Unit): Boolean {
        this.onPermissionsGrantedCallback = onPermissionsGranted
        val permissions = getRequiredStoragePermissions()

        if (permissions.isEmpty()) {
            LogUtil.processDebug("Все необходимые разрешения для хранилища уже предоставлены")
            onPermissionsGranted()
            return true
        }

        LogUtil.processDebug("Запрашиваем разрешения для хранилища: ${permissions.joinToString()}")
        incrementPermissionRequestCount()
        requestPermissionLauncher.launch(permissions.toTypedArray())
        return false
    }

    /**
     * Получить список необходимых разрешений для хранилища в зависимости от версии Android
     */
    private fun getRequiredStoragePermissions(): MutableList<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            // Для Android 14+ добавляем также запрос на частичный доступ
            if (Build.VERSION.SDK_INT >= 34) { // Android 14
                 if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        return permissions
    }

    /**
     * Запрашивает разрешение на отправку уведомлений (только для Android 13+)
     * @return true если разрешение уже предоставлено или не требуется
     */
    override fun requestNotificationPermission(onPermissionGranted: () -> Unit): Boolean {
        this.onPermissionsGrantedCallback = onPermissionGranted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) {
                LogUtil.processDebug("Запрашиваем разрешение POST_NOTIFICATIONS")
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                return false
            }
        }
        onPermissionGranted()
        return true
    }

    /**
     * Запрашивает остальные разрешения (кроме MANAGE_EXTERNAL_STORAGE)
     * @return true если все разрешения уже предоставлены
     */
    override fun requestOtherPermissions(onPermissionsGranted: () -> Unit): Boolean {
        this.onPermissionsGrantedCallback = onPermissionsGranted
        val permissions = mutableListOf<String>()
        
        permissions.addAll(getRequiredStoragePermissions())
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            LogUtil.processDebug("Запрашиваем разрешение POST_NOTIFICATIONS")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasMediaLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            LogUtil.processDebug("Запрашиваем разрешение ACCESS_MEDIA_LOCATION для GPS данных в EXIF")
        }

        if (permissions.isEmpty()) {
            LogUtil.processDebug("Все необходимые разрешения уже предоставлены")
            onPermissionsGranted()
            return true
        }

        LogUtil.processDebug("Запрашиваем разрешения: ${permissions.joinToString()}")
        incrementPermissionRequestCount()
        requestPermissionLauncher.launch(permissions.toTypedArray())
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
     * Проверка разрешения ACCESS_MEDIA_LOCATION для доступа к GPS данным в EXIF
     */
    override fun hasMediaLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_MEDIA_LOCATION) == 
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

        var hasPermissions: Boolean
        
        // Проверка разрешений по версии Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Для Android 13+ проверяем READ_MEDIA_IMAGES или частичный доступ
            hasPermissions = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                    (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
        } 
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Для Android 11+ проверяем MANAGE_EXTERNAL_STORAGE
            hasPermissions = Environment.isExternalStorageManager()
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
        AlertDialog.Builder(activity, R.style.Theme_CompressPhotoFast_AlertDialog)
            .setTitle(R.string.dialog_storage_permission_title)
            .setMessage(R.string.dialog_storage_permission_message)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse("package:${activity.applicationContext.packageName}")
                    storagePermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    storagePermissionLauncher.launch(intent)
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
        val titleResId = when (permissionType) {
            IPermissionsManager.PermissionType.STORAGE -> R.string.dialog_storage_permission_title
            IPermissionsManager.PermissionType.NOTIFICATIONS -> R.string.dialog_notification_permission_title
            IPermissionsManager.PermissionType.ALL -> R.string.dialog_permissions_title
        }
        
        val messageResId = when (permissionType) {
            IPermissionsManager.PermissionType.STORAGE -> R.string.dialog_storage_permission_explanation
            IPermissionsManager.PermissionType.NOTIFICATIONS -> R.string.dialog_notification_permission_explanation
            IPermissionsManager.PermissionType.ALL -> R.string.dialog_permissions_explanation
        }
        
        AlertDialog.Builder(activity, R.style.Theme_CompressPhotoFast_AlertDialog)
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
     * Проверяет, выбрал ли пользователь "больше не спрашивать" для указанного разрешения
     */
    private fun isPermissionPermanentlyDenied(permission: String): Boolean {
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
    
    /**
     * Обработка результата запроса разрешений
     */
    override fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        onAllGranted: () -> Unit,
        onSomePermissionsDenied: () -> Unit
    ) {
        val permissionResults = permissions.zip(grantResults.map { it == PackageManager.PERMISSION_GRANTED }).toMap()
        handlePermissionResult(permissionResults, onAllGranted)
    }

    private fun handlePermissionResult(
        permissions: Map<String, Boolean>,
        onAllGranted: () -> Unit
    ) {
        val allGranted = permissions.values.all { it }

        if (allGranted) {
            LogUtil.processDebug("Все разрешения предоставлены")
            prefs.edit()
                .putBoolean("has_storage_permission_granted", true)
                .putBoolean(PREF_PERMISSION_SKIPPED, false)
                .apply()
            onAllGranted()
            return
        }

        LogUtil.processDebug("Не все разрешения были предоставлены")

        // Обработка отказа в разрешении на уведомления
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            permissions.containsKey(Manifest.permission.POST_NOTIFICATIONS) &&
            permissions[Manifest.permission.POST_NOTIFICATIONS] == false
        ) {
            handleNotificationPermissionDenied(onAllGranted)
        }

        // Обработка отказа в разрешении на доступ к хранилищу
        val storagePermissions = getRequiredStoragePermissions()
        val storageDenied = storagePermissions.any { permissions[it] == false }

        if (storageDenied) {
            handleStoragePermissionDenied(onAllGranted)
        } else {
            // Если отказали только в необязательных, все равно вызываем колбек
            onAllGranted()
        }
    }

    private fun handleNotificationPermissionDenied(onAllGranted: () -> Unit) {
        LogUtil.processDebug("Разрешение на уведомления было отклонено")
        if (isPermissionPermanentlyDenied(Manifest.permission.POST_NOTIFICATIONS)) {
            LogUtil.processDebug("Пользователь выбрал 'больше не спрашивать' для уведомлений")
            prefs.edit().putBoolean(PREF_NOTIFICATION_PERMISSION_SKIPPED, true).apply()
        } else {
            showNotificationPermissionExplanation(
                onRetry = { requestNotificationPermission(onAllGranted) },
                onSkip = onAllGranted
            )
        }
    }

    private fun handleStoragePermissionDenied(onAllGranted: () -> Unit) {
        val storagePermissions = getRequiredStoragePermissions()
        val isPermanentlyDenied = storagePermissions.any { isPermissionPermanentlyDenied(it) }

        if (isPermanentlyDenied) {
            LogUtil.processDebug("Пользователь выбрал 'больше не спрашивать' для доступа к файлам")
            prefs.edit().putBoolean(PREF_PERMISSION_SKIPPED, true).apply()
            onAllGranted()
        } else {
            showPermissionExplanationDialog(
                IPermissionsManager.PermissionType.STORAGE,
                onRetry = { checkAndRequestAllPermissions(onAllGranted) },
                onSkip = onAllGranted
            )
        }
    }
}
package com.compressphotofast.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.Html
import android.transition.TransitionManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import com.compressphotofast.R
import com.compressphotofast.databinding.ActivityMainBinding
import com.compressphotofast.service.BackgroundMonitoringService
import com.compressphotofast.service.ImageDetectionJobService
import com.compressphotofast.ui.CompressionPreset
import com.compressphotofast.ui.CompressionResult
import com.compressphotofast.ui.MultipleImagesProgress
import com.compressphotofast.util.Constants
import com.compressphotofast.util.FileOperationsUtil
import com.compressphotofast.util.ImageProcessingUtil
import com.compressphotofast.util.IPermissionsManager
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.PermissionsManager
import com.compressphotofast.worker.ImageCompressionWorker
import com.compressphotofast.util.StatsTracker
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.UriUtil
import com.compressphotofast.util.CompressionBatchTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var prefs: SharedPreferences
    private lateinit var permissionsManager: IPermissionsManager
    
    // Запуск запроса разрешений
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            LogUtil.processDebug("Все разрешения получены")
            
            // Проверяем, было ли предоставлено разрешение ACCESS_MEDIA_LOCATION
            if (permissions.containsKey(Manifest.permission.ACCESS_MEDIA_LOCATION)) {
                if (permissions[Manifest.permission.ACCESS_MEDIA_LOCATION] == true) {
                    LogUtil.processInfo("✅ Разрешение ACCESS_MEDIA_LOCATION предоставлено - GPS координаты будут сохраняться")
                    showToast("GPS координаты в фото будут сохраняться при сжатии")
                } else {
                    LogUtil.processWarning("❌ Разрешение ACCESS_MEDIA_LOCATION отклонено - GPS координаты будут потеряны")
                    showToast("GPS координаты не будут сохраняться в сжатых фото")
                }
            }
            
            initializeBackgroundServices()
        } else {
            LogUtil.processDebug("Не все разрешения получены")
            
            // Если отклонено только ACCESS_MEDIA_LOCATION - продолжаем работу
            if (permissions.size == 1 && permissions.containsKey(Manifest.permission.ACCESS_MEDIA_LOCATION)) {
                LogUtil.processWarning("Разрешение ACCESS_MEDIA_LOCATION отклонено, продолжаем без сохранения GPS")
                showToast("GPS координаты не будут сохраняться в сжатых фото")
                initializeBackgroundServices()
            } else {
                showPermissionExplanationDialog()
            }
        }
    }

    // BroadcastReceiver для запросов на удаление файлов
    private val deletePermissionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Constants.ACTION_REQUEST_DELETE_PERMISSION) {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Constants.EXTRA_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Constants.EXTRA_URI)
                }
                uri?.let {
                    LogUtil.processDebug("Получен запрос на удаление файла через broadcast: $it")
                    requestFileDelete(it)
                }
            }
        }
    }
    
    // Регистрируем launcher в начале класса
    private val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            LogUtil.processDebug("Файл успешно удален")
            showToast(getString(R.string.file_deleted_successfully))
        } else {
            LogUtil.processDebug("Пользователь отклонил запрос на удаление файла")
        }
        // Проверяем, есть ли еще отложенные запросы на удаление
        checkPendingDeleteRequests()
    }

    /**
     * Показывает Toast в верхней части экрана с проверкой дублирования
     */
    private fun showToast(message: String, duration: Int = Toast.LENGTH_LONG) {
        // Добавляем эмодзи к сообщению, если оно еще не содержит эмодзи
        val messageWithEmoji = if (!message.startsWith("✅") && !message.startsWith("❌") && !message.startsWith("ℹ️") && 
                                   !message.startsWith("⏹️") && !message.startsWith("📱")) {
            "ℹ️ $message"
        } else {
            message
        }
        NotificationUtil.showToast(this, messageWithEmoji, duration)
    }

    /**
     * Показывает Toast с результатами сжатия
     */
    private fun showCompressionResult(fileName: String, originalSize: Long, compressedSize: Long) {
        val truncatedFileName = FileOperationsUtil.truncateFileName(fileName)
        NotificationUtil.showCompressionResultToast(this, "🖼️ $truncatedFileName", originalSize, compressedSize)
    }

    /**
     * BroadcastReceiver для получения уведомлений о завершении сжатия одного изображения
     * Теперь используется только для задач без batch ID (обратная совместимость)
     */
    private val compressionCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_COMPRESSION_COMPLETED) {
                val fileName = intent.getStringExtra(Constants.EXTRA_FILE_NAME) ?: return
                val originalSize = intent.getLongExtra(Constants.EXTRA_ORIGINAL_SIZE, 0L)
                val compressedSize = intent.getLongExtra(Constants.EXTRA_COMPRESSED_SIZE, 0L)
                val batchId = intent.getStringExtra(Constants.EXTRA_BATCH_ID)
                
                // Показываем результаты сжатия только для задач без batch ID (старое поведение)
                if (batchId.isNullOrEmpty()) {
                    showCompressionResult(fileName, originalSize, compressedSize)
                }
                // Для задач с batch ID результат будет показан через CompressionBatchTracker
            }
        }
    }
    
    /**
     * BroadcastReceiver для получения уведомлений о пропуске изображения
     */
    private val compressionSkippedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_COMPRESSION_SKIPPED) {
                viewModel.incrementSkippedCount()
            }
        }
    }
    
    /**
     * BroadcastReceiver для получения уведомлений об уже оптимизированных изображениях
     */
    private val alreadyOptimizedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_ALREADY_OPTIMIZED) {
                viewModel.incrementAlreadyOptimizedCount()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        
        // Регистрируем BroadcastReceiver для получения уведомлений о завершении сжатия
        registerReceiver(
            compressionCompletedReceiver,
            IntentFilter(Constants.ACTION_COMPRESSION_COMPLETED),
            Context.RECEIVER_NOT_EXPORTED
        )
        
        // Регистрируем receiver для запросов на удаление файлов
        registerReceiver(deletePermissionReceiver, 
            IntentFilter(Constants.ACTION_REQUEST_DELETE_PERMISSION),
            Context.RECEIVER_NOT_EXPORTED)
        
        // Регистрируем BroadcastReceiver для получения уведомлений о пропуске сжатия
        registerReceiver(
            compressionSkippedReceiver,
            IntentFilter(Constants.ACTION_COMPRESSION_SKIPPED),
            Context.RECEIVER_NOT_EXPORTED
        )
        
        // Регистрируем BroadcastReceiver для получения уведомлений о ранее оптимизированных изображениях
        registerReceiver(
            alreadyOptimizedReceiver,
            IntentFilter(Constants.ACTION_ALREADY_OPTIMIZED),
            Context.RECEIVER_NOT_EXPORTED
        )
    }
    
    override fun onStop() {
        // Отменяем регистрацию BroadcastReceiver при остановке активности
        try {
            unregisterReceiver(deletePermissionReceiver)
            unregisterReceiver(compressionCompletedReceiver)
            unregisterReceiver(compressionSkippedReceiver)
            unregisterReceiver(alreadyOptimizedReceiver)
        } catch (e: Exception) {
            LogUtil.errorWithException("BROADCAST_UNREGISTER", e)
        }
        
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Очистка ресурсов
        // Не отменяем регистрацию BroadcastReceiver здесь, так как это уже сделано в onStop
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Инициализация для логирования
        LogUtil.processDebug("MainActivity onCreate")
        
        // Инициализация SharedPreferences
        prefs = getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
        
        // Инициализация ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Инициализация менеджера разрешений
        permissionsManager = PermissionsManager(this)
        
        // Обрабатываем действие остановки
        if (intent?.action == Constants.ACTION_STOP_SERVICE) {
            viewModel.stopBatchProcessing()
        }
        
        // Настраиваем пользовательский интерфейс
        setupUI()
        
        // Настраиваем наблюдателей ViewModel
        observeViewModel()
        
        // Обрабатываем входящий Intent (если есть)
        handleIntent(intent)
        
        // Проверяем, есть ли отложенные запросы на удаление файлов
        checkPendingDeleteRequests()
        
        // Запрашиваем разрешения только если это не Share интент
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) {
            checkAndRequestPermissions()
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Constants.ACTION_STOP_SERVICE) {
            viewModel.stopBatchProcessing()
        }
        handleIntent(intent)
    }
    
    /**
     * Извлекает URI из Intent в зависимости от его типа
     */
    private fun extractUrisFromIntent(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        
        when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type?.startsWith("image/") == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uriList ->
                            uris.addAll(uriList)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uriList ->
                            uris.addAll(uriList)
                        }
                    }
                }
            }
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uri ->
                            uris.add(uri)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                            uris.add(uri)
                        }
                    }
                }
            }
        }
        
        return uris
    }

    /**
     * Обработка входящих интентов для получения изображений от других приложений
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        
        LogUtil.processDebug("handleIntent: Получен интент с action=${intent.action}, type=${intent.type}")
        
        // Логируем все данные интента для отладки
        intent.extras?.keySet()?.forEach { key ->
            @Suppress("DEPRECATION")
            LogUtil.processDebug("handleIntent: интент содержит extra[$key]=${intent.extras?.get(key)}")
        }
        
        val uris = extractUrisFromIntent(intent)
        if (uris.isEmpty()) return

        // Сбрасываем счетчики перед началом новой пакетной обработки
        viewModel.resetBatchCounters()
        
        // Создаем batch ID для Intent-сжатий
        val batchId = CompressionBatchTracker.createIntentBatch(this, uris.size)
        LogUtil.processDebug("Создан Intent батч для ${uris.size} изображений: $batchId")
        
        // Обрабатываем изображения
        lifecycleScope.launch {
            // Если есть хотя бы одно изображение, показываем первое в UI
            viewModel.setSelectedImageUri(uris[0])
            
            // Обрабатываем несколько изображений принудительно, независимо от настройки автосжатия
            var processedCount = 0
            
            for (uri in uris) {
                LogUtil.processDebug("handleIntent: Обработка URI: $uri")
                logFileDetails(uri)
                
                // Принудительно обрабатываем изображения, полученные через Share, передаем batch ID
                val result = ImageProcessingUtil.handleImage(this@MainActivity, uri, forceProcess = true, batchId = batchId)
                
                // Считаем обработанные изображения
                if (result.first && result.second) {
                    processedCount++
                } else {
                    // Ошибки или уже обработанные изображения
                    LogUtil.processDebug("handleIntent: URI $uri пропущен: ${result.third}")
                }
            }
            
            // Показываем уведомление о запуске сжатия
            if (processedCount > 0) {
                // Не показываем уведомление о запуске сжатия для Share
                // Сохраняем только логирование
                LogUtil.processDebug("Запущено сжатие для $processedCount изображений в батче $batchId")
            } else {
                // Если все изображения уже обработаны, завершаем батч и показываем сообщение
                CompressionBatchTracker.finalizeBatch(batchId)
                showToast(getString(R.string.all_images_already_compressed))
            }
        }
    }

    /**
     * Логирует подробную информацию о файле
     */
    private fun logFileDetails(uri: Uri) {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE
            )
            
            contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val dateIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    
                    val id = if (idIndex != -1) cursor.getLong(idIndex) else -1
                    val name = if (nameIndex != -1) cursor.getString(nameIndex) else "unknown"
                    val size = if (sizeIndex != -1) cursor.getLong(sizeIndex) else -1
                    val date = if (dateIndex != -1) cursor.getLong(dateIndex) else -1
                    val mime = if (mimeIndex != -1) cursor.getString(mimeIndex) else "unknown"
                    
                    LogUtil.processDebug("Файл: ID=$id, Имя=$name, Размер=$size, Дата=$date, MIME=$mime, URI=$uri")
                }
            }
        } catch (e: Exception) {
            LogUtil.errorWithMessageAndException("FILE_INFO", "Ошибка при получении информации о файле", e)
        }
    }

    /**
     * Настройка пользовательского интерфейса
     */
    private fun setupUI() {
        // Переключатель автоматического сжатия
        binding.switchAutoCompression.isChecked = viewModel.isAutoCompressionEnabled()
        binding.switchAutoCompression.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoCompression(isChecked)
            if (isChecked) {
                setupBackgroundService()
            }
        }

        // Кнопка раскрытия предупреждения
        binding.autoCompressionHeader.setOnClickListener {
            viewModel.toggleWarningExpanded()
        }
        
        // Настраиваем HTML-форматирование для предупреждения
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            binding.tvBackgroundModeWarning.text = Html.fromHtml(getString(R.string.background_mode_warning), Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            binding.tvBackgroundModeWarning.text = Html.fromHtml(getString(R.string.background_mode_warning))
        }
        
        // Добавляем обработчик нажатия на предупреждение для перехода в настройки
        binding.tvBackgroundModeWarning.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
                showToast(getString(R.string.notification_toast_battery_settings))
            } catch (e: Exception) {
                LogUtil.errorWithMessageAndException("APP_SETTINGS", "Ошибка при открытии настроек приложения", e)
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    LogUtil.errorWithMessageAndException("APP_SETTINGS", "Ошибка при открытии общих настроек приложений", e)
                    showToast("Пожалуйста, откройте настройки вручную")
                }
            }
        }
        
        // Переключатель режима сохранения
        binding.switchSaveMode.isChecked = viewModel.isSaveModeReplace()
        binding.switchSaveMode.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setSaveMode(isChecked)
        }
        
        // Установка начального состояния для переключателей качества
        setupCompressionQualityRadioButtons()

        // Переключатель игнорирования фото из мессенджеров
        binding.switchIgnoreMessengerPhotos.isChecked = viewModel.shouldIgnoreMessengerPhotos()
        binding.switchIgnoreMessengerPhotos.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setIgnoreMessengerPhotos(isChecked)
        }
    }

    /**
     * Наблюдение за ViewModel
     */
    private fun observeViewModel() {
        // Наблюдение за состоянием раскрывающегося предупреждения
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.isWarningExpanded.collect { isExpanded ->
                    TransitionManager.beginDelayedTransition(binding.mainContainer)
                    binding.tvBackgroundModeWarning.visibility = if (isExpanded) View.VISIBLE else View.GONE
                    binding.ivExpandArrow.rotation = if (isExpanded) 180f else 0f
                    // Эта строка будет менять фон в зависимости от состояния (свернуто/развернуто)
                    binding.autoCompressionHeader.isActivated = isExpanded
                }
            }
        }

        // Наблюдение за состоянием загрузки
        viewModel.isLoading.observe(this) { isLoading ->
            if (isLoading) {
                binding.progressBar.visibility = View.VISIBLE
                // Запускаем анимацию
                val rotateAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.rotate)
                binding.progressBar.startAnimation(rotateAnim)
            } else {
                binding.progressBar.clearAnimation()
                binding.progressBar.visibility = View.GONE
            }
        }
        
        // Наблюдение за прогрессом обработки нескольких изображений
        viewModel.multipleImagesProgress.observe(this) { progress ->
            if (progress.total > 1 && !progress.isComplete) {
                binding.progressBar.visibility = View.VISIBLE
                // Запускаем анимацию
                val rotateAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.rotate)
                binding.progressBar.startAnimation(rotateAnim)
            } else if (progress.isComplete) {
                binding.progressBar.clearAnimation()
                binding.progressBar.visibility = View.GONE
                
                // Логируем завершение для отладки
                LogUtil.processDebug("Завершена обработка всех изображений (${progress.processed}/${progress.total})")
                // Показываем итоговое сообщение
                viewModel.showBatchSummary()
            }
        }
        
        // Наблюдение за результатом сжатия (только для логирования)
        viewModel.compressionResult.observe(this) { result ->
            result?.let {
                // Создаем детальную строку логирования с учетом пропущенных файлов
                val resultLog = if (it.skippedImages > 0) {
                    "Реальный результат: success=${it.success}, allSuccessful=${it.allSuccessful}, " +
                    "totalImages=${it.totalImages}, successfulImages=${it.successfulImages}, " +
                    "skippedImages=${it.skippedImages}, failedImages=${it.failedImages}"
                } else {
                    "Реальный результат: success=${it.success}, allSuccessful=${it.allSuccessful}, " +
                    "totalImages=${it.totalImages}, successfulImages=${it.successfulImages}, " +
                    "failedImages=${it.failedImages}"
                }
                LogUtil.processDebug(resultLog)
            }
        }
    }

    /**
     * Проверка необходимых разрешений
     */
    private fun checkAndRequestPermissions() {
        permissionsManager.checkAndRequestAllPermissions { 
            checkMediaLocationPermission() 
        }
    }
    
    /**
     * Проверка разрешения ACCESS_MEDIA_LOCATION для GPS данных
     */
    private fun checkMediaLocationPermission() {
        if (!permissionsManager.hasMediaLocationPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            showMediaLocationPermissionDialog()
        } else {
            initializeBackgroundServices()
        }
    }
    
    /**
     * Показать диалог с объяснением разрешения ACCESS_MEDIA_LOCATION
     */
    private fun showMediaLocationPermissionDialog() {
        AlertDialog.Builder(this, R.style.Theme_CompressPhotoFast_AlertDialog)
            .setTitle("Сохранение геолокации")
            .setMessage("Для сохранения GPS координат в сжатых фото требуется разрешение доступа к местоположению в медиафайлах.\n\nБез этого разрешения координаты будут потеряны при сжатии фото.")
            .setPositiveButton("Предоставить") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
                }
            }
            .setNegativeButton("Пропустить") { _, _ ->
                showToast("GPS координаты не будут сохраняться в сжатых фото")
                initializeBackgroundServices()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Обработка результата запроса разрешений
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        permissionsManager.handlePermissionResult(
            requestCode,
            permissions,
            grantResults,
            onAllGranted = { initializeBackgroundServices() },
            onSomePermissionsDenied = { /* Ничего не делаем, т.к. это обрабатывается внутри handlePermissionResult */ }
        )
    }

    /**
     * Показать диалог с объяснением необходимости разрешений
     */
    private fun showPermissionExplanationDialog() {
        permissionsManager.showPermissionExplanationDialog(
            IPermissionsManager.PermissionType.ALL,
            onRetry = { checkAndRequestPermissions() },
            onSkip = {
                initializeBackgroundServices()
                // Показываем toast о том, что функциональность может быть ограничена
                showToast("Функциональность приложения может быть ограничена без необходимых разрешений")
            }
        )
    }

    /**
     * Настройка фоновой службы
     */
    private fun setupBackgroundService() {
        val isEnabled = viewModel.isAutoCompressionEnabled()
        LogUtil.processDebug("setupBackgroundService: автоматическое сжатие ${if (isEnabled) "включено" else "выключено"}")
        
        if (isEnabled) {
            // Запускаем JobService для отслеживания новых изображений
            ImageDetectionJobService.scheduleJob(this)
            LogUtil.processDebug("setupBackgroundService: JobService запланирован")
            
            // Запускаем фоновый сервис
            val serviceIntent = Intent(this, BackgroundMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                LogUtil.processDebug("setupBackgroundService: запуск как foreground сервис (Android O+)")
                startForegroundService(serviceIntent)
            } else {
                LogUtil.processDebug("setupBackgroundService: запуск как обычный сервис")
                startService(serviceIntent)
            }
            LogUtil.processDebug("Фоновые сервисы запущены успешно")
        } else {
            // Останавливаем фоновый сервис при выключении автоматического сжатия
            stopService(Intent(this, BackgroundMonitoringService::class.java))
            LogUtil.processDebug("Фоновые сервисы остановлены")
        }
    }

    /**
     * Настройка переключателей уровня сжатия
     */
    private fun setupCompressionQualityRadioButtons() {
        // Выбираем соответствующую радиокнопку
        when (viewModel.getCompressionQuality()) {
            Constants.COMPRESSION_QUALITY_LOW -> binding.rbQualityLow.isChecked = true
            Constants.COMPRESSION_QUALITY_HIGH -> binding.rbQualityHigh.isChecked = true
            else -> binding.rbQualityMedium.isChecked = true
        }
        
        // Устанавливаем обработчики событий
        binding.rbQualityLow.setOnClickListener {
            viewModel.setCompressionPreset(CompressionPreset.LOW)
        }
        
        binding.rbQualityMedium.setOnClickListener {
            viewModel.setCompressionPreset(CompressionPreset.MEDIUM)
        }
        
        binding.rbQualityHigh.setOnClickListener {
            viewModel.setCompressionPreset(CompressionPreset.HIGH)
        }
        
        // Наблюдаем за изменениями качества сжатия
        viewModel.compressionQuality.observe(this) { quality ->
            LogUtil.processDebug("Установлено качество сжатия: $quality")
            when (quality) {
                Constants.COMPRESSION_QUALITY_LOW -> binding.rbQualityLow.isChecked = true
                Constants.COMPRESSION_QUALITY_MEDIUM -> binding.rbQualityMedium.isChecked = true
                Constants.COMPRESSION_QUALITY_HIGH -> binding.rbQualityHigh.isChecked = true
            }
        }
    }

    /**
     * Проверка наличия отложенных запросов на удаление файлов
     */
    private fun checkPendingDeleteRequests() {
        // Получаем список URI, ожидающих удаления
        val prefs = getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
        val pendingDeleteUris = prefs.getStringSet(Constants.PREF_PENDING_DELETE_URIS, null)
        
        if (!pendingDeleteUris.isNullOrEmpty()) {
            LogUtil.processDebug("Найдено ${pendingDeleteUris.size} отложенных запросов на удаление файлов")
            
            // Обрабатываем первый URI в списке
            val uriString = pendingDeleteUris.firstOrNull()
            if (uriString != null) {
                try {
                    val uri = Uri.parse(uriString)
                    // Удаляем URI из списка ожидающих
                    val newSet = pendingDeleteUris.toMutableSet()
                    newSet.remove(uriString)
                    prefs.edit()
                        .putStringSet(Constants.PREF_PENDING_DELETE_URIS, newSet)
                        .apply()
                    
                    // Запрашиваем удаление файла
                    requestFileDelete(uri)
                } catch (e: Exception) {
                    LogUtil.errorWithMessageAndException("PENDING_DELETE", "Ошибка при обработке отложенного запроса на удаление", e)
                }
            }
        }
    }
    
    /**
     * Запрос на удаление файла с получением разрешения
     */
    private fun requestFileDelete(uri: Uri) {
        try {
            val intentSender = FileOperationsUtil.deleteFile(this, uri)
            if (intentSender is IntentSender) {
                // И используем его вместо startIntentSenderForResult
                intentSenderLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
        } catch (e: Exception) {
            LogUtil.errorWithMessageAndException(uri, "DELETE_FILE", "Ошибка при запросе удаления файла", e)
        }
    }
    
    /**
     * Обработка результата запроса на удаление файла
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        permissionsManager.handleActivityResult(
            requestCode,
            resultCode,
            onSuccess = { initializeBackgroundServices() }
        )
        
        
    }

    /**
     * Запускает обработку изображения через фоновый сервис
     */
    private fun startBackgroundProcessing(uri: Uri) {
        try {
            // Запускаем фоновый сервис, если он еще не запущен
            val serviceIntent = Intent(this, BackgroundMonitoringService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            
            // Создаем интент для обработки конкретного изображения
            val processIntent = Intent(Constants.ACTION_PROCESS_IMAGE)
            processIntent.setPackage(packageName)
            processIntent.putExtra(Constants.EXTRA_URI, uri)
            sendBroadcast(processIntent)
            
            LogUtil.processDebug("startBackgroundProcessing: Отправлен запрос на обработку изображения: $uri")
        } catch (e: Exception) {
            LogUtil.errorWithMessageAndException(uri, "BACKGROUND_PROCESS", "Ошибка при запуске фонового сервиса", e)
        }
    }

    /**
     * Инициализирует фоновые сервисы и продолжает запуск приложения
     */
    private fun initializeBackgroundServices() {
        try {
            // Запускаем фоновый сервис, если включено автоматическое сжатие
            if (viewModel.isAutoCompressionEnabled()) {
                setupBackgroundService()
            }
            
            // Логируем успешную инициализацию
            LogUtil.processDebug("Фоновые сервисы инициализированы успешно")
        } catch (e: Exception) {
            LogUtil.errorWithMessageAndException("BACKGROUND_INIT", "Ошибка при инициализации фоновых сервисов", e)
        }
    }

    companion object {
        // Удаляем дублирующиеся константы, т.к. они теперь в PermissionsManager
        // Оставляем остальные константы
    }
}

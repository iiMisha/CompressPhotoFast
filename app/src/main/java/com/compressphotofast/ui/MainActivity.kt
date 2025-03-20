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
import androidx.work.WorkInfo
import com.compressphotofast.R
import com.compressphotofast.databinding.ActivityMainBinding
import com.compressphotofast.service.BackgroundMonitoringService
import com.compressphotofast.service.ImageDetectionJobService
import com.compressphotofast.ui.CompressionPreset
import com.compressphotofast.ui.CompressionResult
import com.compressphotofast.ui.MultipleImagesProgress
import com.compressphotofast.util.Constants
import com.compressphotofast.util.FileUtil
import com.compressphotofast.util.ImageProcessingUtil
import com.compressphotofast.util.IPermissionsManager
import com.compressphotofast.util.NotificationUtil
import com.compressphotofast.util.PermissionsManager
import com.compressphotofast.worker.ImageCompressionWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

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
            Timber.d("Все разрешения получены")
            initializeBackgroundServices()
        } else {
            Timber.d("Не все разрешения получены")
            showPermissionExplanationDialog()
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
                    Timber.d("Получен запрос на удаление файла через broadcast: $it")
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
            // Обработка результата
        }
    }

    /**
     * Базовый класс для обработки уведомлений о сжатии
     */
    private abstract inner class BaseCompressionReceiver : BroadcastReceiver() {
        protected fun getFileInfo(intent: Intent?): Triple<String, Long, Long>? {
            if (intent == null) return null
            
            val fileName = intent.getStringExtra(Constants.EXTRA_FILE_NAME) ?: "Файл"
            val originalSize = intent.getLongExtra(Constants.EXTRA_ORIGINAL_SIZE, 0)
            val compressedSize = intent.getLongExtra(Constants.EXTRA_COMPRESSED_SIZE, 0)
            
            return Triple(fileName, originalSize, compressedSize)
        }
        
        protected fun formatFileSizes(originalSize: Long, compressedSize: Long): Pair<String, String> {
            val originalSizeStr = FileUtil.formatFileSize(originalSize)
            val compressedSizeStr = FileUtil.formatFileSize(compressedSize)
            return Pair(originalSizeStr, compressedSizeStr)
        }
    }

    /**
     * Показывает Toast в верхней части экрана с проверкой дублирования
     */
    private fun showToast(message: String, duration: Int = Toast.LENGTH_LONG) {
        NotificationUtil.showToast(this, message, duration)
    }

    /**
     * Приемник для получения уведомлений о завершении сжатия
     */
    private val compressionCompletedReceiver = object : BaseCompressionReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_COMPRESSION_COMPLETED) {
                val fileInfo = getFileInfo(intent) ?: return
                val (fileName, originalSize, compressedSize) = fileInfo
                val reduction = intent.getFloatExtra(Constants.EXTRA_REDUCTION_PERCENT, 0f)
                
                // Форматируем размеры
                val (originalSizeStr, compressedSizeStr) = formatFileSizes(originalSize, compressedSize)
                val reductionStr = String.format("%.1f", reduction)
                
                // Логируем информацию о завершении сжатия
                Timber.d("Получено уведомление о завершении сжатия: $fileName, $originalSizeStr → $compressedSizeStr (-$reductionStr%)")
                
                // Показываем Toast с результатами сжатия
                val truncatedFileName = FileUtil.truncateFileName(fileName)
                showToast("$truncatedFileName: $originalSizeStr → $compressedSizeStr (-$reductionStr%)")
            }
        }
    }
    
    /**
     * Приемник для получения уведомлений о пропуске сжатия
     */
    private val compressionSkippedReceiver = object : BaseCompressionReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_COMPRESSION_SKIPPED) {
                val fileInfo = getFileInfo(intent) ?: return
                val (_, originalSize, _) = fileInfo
                
                // Получаем форматированный размер оригинального файла
                val originalSizeStr = FileUtil.formatFileSize(originalSize)
                
                // Показываем toast
                showToast(getString(
                    R.string.compression_skipped_size_limit,
                    originalSizeStr
                ))
            }
        }
    }

    // Приемник для получения информации о пропущенных (уже оптимизированных) изображениях
    private val compressionSkippedFromGalleryReceiver = object : BaseCompressionReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_IMAGE_ALREADY_OPTIMIZED) {
                // Показываем уведомление для пользователя
                showToast(getString(R.string.image_already_optimized))
                Timber.d("Получено уведомление о ранее оптимизированном изображении")
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
            compressionSkippedFromGalleryReceiver,
            IntentFilter(Constants.ACTION_IMAGE_ALREADY_OPTIMIZED),
            Context.RECEIVER_NOT_EXPORTED
        )
    }
    
    override fun onStop() {
        // Отменяем регистрацию BroadcastReceiver при остановке активности
        try {
            unregisterReceiver(deletePermissionReceiver)
            unregisterReceiver(compressionCompletedReceiver)
            unregisterReceiver(compressionSkippedReceiver)
            unregisterReceiver(compressionSkippedFromGalleryReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при отмене регистрации BroadcastReceiver в onStop")
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
        
        // Инициализация Timber для логирования
        Timber.d("MainActivity onCreate")
        
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
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Constants.ACTION_STOP_SERVICE) {
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
        
        Timber.d("handleIntent: Получен интент с action=${intent.action}, type=${intent.type}")
        
        // Логируем все данные интента для отладки
        intent.extras?.keySet()?.forEach { key ->
            @Suppress("DEPRECATION")
            Timber.d("handleIntent: интент содержит extra[$key]=${intent.extras?.get(key)}")
        }
        
        val uris = extractUrisFromIntent(intent)
        if (uris.isEmpty()) return
        
        // Обрабатываем изображения
        lifecycleScope.launch {
            // Если есть хотя бы одно изображение, показываем первое в UI
            viewModel.setSelectedImageUri(uris[0])
            
            // Обрабатываем несколько изображений принудительно, независимо от настройки автосжатия
            var processedCount = 0
            
            for (uri in uris) {
                Timber.d("handleIntent: Обработка URI: $uri")
                logFileDetails(uri)
                
                // Принудительно обрабатываем изображения, полученные через Share
                val result = ImageProcessingUtil.handleImage(this@MainActivity, uri, forceProcess = true)
                
                // Считаем обработанные изображения
                if (result.first && result.second) {
                    processedCount++
                } else {
                    // Ошибки или уже обработанные изображения
                    Timber.d("handleIntent: URI $uri пропущен: ${result.third}")
                }
            }
            
            // Показываем уведомление о запуске сжатия
            if (processedCount > 0) {
                // Не показываем уведомление о запуске сжатия для Share
                // Сохраняем только логирование
                Timber.d("Запущено сжатие для $processedCount изображений")
            } else {
                // Если все изображения уже обработаны, показываем сообщение
                showToast(getString(R.string.all_images_already_compressed))
            }
        }
    }

    /**
     * Получение списка URI из интента
     */
    private fun getMultipleUrisFromIntent(intent: Intent): List<Uri> {
        return extractUrisFromIntent(intent)
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
                    
                    Timber.d("Файл: ID=$id, Имя=$name, Размер=$size, Дата=$date, MIME=$mime, URI=$uri")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении информации о файле: $uri")
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
                // Показываем предупреждение о необходимости разрешения фонового режима
                binding.tvBackgroundModeWarning.visibility = View.VISIBLE
            } else {
                binding.tvBackgroundModeWarning.visibility = View.GONE
            }
        }
        
        // Обновляем видимость предупреждения при запуске
        binding.tvBackgroundModeWarning.visibility = if (viewModel.isAutoCompressionEnabled()) View.VISIBLE else View.GONE
        
        // Настраиваем HTML-форматирование для предупреждения
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            binding.tvBackgroundModeWarning.text = Html.fromHtml(getString(R.string.background_mode_warning), Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            binding.tvBackgroundModeWarning.text = Html.fromHtml(getString(R.string.background_mode_warning))
        }
        
        // Добавляем обработчик нажатия на предупреждение
        binding.tvBackgroundModeWarning.setOnClickListener {
            try {
                // Пробуем открыть настройки батареи для приложения напрямую
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
                showToast(getString(R.string.notification_toast_battery_settings))
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при открытии настроек приложения")
                try {
                    // Если прямой метод не сработал, открываем общие настройки приложения
                    val intent = Intent(Settings.ACTION_APPLICATION_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при открытии общих настроек приложений")
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
    }

    /**
     * Наблюдение за ViewModel
     */
    private fun observeViewModel() {
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
                
                // Показываем Toast с результатом обработки
                if (progress.total > 1) {
                    // Подготовим текст сообщения учитывая пропущенные файлы
                    val message = if (progress.skipped > 0) {
                        getString(
                            R.string.notification_batch_processing_completed_with_skipped,
                            progress.successful,
                            progress.skipped,
                            progress.failed,
                            progress.total
                        )
                    } else {
                        getString(
                            R.string.notification_batch_processing_completed,
                            progress.successful,
                            progress.failed,
                            progress.total
                        )
                    }
                    
                    showToast(message)
                }
                
                // Логируем завершение для отладки
                Timber.d("Завершена обработка всех изображений (${progress.processed}/${progress.total})")
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
                Timber.d(resultLog)
            }
        }
    }

    /**
     * Показывает диалог с объяснением необходимости полного доступа к файловой системе
     */
    private fun showStoragePermissionDialog() {
        permissionsManager.showStoragePermissionDialog { initializeBackgroundServices() }
    }

    /**
     * Запрашивает остальные разрешения (кроме MANAGE_EXTERNAL_STORAGE)
     */
    private fun requestOtherPermissions() {
        permissionsManager.requestOtherPermissions { initializeBackgroundServices() }
    }

    /**
     * Проверка необходимых разрешений
     */
    private fun checkAndRequestPermissions() {
        permissionsManager.checkAndRequestAllPermissions { initializeBackgroundServices() }
    }

    /**
     * Проверка разрешения на отправку уведомлений
     */
    private fun hasNotificationPermission(): Boolean {
        return permissionsManager.hasNotificationPermission()
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
     * Показывает объяснение о необходимости разрешения на уведомления
     */
    private fun showNotificationPermissionExplanation() {
        permissionsManager.showNotificationPermissionExplanation(
            onRetry = { 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        PermissionsManager.PERMISSION_REQUEST_CODE
                    )
                }
            },
            onSkip = {
                initializeBackgroundServices()
                // Показываем toast о том, что уведомления не будут отображаться
                showToast("Уведомления о завершении сжатия не будут отображаться")
            }
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
        Timber.d("setupBackgroundService: автоматическое сжатие ${if (isEnabled) "включено" else "выключено"}")
        
        if (isEnabled) {
            // Запускаем JobService для отслеживания новых изображений
            ImageDetectionJobService.scheduleJob(this)
            Timber.d("setupBackgroundService: JobService запланирован")
            
            // Запускаем фоновый сервис
            val serviceIntent = Intent(this, BackgroundMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Timber.d("setupBackgroundService: запуск как foreground сервис (Android O+)")
                startForegroundService(serviceIntent)
            } else {
                Timber.d("setupBackgroundService: запуск как обычный сервис")
                startService(serviceIntent)
            }
            Timber.d("Фоновые сервисы запущены успешно")
        } else {
            // Останавливаем фоновый сервис при выключении автоматического сжатия
            stopService(Intent(this, BackgroundMonitoringService::class.java))
            Timber.d("Фоновые сервисы остановлены")
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
            Timber.d("Установлено качество сжатия: $quality")
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
            Timber.d("Найдено ${pendingDeleteUris.size} отложенных запросов на удаление файлов")
            
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
                    Timber.e(e, "Ошибка при обработке отложенного запроса на удаление: $uriString")
                }
            }
        }
    }
    
    /**
     * Запрос на удаление файла с получением разрешения
     */
    private fun requestFileDelete(uri: Uri) {
        try {
            val intentSender = FileUtil.deleteFile(this, uri)
            if (intentSender is IntentSender) {
                // И используем его вместо startIntentSenderForResult
                intentSenderLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при запросе удаления файла: $uri")
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
        
        when (requestCode) {
            Constants.REQUEST_CODE_DELETE_FILE -> {
                if (FileUtil.handleDeleteFileRequest(resultCode)) {
                    Timber.d("Файл успешно удален")
                    showToast(getString(R.string.file_deleted_successfully))
                } else {
                    Timber.d("Пользователь отклонил запрос на удаление файла")
                }
                
                // Проверяем, есть ли еще отложенные запросы на удаление
                checkPendingDeleteRequests()
            }
            
            Constants.REQUEST_CODE_DELETE_PERMISSION -> {
                // Устанавливаем флаг, что разрешение было запрошено
                prefs.edit().putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, true).apply()
                    
                if (FileUtil.handleDeleteFileRequest(resultCode)) {
                    Timber.d("Тестовый файл успешно удален, разрешение получено")
                } else {
                    Timber.d("Пользователь отклонил запрос на удаление тестового файла")
                }
                
                // Продолжаем инициализацию
                initializeBackgroundServices()
            }
        }
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
            processIntent.putExtra(Constants.EXTRA_URI, uri)
            sendBroadcast(processIntent)
            
            Timber.d("startBackgroundProcessing: Отправлен запрос на обработку изображения: $uri")
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при запуске фонового сервиса")
        }
    }

    /**
     * Обработка списка изображений
     */
    private fun processImages() {
        // Проверяем, включено ли автоматическое сжатие
        if (!viewModel.isAutoCompressionEnabled()) {
            // Если автоматическое сжатие выключено, запускаем сжатие вручную
            viewModel.selectedImageUri.value?.let { _ ->
                viewModel.compressSelectedImage()
            }
        } else {
            // Иначе запускаем обработку через фоновый сервис
            viewModel.selectedImageUri.value?.let { uri ->
                startBackgroundProcessing(uri)
            }
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
            Timber.d("Фоновые сервисы инициализированы успешно")
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при инициализации фоновых сервисов")
        }
    }

    companion object {
        // Удаляем дублирующиеся константы, т.к. они теперь в PermissionsManager
        // Оставляем остальные константы
    }
} 
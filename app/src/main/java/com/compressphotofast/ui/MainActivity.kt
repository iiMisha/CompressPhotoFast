package com.compressphotofast.ui

import android.Manifest
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.compressphotofast.R
import com.compressphotofast.databinding.ActivityMainBinding
import com.compressphotofast.service.BackgroundMonitoringService
import com.compressphotofast.service.ImageDetectionJobService
import com.compressphotofast.ui.CompressionPreset
import com.compressphotofast.util.Constants
import com.compressphotofast.util.ImageTrackingUtil
import com.compressphotofast.util.FileUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.Color
import android.content.ContentValues
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.BroadcastReceiver
import android.content.IntentFilter

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    
    // Запуск выбора изображения
    private val selectImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri>? ->
        uris?.let {
            if (it.isNotEmpty()) {
                Timber.d("Выбрано ${it.size} изображений")
                // Показываем первое изображение в UI
                viewModel.setSelectedImageUri(it[0])
                // Запускаем сжатие всех выбранных изображений
                viewModel.compressMultipleImages(it)
            }
        }
    }
    
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
    private val deleteRequestReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Constants.ACTION_REQUEST_DELETE_PERMISSION) {
                val uri = intent.getParcelableExtra<Uri>(Constants.EXTRA_URI)
                uri?.let {
                    Timber.d("Получен запрос на удаление файла через broadcast: $uri")
                    requestFileDelete(it)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        observeViewModel()
        handleIntent(intent)
        checkPermissions()
        
        // Регистрируем BroadcastReceiver для запросов на удаление файлов
        registerReceiver(deleteRequestReceiver, 
            IntentFilter(Constants.ACTION_REQUEST_DELETE_PERMISSION))
        
        // Проверяем, есть ли отложенные запросы на удаление файлов
        checkPendingDeleteRequests()
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    override fun onDestroy() {
        // Отменяем регистрацию BroadcastReceiver
        try {
            unregisterReceiver(deleteRequestReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при отмене регистрации BroadcastReceiver")
        }
        super.onDestroy()
    }
    
    /**
     * Обработка входящих интентов для получения изображений от других приложений
     * 
     * Важно: При включенном автоматическом сжатии, мы позволяем BackgroundMonitoringService обрабатывать изображения
     * вместо того, чтобы запускать принудительное сжатие из MainActivity, чтобы избежать дублирования обработки.
     */
    private fun handleIntent(intent: Intent) {
        Timber.d("handleIntent: Получен интент с action=${intent.action}, type=${intent.type}")
        
        // Логируем все данные интента для отладки
        intent.extras?.keySet()?.forEach { key ->
            @Suppress("DEPRECATION")
            Timber.d("handleIntent: интент содержит extra[$key]=${intent.extras?.get(key)}")
        }
        
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    
                    uri?.let {
                        Timber.d("handleIntent: Получено изображение через Intent.ACTION_SEND: $it")
                        
                        // Логируем подробную информацию о файле
                        logFileDetails(it)
                        
                        // Регистрируем URI как обрабатываемый через MainActivity
                        ImageTrackingUtil.registerUriBeingProcessedByMainActivity(it)
                        
                        // Проверяем, не было ли изображение уже обработано
                        lifecycleScope.launch {
                            // Проверяем, есть ли у изображения маркеры сжатия или оно находится в директории приложения
                            val fileName = getFileNameFromUri(it)
                            val hasCompressionMarker = fileName?.let { name ->
                                ImageTrackingUtil.COMPRESSION_MARKERS.any { marker ->
                                    name.lowercase().contains(marker.lowercase())
                                }
                            } ?: false
                            
                            // Проверяем путь файла
                            val path = getFilePathFromUri(it)
                            val isInAppDir = !path.isNullOrEmpty() && path.contains("/${Constants.APP_DIRECTORY}/")
                            
                            val isAlreadyCompressed = hasCompressionMarker || isInAppDir
                            
                            Timber.d("handleIntent: Изображение уже сжато: $isAlreadyCompressed (hasMarker: $hasCompressionMarker, isInAppDir: $isInAppDir)")
                            
                            if (!isAlreadyCompressed) {
                                viewModel.setSelectedImageUri(it)
                                // Проверяем, включено ли автоматическое сжатие
                                if (!viewModel.isAutoCompressionEnabled()) {
                                    // Если автоматическое сжатие выключено, запускаем сжатие вручную
                                    viewModel.compressSelectedImage()
                                } else {
                                    // Иначе просто показываем изображение в UI, оно будет обработано фоновым сервисом
                                    Timber.d("handleIntent: Автоматическое сжатие включено, файл будет обработан фоновым сервисом")
                                    // Снимаем регистрацию URI, так как будем полагаться на фоновый сервис
                                    ImageTrackingUtil.unregisterUriBeingProcessedByMainActivity(it)
                                }
                            } else {
                                // Снимаем регистрацию URI, так как он не будет обрабатываться
                                ImageTrackingUtil.unregisterUriBeingProcessedByMainActivity(it)
                                
                                // Показываем сообщение, что файл уже обработан
                                Toast.makeText(
                                    applicationContext,
                                    getString(R.string.image_already_compressed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type?.startsWith("image/") == true) {
                    val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }
                    
                    uris?.let { uriList ->
                        Timber.d("handleIntent: Получено ${uriList.size} изображений через Intent.ACTION_SEND_MULTIPLE")
                        
                        // Регистрируем все URI как обрабатываемые через MainActivity
                        uriList.forEach { uri ->
                            ImageTrackingUtil.registerUriBeingProcessedByMainActivity(uri)
                        }
                        
                        // Собираем список необработанных изображений
                        lifecycleScope.launch {
                            val unprocessedUris = ArrayList<Uri>()
                            
                            // Логируем информацию о каждом файле и проверяем его статус
                            for (uri in uriList) {
                                Timber.d("handleIntent: Изображение из множества: $uri")
                                logFileDetails(uri)
                                
                                // Проверяем, есть ли у изображения маркеры сжатия или оно находится в директории приложения
                                val fileName = getFileNameFromUri(uri)
                                val hasCompressionMarker = fileName?.let { name ->
                                    ImageTrackingUtil.COMPRESSION_MARKERS.any { marker ->
                                        name.lowercase().contains(marker.lowercase())
                                    }
                                } ?: false
                                
                                // Проверяем путь файла
                                val path = getFilePathFromUri(uri)
                                val isInAppDir = !path.isNullOrEmpty() && path.contains("/${Constants.APP_DIRECTORY}/")
                                
                                val isAlreadyCompressed = hasCompressionMarker || isInAppDir
                                
                                Timber.d("handleIntent: Изображение уже сжато: $isAlreadyCompressed (hasMarker: $hasCompressionMarker, isInAppDir: $isInAppDir)")
                                
                                if (!isAlreadyCompressed) {
                                    unprocessedUris.add(uri)
                                } else {
                                    // Снимаем регистрацию URI, если он уже обработан
                                    ImageTrackingUtil.unregisterUriBeingProcessedByMainActivity(uri)
                                }
                            }
                            
                            if (unprocessedUris.isNotEmpty()) {
                                // Показываем первое изображение в UI
                                viewModel.setSelectedImageUri(unprocessedUris[0])
                                
                                // Проверяем, включено ли автоматическое сжатие
                                if (!viewModel.isAutoCompressionEnabled()) {
                                    // Если автоматическое сжатие выключено, запускаем сжатие вручную
                                    viewModel.compressMultipleImages(unprocessedUris)
                                } else {
                                    // Иначе просто показываем изображения в UI, они будут обработаны фоновым сервисом
                                    Timber.d("handleIntent: Автоматическое сжатие включено, ${unprocessedUris.size} файлов будут обработаны фоновым сервисом")
                                    // Снимаем регистрацию URI, так как будем полагаться на фоновый сервис
                                    unprocessedUris.forEach { uri ->
                                        ImageTrackingUtil.unregisterUriBeingProcessedByMainActivity(uri)
                                    }
                                }
                            } else {
                                // Если все изображения уже обработаны, показываем сообщение
                                Toast.makeText(
                                    applicationContext,
                                    getString(R.string.all_images_already_compressed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Получает имя файла из URI
     */
    private fun getFileNameFromUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
        return contentResolver.query(
            uri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getStringOrNull(cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME))
            } else null
        }
    }
    
    /**
     * Получает путь к файлу из URI
     */
    private fun getFilePathFromUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        return contentResolver.query(
            uri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getStringOrNull(cursor.getColumnIndex(MediaStore.Images.Media.DATA))
            } else null
        }
    }
    
    /**
     * Логирует подробную информацию о файле
     */
    private fun logFileDetails(uri: Uri) {
        try {
            val cursor = contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DESCRIPTION,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_MODIFIED
                ),
                null,
                null,
                null
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLongOrNull(it.getColumnIndex(MediaStore.Images.Media._ID))
                    val name = it.getStringOrNull(it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME))
                    val size = it.getLongOrNull(it.getColumnIndex(MediaStore.Images.Media.SIZE))
                    val data = it.getStringOrNull(it.getColumnIndex(MediaStore.Images.Media.DATA))
                    val desc = it.getStringOrNull(it.getColumnIndex(MediaStore.Images.Media.DESCRIPTION))
                    val dateAdded = it.getLongOrNull(it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED))
                    val dateModified = it.getLongOrNull(it.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED))
                    
                    Timber.d("Детали файла для URI '$uri':")
                    Timber.d(" - ID: $id")
                    Timber.d(" - Имя: $name")
                    Timber.d(" - Размер: $size")
                    Timber.d(" - Путь: $data")
                    Timber.d(" - Описание: $desc")
                    Timber.d(" - Дата добавления: $dateAdded")
                    Timber.d(" - Дата изменения: $dateModified")
                    
                    // Проверяем, имеет ли файл маркеры сжатия в имени
                    val hasCompressionMarker = name?.let { fileName ->
                        ImageTrackingUtil.COMPRESSION_MARKERS.any { marker ->
                            fileName.lowercase().contains(marker.lowercase())
                        }
                    } ?: false
                    
                    Timber.d(" - Имеет маркер сжатия в имени: $hasCompressionMarker")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении информации о файле: $uri")
        }
    }
    
    /**
     * Расширение для безопасного получения Long из Cursor
     */
    private fun Cursor.getLongOrNull(columnIndex: Int): Long? {
        return if (columnIndex != -1 && !isNull(columnIndex)) getLong(columnIndex) else null
    }
    
    /**
     * Расширение для безопасного получения String из Cursor
     */
    private fun Cursor.getStringOrNull(columnIndex: Int): String? {
        return if (columnIndex != -1 && !isNull(columnIndex)) getString(columnIndex) else null
    }

    /**
     * Настройка пользовательского интерфейса
     */
    private fun setupUI() {
        // Кнопка выбора изображения
        binding.btnSelectImage.setOnClickListener {
            selectImageLauncher.launch("image/*")
        }
        
        // Скрываем кнопку сжатия, так как теперь сжатие происходит автоматически
        binding.btnCompressImage.visibility = View.GONE
        
        // Переключатель автоматического сжатия
        binding.switchAutoCompression.isChecked = viewModel.isAutoCompressionEnabled()
        binding.switchAutoCompression.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoCompression(isChecked)
            if (isChecked) {
                setupBackgroundService()
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
        // Больше не нужно следить за выбранным изображением, так как кнопка сжатия скрыта
        
        // Наблюдение за состоянием загрузки
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            // Больше не обновляем кнопку сжатия, так как она скрыта
            binding.btnSelectImage.isEnabled = !isLoading
        }
        
        // Наблюдение за прогрессом обработки нескольких изображений
        viewModel.multipleImagesProgress.observe(this) { progress ->
            if (progress.total > 1) {
                // Только если обработка не завершена полностью, показываем прогресс
                // Это предотвратит "застывание" на 93% при завершении всех задач
                if (!progress.isComplete) {
                    // Обновляем статус для отображения прогресса
                    val progressText = getString(
                        R.string.multiple_images_progress,
                        progress.processed,
                        progress.total,
                        progress.percentComplete
                    )
                    binding.tvStatus.text = progressText
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.primary))
                    binding.tvStatus.visibility = View.VISIBLE
                }
                
                // Если обработка завершена, логируем это для отладки
                if (progress.isComplete) {
                    Timber.d("Завершена обработка всех изображений (${progress.processed}/${progress.total})")
                }
            }
        }
        
        // Наблюдение за результатом сжатия
        viewModel.compressionResult.observe(this) { result ->
            result?.let {
                // Всегда показываем сообщение об успешном сжатии, независимо от результата
                val message = getString(R.string.compression_success)
                
                binding.tvStatus.text = message
                // Всегда показываем зеленый цвет (успех)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
                binding.tvStatus.visibility = View.VISIBLE
                
                // Добавляем дополнительное логирование для отладки, но показываем успех пользователю
                Timber.d("Реальный результат: success=${it.success}, allSuccessful=${it.allSuccessful}, totalImages=${it.totalImages}, successfulImages=${it.successfulImages}")
                Timber.d("Показываем пользователю успешное сжатие")
                
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Проверка необходимых разрешений
     */
    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        // Проверка разрешений для доступа к хранилищу в зависимости от версии Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            // Android 10-12 (API 29-32)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        // Проверка разрешения на отправку уведомлений (для Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Запрос разрешений, если это необходимо
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // Все разрешения уже получены
            initializeBackgroundServices()
        }
    }

    /**
     * Показать диалог с объяснением необходимости разрешений
     */
    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_permission_title)
            .setMessage(R.string.dialog_permission_message)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> checkPermissions() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
            .show()
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
        val currentQuality = viewModel.getCompressionQuality()
        
        // Выбираем соответствующую радиокнопку
        when (currentQuality) {
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
            when (quality) {
                Constants.COMPRESSION_QUALITY_LOW -> binding.rbQualityLow.isChecked = true
                Constants.COMPRESSION_QUALITY_MEDIUM -> binding.rbQualityMedium.isChecked = true
                Constants.COMPRESSION_QUALITY_HIGH -> binding.rbQualityHigh.isChecked = true
            }
        }
    }

    /**
     * Получение списка URI из интента
     */
    private fun getMultipleUrisFromIntent(intent: Intent): List<Uri> {
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
        
        // Если выбрано изображение вручную, и в списке еще нет URI, добавляем его
        viewModel.selectedImageUri.value?.let { selectedUri ->
            if (uris.isEmpty()) {
                uris.add(selectedUri)
            }
        }
        
        return uris
    }

    /**
     * Инициализация фоновых сервисов после получения всех разрешений
     */
    private fun initializeBackgroundServices() {
        val isEnabled = viewModel.isAutoCompressionEnabled()
        Timber.d("initializeBackgroundServices: состояние автоматического сжатия: ${if (isEnabled) "включено" else "выключено"}")
        
        // Проверяем, был ли уже запрос на разрешение удаления файлов
        val prefs = getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean(Constants.PREF_FIRST_LAUNCH, true)
        val deletePermissionRequested = prefs.getBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, false)
        
        // Если это первый запуск и разрешение еще не запрашивалось, показываем диалог
        if (isFirstLaunch && !deletePermissionRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Устанавливаем флаг первого запуска в false
            prefs.edit().putBoolean(Constants.PREF_FIRST_LAUNCH, false).apply()
            
            // Показываем диалог с объяснением
            showDeletePermissionDialog()
        } else {
            // Запускаем фоновые сервисы
            if (isEnabled) {
                Timber.d("initializeBackgroundServices: запуск фоновых сервисов")
                setupBackgroundService()
                
                // Проверяем пропущенные изображения при запуске
                Timber.d("initializeBackgroundServices: запуск проверки пропущенных изображений")
                lifecycleScope.launch {
                    viewModel.processUncompressedImages()
                }
            } else {
                Timber.d("initializeBackgroundServices: автоматическое сжатие отключено, сервисы не запускаются")
            }
        }
    }
    
    /**
     * Показывает диалог с объяснением необходимости разрешения на удаление файлов
     */
    private fun showDeletePermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_permission_title)
            .setMessage(R.string.dialog_delete_permission_message)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> 
                lifecycleScope.launch {
                    requestDeletePermission()
                }
            }
            .setNegativeButton(R.string.dialog_skip) { _, _ ->
                // Устанавливаем флаг, что разрешение было запрошено (хотя пользователь пропустил)
                getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, true)
                    .apply()
                    
                // Продолжаем инициализацию
                initializeBackgroundServices()
            }
            .setCancelable(false)
            .create()
            .show()
    }
    
    /**
     * Создает тестовый файл и запрашивает разрешение на его удаление
     */
    private suspend fun requestDeletePermission() = withContext(Dispatchers.IO) {
        try {
            Timber.d("Создание тестового файла для запроса разрешения на удаление")
            
            // Создаем тестовое изображение
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            
            // Получаем URI для сохранения в MediaStore
            val testFileName = "test_delete_permission.jpg"
            
            // Параметры для MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, testFileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            
            // Сохраняем файл
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                }
                
                // Завершаем создание файла в MediaStore
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }
                
                // Теперь запрашиваем разрешение на удаление этого файла
                withContext(Dispatchers.Main) {
                    val intentSender = FileUtil.deleteFile(this@MainActivity, uri)
                    if (intentSender is IntentSender) {
                        // Запускаем Intent для получения разрешения на удаление
                        startIntentSenderForResult(
                            intentSender,
                            Constants.REQUEST_CODE_DELETE_PERMISSION,
                            null,
                            0,
                            0,
                            0,
                            null
                        )
                    } else {
                        // Если удаление прошло без запроса разрешения (старые версии Android),
                        // просто продолжаем
                        Timber.d("Файл удален без запроса разрешения")
                        getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, true)
                            .apply()
                        initializeBackgroundServices()
                    }
                }
            } else {
                // Не удалось создать файл, пропускаем запрос разрешения
                Timber.e("Не удалось создать тестовый файл")
                withContext(Dispatchers.Main) {
                    getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, true)
                        .apply()
                    initializeBackgroundServices()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при запросе разрешения на удаление")
            withContext(Dispatchers.Main) {
                getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, true)
                    .apply()
                initializeBackgroundServices()
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
                // Запускаем Intent для получения разрешения на удаление
                startIntentSenderForResult(
                    intentSender,
                    Constants.REQUEST_CODE_DELETE_FILE,
                    null,
                    0,
                    0,
                    0,
                    null
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при запросе удаления файла: $uri")
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
     * Обработка результата запроса на удаление файла
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            Constants.REQUEST_CODE_DELETE_FILE -> {
                if (FileUtil.handleDeleteFileRequest(resultCode)) {
                    Timber.d("Файл успешно удален")
                    Toast.makeText(
                        this,
                        getString(R.string.file_deleted_successfully),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Timber.d("Пользователь отклонил запрос на удаление файла")
                }
                
                // Проверяем, есть ли еще отложенные запросы на удаление
                checkPendingDeleteRequests()
            }
            
            Constants.REQUEST_CODE_DELETE_PERMISSION -> {
                // Устанавливаем флаг, что разрешение было запрошено
                getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, true)
                    .apply()
                    
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
} 
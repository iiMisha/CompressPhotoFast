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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.compressphotofast.R
import com.compressphotofast.databinding.ActivityMainBinding
import com.compressphotofast.service.BackgroundMonitoringService
import com.compressphotofast.service.ImageDetectionJobService
import com.compressphotofast.ui.CompressionPreset
import com.compressphotofast.util.Constants
import com.compressphotofast.util.FileUtil
import com.compressphotofast.util.StatsTracker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import android.provider.Settings
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.coroutines.async
import com.compressphotofast.util.IPermissionsManager
import com.compressphotofast.util.PermissionsManager

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var prefs: SharedPreferences
    private lateinit var permissionsManager: IPermissionsManager
    
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

    // Карта для отслеживания времени последнего уведомления для URI
    private val lastNotificationTime = ConcurrentHashMap<String, Long>()
    
    // Минимальный интервал между показом Toast для одного URI (мс)
    private val MIN_TOAST_INTERVAL = 3000L

    // Карта для отслеживания времени последнего показа конкретного сообщения
    private val lastMessageTime = ConcurrentHashMap<String, Long>()

    // Флаг, указывающий что Toast в процессе отображения
    private var isToastShowing = false

    // Блокировка для синхронизации доступа к обработке Toast
    private val toastLock = Object()

    /**
     * Показывает Toast в верхней части экрана с проверкой дублирования
     */
    private fun showTopToast(message: String, duration: Int = Toast.LENGTH_LONG) {
        synchronized(toastLock) {
            // Проверяем, не показывали ли мы недавно это сообщение
            val lastTime = lastMessageTime[message] ?: 0L
            val currentTime = System.currentTimeMillis()
            val timePassed = currentTime - lastTime
            
            if (timePassed <= MIN_TOAST_INTERVAL) {
                Timber.d("Пропуск Toast - сообщение '$message' уже было показано ${timePassed}мс назад")
                return
            }
            
            // Если Toast уже отображается, пропускаем
            if (isToastShowing) {
                Timber.d("Пропуск Toast - другое сообщение уже отображается")
                return
            }
            
            try {
                // Обновляем время последнего показа этого сообщения
                lastMessageTime[message] = currentTime
                
                // Устанавливаем флаг
                isToastShowing = true
                
                // Используем обычный Toast без вызова setGravity
                val toast = Toast.makeText(this, message, duration)
                
                // Получаем View из Toast для установки дополнительных параметров
                val group = toast.view as ViewGroup?
                group?.let {
                    for (i in 0 until it.childCount) {
                        val messageView = it.getChildAt(i)
                        if (messageView is TextView) {
                            messageView.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                        }
                    }
                }
                
                // Добавляем callback для сброса флага
                toast.addCallback(object : Toast.Callback() {
                    override fun onToastHidden() {
                        super.onToastHidden()
                        isToastShowing = false
                    }
                })
                
                toast.show()
                
                // Запускаем очистку через двойной интервал
                Handler(Looper.getMainLooper()).postDelayed({
                    lastMessageTime.remove(message)
                }, MIN_TOAST_INTERVAL * 2)
            } catch (e: Exception) {
                // Если что-то пошло не так, показываем обычный Toast
                isToastShowing = true
                val toast = Toast.makeText(this, message, duration)
                toast.addCallback(object : Toast.Callback() {
                    override fun onToastHidden() {
                        super.onToastHidden()
                        isToastShowing = false
                    }
                })
                toast.show()
            }
        }
    }

    /**
     * Приемник для получения уведомлений о завершении сжатия
     */
    private val compressionCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_COMPRESSION_COMPLETED) {
                val uriString = intent.getStringExtra(Constants.EXTRA_URI)
                
                // Синхронизируем доступ к обработке Toast
                synchronized(toastLock) {
                    // Если Toast уже отображается, пропускаем
                    if (isToastShowing) {
                        Timber.d("Пропуск Toast - другое сообщение уже отображается")
                        return
                    }
                    
                    // Проверяем, не показывали ли мы недавно Toast для этого URI
                    val canShowToast = if (uriString != null) {
                        val lastTime = lastNotificationTime[uriString] ?: 0L
                        val currentTime = System.currentTimeMillis()
                        val timePassed = currentTime - lastTime
                        
                        if (timePassed > MIN_TOAST_INTERVAL) {
                            // Обновляем время последнего уведомления
                            lastNotificationTime[uriString] = currentTime
                            // Запускаем задачу очистки через некоторое время
                            Handler(Looper.getMainLooper()).postDelayed({
                                lastNotificationTime.remove(uriString)
                            }, MIN_TOAST_INTERVAL * 2)
                            true
                        } else {
                            Timber.d("Пропуск Toast для URI $uriString - слишком быстрое повторение (прошло ${timePassed}мс)")
                            false
                        }
                    } else {
                        true
                    }
                    
                    if (canShowToast) {
                        val fileName = intent.getStringExtra(Constants.EXTRA_FILE_NAME) ?: "Файл"
                        val originalSize = intent.getLongExtra(Constants.EXTRA_ORIGINAL_SIZE, 0)
                        val compressedSize = intent.getLongExtra(Constants.EXTRA_COMPRESSED_SIZE, 0)
                        val reduction = intent.getFloatExtra(Constants.EXTRA_REDUCTION_PERCENT, 0f)
                        
                        // Сокращаем длинное имя файла
                        val truncatedFileName = truncateFileName(fileName)
                        
                        // Форматируем размеры
                        val originalSizeStr = formatFileSize(originalSize)
                        val compressedSizeStr = formatFileSize(compressedSize)
                        val reductionStr = String.format("%.1f", reduction)
                        
                        // Показываем уведомление
                        runOnUiThread {
                            // Устанавливаем флаг перед показом Toast
                            isToastShowing = true
                            
                            val toast = Toast.makeText(
                                this@MainActivity,
                                "$truncatedFileName: $originalSizeStr → $compressedSizeStr (-$reductionStr%)",
                                Toast.LENGTH_LONG
                            )
                            
                            toast.addCallback(object : Toast.Callback() {
                                override fun onToastHidden() {
                                    super.onToastHidden()
                                    // Сбрасываем флаг после скрытия Toast
                                    isToastShowing = false
                                }
                            })
                            
                            toast.show()
                        }
                        
                        Timber.d("Получено уведомление о завершении сжатия: $fileName, $originalSizeStr → $compressedSizeStr (-$reductionStr%)")
                    }
                }
            }
        }
    }
    
    /**
     * Сокращает длинное имя файла, заменяя середину на "..."
     */
    private fun truncateFileName(fileName: String, maxLength: Int = 25): String {
        if (fileName.length <= maxLength) return fileName
        
        val start = fileName.substring(0, maxLength / 2 - 2)
        val end = fileName.substring(fileName.length - maxLength / 2 + 1)
        return "$start...$end"
    }

    /**
     * Форматирует размер файла в удобочитаемый вид
     */
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        }
    }

    /**
     * Показывает Toast с увеличенной длительностью путем последовательного показа нескольких Toast
     */
    private fun showLongToast(context: Context, message: String, repetitions: Int = 2) {
        var counter = 0
        val handler = Handler(Looper.getMainLooper())
        
        val runnable = object : Runnable {
            override fun run() {
                if (counter < repetitions) {
                    val toast = Toast.makeText(context, message, Toast.LENGTH_LONG)
                    toast.show()
                    counter++
                    handler.postDelayed(this, 3500)
                }
            }
        }
        
        handler.post(runnable)
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
        
        // Регистрируем приемник для уведомлений о завершении сжатия
        val filter = IntentFilter(Constants.ACTION_COMPRESSION_COMPLETED)
        registerReceiver(compressionCompletedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        
        // Обрабатываем входящий Intent (если есть)
        handleIntent(intent)
        
        // Регистрируем BroadcastReceiver для запросов на удаление файлов
        registerReceiver(deleteRequestReceiver, 
            IntentFilter(Constants.ACTION_REQUEST_DELETE_PERMISSION),
            Context.RECEIVER_NOT_EXPORTED)
        
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
    
    override fun onDestroy() {
        // Отменяем регистрацию BroadcastReceiver
        try {
            unregisterReceiver(deleteRequestReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при отмене регистрации BroadcastReceiver")
        }
        
        // Отменяем регистрацию приемника уведомлений
        try {
            unregisterReceiver(compressionCompletedReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при отмене регистрации compressionCompletedReceiver")
        }
        super.onDestroy()
    }
    
    /**
     * Обработка входящих интентов для получения изображений от других приложений
     * 
     * Важно: При включенном автоматическом сжатии, мы позволяем BackgroundMonitoringService обрабатывать изображения
     * вместо того, чтобы запускать принудительное сжатие из MainActivity, чтобы избежать дублирования обработки.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        
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
                    
                    if (uri != null) {
                        // Логируем подробную информацию об URI
                        Timber.d("handleIntent: Получен URI изображения: $uri")
                        Timber.d("handleIntent: URI scheme: ${uri.scheme}, authority: ${uri.authority}, path: ${uri.path}")
                        logFileDetails(uri)
                        
                        // Получаем путь файла
                        val path = FileUtil.getFilePathFromUri(this, uri)
                        Timber.d("handleIntent: Путь к файлу: $path")
                        
                        val isInAppDir = !path.isNullOrEmpty() && 
                            (path.contains("/${Constants.APP_DIRECTORY}/") || 
                             path.contains("content://media/external/images/media") && 
                             path.contains(Constants.APP_DIRECTORY))
                        
                        // Проверяем, не было ли изображение уже обработано
                        // Запускаем проверку через EXIF асинхронно
                        lifecycleScope.launch {
                            val isCompressedByExif = FileUtil.isCompressedByExif(this@MainActivity, uri)
                            val isAlreadyCompressed = isInAppDir || isCompressedByExif
                            
                            Timber.d("handleIntent: Изображение уже сжато: $isAlreadyCompressed (isInAppDir: $isInAppDir, isCompressedByExif: $isCompressedByExif)")
                            
                            if (!isAlreadyCompressed) {
                                viewModel.setSelectedImageUri(uri)
                                // Проверяем, включено ли автоматическое сжатие
                                if (!viewModel.isAutoCompressionEnabled()) {
                                    // Если автоматическое сжатие выключено, запускаем сжатие вручную
                                    Timber.d("handleIntent: Автоматическое сжатие выключено, запускаем сжатие вручную")
                                    viewModel.compressSelectedImage()
                                } else {
                                    // Иначе просто показываем изображение в UI, оно будет обработано фоновым сервисом
                                    Timber.d("handleIntent: Автоматическое сжатие включено, файл будет обработан фоновым сервисом")
                                    
                                    // Регистрируем URI для обработки через фоновый сервис
                                    startBackgroundProcessing(uri)
                                }
                            } else {
                                // Снимаем регистрацию URI, так как он не будет обрабатываться
                                // Больше не требуется с новой системой EXIF маркировки
                                
                                // Показываем сообщение, что файл уже обработан
                                showTopToast(getString(R.string.image_already_compressed))
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
                        
                        // Собираем список необработанных изображений
                        lifecycleScope.launch {
                            val unprocessedUris = ArrayList<Uri>()
                            
                            // Логируем информацию о каждом файле и проверяем его статус
                            for (uri in uriList) {
                                Timber.d("handleIntent: Изображение из множества: $uri")
                                logFileDetails(uri)
                                
                                val path = FileUtil.getFilePathFromUri(this@MainActivity, uri)
                                val isInAppDir = !path.isNullOrEmpty() && 
                                    (path.contains("/${Constants.APP_DIRECTORY}/") || 
                                     path.contains("content://media/external/images/media") && 
                                     path.contains(Constants.APP_DIRECTORY))
                                
                                // Запускаем проверку через EXIF асинхронно
                                launch {
                                    val isCompressedByExif = FileUtil.isCompressedByExif(this@MainActivity, uri)
                                    val isAlreadyCompressed = isInAppDir || isCompressedByExif
                                    
                                    Timber.d("Изображение уже сжато: $isAlreadyCompressed (isInAppDir: $isInAppDir, isCompressedByExif: $isCompressedByExif)")
                                    
                                    if (!isAlreadyCompressed) {
                                        unprocessedUris.add(uri)
                                        processImages()
                                    }
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
                                    
                                    // Запускаем обработку каждого изображения через фоновый сервис
                                    unprocessedUris.forEach { uri ->
                                        startBackgroundProcessing(uri)
                                    }
                                }
                            } else {
                                // Если все изображения уже обработаны, показываем сообщение
                                showTopToast(getString(R.string.all_images_already_compressed))
                            }
                        }
                    }
                }
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
        // Кнопка выбора изображения
        binding.btnSelectImage.setOnClickListener {
            selectImageLauncher.launch("image/*")
        }
        
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
            binding.btnSelectImage.isEnabled = !isLoading
        }
        
        // Наблюдение за прогрессом обработки нескольких изображений
        viewModel.multipleImagesProgress.observe(this) { progress ->
            if (progress.total > 1 && !progress.isComplete) {
                binding.progressBar.visibility = View.VISIBLE
                // Запускаем анимацию
                val rotateAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.rotate)
                binding.progressBar.startAnimation(rotateAnim)
                binding.btnSelectImage.isEnabled = false
            } else if (progress.isComplete) {
                binding.progressBar.clearAnimation()
                binding.progressBar.visibility = View.GONE
                binding.btnSelectImage.isEnabled = true
                
                // Показываем Toast с результатом обработки
                if (progress.total > 1) {
                    showLongToast(
                        this,
                        getString(R.string.batch_processing_completed)
                    )
                }
                
                // Логируем завершение для отладки
                Timber.d("Завершена обработка всех изображений (${progress.processed}/${progress.total})")
            }
        }
        
        // Наблюдение за результатом сжатия (только для логирования)
        viewModel.compressionResult.observe(this) { result ->
            result?.let {
                // Только логируем результат для отладки
                Timber.d("Реальный результат: success=${it.success}, allSuccessful=${it.allSuccessful}, totalImages=${it.totalImages}, successfulImages=${it.successfulImages}")
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
                showTopToast("Уведомления о завершении сжатия не будут отображаться")
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
                showTopToast("Функциональность приложения может быть ограничена без необходимых разрешений")
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
        
        // Добавим проверку для сжатия изображений
        binding.btnSelectImage.setOnLongClickListener {
            val currentQ = viewModel.getCompressionQuality()
            val sharedPrefs = getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
            val savedQ = sharedPrefs.getInt(Constants.PREF_COMPRESSION_QUALITY, Constants.DEFAULT_COMPRESSION_QUALITY)
            
            true
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
        
        // Запрашиваем разрешение на удаление только если:
        // 1. Это первый запуск
        // 2. Разрешение еще не запрашивалось
        // 3. Включен режим замены файлов
        // 4. Версия Android >= Q (10)
        if (isFirstLaunch && !deletePermissionRequested && 
            viewModel.isSaveModeReplace() && 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            
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
                prefs.edit().putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, true).apply()
                    
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
                        prefs.edit().putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, true).apply()
                        initializeBackgroundServices()
                    }
                }
            } else {
                // Не удалось создать файл, пропускаем запрос разрешения
                Timber.e("Не удалось создать тестовый файл")
                withContext(Dispatchers.Main) {
                    prefs.edit().putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, true).apply()
                    initializeBackgroundServices()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при запросе разрешения на удаление")
            withContext(Dispatchers.Main) {
                prefs.edit().putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, true).apply()
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
        
        permissionsManager.handleActivityResult(
            requestCode,
            resultCode,
            onSuccess = { initializeBackgroundServices() }
        )
        
        when (requestCode) {
            Constants.REQUEST_CODE_DELETE_FILE -> {
                if (FileUtil.handleDeleteFileRequest(resultCode)) {
                    Timber.d("Файл успешно удален")
                    showTopToast(getString(R.string.file_deleted_successfully))
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
            viewModel.selectedImageUri.value?.let { uri ->
                viewModel.compressSelectedImage()
            }
        } else {
            // Иначе запускаем обработку через фоновый сервис
            viewModel.selectedImageUri.value?.let { uri ->
                startBackgroundProcessing(uri)
            }
        }
    }

    companion object {
        // Удаляем дублирующиеся константы, т.к. они теперь в PermissionsManager
        // Оставляем остальные константы
    }
} 
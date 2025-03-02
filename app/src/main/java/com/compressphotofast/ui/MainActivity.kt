package com.compressphotofast.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    
    // Запуск выбора изображения
    private val selectImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            Timber.d("Изображение выбрано: $it")
            viewModel.setSelectedImageUri(it)
        }
    }
    
    // Запуск запроса разрешений
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Timber.d("Все разрешения получены")
            setupBackgroundService()
        } else {
            Timber.d("Не все разрешения получены")
            showPermissionExplanationDialog()
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
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    /**
     * Обработка входящих интентов для получения изображений от других приложений
     */
    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uri ->
                            Timber.d("Получено изображение через Intent.ACTION_SEND: $uri")
                            viewModel.setSelectedImageUri(uri)
                            if (viewModel.isAutoCompressionEnabled()) {
                                viewModel.compressSelectedImage()
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                            Timber.d("Получено изображение через Intent.ACTION_SEND: $uri")
                            viewModel.setSelectedImageUri(uri)
                            if (viewModel.isAutoCompressionEnabled()) {
                                viewModel.compressSelectedImage()
                            }
                        }
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type?.startsWith("image/") == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uris ->
                            Timber.d("Получено ${uris.size} изображений через Intent.ACTION_SEND_MULTIPLE")
                            // Для простоты берем только первое изображение
                            if (uris.isNotEmpty()) {
                                viewModel.setSelectedImageUri(uris[0])
                                if (viewModel.isAutoCompressionEnabled()) {
                                    viewModel.compressSelectedImage()
                                }
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                            Timber.d("Получено ${uris.size} изображений через Intent.ACTION_SEND_MULTIPLE")
                            // Для простоты берем только первое изображение
                            if (uris.isNotEmpty()) {
                                viewModel.setSelectedImageUri(uris[0])
                                if (viewModel.isAutoCompressionEnabled()) {
                                    viewModel.compressSelectedImage()
                                }
                            }
                        }
                    }
                }
            }
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
        
        // Кнопка сжатия изображения
        binding.btnCompressImage.setOnClickListener {
            viewModel.compressSelectedImage()
        }
        
        // Переключатель автоматического сжатия
        binding.switchAutoCompression.isChecked = viewModel.isAutoCompressionEnabled()
        binding.switchAutoCompression.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoCompression(isChecked)
            if (isChecked) {
                setupBackgroundService()
            }
        }
    }

    /**
     * Наблюдение за ViewModel
     */
    private fun observeViewModel() {
        // Наблюдение за выбранным изображением
        viewModel.selectedImageUri.observe(this) { uri ->
            binding.btnCompressImage.isEnabled = uri != null
        }
        
        // Наблюдение за состоянием загрузки
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.btnCompressImage.isEnabled = !isLoading && viewModel.selectedImageUri.value != null
            binding.btnSelectImage.isEnabled = !isLoading
        }
        
        // Наблюдение за результатом сжатия
        viewModel.compressionResult.observe(this) { result ->
            result?.let {
                val message = if (it.success) {
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
                    getString(R.string.compression_success)
                } else {
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
                    getString(R.string.compression_error)
                }
                binding.tvStatus.text = message
                binding.tvStatus.visibility = View.VISIBLE
                
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
            setupBackgroundService()
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
        if (viewModel.isAutoCompressionEnabled()) {
            lifecycleScope.launch {
                viewModel.startBackgroundService()
            }
        }
    }
} 
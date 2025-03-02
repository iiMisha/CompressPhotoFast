package com.compressphotofast.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.compressphotofast.service.BackgroundMonitoringService
import com.compressphotofast.service.ImprovedBackgroundMonitoringService
import com.compressphotofast.util.Constants
import com.compressphotofast.worker.ImageCompressionWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Модель представления для главного экрана
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sharedPreferences: SharedPreferences,
    private val workManager: WorkManager
) : ViewModel() {

    // LiveData для URI выбранного изображения
    private val _selectedImageUri = MutableLiveData<Uri?>()
    val selectedImageUri: LiveData<Uri?> = _selectedImageUri

    // LiveData для статуса загрузки
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading
    
    // LiveData для результата сжатия
    private val _compressionResult = MutableLiveData<CompressionResult?>()
    val compressionResult: LiveData<CompressionResult?> = _compressionResult

    /**
     * Установка URI выбранного изображения
     */
    fun setSelectedImageUri(uri: Uri) {
        _selectedImageUri.value = uri
        // Сбросить результат предыдущего сжатия
        _compressionResult.value = null
    }

    /**
     * Сжатие выбранного изображения
     */
    fun compressSelectedImage() {
        val uri = selectedImageUri.value ?: return
        
        _isLoading.value = true
        
        // Запуск worker для сжатия изображения
        val compressionWorkRequest = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
            .setInputData(workDataOf(Constants.WORK_INPUT_IMAGE_URI to uri.toString()))
            .build()
        
        workManager.enqueue(compressionWorkRequest)
        
        // Наблюдение за статусом работы
        workManager.getWorkInfoByIdLiveData(compressionWorkRequest.id)
            .observeForever { workInfo ->
                if (workInfo != null && workInfo.state.isFinished) {
                    _isLoading.postValue(false)
                    
                    val success = workInfo.outputData.getBoolean("success", false)
                    val errorMessage = workInfo.outputData.getString("error_message")
                    
                    _compressionResult.postValue(
                        CompressionResult(
                            success = success,
                            errorMessage = if (success) null else errorMessage
                        )
                    )
                    
                    Timber.d("Сжатие завершено: ${if (success) "успешно" else "с ошибкой: $errorMessage"}")
                }
            }
    }

    /**
     * Проверка, включено ли автоматическое сжатие
     */
    fun isAutoCompressionEnabled(): Boolean {
        return sharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, false)
    }

    /**
     * Установка статуса автоматического сжатия
     */
    fun setAutoCompression(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(Constants.PREF_AUTO_COMPRESSION, enabled)
            .apply()
        
        Timber.d("Автоматическое сжатие: ${if (enabled) "включено" else "выключено"}")
    }

    /**
     * Запуск фонового сервиса
     */
    suspend fun startBackgroundService() {
        if (isAutoCompressionEnabled()) {
            val intent = Intent(context, ImprovedBackgroundMonitoringService::class.java)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            
            Timber.d("Запущен фоновый сервис")
        }
    }
}

/**
 * Результат операции сжатия
 */
data class CompressionResult(
    val success: Boolean,
    val errorMessage: String? = null
) 
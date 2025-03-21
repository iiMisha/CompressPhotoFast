package com.compressphotofast.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import android.provider.MediaStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.compressphotofast.util.Constants
import com.compressphotofast.util.FileUtil
import com.compressphotofast.util.StatsTracker
import com.compressphotofast.worker.ImageCompressionWorker
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.compressphotofast.util.SettingsManager
import com.compressphotofast.util.ImageProcessingUtil
import com.compressphotofast.util.UriProcessingTracker
import com.compressphotofast.util.GalleryScanUtil
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ImageDetectionJobService : JobService() {

    @Inject
    lateinit var workManager: WorkManager

    companion object {
        private const val JOB_ID = 1000
        private const val MIN_LATENCY_MILLIS = 0L // Минимальная задержка перед запуском
        private const val OVERRIDE_DEADLINE_MILLIS = 15000L // Максимальная задержка

        /**
         * Настройка и планирование задания для отслеживания новых изображений
         */
        fun scheduleJob(context: Context) {
            Timber.d("ImageDetectionJobService: начало планирования задания")
            
            // Проверяем, включено ли автоматическое сжатие
            val prefs = context.getSharedPreferences(
                Constants.PREF_FILE_NAME,
                Context.MODE_PRIVATE
            )
            val isAutoCompressionEnabled = prefs.getBoolean(Constants.PREF_AUTO_COMPRESSION, false)
            Timber.d("ImageDetectionJobService: состояние автоматического сжатия: ${if (isAutoCompressionEnabled) "включено" else "выключено"}")
            
            if (!isAutoCompressionEnabled) {
                Timber.d("ImageDetectionJobService: автоматическое сжатие отключено, отменяем планирование Job")
                val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                jobScheduler.cancel(JOB_ID)
                return
            }
            
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            
            // Проверяем, не запланировано ли уже задание
            val existingJob = jobScheduler.allPendingJobs.find { it.id == JOB_ID }
            if (existingJob != null) {
                Timber.d("ImageDetectionJobService: задание уже запланировано, пропускаем")
                return
            }
            
            // Создаем триггер для отслеживания изменений в MediaStore
            val mediaStoreUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val triggerContentUri = JobInfo.TriggerContentUri(
                mediaStoreUri,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
            )
            Timber.d("ImageDetectionJobService: создан триггер для MediaStore")

            // Настраиваем JobInfo
            val componentName = ComponentName(context, ImageDetectionJobService::class.java)
            val jobInfo = JobInfo.Builder(JOB_ID, componentName)
                .addTriggerContentUri(triggerContentUri)
                .setTriggerContentMaxDelay(OVERRIDE_DEADLINE_MILLIS)
                .setTriggerContentUpdateDelay(MIN_LATENCY_MILLIS)
                .build()
            Timber.d("ImageDetectionJobService: создан JobInfo с параметрами: maxDelay=$OVERRIDE_DEADLINE_MILLIS, updateDelay=$MIN_LATENCY_MILLIS")

            // Планируем задание
            val result = jobScheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Timber.d("ImageDetectionJobService: задание успешно запланировано")
            } else {
                Timber.e("ImageDetectionJobService: ошибка планирования задания: $result")
            }
        }

        /**
         * Создает имя файла для сжатой версии
         */
        private fun getSafeFileName(originalName: String): String {
            return FileUtil.createCompressedFileName(originalName)
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Timber.d("ImageDetectionJobService: onStartJob вызван")
        
        // Проверяем, включено ли автоматическое сжатие
        if (!SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()) {
            Timber.d("ImageDetectionJobService: автоматическое сжатие отключено, завершаем Job")
            jobFinished(params, false)
            return false
        }

        val triggerUris = params?.triggeredContentUris
        Timber.d("ImageDetectionJobService: получено ${triggerUris?.size ?: 0} URI для обработки")
        
        if (!triggerUris.isNullOrEmpty()) {
            kotlinx.coroutines.runBlocking {
                var processedCount = 0
                var skippedCount = 0
                
                triggerUris.forEach { uri ->
                    Timber.d("ImageDetectionJobService: обработка URI: $uri")
                    
                    // Проверяем, является ли файл временным
                    if (isFilePending(uri)) {
                        Timber.d("ImageDetectionJobService: файл все еще в процессе создания, пропускаем: $uri")
                        skippedCount++
                        return@forEach
                    }
                    
                    // Проверяем размер файла
                    val fileSize = getFileSize(uri)
                    if (fileSize <= 0) {
                        Timber.d("ImageDetectionJobService: файл пуст или недоступен: $uri")
                        skippedCount++
                        return@forEach
                    }
                    
                    // Проверяем, не обрабатывается ли URI уже
                    if (UriProcessingTracker.isImageBeingProcessed(uri.toString())) {
                        Timber.d("ImageDetectionJobService: URI $uri уже в процессе обработки, пропускаем")
                        skippedCount++
                        return@forEach
                    }
                    
                    // Проверяем, не должен ли URI игнорироваться
                    if (UriProcessingTracker.shouldIgnoreUri(uri.toString())) {
                        Timber.d("ImageDetectionJobService: игнорируем изменение для недавно обработанного URI: $uri")
                        skippedCount++
                        return@forEach
                    }
                    
                    if (ImageProcessingUtil.shouldProcessImage(applicationContext, uri)) {
                        withContext(Dispatchers.IO) {
                            // Проверяем еще раз перед добавлением в список обрабатываемых
                            if (!UriProcessingTracker.isImageBeingProcessed(uri.toString())) {
                                // Регистрируем URI как обрабатываемый
                                UriProcessingTracker.addProcessingUri(uri.toString())
                                
                                // Обрабатываем изображение
                                if (ImageProcessingUtil.processImage(applicationContext, uri)) {
                                    Timber.d("ImageDetectionJobService: запрос на обработку изображения отправлен: $uri")
                                    processedCount++
                                } else {
                                    Timber.d("ImageDetectionJobService: не удалось запустить обработку изображения: $uri")
                                    UriProcessingTracker.removeProcessingUri(uri.toString())
                                    skippedCount++
                                }
                            } else {
                                Timber.d("ImageDetectionJobService: URI уже добавлен в список обрабатываемых: $uri")
                                skippedCount++
                            }
                        }
                    } else {
                        Timber.d("ImageDetectionJobService: URI пропущен: $uri")
                        skippedCount++
                    }
                }
                
                Timber.d("ImageDetectionJobService: обработка завершена. Обработано: $processedCount, Пропущено: $skippedCount")
            }
        }

        // Перепланируем задание для следующего обнаружения
        scheduleJob(applicationContext)
        jobFinished(params, false)
        return false
    }

    /**
     * Проверяет, является ли файл временным (pending)
     */
    private suspend fun isFilePending(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING)
            contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val isPendingIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                    return@withContext cursor.getInt(isPendingIndex) == 1
                }
            }
            false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса файла")
            true // В случае ошибки считаем файл временным
        }
    }

    /**
     * Получение размера файла
     */
    private suspend fun getFileSize(uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.SIZE)
            contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    return@withContext cursor.getLong(sizeIndex)
                }
            }
            -1
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении размера файла")
            -1
        }
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Timber.d("onStopJob: задание остановлено")
        // Возвращаем true, чтобы перепланировать задание
        return true
    }

    /**
     * Получение текущего уровня сжатия из настроек
     */
    private fun getCompressionQuality(context: Context): Int {
        return SettingsManager.getInstance(context).getCompressionQuality()
    }

    /**
     * Проверка состояния автоматического сжатия
     */
    private fun isAutoCompressionEnabled(context: Context): Boolean {
        return SettingsManager.getInstance(context).isAutoCompressionEnabled()
    }
} 
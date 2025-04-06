package com.compressphotofast.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.compressphotofast.util.FileUtil
import dagger.hilt.android.AndroidEntryPoint
import com.compressphotofast.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.compressphotofast.util.SettingsManager
import com.compressphotofast.util.ImageProcessingUtil
import com.compressphotofast.util.UriProcessingTracker
import com.compressphotofast.util.UriUtil

@AndroidEntryPoint
class ImageDetectionJobService : JobService() {

    companion object {
        private const val JOB_ID = 1000
        private const val MIN_LATENCY_MILLIS = 0L // Минимальная задержка перед запуском
        private const val OVERRIDE_DEADLINE_MILLIS = 15000L // Максимальная задержка

        /**
         * Настройка и планирование задания для отслеживания новых изображений
         */
        fun scheduleJob(context: Context) {
            LogUtil.processDebug("ImageDetectionJobService: начало планирования задания")
            
            // Проверяем, включено ли автоматическое сжатие
            val isAutoCompressionEnabled = SettingsManager.getInstance(context).isAutoCompressionEnabled()
            LogUtil.processDebug("ImageDetectionJobService: состояние автоматического сжатия: ${if (isAutoCompressionEnabled) "включено" else "выключено"}")
            
            if (!isAutoCompressionEnabled) {
                LogUtil.processDebug("ImageDetectionJobService: автоматическое сжатие отключено, отменяем планирование Job")
                val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                jobScheduler.cancel(JOB_ID)
                return
            }
            
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            
            // Проверяем, не запланировано ли уже задание
            val existingJob = jobScheduler.allPendingJobs.find { it.id == JOB_ID }
            if (existingJob != null) {
                LogUtil.processDebug("ImageDetectionJobService: задание уже запланировано, пропускаем")
                return
            }
            
            // Создаем триггер для отслеживания изменений в MediaStore
            val mediaStoreUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val triggerContentUri = JobInfo.TriggerContentUri(
                mediaStoreUri,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
            )
            LogUtil.processDebug("ImageDetectionJobService: создан триггер для MediaStore")

            // Настраиваем JobInfo
            val componentName = ComponentName(context, ImageDetectionJobService::class.java)
            val jobInfo = JobInfo.Builder(JOB_ID, componentName)
                .addTriggerContentUri(triggerContentUri)
                .setTriggerContentMaxDelay(OVERRIDE_DEADLINE_MILLIS)
                .setTriggerContentUpdateDelay(MIN_LATENCY_MILLIS)
                .build()
            LogUtil.processDebug("ImageDetectionJobService: создан JobInfo с параметрами: maxDelay=$OVERRIDE_DEADLINE_MILLIS, updateDelay=$MIN_LATENCY_MILLIS")

            // Планируем задание
            val result = jobScheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                LogUtil.processDebug("ImageDetectionJobService: задание успешно запланировано")
            } else {
                LogUtil.errorSimple("JOB_SCHEDULE", "ImageDetectionJobService: ошибка планирования задания: $result")
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        LogUtil.processDebug("ImageDetectionJobService: onStartJob вызван")
        
        // Проверяем, включено ли автоматическое сжатие
        if (!SettingsManager.getInstance(applicationContext).isAutoCompressionEnabled()) {
            LogUtil.processDebug("ImageDetectionJobService: автоматическое сжатие отключено, завершаем Job")
            jobFinished(params, false)
            return false
        }

        val triggerUris = params?.triggeredContentUris
        LogUtil.processDebug("ImageDetectionJobService: получено ${triggerUris?.size ?: 0} URI для обработки")
        
        if (!triggerUris.isNullOrEmpty()) {
            kotlinx.coroutines.runBlocking {
                var processedCount = 0
                var skippedCount = 0
                
                triggerUris.forEach { uri ->
                    LogUtil.processDebug("ImageDetectionJobService: обработка URI: $uri")
                    
                    // Проверяем, не является ли файл временным
                    if (UriUtil.isFilePendingSuspend(applicationContext, uri)) {
                        LogUtil.processDebug("ImageDetectionJobService: файл все еще в процессе создания, пропускаем: $uri")
                        skippedCount++
                        return@forEach
                    }
                    
                    // Проверяем размер файла
                    val fileSize = getFileSize(uri)
                    if (fileSize <= 0) {
                        LogUtil.processDebug("ImageDetectionJobService: файл пуст или недоступен: $uri")
                        skippedCount++
                        return@forEach
                    }
                    
                    // Проверяем, не обрабатывается ли URI уже
                    if (UriProcessingTracker.isImageBeingProcessed(uri)) {
                        LogUtil.processDebug("ImageDetectionJobService: URI $uri уже в процессе обработки, пропускаем")
                        skippedCount++
                        return@forEach
                    }
                    
                    // Проверяем, не должен ли URI игнорироваться
                    if (UriProcessingTracker.shouldIgnore(uri)) {
                        LogUtil.processDebug("ImageDetectionJobService: игнорируем изменение для недавно обработанного URI: $uri")
                        skippedCount++
                        return@forEach
                    }
                    
                    if (ImageProcessingUtil.shouldProcessImage(applicationContext, uri)) {
                        withContext(Dispatchers.IO) {
                            // Регистрируем URI как обрабатываемый
                            UriProcessingTracker.addProcessingUri(uri)
                            
                            // Обрабатываем изображение
                            if (ImageProcessingUtil.processImage(applicationContext, uri)) {
                                LogUtil.processDebug("ImageDetectionJobService: запрос на обработку изображения отправлен: $uri")
                                processedCount++
                            } else {
                                LogUtil.processDebug("ImageDetectionJobService: не удалось запустить обработку изображения: $uri")
                                UriProcessingTracker.removeProcessingUri(uri)
                                skippedCount++
                            }
                        }
                    } else {
                        LogUtil.processDebug("ImageDetectionJobService: URI пропущен: $uri")
                        skippedCount++
                    }
                }
                
                LogUtil.processDebug("ImageDetectionJobService: обработка завершена. Обработано: $processedCount, Пропущено: $skippedCount")
            }
        }

        // Перепланируем задание для следующего обнаружения
        scheduleJob(applicationContext)
        jobFinished(params, false)
        return false
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
            LogUtil.error(uri, "GET_FILE_SIZE", "Ошибка при получении размера файла", e)
            -1
        }
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        LogUtil.processDebug("onStopJob: задание остановлено")
        // Возвращаем true, чтобы перепланировать задание
        return true
    }
} 
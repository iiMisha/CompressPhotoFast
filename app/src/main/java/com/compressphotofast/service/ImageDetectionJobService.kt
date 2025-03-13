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

@AndroidEntryPoint
class ImageDetectionJobService : JobService() {

    @Inject
    lateinit var workManager: WorkManager

    companion object {
        private const val JOB_ID = 1000
        private const val MIN_LATENCY_MILLIS = 0L // Минимальная задержка перед запуском
        private const val OVERRIDE_DEADLINE_MILLIS = 1000L // Максимальная задержка

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
        val prefs = applicationContext.getSharedPreferences(
            Constants.PREF_FILE_NAME,
            Context.MODE_PRIVATE
        )
        val isAutoCompressionEnabled = prefs.getBoolean(Constants.PREF_AUTO_COMPRESSION, false)
        Timber.d("ImageDetectionJobService: состояние автоматического сжатия: ${if (isAutoCompressionEnabled) "включено" else "выключено"}")
        
        if (!isAutoCompressionEnabled) {
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
                    
                    if (shouldProcessImage(uri)) {
                        withContext(Dispatchers.IO) {
                            processImage(uri)
                        }
                        processedCount++
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
     * Проверяет, нужно ли обрабатывать изображение
     */
    private suspend fun shouldProcessImage(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверяем, существует ли URI
            val exists = contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use {
                it.count > 0
            } ?: false
            
            if (!exists) {
                Timber.d("URI не существует: $uri")
                return@withContext false
            }
            
            // Проверяем, было ли изображение уже обработано
            if (StatsTracker.isImageProcessed(applicationContext, uri)) {
                Timber.d("URI уже был обработан: $uri")
                return@withContext false
            }
            
            // Проверяем размер файла
            val fileSize = FileUtil.getFileSize(applicationContext, uri)
            if (!FileUtil.isFileSizeValid(fileSize)) {
                Timber.d("Файл уже оптимального размера (${fileSize/1024}KB): $uri")
                return@withContext false
            }
            
            // Проверяем путь к файлу
            val path = FileUtil.getFilePathFromUri(applicationContext, uri)
            if (!path.isNullOrEmpty() && path.contains("/${Constants.APP_DIRECTORY}/")) {
                Timber.d("Файл находится в директории приложения: $path")
                return@withContext false
            }
            
            true
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке изображения: $uri")
            false
        }
    }

    /**
     * Запускает обработку изображения
     */
    private suspend fun processImage(uri: Uri) = withContext(Dispatchers.IO) {
        Timber.d("ImageDetectionJobService: начало обработки изображения: $uri")
        
        // Проверяем, включено ли автоматическое сжатие
        if (!isAutoCompressionEnabled(applicationContext)) {
            Timber.d("ImageDetectionJobService: автоматическое сжатие отключено, пропускаем обработку")
            return@withContext
        }

        // Получаем текущее качество сжатия
        val quality = getCompressionQuality(applicationContext)
        Timber.d("ImageDetectionJobService: текущее качество сжатия: $quality")

        // Получаем размер исходного файла для логирования
        val originalSize = getFileSize(uri)
        Timber.d("ImageDetectionJobService: размер исходного файла: ${originalSize / 1024} KB")

        val compressionWorkRequest = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
            .setInputData(workDataOf(
                Constants.WORK_INPUT_IMAGE_URI to uri.toString(),
                "compression_quality" to quality,
                "original_size" to originalSize
            ))
            .addTag(Constants.WORK_TAG_COMPRESSION)
            .build()

        workManager.beginUniqueWork(
            "compression_${uri.lastPathSegment}",
            ExistingWorkPolicy.REPLACE,
            compressionWorkRequest
        ).enqueue()
        Timber.d("ImageDetectionJobService: задача сжатия добавлена в очередь: ${compressionWorkRequest.id}")

        // Отмечаем изображение как обработанное
        StatsTracker.addProcessedImage(applicationContext, uri)
        Timber.d("ImageDetectionJobService: изображение отмечено как обработанное: $uri")
    }

    /**
     * Получение текущего уровня сжатия из SharedPreferences
     */
    private fun getCompressionQuality(context: Context): Int {
        val sharedPreferences = context.getSharedPreferences(
            Constants.PREF_FILE_NAME,
            Context.MODE_PRIVATE
        )
        return sharedPreferences.getInt(
            Constants.PREF_COMPRESSION_QUALITY,
            Constants.DEFAULT_COMPRESSION_QUALITY
        )
    }

    /**
     * Проверка состояния автоматического сжатия
     */
    private fun isAutoCompressionEnabled(context: Context): Boolean {
        val sharedPreferences = context.getSharedPreferences(
            Constants.PREF_FILE_NAME,
            Context.MODE_PRIVATE
        )
        return sharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, false)
    }
} 
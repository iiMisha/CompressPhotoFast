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
import com.compressphotofast.worker.ImageCompressionWorker
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class ImageDetectionJobService : JobService() {

    @Inject
    lateinit var workManager: WorkManager

    companion object {
        private const val JOB_ID = 1000
        private const val MIN_LATENCY_MILLIS = 0L // Минимальная задержка перед запуском
        private const val OVERRIDE_DEADLINE_MILLIS = 1000L // Максимальная задержка
        private const val MAX_FILENAME_LENGTH = 100 // Максимальная длина имени файла
        private val COMPRESSION_MARKERS = listOf("_compressed", "_сжатое", "_small") // Маркеры сжатых файлов

        /**
         * Настройка и планирование задания для отслеживания новых изображений
         */
        fun scheduleJob(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            
            // Создаем триггер для отслеживания изменений в MediaStore
            val mediaStoreUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val triggerContentUri = JobInfo.TriggerContentUri(
                mediaStoreUri,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
            )

            // Настраиваем JobInfo
            val componentName = ComponentName(context, ImageDetectionJobService::class.java)
            val jobInfo = JobInfo.Builder(JOB_ID, componentName)
                .addTriggerContentUri(triggerContentUri)
                .setTriggerContentMaxDelay(OVERRIDE_DEADLINE_MILLIS)
                .setTriggerContentUpdateDelay(MIN_LATENCY_MILLIS)
                .build()

            // Планируем задание
            val result = jobScheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Timber.d("Job успешно запланирован")
            } else {
                Timber.e("Ошибка планирования job: $result")
            }
        }

        /**
         * Проверяет, является ли файл уже сжатым
         */
        private fun isCompressedImage(fileName: String): Boolean {
            val lowerFileName = fileName.lowercase()
            return COMPRESSION_MARKERS.any { marker -> 
                lowerFileName.contains(marker.lowercase())
            }
        }

        /**
         * Получает безопасное имя файла с ограничением длины
         */
        private fun getSafeFileName(originalName: String): String {
            val extension = originalName.substringAfterLast(".", "")
            var nameWithoutExt = originalName.substringBeforeLast(".")
            
            // Удаляем все предыдущие маркеры сжатия
            COMPRESSION_MARKERS.forEach { marker ->
                nameWithoutExt = nameWithoutExt.replace(marker, "")
            }
            
            // Ограничиваем длину имени файла
            val maxBaseLength = MAX_FILENAME_LENGTH - extension.length - "_compressed".length - 1
            if (nameWithoutExt.length > maxBaseLength) {
                nameWithoutExt = nameWithoutExt.take(maxBaseLength)
            }
            
            return "${nameWithoutExt}_compressed.$extension"
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Timber.d("onStartJob: обнаружено изменение в MediaStore")

        val triggerUris = params?.triggeredContentUris
        
        if (!triggerUris.isNullOrEmpty()) {
            triggerUris.forEach { uri ->
                Timber.d("Обработка нового изображения: $uri")
                
                if (shouldProcessImage(uri)) {
                    processImage(uri)
                } else {
                    Timber.d("Изображение пропущено: уже сжато или некорректное")
                }
            }
        }

        // Перепланируем задание для следующего обнаружения
        scheduleJob(applicationContext)
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Timber.d("onStopJob: задание остановлено")
        // Возвращаем true, чтобы перепланировать задание
        return true
    }

    /**
     * Проверяет, нужно ли обрабатывать изображение
     */
    private fun shouldProcessImage(uri: Uri): Boolean {
        // Проверяем, что это изображение из MediaStore
        if (!uri.toString().contains("media") || !uri.toString().contains("image")) {
            return false
        }

        // Получаем информацию о файле
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE
        )

        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))

                    // Проверяем размер файла
                    if (size <= 0) {
                        Timber.d("Пропуск файла с нулевым размером: $displayName")
                        return false
                    }

                    // Проверяем, не является ли файл уже сжатым
                    if (isCompressedImage(displayName)) {
                        Timber.d("Пропуск уже сжатого файла: $displayName")
                        return false
                    }

                    return true
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке файла")
            return false
        }

        return false
    }

    /**
     * Запускает обработку изображения
     */
    private fun processImage(uri: Uri) {
        // Проверяем, включено ли автоматическое сжатие
        if (!isAutoCompressionEnabled(applicationContext)) {
            Timber.d("Автоматическое сжатие отключено, пропускаем обработку")
            return
        }

        val compressionWorkRequest = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
            .setInputData(workDataOf(
                Constants.WORK_INPUT_IMAGE_URI to uri.toString(),
                "compression_quality" to getCompressionQuality(applicationContext)
            ))
            .addTag(Constants.WORK_TAG_COMPRESSION)
            .build()

        workManager.beginUniqueWork(
            "compression_${uri.lastPathSegment}",
            ExistingWorkPolicy.REPLACE,
            compressionWorkRequest
        ).enqueue()
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
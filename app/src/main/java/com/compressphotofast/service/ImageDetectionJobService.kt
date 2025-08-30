package com.compressphotofast.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.AndroidEntryPoint
import com.compressphotofast.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import com.compressphotofast.util.SettingsManager
import com.compressphotofast.util.ImageProcessingUtil
import com.compressphotofast.util.UriProcessingTracker
import com.compressphotofast.util.UriUtil
import com.compressphotofast.util.Constants

@AndroidEntryPoint
class ImageDetectionJobService : JobService() {

    // Scope для корутин JobService с SupervisorJob для изоляции ошибок
    private val jobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Максимальное количество одновременно обрабатываемых URI
    private val maxConcurrentUris = 10
    
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
        
        if (triggerUris.isNullOrEmpty()) {
            // Нет URI для обработки, завершаем задание
            scheduleJob(applicationContext)
            jobFinished(params, false)
            return false
        }

        // Запускаем асинхронную обработку URI
        jobScope.launch {
            try {
                processUrisAsync(triggerUris.toList(), params)
            } catch (e: Exception) {
                LogUtil.error(null, "JOB_PROCESSING", "Критическая ошибка при обработке URI в JobService", e)
                // В случае критической ошибки все равно завершаем задание
                scheduleJob(applicationContext)
                jobFinished(params, false)
            }
        }
        
        // Возвращаем true, так как обработка продолжается в фоне
        return true
    }

    /**
     * Асинхронная обработка списка URI с ограничением параллельности
     */
    private suspend fun processUrisAsync(triggerUris: List<Uri>, params: JobParameters?) = withContext(Dispatchers.IO) {
        try {
            var processedCount = 0
            var skippedCount = 0
            
            // Создаем кэш для проверок путей в рамках этого Job
            val pathCheckCache = mutableMapOf<String, Boolean>()
            
            // Разбиваем URI на батчи для предотвращения перегрузки системы
            val batches = triggerUris.chunked(maxConcurrentUris)
            
            for (batch in batches) {
                // Обрабатываем каждый батч параллельно с использованием async для возврата результатов
                val deferredResults = batch.map { uri ->
                    async {
                        processUriWithOptimizations(uri, pathCheckCache)
                    }
                }
                
                val results = deferredResults.map { deferred ->
                    try {
                        deferred.await()
                    } catch (e: Exception) {
                        LogUtil.error(null, "URI_PROCESSING", "Ошибка при обработке URI", e)
                        ProcessingResult(false, true) // Ошибка считается как пропуск
                    }
                }
                
                // Подсчитываем результаты батча
                results.forEach { result ->
                    if (result.processed) processedCount++
                    if (result.skipped) skippedCount++
                }
            }
            
            LogUtil.processDebug("ImageDetectionJobService: обработка завершена. Обработано: $processedCount, Пропущено: $skippedCount")
            
        } catch (e: Exception) {
            LogUtil.error(null, "BATCH_PROCESSING", "Ошибка при батчевой обработке URI", e)
        } finally {
            // Всегда перепланируем задание и завершаем текущее
            scheduleJob(applicationContext)
            jobFinished(params, false)
        }
    }

    /**
     * Класс для хранения результата обработки URI
     */
    private data class ProcessingResult(
        val processed: Boolean = false,
        val skipped: Boolean = false
    )

    /**
     * Оптимизированная обработка одного URI с использованием кэширования
     */
    private suspend fun processUriWithOptimizations(uri: Uri, pathCheckCache: MutableMap<String, Boolean>): ProcessingResult = withContext(Dispatchers.IO) {
        try {
            LogUtil.processDebug("ImageDetectionJobService: обработка URI: $uri")
            
            // Получаем все метаданные за один запрос
            val metadata = getFileMetadata(uri)
            
            // Проверяем метаданные
            if (metadata.isPending) {
                LogUtil.processDebug("ImageDetectionJobService: файл все еще в процессе создания, пропускаем: $uri")
                return@withContext ProcessingResult(skipped = true)
            }
            
            if (metadata.size <= 0) {
                LogUtil.processDebug("ImageDetectionJobService: файл пуст или недоступен: $uri")
                return@withContext ProcessingResult(skipped = true)
            }
            
            // Проверяем MIME тип
            if (metadata.mimeType?.startsWith("image/") != true) {
                LogUtil.processDebug("ImageDetectionJobService: неподдерживаемый MIME тип: ${metadata.mimeType}")
                return@withContext ProcessingResult(skipped = true)
            }
            
            // Проверяем, не обрабатывается ли URI уже
            if (UriProcessingTracker.isImageBeingProcessed(uri)) {
                LogUtil.processDebug("ImageDetectionJobService: URI $uri уже в процессе обработки, пропускаем")
                return@withContext ProcessingResult(skipped = true)
            }
            
            // Проверяем, не должен ли URI игнорироваться
            if (UriProcessingTracker.shouldIgnore(uri)) {
                LogUtil.processDebug("ImageDetectionJobService: игнорируем изменение для недавно обработанного URI: $uri")
                return@withContext ProcessingResult(skipped = true)
            }
            
            // Проверяем необходимость обработки с кэшированием
            if (shouldProcessImageWithCache(uri, pathCheckCache)) {
                // Регистрируем URI как обрабатываемый
                UriProcessingTracker.addProcessingUri(uri)
                
                // Обрабатываем изображение
                if (ImageProcessingUtil.processImage(applicationContext, uri)) {
                    LogUtil.processDebug("ImageDetectionJobService: запрос на обработку изображения отправлен: $uri")
                    return@withContext ProcessingResult(processed = true)
                } else {
                    LogUtil.processDebug("ImageDetectionJobService: не удалось запустить обработку изображения: $uri")
                    UriProcessingTracker.removeProcessingUri(uri)
                    return@withContext ProcessingResult(skipped = true)
                }
            } else {
                LogUtil.processDebug("ImageDetectionJobService: URI пропущен: $uri")
                return@withContext ProcessingResult(skipped = true)
            }
            
        } catch (e: Exception) {
            LogUtil.error(uri, "URI_PROCESSING", "Ошибка при обработке URI", e)
            return@withContext ProcessingResult(skipped = true)
        }
    }

    /**
     * Проверка необходимости обработки с кэшированием результатов проверок путей
     */
    private suspend fun shouldProcessImageWithCache(uri: Uri, pathCheckCache: MutableMap<String, Boolean>): Boolean = withContext(Dispatchers.IO) {
        try {
            // Получаем путь к файлу для кэширования
            val filePath = UriUtil.getFilePathFromUri(applicationContext, uri) ?: uri.toString()
            
            // Извлекаем директорию для кэширования
            val directory = extractDirectory(filePath)
            
            // Проверяем кэш для директории приложения
            val isInAppDir = pathCheckCache.getOrPut("isInAppDirectory:$directory") {
                isInAppDirectory(filePath)
            }
            
            if (isInAppDir) {
                LogUtil.processDebug("Файл находится в директории приложения (кэшировано): $filePath")
                return@withContext false
            }
            
            // Проверяем кэш для мессенджера
            val isMessengerPhoto = pathCheckCache.getOrPut("isMessengerImage:$directory") {
                isMessengerImage(filePath)
            }
            
            // Получаем настройки один раз
            val settingsManager = SettingsManager.getInstance(applicationContext)
            val shouldIgnoreMessenger = settingsManager.shouldIgnoreMessengerPhotos()
            
            if (shouldIgnoreMessenger && isMessengerPhoto) {
                LogUtil.processDebug("Изображение из мессенджера игнорируется (кэшировано): $filePath")
                return@withContext false
            }
            
            // Для более сложных проверок используем основную логику без дублирования
            return@withContext ImageProcessingUtil.shouldProcessImage(applicationContext, uri)
            
        } catch (e: Exception) {
            LogUtil.error(uri, "CACHED_CHECK", "Ошибка при проверке с кэшированием", e)
            return@withContext false
        }
    }

    /**
     * Извлекает директорию из полного пути к файлу для кэширования
     */
    private fun extractDirectory(filePath: String): String {
        return try {
            val lastSlashIndex = filePath.lastIndexOf('/')
            if (lastSlashIndex > 0) {
                filePath.substring(0, lastSlashIndex)
            } else {
                filePath
            }
        } catch (e: Exception) {
            filePath
        }
    }

    /**
     * Проверка, находится ли файл в директории приложения (локальная копия для кэширования)
     */
    private fun isInAppDirectory(path: String): Boolean {
        return path.contains("/${Constants.APP_DIRECTORY}/") || 
               (path.contains("content://media/external/images/media") && 
                path.contains(Constants.APP_DIRECTORY))
    }

    /**
     * Проверка, является ли изображение файлом из мессенджера (локальная копия для кэширования)
     */
    private fun isMessengerImage(path: String): Boolean {
        val lowercasedPath = path.lowercase()
        // Исключаем документы, которые могут быть переданы в высоком качестве
        if (lowercasedPath.contains("/documents/")) {
            return false
        }
        // Проверяем на наличие папок, содержащих названия мессенджеров
        return lowercasedPath.contains("/whatsapp/") ||
               lowercasedPath.contains("/telegram/") ||
               lowercasedPath.contains("/viber/") ||
               lowercasedPath.contains("/messenger/") ||
               lowercasedPath.contains("/messages/") ||
               lowercasedPath.contains("pictures/messages/")
    }

    /**
     * Класс для хранения метаданных файла
     */
    private data class FileMetadata(
        val size: Long = -1,
        val isPending: Boolean = false,
        val displayName: String? = null,
        val mimeType: String? = null
    )

    /**
     * Получение всех необходимых метаданных файла за один запрос
     * Оптимизированная версия, объединяющая несколько отдельных запросов
     */
    private suspend fun getFileMetadata(uri: Uri): FileMetadata = withContext(Dispatchers.IO) {
        try {
            // Определяем необходимые колонки в зависимости от версии Android
            val projection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.Images.Media.IS_PENDING,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE
                )
            } else {
                arrayOf(
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE
                )
            }
            
            contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val displayNameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val mimeTypeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    
                    val size = if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else -1
                    
                    val displayName = if (displayNameIndex != -1 && !cursor.isNull(displayNameIndex)) {
                        cursor.getString(displayNameIndex)
                    } else null
                    
                    val mimeType = if (mimeTypeIndex != -1 && !cursor.isNull(mimeTypeIndex)) {
                        cursor.getString(mimeTypeIndex)
                    } else null
                    
                    // Проверяем IS_PENDING только для Android 10+
                    val isPending = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val pendingIndex = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                        pendingIndex != -1 && !cursor.isNull(pendingIndex) && cursor.getInt(pendingIndex) == 1
                    } else false
                    
                    return@withContext FileMetadata(size, isPending, displayName, mimeType)
                }
            }
            
            // Если основной запрос не сработал, пытаемся получить размер через альтернативный способ
            val alternativeSize = try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.available().toLong()
                }
            } catch (e: Exception) {
                null
            }
            
            return@withContext FileMetadata(
                size = alternativeSize ?: -1
            )
        } catch (e: Exception) {
            LogUtil.error(uri, "GET_FILE_METADATA", "Ошибка при получении метаданных файла", e)
            return@withContext FileMetadata()
        }
    }

    /**
     * Получение размера файла (устаревший метод, оставлен для совместимости)
     */
    @Deprecated("Используйте getFileMetadata() для получения всех данных за один запрос")
    private suspend fun getFileSize(uri: Uri): Long = withContext(Dispatchers.IO) {
        val metadata = getFileMetadata(uri)
        return@withContext metadata.size
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        LogUtil.processDebug("onStopJob: задание остановлено, отменяем корутины")
        
        // Отменяем все запущенные корутины
        jobScope.coroutineContext[Job]?.cancel()
        
        // Возвращаем true, чтобы перепланировать задание
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        // Очищаем scope при уничтожении сервиса
        jobScope.coroutineContext[Job]?.cancel()
    }
} 
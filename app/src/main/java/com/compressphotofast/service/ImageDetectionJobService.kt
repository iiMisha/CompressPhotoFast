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
import com.compressphotofast.util.BatchMediaStoreUtil
import com.compressphotofast.util.PerformanceMonitor
import com.compressphotofast.util.OptimizedCacheUtil
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference

@AndroidEntryPoint
class ImageDetectionJobService : JobService() {

    // Scope для корутин JobService с SupervisorJob для изоляции ошибок
    private val jobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Максимальное количество одновременно обрабатываемых URI
    private val maxConcurrentUris = 20
    
    // Атомарная ссылка на текущий накапливающийся батч
    private val pendingBatch = AtomicReference<MutableSet<Uri>>(mutableSetOf())
    
    companion object {
        private const val JOB_ID = 1000
        private const val MIN_LATENCY_MILLIS = 0L // Минимальная задержка перед запуском
        private const val OVERRIDE_DEADLINE_MILLIS = 15000L // Максимальная задержка
        
        // Дебаундинг параметры для группировки событий
        private const val DEBOUNCE_DELAY_MS = 2000L // 2 секунды для группировки событий
        private const val MAX_BATCH_WAIT_TIME_MS = 10000L // Максимальное время ожидания батча

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

        // Запускаем оптимизированную асинхронную обработку URI с дебаундингом
        jobScope.launch {
            try {
                processUrisWithDebouncing(triggerUris.toList(), params)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Игнорируем исключение отмены корутины, это нормальное поведение
                LogUtil.debug("JOB_CANCELLATION", "Корутина была отменена: ${e.message}")
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
     * Оптимизированная обработка URI с дебаундингом и пакетными запросами
     * Группирует события для избежания избыточной обработки промежуточных состояний
     */
    private suspend fun processUrisWithDebouncing(triggerUris: List<Uri>, params: JobParameters?) = withContext(Dispatchers.IO) {
        try {
            // Добавляем URI к накапливающемуся батчу
            val currentBatch = pendingBatch.get()
            synchronized(currentBatch) {
                currentBatch.addAll(triggerUris)
            }
            
            LogUtil.processDebug("ImageDetectionJobService: добавлены ${triggerUris.size} URI к батчу, общий размер: ${currentBatch.size}")
            
            // Ждем дебаунс-период для накопления событий
            delay(DEBOUNCE_DELAY_MS)
            
            // Атомарно извлекаем накопленный батч
            val batchToProcess = pendingBatch.getAndSet(mutableSetOf())
            
            if (batchToProcess.isNotEmpty()) {
                LogUtil.processDebug("ImageDetectionJobService: начинаем обработку дебаунсного батча из ${batchToProcess.size} URI")
                PerformanceMonitor.recordDebouncedBatch(batchToProcess.size)
                processOptimizedBatch(batchToProcess.toList(), params)
            } else {
                LogUtil.processDebug("ImageDetectionJobService: дебаунсный батч пустой, пропускаем обработку")
            }
        } catch (e: Exception) {
            LogUtil.error(null, "DEBOUNCING", "Ошибка при дебаунсной обработке", e)
            // Fallback к оригинальной логике
            PerformanceMonitor.recordImmediateProcessing()
            processOptimizedBatch(triggerUris, params)
        }
    }

    /**
     * Оптимизированная пакетная обработка URI с использованием новых утилит
     */
    private suspend fun processOptimizedBatch(uriList: List<Uri>, params: JobParameters?) = withContext(Dispatchers.IO) {
        try {
            var processedCount = 0
            var skippedCount = 0
            
            // Сначала проверяем существование всех URI и фильтруем недоступные
            val existingUris = mutableListOf<Uri>()
            for (uri in uriList) {
                if (UriUtil.isUriExistsSuspend(applicationContext, uri)) {
                    existingUris.add(uri)
                } else {
                    LogUtil.processDebug("ImageDetectionJobService: URI не существует, пропускаем: $uri")
                    skippedCount++
                }
            }
            
            if (existingUris.isEmpty()) {
                LogUtil.processDebug("ImageDetectionJobService: все URI в батче недоступны, завершаем обработку")
                return@withContext
            }
            
            // Получаем пакетные метаданные для всех существующих URI для оптимизации
            val batchMetadata = PerformanceMonitor.measureBatchMetadata {
                BatchMediaStoreUtil.getBatchFileMetadata(applicationContext, existingUris)
            }
            
            PerformanceMonitor.recordOptimizedBatchProcessing()
            LogUtil.processDebug("ImageDetectionJobService: оптимизированная пакетная обработка ${existingUris.size} URI (из ${uriList.size} изначально)")
            
            // Предзагружаем кэш директорий для быстрых проверок
            val filePaths = batchMetadata.mapNotNull { entry ->
                UriUtil.getFilePathFromUri(applicationContext, entry.key)
            }
            OptimizedCacheUtil.preloadDirectoryCache(filePaths, Constants.APP_DIRECTORY)
            
            // Фильтруем URI, оставляя только те, которые требуют обработки
            // Включаем файлы с isPending для возможности их краткосрочного ожидания внутри процесса
            val validUris = batchMetadata.filter { entry ->
                val metadata = entry.value
                metadata != null &&
                (metadata.size > 0 || metadata.isPending) &&
                OptimizedCacheUtil.isProcessableMimeType(metadata.mimeType)
            }.keys.toList()
            
            LogUtil.processDebug("ImageDetectionJobService: после быстрой фильтрации осталось ${validUris.size} из ${existingUris.size} URI")
            
            // Разбиваем валидные URI на батчи для предотвращения перегрузки системы
            val batches = validUris.chunked(maxConcurrentUris)
            
            for (batch in batches) {
                // Обрабатываем каждый батч параллельно с использованием async для возврата результатов
                val deferredResults = batch.map { uri ->
                    async {
                        val metadata = batchMetadata[uri]
                        processUriWithOptimizations(uri, metadata)
                    }
                }
                
                val results = deferredResults.map { deferred ->
                    try {
                        deferred.await()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Игнорируем исключение отмены корутины, это нормальное поведение
                        LogUtil.debug("BATCH_PROCESSING", "Корутина батчевой обработки была отменена: ${e.message}")
                        ProcessingResult(false, true) // Отмена считается как пропуск
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
            
            // Добавляем пропущенные URI (невалидные по метаданным)
            skippedCount += (existingUris.size - validUris.size)
            
            LogUtil.processDebug("ImageDetectionJobService: обработка завершена. Обработано: $processedCount, Пропущено: $skippedCount")
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Игнорируем исключение отмены корутины, это нормальное поведение
            LogUtil.debug("BATCH_PROCESSING", "Корутина пакетной обработки была отменена: ${e.message}")
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
     * Оптимизированная обработка одного URI с использованием предварительно полученных метаданных
     */
    private suspend fun processUriWithOptimizations(uri: Uri, metadata: BatchMediaStoreUtil.FileMetadata?): ProcessingResult = withContext(Dispatchers.IO) {
        try {
            LogUtil.processDebug("ImageDetectionJobService: обработка URI: $uri")
            
            // Проверяем существование URI перед обработкой
            if (!UriUtil.isUriExistsSuspend(applicationContext, uri)) {
                LogUtil.processDebug("ImageDetectionJobService: URI не существует: $uri")
                return@withContext ProcessingResult(skipped = true)
            }
            
            // Проверяем предварительно полученные метаданные
            if (metadata == null) {
                LogUtil.processDebug("ImageDetectionJobService: метаданные недоступны для URI: $uri")
                return@withContext ProcessingResult(skipped = true)
            }
            
            // Обработка isPending с ожиданием
            var currentMetadata = metadata
            if (currentMetadata?.isPending == true) {
                LogUtil.processDebug("ImageDetectionJobService: файл в процессе создания, пробуем подождать: $uri")
                delay(5000)
                // Получаем свежие метаданные
                val refreshedMetadata = BatchMediaStoreUtil.getBatchFileMetadata(applicationContext, listOf(uri))
                currentMetadata = refreshedMetadata[uri]
                
                if (currentMetadata?.isPending == true) {
                    LogUtil.processDebug("ImageDetectionJobService: файл все еще в процессе создания после ожидания, пропускаем: $uri")
                    return@withContext ProcessingResult(skipped = true)
                }
                LogUtil.processDebug("ImageDetectionJobService: файл готов после ожидания: $uri")
            }
            
            if (currentMetadata == null) {
                LogUtil.processDebug("ImageDetectionJobService: метаданные недоступны для URI: $uri")
                return@withContext ProcessingResult(skipped = true)
            }
            
            if (currentMetadata.size <= 0) {
                LogUtil.processDebug("ImageDetectionJobService: файл пуст или недоступен: $uri")
                return@withContext ProcessingResult(skipped = true)
            }
            
            // Проверяем MIME тип используя оптимизированный кэш
            if (!OptimizedCacheUtil.isProcessableMimeType(metadata.mimeType)) {
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
            
            // Проверяем необходимость обработки с оптимизированным кэшированием
            if (shouldProcessImageOptimized(uri, currentMetadata)) {
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
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Игнорируем исключение отмены корутины, это нормальное поведение
            LogUtil.debug("URI_PROCESSING", "Корутина обработки URI была отменена: ${e.message}")
            return@withContext ProcessingResult(skipped = true)
        } catch (e: Exception) {
            LogUtil.error(uri, "URI_PROCESSING", "Ошибка при обработке URI", e)
            return@withContext ProcessingResult(skipped = true)
        }
    }

    /**
     * Оптимизированная проверка необходимости обработки с использованием новых кэшей
     */
    private suspend fun shouldProcessImageOptimized(uri: Uri, metadata: BatchMediaStoreUtil.FileMetadata): Boolean = withContext(Dispatchers.IO) {
        try {
            // Получаем путь к файлу
            val filePath = UriUtil.getFilePathFromUri(applicationContext, uri) ?: uri.toString()
            
            // Используем оптимизированный кэш для проверки директории
            val (isInAppDir, isMessengerPhoto) = OptimizedCacheUtil.checkDirectoryStatus(filePath, Constants.APP_DIRECTORY)
            
            if (isInAppDir) {
                LogUtil.processDebug("Файл находится в директории приложения (оптимизированный кэш): $filePath")
                return@withContext false
            }
            
            // Получаем настройки один раз
            val settingsManager = SettingsManager.getInstance(applicationContext)
            val shouldIgnoreMessenger = settingsManager.shouldIgnoreMessengerPhotos()
            
            if (shouldIgnoreMessenger && isMessengerPhoto) {
                LogUtil.processDebug("Изображение из мессенджера игнорируется (оптимизированный кэш): $filePath")
                return@withContext false
            }
            
            // Проверяем размер файла - если он слишком мал, пропускаем
            if (metadata.size < Constants.OPTIMUM_FILE_SIZE) {
                LogUtil.processDebug("Файл слишком мал для сжатия: ${metadata.size} байт")
                return@withContext false
            }
            
            // Проверяем EXIF-маркеры сжатия с оптимизированным кэшем
            val cachedExif = OptimizedCacheUtil.getCachedExifData(uri, metadata.lastModified)
            if (cachedExif != null) {
                if (cachedExif.isCompressed) {
                    // Проверяем, был ли файл изменен после сжатия
                    val timeDiff = metadata.lastModified - cachedExif.compressionTimestamp
                    if (timeDiff <= 20000) { // 20 секунд допустимая погрешность
                        LogUtil.processDebug("Файл уже сжат согласно кэшированным EXIF-данным")
                        return@withContext false
                    }
                }
            }
            
            // Для более сложных проверок используем основную логику
            return@withContext ImageProcessingUtil.shouldProcessImage(applicationContext, uri)
            
        } catch (e: Exception) {
            LogUtil.error(uri, "OPTIMIZED_CHECK", "Ошибка при оптимизированной проверке", e)
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
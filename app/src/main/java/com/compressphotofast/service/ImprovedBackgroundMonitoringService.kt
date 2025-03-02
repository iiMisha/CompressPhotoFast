package com.compressphotofast.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.compressphotofast.R
import com.compressphotofast.ui.MainActivity
import com.compressphotofast.util.Constants
import com.compressphotofast.util.FileUtil
import com.compressphotofast.worker.ImageCompressionWorker
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Сервис для мониторинга новых изображений в галерее, полностью базирующийся на
 * периодическом сканировании без использования ContentObserver
 */
@AndroidEntryPoint
class ImprovedBackgroundMonitoringService : Service() {

    @Inject
    lateinit var workManager: WorkManager
    
    private val executorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    
    // Глобальный блокировщик для предотвращения одновременной обработки
    private val processingLock = ReentrantLock()
    
    // Постоянное хранилище обработанных изображений
    private lateinit var processedImagesPrefs: SharedPreferences
    
    // Кэш обработанных изображений в памяти
    private val processedImageIds = ConcurrentHashMap<Long, Long>() // id -> timestamp
    private val processedImageNames = ConcurrentHashMap<String, Long>() // filename -> timestamp
    private val currentlyProcessingIds = ConcurrentHashMap<Long, Long>() // id -> timestamp
    
    // Отслеживание серии фотографий
    private var seriesDetectionActive = AtomicBoolean(false)
    private var lastPhotoTimestamp = 0L
    private val currentSeriesIds = ConcurrentHashMap<Long, Long>() // id -> timestamp
    private val SERIES_TIMEOUT_MS = 60000L // 60 секунд для серии
    
    // Счетчики для диагностики
    private var processedCounter = AtomicInteger(0)
    private var skippedCounter = AtomicInteger(0)
    private var duplicatePreventions = AtomicInteger(0)
    
    // Интервалы сканирования
    private val NORMAL_SCAN_INTERVAL_MS = 7000L // 7 секунд между обычными сканированиями
    private val SERIES_SCAN_INTERVAL_MS = 1500L // 1.5 секунды между сканированиями в режиме серии
    private val SCAN_AFTER_START_DELAY_MS = 1000L // 1 секунда после запуска
    
    // Максимальное количество одновременных задач
    private val MAX_CONCURRENT_TASKS = 4
    
    // Период хранения данных об обработанных изображениях
    private val IMAGE_HISTORY_RETENTION_DAYS = 7 // 7 дней
    
    // Ключи для SharedPreferences
    private val PREFS_NAME = "processed_images_store"
    private val KEY_PROCESSED_IDS = "processed_ids"
    private val KEY_PROCESSED_NAMES = "processed_names"
    private val KEY_LAST_CLEANUP = "last_cleanup"
    private val KEY_LAST_SCAN_TIME = "last_scan_time" // Новый ключ для времени последнего сканирования
    
    // Максимальное время в минутах для сканирования прошлого периода
    private val MAX_LOOKBACK_MINUTES = 24 * 60 // 24 часа в минутах
    
    // Флаг активного сканирования для предотвращения параллельного выполнения
    private val isScanningActive = AtomicBoolean(false)

    // Основной Runnable для периодического сканирования
    private val scanRunnable = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()
                val inSeriesMode = seriesDetectionActive.get()
                val timeSinceLastPhoto = now - lastPhotoTimestamp
                
                // Выход из режима серии, если прошло достаточно времени
                if (inSeriesMode && timeSinceLastPhoto > SERIES_TIMEOUT_MS) {
                    val seriesSize = currentSeriesIds.size
                    Timber.d("СЕРИЯ: Завершена серия из $seriesSize фото, длительность ${timeSinceLastPhoto/1000}с")
                    seriesDetectionActive.set(false)
                    currentSeriesIds.clear()
                }
                
                // Запуск сканирования
                if (!isScanningActive.getAndSet(true)) {
                    executorService.execute {
                        try {
                            // В режиме серии сканируем за последние 30 минут
                            // В обычном режиме - за последние 10 минут
                            val minutesToScan = if (inSeriesMode) 30 else 10
                            scanForNewImages(minutesToScan)
                            
                            // Сохраняем время последнего сканирования
                            saveLastScanTime()
                        } finally {
                            isScanningActive.set(false)
                        }
                    }
                }
                
                // Периодически очищаем историю
                if (now % (15 * 60 * 1000) < 1000) { // Примерно раз в 15 минут
                    cleanupHistory()
                }
                
                // Планируем следующее сканирование с интервалом в зависимости от режима
                val nextInterval = if (inSeriesMode) SERIES_SCAN_INTERVAL_MS else NORMAL_SCAN_INTERVAL_MS
                handler.postDelayed(this, nextInterval)
                
            } catch (e: Exception) {
                Timber.e(e, "ОШИБКА: В планировщике сканирования: ${e.message}")
                // Восстанавливаем цикл при ошибке
                handler.postDelayed(this, NORMAL_SCAN_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("СЕРВИС: ImprovedBackgroundMonitoringService создан")
        
        // Инициализация хранилища
        processedImagesPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Загрузка ранее обработанных изображений
        loadProcessedImagesFromStorage()
        
        // Запуск периодического сканирования с задержкой
        handler.postDelayed(scanRunnable, SCAN_AFTER_START_DELAY_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("СЕРВИС: ImprovedBackgroundMonitoringService запущен")
        
        val notification = createNotification()
        startForeground(Constants.NOTIFICATION_ID_BACKGROUND_SERVICE, notification)
        
        // Запускаем немедленное первое сканирование с учетом времени простоя
        executorService.execute {
            // Расчет времени для охвата периода с момента последнего сканирования
            val minutesToScan = calculateScanPeriod()
            Timber.d("ЗАПУСК: Сканирование после запуска за последние $minutesToScan минут")
            scanForNewImages(minutesToScan)
            
            // Сохраняем текущее время как время последнего сканирования
            saveLastScanTime()
        }
        
        return START_STICKY
    }

    /**
     * Расчет периода времени для сканирования с учетом времени, прошедшего с последнего сканирования
     */
    private fun calculateScanPeriod(): Int {
        val lastScanTimeSeconds = processedImagesPrefs.getLong(KEY_LAST_SCAN_TIME, 0) / 1000
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        
        // Если нет данных о последнем сканировании, используем стандартное значение в 60 минут
        if (lastScanTimeSeconds <= 0) {
            return 60
        }
        
        // Расчет разницы в минутах между текущим временем и последним сканированием
        val minutesSinceLastScan = ((currentTimeSeconds - lastScanTimeSeconds) / 60).toInt()
        
        // Ограничиваем максимальное время сканирования
        val period = minutesSinceLastScan.coerceAtMost(MAX_LOOKBACK_MINUTES)
        
        Timber.d("ВРЕМЯ: С момента последнего сканирования прошло $minutesSinceLastScan минут, будем сканировать за $period минут")
        
        // Возвращаем минимум 60 минут или фактическое время с момента последнего сканирования
        return period.coerceAtLeast(60)
    }
    
    /**
     * Сохранение времени последнего сканирования
     */
    private fun saveLastScanTime() {
        val currentTime = System.currentTimeMillis()
        processedImagesPrefs.edit()
            .putLong(KEY_LAST_SCAN_TIME, currentTime)
            .apply()
        
        Timber.d("ВРЕМЯ: Сохранено время последнего сканирования: ${formatTime(currentTime)}")
    }
    
    /**
     * Форматирование времени для логов
     */
    private fun formatTime(timeMillis: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(timeMillis))
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        executorService.shutdown()
        handler.removeCallbacks(scanRunnable)
        
        // Сохраняем данные перед закрытием
        saveProcessedImagesToStorage()
        
        Timber.d("СЕРВИС: ImprovedBackgroundMonitoringService уничтожен")
    }
    
    /**
     * Создание уведомления для foreground сервиса
     */
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_background_service))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    /**
     * Основная функция сканирования галереи за указанный период
     */
    private fun scanForNewImages(minutesBack: Int) {
        val scanStartTime = System.currentTimeMillis()
        Timber.d("СКАН: Начат (за $minutesBack мин.)")
        
        // Проверка на максимальное количество активных задач
        if (currentlyProcessingIds.size >= MAX_CONCURRENT_TASKS) {
            Timber.d("СКАН: Превышен лимит одновременных задач (${currentlyProcessingIds.size}/$MAX_CONCURRENT_TASKS)")
            return
        }
        
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        
        // Настраиваем фильтр по времени создания
        val timeAgo = (System.currentTimeMillis() / 1000) - (minutesBack * 60)
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(timeAgo.toString())
        
        // Сначала смотрим новейшие фото (сортировка по убыванию даты)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )
            
            val foundCount = cursor?.count ?: 0
            Timber.d("СКАН: Найдено $foundCount изображений")
            
            var processedInThisScan = 0
            var skippedInThisScan = 0
            
            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateAddedColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                
                while (it.moveToNext()) {
                    try {
                        val id = it.getLong(idColumn)
                        val name = it.getString(nameColumn)
                        val size = it.getLong(sizeColumn)
                        val dateAdded = it.getLong(dateAddedColumn)
                        
                        // Пропускаем уже сжатые изображения
                        if (name.lowercase().contains("_compressed")) {
                            continue
                        }
                        
                        // Используем глобальную блокировку для атомарной проверки и обработки
                        val result = processingLock.withLock {
                            processImageIfNeeded(id, name, size, dateAdded)
                        }
                        
                        if (result) {
                            processedInThisScan++
                            
                            // Проверяем режим серии и обновляем время последнего фото
                            val now = System.currentTimeMillis()
                            lastPhotoTimestamp = now
                            
                            // Если не в режиме серии, включаем его
                            if (!seriesDetectionActive.getAndSet(true)) {
                                Timber.d("СЕРИЯ: Начата новая серия с изображения $name (ID=$id)")
                                currentSeriesIds.clear()
                            }
                            
                            // Добавляем в текущую серию
                            currentSeriesIds[id] = now
                            
                            // Ограничиваем количество обрабатываемых за раз изображений
                            if (currentlyProcessingIds.size >= MAX_CONCURRENT_TASKS) {
                                Timber.d("СКАН: Достигнут лимит одновременной обработки, приостановка")
                                break
                            }
                        } else {
                            skippedInThisScan++
                        }
                        
                    } catch (e: Exception) {
                        Timber.e(e, "ОШИБКА: При обработке изображения из курсора: ${e.message}")
                    }
                }
            }
            
            val scanDuration = System.currentTimeMillis() - scanStartTime
            Timber.d("СКАН: Завершен за ${scanDuration}мс. Обработано: $processedInThisScan, пропущено: $skippedInThisScan")
            
            // Логируем общую статистику раз в 20 сканирований
            if (foundCount > 0 && (processedCounter.get() + skippedCounter.get()) % 20 == 0) {
                Timber.d("СТАТИСТИКА: Всего обработано: ${processedCounter.get()}, " +
                        "пропущено: ${skippedCounter.get()}, " +
                        "предотвращено дубликатов: ${duplicatePreventions.get()}, " +
                        "текущих задач: ${currentlyProcessingIds.size}, " +
                        "в серии: ${currentSeriesIds.size}")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "ОШИБКА: При сканировании галереи: ${e.message}")
        } finally {
            cursor?.close()
        }
    }
    
    /**
     * Проверка и обработка отдельного изображения
     * @return true если изображение отправлено на обработку, false если пропущено
     */
    private fun processImageIfNeeded(id: Long, name: String, size: Long, dateAdded: Long): Boolean {
        val now = System.currentTimeMillis()
        
        // Создаем стабильный идентификатор для изображения
        val workName = "compression_$id"
        
        // 1. Проверяем, обрабатывается ли это изображение в данный момент
        if (currentlyProcessingIds.containsKey(id)) {
            duplicatePreventions.incrementAndGet()
            return false
        }
        
        // 2. Проверяем, было ли это изображение обработано ранее по ID
        if (processedImageIds.containsKey(id)) {
            duplicatePreventions.incrementAndGet()
            return false
        }
        
        // 3. Проверяем, было ли это изображение обработано ранее по имени
        if (processedImageNames.containsKey(name)) {
            duplicatePreventions.incrementAndGet()
            return false
        }
        
        // 4. Проверяем, существует ли уже сжатая версия изображения
        val compressedName = FileUtil.createCompressedFileName(name)
        if (checkFileExistsInGallery(compressedName)) {
            Timber.d("ПРОПУСК: $name уже имеет сжатую версию: $compressedName")
            
            // Помечаем как обработанное, чтобы не проверять снова
            markAsProcessed(id, name)
            
            skippedCounter.incrementAndGet()
            return false
        }
        
        // Создаем URI для этого изображения
        val contentUri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
        )
        
        // 5. Сначала помечаем изображение как в процессе обработки
        currentlyProcessingIds[id] = now
        
        // 6. Запускаем задачу сжатия с политикой REPLACE
        Timber.d("СЖАТИЕ: Запуск задачи для $name (ID=$id)")
        
        val compressionWorkRequest = OneTimeWorkRequestBuilder<ImageCompressionWorker>()
            .setInputData(workDataOf(Constants.WORK_INPUT_IMAGE_URI to contentUri.toString()))
            .addTag(Constants.WORK_TAG_COMPRESSION)
            .build()
        
        workManager.beginUniqueWork(
            workName,
            ExistingWorkPolicy.REPLACE,
            compressionWorkRequest
        ).enqueue()
        
        // 7. Помечаем изображение как обработанное сразу после запуска задачи
        markAsProcessed(id, name)
        
        processedCounter.incrementAndGet()
        return true
    }
    
    /**
     * Пометка изображения как обработанного во всех кэшах
     */
    private fun markAsProcessed(id: Long, name: String) {
        val now = System.currentTimeMillis()
        
        // Добавляем в кэш обработанных ID
        processedImageIds[id] = now
        
        // Добавляем в кэш обработанных имен файлов
        if (name.isNotEmpty()) {
            processedImageNames[name] = now
        }
        
        // Удаляем из списка обрабатываемых
        currentlyProcessingIds.remove(id)
        
        // Сохраняем в постоянное хранилище с задержкой
        handler.postDelayed({
            saveProcessedImagesToStorage()
        }, 5000) // Сохраняем с задержкой в 5 секунд для оптимизации
    }
    
    /**
     * Проверка существования файла в галерее по имени
     */
    private fun checkFileExistsInGallery(fileName: String): Boolean {
        var cursor: Cursor? = null
        try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(fileName)
            
            cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )
            
            return cursor != null && cursor.count > 0
        } catch (e: Exception) {
            Timber.e(e, "ОШИБКА: При проверке существования файла: $fileName")
            return false
        } finally {
            cursor?.close()
        }
    }
    
    /**
     * Загрузка данных об обработанных изображениях из постоянного хранилища
     */
    private fun loadProcessedImagesFromStorage() {
        try {
            // Загрузка ID обработанных изображений
            val idsJsonString = processedImagesPrefs.getString(KEY_PROCESSED_IDS, null)
            if (!idsJsonString.isNullOrEmpty()) {
                val idsJson = JSONObject(idsJsonString)
                val keys = idsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val timestamp = idsJson.getLong(key)
                    processedImageIds[key.toLong()] = timestamp
                }
            }
            
            // Загрузка имен обработанных файлов
            val namesJsonString = processedImagesPrefs.getString(KEY_PROCESSED_NAMES, null)
            if (!namesJsonString.isNullOrEmpty()) {
                val namesJson = JSONObject(namesJsonString)
                val keys = namesJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val timestamp = namesJson.getLong(key)
                    processedImageNames[key] = timestamp
                }
            }
            
            // Получаем и логируем время последнего сканирования
            val lastScanTime = processedImagesPrefs.getLong(KEY_LAST_SCAN_TIME, 0)
            if (lastScanTime > 0) {
                val timeSinceLastScan = (System.currentTimeMillis() - lastScanTime) / 1000 / 60
                Timber.d("ВРЕМЯ: Последнее сканирование было ${formatTime(lastScanTime)} ($timeSinceLastScan минут назад)")
            }
            
            Timber.d("ХРАНИЛИЩЕ: Загружено ${processedImageIds.size} ID и ${processedImageNames.size} имен файлов")
            
        } catch (e: Exception) {
            Timber.e(e, "ОШИБКА: При загрузке данных об обработанных изображениях: ${e.message}")
            // В случае ошибки очищаем кэши
            processedImageIds.clear()
            processedImageNames.clear()
        }
    }
    
    /**
     * Сохранение данных об обработанных изображениях в постоянное хранилище
     */
    private fun saveProcessedImagesToStorage() {
        try {
            // Сохранение ID обработанных изображений
            val idsJson = JSONObject()
            processedImageIds.forEach { (id, timestamp) ->
                idsJson.put(id.toString(), timestamp)
            }
            
            // Сохранение имен обработанных файлов
            val namesJson = JSONObject()
            processedImageNames.forEach { (name, timestamp) ->
                namesJson.put(name, timestamp)
            }
            
            processedImagesPrefs.edit()
                .putString(KEY_PROCESSED_IDS, idsJson.toString())
                .putString(KEY_PROCESSED_NAMES, namesJson.toString())
                .putLong(KEY_LAST_CLEANUP, System.currentTimeMillis())
                .apply()
            
            Timber.d("ХРАНИЛИЩЕ: Сохранено ${processedImageIds.size} ID и ${processedImageNames.size} имен файлов")
            
        } catch (e: Exception) {
            Timber.e(e, "ОШИБКА: При сохранении данных об обработанных изображениях: ${e.message}")
        }
    }
    
    /**
     * Очистка устаревших записей в истории обработанных изображений
     */
    private fun cleanupHistory() {
        try {
            val now = System.currentTimeMillis()
            val lastCleanup = processedImagesPrefs.getLong(KEY_LAST_CLEANUP, 0)
            
            // Очищаем не чаще одного раза в день
            if (now - lastCleanup < TimeUnit.DAYS.toMillis(1)) {
                return
            }
            
            val expiryTime = now - TimeUnit.DAYS.toMillis(IMAGE_HISTORY_RETENTION_DAYS.toLong())
            var removedIds = 0
            var removedNames = 0
            
            // Очистка устаревших ID
            val idsToRemove = processedImageIds.entries
                .filter { it.value < expiryTime }
                .map { it.key }
            
            idsToRemove.forEach { id ->
                processedImageIds.remove(id)
                removedIds++
            }
            
            // Очистка устаревших имен файлов
            val namesToRemove = processedImageNames.entries
                .filter { it.value < expiryTime }
                .map { it.key }
            
            namesToRemove.forEach { name ->
                processedImageNames.remove(name)
                removedNames++
            }
            
            if (removedIds > 0 || removedNames > 0) {
                Timber.d("ОЧИСТКА: Удалено $removedIds устаревших ID и $removedNames имен файлов")
                saveProcessedImagesToStorage()
            }
            
        } catch (e: Exception) {
            Timber.e(e, "ОШИБКА: При очистке истории обработанных изображений: ${e.message}")
        }
    }
} 
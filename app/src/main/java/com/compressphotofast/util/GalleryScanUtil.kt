package com.compressphotofast.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.BatchMediaStoreUtil
import com.compressphotofast.util.PerformanceMonitor

/**
 * Класс для централизованной работы со сканированием галереи
 */
object GalleryScanUtil {
    
    /**
     * Результат сканирования галереи
     */
    data class ScanResult(
        val processedCount: Int = 0,
        val skippedCount: Int = 0,
        val foundUris: List<Uri> = emptyList()
    )
    
    /**
     * Сканирует галерею для поиска недавно добавленных изображений
     * @param context Контекст приложения
     * @param timeWindowSeconds Временное окно в секундах для поиска изображений (по умолчанию 5 минут)
     * @param checkProcessable Проверять, подлежит ли изображение обработке
     * @return Результат сканирования
     */
    suspend fun scanRecentImages(
        context: Context, 
        timeWindowSeconds: Int = Constants.RECENT_SCAN_WINDOW_SECONDS.toInt(), // 5 минут по умолчанию
        checkProcessable: Boolean = true
    ): ScanResult = withContext(Dispatchers.IO) {
        var processedCount = 0
        var skippedCount = 0
        val foundUris = mutableListOf<Uri>()
        
        try {
            // Проверяем состояние автоматического сжатия
            if (checkProcessable && !SettingsManager.getInstance(context).isAutoCompressionEnabled()) {
                LogUtil.processDebug("GalleryScanUtil: автоматическое сжатие отключено, пропускаем сканирование")
                return@withContext ScanResult(0, 0, emptyList())
            }
            
            // Запрашиваем последние изображения из MediaStore
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE
            )
            
            // Ищем фотографии, созданные за последнее заданное время
            val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
            val currentTimeInSeconds = System.currentTimeMillis() / 1000
            val timeAgo = currentTimeInSeconds - timeWindowSeconds
            val selectionArgs = arrayOf(timeAgo.toString())
            
            // Сортируем по времени создания (сначала новые)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                
                val totalImages = cursor.count
                LogUtil.processDebug("GalleryScanUtil: найдено $totalImages изображений за последние ${timeWindowSeconds / 60} минут")
                
                // Сначала собираем все URI для пакетной обработки
                val allUris = mutableListOf<Uri>()
                val uriSizeMap = mutableMapOf<Uri, Long>()
                val uriNameMap = mutableMapOf<Uri, String>()
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = if (nameColumn != -1) cursor.getString(nameColumn) else "unknown"
                    val size = if (sizeColumn != -1) cursor.getLong(sizeColumn) else 0L
                    
                    // Пропускаем слишком маленькие файлы
                    if (size < Constants.MIN_FILE_SIZE) {
                        LogUtil.processDebug("GalleryScanUtil: пропуск маленького файла: $name ($size байт)")
                        skippedCount++
                        continue
                    }
                    
                    // Пропускаем слишком большие файлы
                    if (size > Constants.MAX_FILE_SIZE) {
                        LogUtil.processDebug("GalleryScanUtil: пропуск большого файла: $name (${size / (1024 * 1024)} MB)")
                        skippedCount++
                        continue
                    }
                    
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    
                    // Проверяем, не находится ли URI уже в процессе обработки
                    if (checkProcessable && UriProcessingTracker.isImageBeingProcessed(contentUri)) {
                        LogUtil.processDebug("GalleryScanUtil: URI $contentUri уже в процессе обработки, пропускаем")
                        skippedCount++
                        continue
                    }
                    
                    // Дополнительная проверка: исключаем файлы, которые были недавно изменены
                    // чтобы избежать конфликта с MediaStoreObserver
                    val lastModified = UriUtil.getFileLastModified(context, contentUri)
                    val currentTime = System.currentTimeMillis()
                    if (lastModified > 0 && (currentTime - lastModified < 3000L)) { // 3 секунды
                        LogUtil.processDebug("GalleryScanUtil: файл $contentUri был недавно изменен, пропускаем для избежания конфликта")
                        skippedCount++
                        continue
                    }
                    
                    allUris.add(contentUri)
                    uriSizeMap[contentUri] = size
                    uriNameMap[contentUri] = name
                }
                
                // Используем пакетную предзагрузку метаданных для оптимизации
                if (checkProcessable && allUris.isNotEmpty()) {
                    LogUtil.processDebug("GalleryScanUtil: пакетная предзагрузка метаданных для ${allUris.size} URI")
                    
                    // Предзагружаем метаданные пакетом для кэширования
                    PerformanceMonitor.measureBatchMetadata {
                        BatchMediaStoreUtil.getBatchFileMetadata(context, allUris)
                    }
                    
                    // Теперь проверяем каждый URI, используя кэшированные данные
                    for (uri in allUris) {
                        if (StatsTracker.shouldProcessImage(context, uri)) {
                            foundUris.add(uri)
                            processedCount++
                        } else {
                            LogUtil.processDebug("GalleryScanUtil: изображение не требует обработки: $uri")
                            skippedCount++
                        }
                    }
                } else if (!checkProcessable) {
                    // Если проверка не нужна, добавляем все URI
                    foundUris.addAll(allUris)
                    processedCount = allUris.size
                }
                
                LogUtil.processDebug("GalleryScanUtil: сканирование завершено. Найдено: $processedCount, Пропущено: $skippedCount")
            }
        } catch (e: Exception) {
            LogUtil.errorWithException("SCAN_GALLERY", e)
        }
        
        ScanResult(processedCount, skippedCount, foundUris)
    }
    
    /**
     * Сканирует галерею для поиска необработанных изображений за последние 24 часа
     * @param context Контекст приложения
     * @return Результат сканирования
     */
    suspend fun scanDayOldImages(context: Context): ScanResult = withContext(Dispatchers.IO) {
        // Вызываем сканирование с окном в 24 часа (86400 секунд)
        scanRecentImages(context, 86400)
    }
} 
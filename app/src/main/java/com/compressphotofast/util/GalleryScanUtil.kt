package com.compressphotofast.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

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
                Timber.d("GalleryScanUtil: автоматическое сжатие отключено, пропускаем сканирование")
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
                Timber.d("GalleryScanUtil: найдено $totalImages изображений за последние ${timeWindowSeconds / 60} минут")
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = if (nameColumn != -1) cursor.getString(nameColumn) else "unknown"
                    val size = if (sizeColumn != -1) cursor.getLong(sizeColumn) else 0L
                    
                    // Пропускаем слишком маленькие файлы
                    if (size < Constants.MIN_FILE_SIZE) {
                        Timber.d("GalleryScanUtil: пропуск маленького файла: $name ($size байт)")
                        skippedCount++
                        continue
                    }
                    
                    // Пропускаем слишком большие файлы
                    if (size > Constants.MAX_FILE_SIZE) {
                        Timber.d("GalleryScanUtil: пропуск большого файла: $name (${size / (1024 * 1024)} MB)")
                        skippedCount++
                        continue
                    }
                    
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    
                    // Проверяем, не находится ли URI уже в процессе обработки
                    if (checkProcessable && UriProcessingTracker.isImageBeingProcessed(contentUri)) {
                        Timber.d("GalleryScanUtil: URI $contentUri уже в процессе обработки, пропускаем")
                        skippedCount++
                        continue
                    }
                    
                    // Проверяем, не было ли изображение уже обработано (по EXIF)
                    if (checkProcessable && !StatsTracker.shouldProcessImage(context, contentUri)) {
                        Timber.d("GalleryScanUtil: изображение не требует обработки: $contentUri")
                        skippedCount++
                        continue
                    }
                    
                    // Добавляем URI в список найденных
                    foundUris.add(contentUri)
                    processedCount++
                }
                
                Timber.d("GalleryScanUtil: сканирование завершено. Найдено: $processedCount, Пропущено: $skippedCount")
            }
        } catch (e: Exception) {
            Timber.e(e, "GalleryScanUtil: ошибка при сканировании галереи")
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
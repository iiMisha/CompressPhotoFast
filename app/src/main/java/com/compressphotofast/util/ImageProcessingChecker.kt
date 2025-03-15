package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Класс для централизованной проверки необходимости обработки изображений
 */
object ImageProcessingChecker {

    /**
     * Проверяет, требуется ли обработка изображения
     * @param context контекст
     * @param uri URI изображения
     * @return true если изображение требует обработки, false в противном случае
     */
    suspend fun shouldProcessImage(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверяем, существует ли URI
            try {
                val checkCursor = context.contentResolver.query(uri, null, null, null, null)
                val exists = checkCursor?.use { it.count > 0 } ?: false
                
                if (!exists) {
                    Timber.d("URI не существует: $uri")
                    return@withContext false
                }
            } catch (e: Exception) {
                Timber.d("Не удалось проверить существование URI: $uri, ${e.message}")
                return@withContext false
            }
            
            // Проверяем, находится ли URI уже в списке обрабатываемых
            if (UriProcessingTracker.isImageBeingProcessed(uri.toString())) {
                Timber.d("URI уже в списке обрабатываемых: $uri")
                return@withContext false
            }
            
            // Получаем размер файла - переместили эту проверку до проверки EXIF
            val fileSize = FileUtil.getFileSize(context, uri)
            
            // Если файл слишком мал, пропускаем его
            if (fileSize < Constants.MIN_PROCESSABLE_FILE_SIZE) {
                Timber.d("Файл слишком мал (${fileSize/1024}KB < минимального порога ${Constants.MIN_PROCESSABLE_FILE_SIZE/1024}KB): $uri")
                return@withContext false
            }
            
            // НОВАЯ ПРОВЕРКА: Обработка для файлов больше 1.5 МБ имеет приоритет
            if (fileSize > Constants.TEST_COMPRESSION_THRESHOLD_SIZE) {
                Timber.d("Файл больше 1.5 МБ (${fileSize / (1024 * 1024)}МБ), будет проведено тестовое сжатие в RAM")
                return@withContext true
            }
            
            // Только после проверки размера делаем проверку на EXIF маркер
            if (StatsTracker.isImageProcessed(context, uri)) {
                Timber.d("Изображение уже обработано (найден EXIF маркер): $uri")
                return@withContext false
            }
            
            // Проверяем, является ли файл временным или в процессе записи
            if (isFilePending(context, uri)) {
                Timber.d("Файл является временным или в процессе записи: $uri")
                return@withContext false
            }
            
            // Проверяем MIME тип
            val mimeType = FileUtil.getMimeType(context, uri)
            if (!isProcessableMimeType(mimeType)) {
                Timber.d("Неподдерживаемый MIME тип: $mimeType для URI: $uri")
                return@withContext false
            }
            
            // Проверяем, не является ли файл скриншотом, если настройка запрещает обработку скриншотов
            if (!SettingsManager.getInstance(context).shouldProcessScreenshots()) {
                if (FileUtil.isScreenshot(context, uri)) {
                    Timber.d("Файл является скриншотом, обработка скриншотов отключена: $uri")
                    return@withContext false
                }
            }
            
            // Если выбрана ручная обработка, проверяем что изображение не должно обрабатываться автоматически
            if (FileUtil.isManualCompression(context)) {
                Timber.d("Активирован режим ручной обработки, автоматическая обработка пропускается: $uri")
                return@withContext false
            }
            
            // Все проверки пройдены, изображение требует обработки
            Timber.d("Изображение требует обработки: $uri")
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке необходимости обработки изображения: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Проверяет, является ли файл временным (IS_PENDING = 1)
     */
    private suspend fun isFilePending(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING)
            context.contentResolver.query(
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
     * Проверяет, поддерживается ли MIME тип для обработки
     */
    private fun isProcessableMimeType(mimeType: String?): Boolean {
        if (mimeType == null) return false
        
        return when {
            mimeType.startsWith("image/jpeg") -> true
            mimeType.startsWith("image/jpg") -> true
            mimeType.startsWith("image/png") -> true
            mimeType.startsWith("image/webp") -> false // WebP уже сжат
            mimeType.startsWith("image/gif") -> false // GIF - анимация
            mimeType.startsWith("image/") -> true // Другие изображения
            else -> false // Другие типы файлов
        }
    }
} 
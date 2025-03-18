package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Date

/**
 * Класс для централизованной проверки необходимости обработки изображений
 */
object ImageProcessingChecker {

    /**
     * Проверяет, требуется ли обработка изображения
     * @param context контекст
     * @param uri URI изображения
     * @param forceProcess Принудительная обработка, игнорируя режим ручной обработки
     * @return true если изображение требует обработки, false в противном случае
     */
    suspend fun shouldProcessImage(context: Context, uri: Uri, forceProcess: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isUriExists(context, uri)) {
                return@withContext false
            }
            
            // Получаем размер файла
            val fileSize = FileUtil.getFileSize(context, uri)
            
            // Если файл слишком мал, пропускаем его
            if (fileSize < Constants.MIN_PROCESSABLE_FILE_SIZE) {
                Timber.d("Файл слишком мал (${fileSize/1024}KB < минимального порога ${Constants.MIN_PROCESSABLE_FILE_SIZE/1024}KB): $uri")
                return@withContext false
            }
            
            // ПРИОРИТЕТНАЯ ПРОВЕРКА: наличие EXIF-маркера сжатия и даты модификации
            // Эта проверка выполняется перед всеми другими логическими проверками для уменьшения нагрузки
            Timber.d("Выполняется приоритетная проверка EXIF-маркера сжатия: $uri")
            val hasCompressMarker = ExifUtil.isImageCompressed(context, uri)
            if (hasCompressMarker) {
                Timber.d("EXIF-маркер сжатия найден, проверяем дату модификации: $uri")
                // Проверяем, был ли файл модифицирован после сжатия
                val wasModifiedAfterCompression = wasFileModifiedAfterCompression(context, uri)
                
                if (wasModifiedAfterCompression) {
                    Timber.d("Файл был модифицирован после сжатия, требуется повторная обработка: $uri")
                    return@withContext true
                } else {
                    Timber.d("Файл был сжат ранее и не модифицирован после этого, пропускаем: $uri")
                    return@withContext false
                }
            } else {
                Timber.d("EXIF-маркер сжатия не найден, файл требует обработки: $uri")
            }
            
            // Все последующие проверки выполняются только если не найден EXIF-маркер сжатия
            
            // Проверяем, находится ли URI уже в списке обрабатываемых
            if (UriProcessingTracker.isImageBeingProcessed(uri.toString())) {
                Timber.d("URI уже в списке обрабатываемых: $uri")
                return@withContext false
            }
            
            // Проверяем, не находится ли файл в директории приложения
            val path = FileUtil.getFilePathFromUri(context, uri) ?: ""
            if (isInAppDirectory(path)) {
                Timber.d("Файл находится в директории приложения: $path")
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
            // Но пропускаем эту проверку, если установлен флаг принудительной обработки
            if (!forceProcess && FileUtil.isManualCompression(context)) {
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
     * Проверяет, был ли файл модифицирован после сжатия
     * Сравнивает дату модификации файла с датой сжатия из EXIF
     * @param context контекст
     * @param uri URI изображения
     * @return true если файл был модифицирован после сжатия, false в противном случае
     */
    suspend fun wasFileModifiedAfterCompression(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Получаем дату последней модификации файла из MediaStore
            val lastModified = getFileModificationDate(context, uri)
            if (lastModified == null) {
                Timber.d("Не удалось получить дату модификации файла, возвращаем false: $uri")
                return@withContext false
            }
            
            // Получаем дату сжатия из EXIF (теперь это timestamp в миллисекундах)
            val compressionTimeMs = ExifUtil.getCompressionDateFromExif(context, uri)
            if (compressionTimeMs == null) {
                Timber.d("Дата сжатия не найдена в EXIF, возвращаем true (требуется проверка): $uri")
                return@withContext true // Если дата сжатия не найдена, но есть маркер, требуется проверка
            }
            
            // Добавляем небольшой запас (10 секунд) чтобы компенсировать разницу в точности timestamps
            val compressionTimeMsWithBuffer = compressionTimeMs + 10000
            
            // Сравниваем даты с учетом буфера
            val wasModified = lastModified > compressionTimeMsWithBuffer
            
            // Логируем для дебага в формате, понятном человеку
            val compressionDate = Date(compressionTimeMs)
            val lastModifiedDate = Date(lastModified)
            Timber.d("Сравнение дат: " +
                    "Дата сжатия (EXIF): $compressionDate (${compressionTimeMs}ms), " +
                    "Дата модификации (MediaStore): $lastModifiedDate (${lastModified}ms), " +
                    "Разница: ${(lastModified - compressionTimeMs) / 1000} сек.")
            
            if (wasModified) {
                Timber.d("Файл был модифицирован после сжатия")
            } else {
                Timber.d("Файл НЕ был модифицирован после сжатия")
            }
            
            return@withContext wasModified
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке модификации файла после сжатия: ${e.message}")
            return@withContext true // В случае ошибки считаем, что файл нужно проверить
        }
    }
    
    /**
     * Получает дату последней модификации файла из MediaStore
     * @param context контекст
     * @param uri URI изображения
     * @return дата модификации в миллисекундах или null, если не удалось получить
     */
    private suspend fun getFileModificationDate(context: Context, uri: Uri): Long? = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.Images.Media.DATE_MODIFIED)
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dateModifiedColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    // DATE_MODIFIED хранится в секундах, умножаем на 1000 для миллисекунд
                    val dateModified = cursor.getLong(dateModifiedColumnIndex) * 1000
                    Timber.d("Дата модификации файла: ${Date(dateModified)} (${dateModified}ms)")
                    return@withContext dateModified
                }
            }
            return@withContext null
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении даты модификации файла: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Проверка, было ли изображение уже обработано (для использования в ImageCompressionWorker)
     * Централизованная версия логики из ImageCompressionWorker.isImageAlreadyProcessedExceptSize
     * @param context контекст
     * @param uri URI изображения
     * @return true если изображение уже обработано, false в противном случае
     */
    suspend fun isAlreadyProcessed(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isUriExists(context, uri)) {
                return@withContext true
            }
            
            // Получаем размер файла для проверок
            val fileSize = FileUtil.getFileSize(context, uri)
            
            // Проверяем EXIF маркер
            val isProcessed = StatsTracker.isImageProcessed(context, uri)
            if (isProcessed) {
                // НОВАЯ ПРОВЕРКА: Если файл был сжат ранее, проверяем, не был ли он модифицирован после сжатия
                val wasModifiedAfterCompression = wasFileModifiedAfterCompression(context, uri)
                if (wasModifiedAfterCompression) {
                    Timber.d("Изображение было сжато ранее, но модифицировано после этого: $uri")
                    return@withContext false // Файл нужно обработать повторно
                }
                
                Timber.d("Изображение уже сжато (найден EXIF маркер) и не модифицировано после этого: $uri")
                return@withContext true
            }

            // Проверяем путь к файлу
            val path = FileUtil.getFilePathFromUri(context, uri) ?: ""
            if (isInAppDirectory(path)) {
                Timber.d("Файл находится в директории приложения: $path")
                return@withContext true
            }
            
            // Если файл уже достаточно мал (меньше 1MB)
            val optimumSize = Constants.OPTIMUM_FILE_SIZE
            if (fileSize < optimumSize) {
                Timber.d("Файл уже достаточно мал (${fileSize/1024}KB < ${optimumSize/1024}KB), пропускаем")
                return@withContext true
            }
            
            return@withContext false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса обработки файла: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Проверка, является ли файл временным или в процессе записи
     * @param context контекст
     * @param uri URI изображения
     * @return true если файл временный, false в противном случае
     */
    private suspend fun isFilePending(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверяем, установлен ли флаг IS_PENDING
            val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING)
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val isPendingColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                    return@withContext cursor.getInt(isPendingColumnIndex) == 1
                }
            }
            
            return@withContext false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке статуса IS_PENDING: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Проверка MIME типа на поддержку
     * @param mimeType MIME тип файла
     * @return true если MIME тип поддерживается, false в противном случае
     */
    private fun isProcessableMimeType(mimeType: String?): Boolean {
        if (mimeType == null) return false
        
        return mimeType.startsWith("image/") && 
               (mimeType.contains("jpeg") || 
                mimeType.contains("jpg") || 
                mimeType.contains("png"))
    }

    private suspend fun isUriExists(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val exists = context.contentResolver.query(uri, null, null, null, null)?.use { it.count > 0 } ?: false
            if (!exists) {
                Timber.d("URI не существует: $uri")
            }
            return@withContext exists
        } catch (e: Exception) {
            Timber.d("Не удалось проверить существование URI: $uri, ${e.message}")
            return@withContext false
        }
    }

    private fun isInAppDirectory(path: String): Boolean {
        return path.contains("/${Constants.APP_DIRECTORY}/") || 
               (path.contains("content://media/external/images/media") && 
                path.contains(Constants.APP_DIRECTORY))
    }
} 
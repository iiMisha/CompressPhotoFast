package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
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
import androidx.documentfile.provider.DocumentFile

/**
 * Централизованный класс для проверки необходимости обработки изображений
 */
object ImageProcessingChecker {

    /**
     * Проверяет, нужно ли обрабатывать изображение
     * Централизованная версия логики для всего приложения
     */
    suspend fun shouldProcessImage(context: Context, uri: Uri, forceProcess: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isUriExists(context, uri)) {
                return@withContext false
            }
            
            // Проверяем, не является ли файл переименованным оригиналом
            val fileName = FileUtil.getFileNameFromUri(context, uri) ?: ""
            if (fileName.contains("_original.")) {
                Timber.d("Файл является переименованным оригиналом, пропускаем: $uri")
                return@withContext false
            }
            
            // Проверяем, включено ли автоматическое сжатие
            val settingsManager = SettingsManager.getInstance(context)
            val isAutoEnabled = settingsManager.isAutoCompressionEnabled()
            
            // Если автосжатие отключено и нет флага принудительной обработки, 
            // возвращаем false
            if (!isAutoEnabled && !forceProcess) {
                return@withContext false
            }
            
            // Проверяем, не является ли изображение скриншотом
            if (FileUtil.isScreenshot(context, uri)) {
                return@withContext false
            }
            
            // Проверяем, было ли изображение уже обработано
            if (isAlreadyProcessed(context, uri)) {
                return@withContext false
            }
            
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке необходимости обработки изображения: $uri")
            return@withContext false
        }
    }
    
    /**
     * Проверяет наличие EXIF-маркера сжатия и было ли изображение модифицировано после сжатия
     * @param context контекст
     * @param uri URI изображения
     * @return true если изображение сжато и не модифицировано после сжатия, false в противном случае
     */
    private suspend fun isImageCompressedAndNotModified(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.d("Выполняется приоритетная проверка EXIF-маркера сжатия: $uri")
            
            // Проверяем наличие EXIF-маркера сжатия
            val hasCompressMarker = ExifUtil.isImageCompressed(context, uri)
            if (!hasCompressMarker) {
                Timber.d("EXIF-маркер сжатия не найден, файл требует обработки: $uri")
                return@withContext false
            }
            
            Timber.d("EXIF-маркер сжатия найден, проверяем дату модификации: $uri")
            // Проверяем, был ли файл модифицирован после сжатия
            val wasModifiedAfterCompression = wasFileModifiedAfterCompression(context, uri)
            
            if (wasModifiedAfterCompression) {
                Timber.d("Файл был модифицирован после сжатия, требуется повторная обработка: $uri")
                return@withContext false
            } else {
                Timber.d("Файл был сжат ранее и не модифицирован после этого, пропускаем: $uri")
                return@withContext true
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке EXIF-маркера сжатия: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Проверяет основные условия для обработки изображения
     * @param context контекст
     * @param uri URI изображения
     * @return true если изображение должно быть обработано, false в противном случае
     */
    private suspend fun shouldBeProcessed(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
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
            
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке базовых условий обработки: ${e.message}")
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
                // Проверяем, есть ли маркер сжатия вообще
                val isCompressed = ExifUtil.isImageCompressed(context, uri)
                if (isCompressed) {
                    Timber.w("Файл имеет маркер сжатия, но дата сжатия не найдена в EXIF. " +
                             "Возможно старый формат маркера. Рекомендуется повторное сжатие. URI: $uri")
                    return@withContext true // Если дата сжатия не найдена, а маркер есть, требуется проверка
                } else {
                    Timber.d("Дата сжатия и маркер не найдены в EXIF, файл не был сжат: $uri")
                    return@withContext true // Файл не был сжат, требуется обработка
                }
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
                Timber.d("Файл был модифицирован после сжатия: $uri")
            } else {
                Timber.d("Файл НЕ был модифицирован после сжатия: $uri")
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
            // Специальная обработка для URI документов из галереи
            if (uri.toString().startsWith("content://com.android.providers.media.documents/")) {
                Timber.d("Обнаружен URI документа медиа-провайдера, используем специальную обработку")
                
                // Получаем ID документа
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val id = split[1]
                    
                    // Для изображений
                    if (type == "image") {
                        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        val selection = MediaStore.Images.Media._ID + "=?"
                        val selectionArgs = arrayOf(id)
                        
                        context.contentResolver.query(
                            contentUri,
                            arrayOf(MediaStore.Images.Media.DATE_MODIFIED),
                            selection,
                            selectionArgs,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val dateModifiedColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                                // DATE_MODIFIED хранится в секундах, умножаем на 1000 для миллисекунд
                                val dateModified = cursor.getLong(dateModifiedColumnIndex) * 1000
                                Timber.d("Дата модификации файла из MediaStore: ${Date(dateModified)} (${dateModified}ms)")
                                return@withContext dateModified
                            }
                        }
                    }
                }
            }
            
            // Стандартный подход для всех остальных URI
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
            
            // Если не удалось получить дату, пробуем использовать DocumentFile
            try {
                val documentFile = DocumentFile.fromSingleUri(context, uri)
                if (documentFile != null && documentFile.exists()) {
                    val lastModified = documentFile.lastModified()
                    if (lastModified > 0) {
                        Timber.d("Дата модификации получена через DocumentFile: ${Date(lastModified)} (${lastModified}ms)")
                        return@withContext lastModified
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при получении даты через DocumentFile: ${e.message}")
            }
            
            Timber.d("Не удалось получить дату модификации для URI: $uri")
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
                Timber.d("URI не существует, считаем файл обработанным: $uri")
                return@withContext true
            }
            
            // Получаем размер файла для проверок
            val fileSize = FileUtil.getFileSize(context, uri)
            
            // Проверяем путь к файлу - если файл находится в директории приложения, считаем его обработанным
            val path = FileUtil.getFilePathFromUri(context, uri) ?: ""
            if (isInAppDirectory(path)) {
                Timber.d("Файл находится в директории приложения, считаем его обработанным: $path")
                return@withContext true
            }
            
            // Проверяем EXIF маркер
            val hasCompressMarker = ExifUtil.isImageCompressed(context, uri)
            
            if (hasCompressMarker) {
                Timber.d("Найден EXIF маркер сжатия, проверяем модификацию после сжатия: $uri")
                
                // Если файл был сжат ранее, проверяем, не был ли он модифицирован после сжатия
                val wasModifiedAfterCompression = wasFileModifiedAfterCompression(context, uri)
                if (wasModifiedAfterCompression) {
                    Timber.d("Изображение было сжато ранее, но модифицировано после этого, требуется повторная обработка: $uri")
                    return@withContext false // Файл нужно обработать повторно
                }
                
                Timber.d("Изображение уже сжато (найден EXIF маркер) и не модифицировано после этого: $uri")
                return@withContext true
            } else {
                Timber.d("EXIF маркер сжатия не найден: $uri")
            }

            // Если файл уже достаточно мал (меньше оптимального размера)
            val optimumSize = Constants.OPTIMUM_FILE_SIZE
            if (fileSize < optimumSize) {
                Timber.d("Файл уже достаточно мал (${fileSize/1024}KB < ${optimumSize/1024}KB), пропускаем: $uri")
                return@withContext true
            }
            
            // В остальных случаях считаем, что файл требует обработки
            Timber.d("Файл требует обработки: $uri")
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
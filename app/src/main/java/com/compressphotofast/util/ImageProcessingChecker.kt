package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.compressphotofast.util.LogUtil
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Date
import androidx.documentfile.provider.DocumentFile
import com.compressphotofast.util.FileOperationsUtil
import com.compressphotofast.util.UriUtil
import com.compressphotofast.util.OptimizedCacheUtil
import com.compressphotofast.util.PerformanceMonitor

/**
 * Централизованный класс для проверки необходимости обработки изображений
 * Предотвращает дублирование логики в разных частях приложения
 */
object ImageProcessingChecker {

    private val TAG = "ImageProcessingChecker"

    /**
     * Нормализует путь к файлу для надежного сравнения
     * Унифицирует разделители, убирает дубли, приводит к lowercase
     */
    private fun normalizePath(path: String): String {
        return path
            .trim()
            .replace("\\", "/")  // Унифицируем разделители
            .replace("//", "/")  // Убираем дубли
            .lowercase()         // Case-insensitive
    }

    /**
     * Проверяет, находится ли файл в директории приложения с нормализованным путем
     * Использует более надежную проверку чем простое contains()
     */
    private fun isInAppDirectoryNormalized(path: String?): Boolean {
        if (path == null) return false

        val normalized = normalizePath(path)

        // Проверяем точное совпадение patterns
        val appDirPatterns = listOf(
            "/pictures/compressphotofast/",
            "/storage/emulated/0/pictures/compressphotofast/",
            "compressphotofast"
        )

        // Проверяем каждый pattern
        for (pattern in appDirPatterns) {
            if (normalized.contains(pattern)) {
                // Дополнительная проверка: не должно быть "documents"
                // Это предотвращает false positives для файлов в /documents/
                if (!normalized.contains("/documents/")) {
                    return true
                }
            }
        }

        return false
    }
    
    /**
     * ОСНОВНОЙ ПУБЛИЧНЫЙ МЕТОД
     * Проверяет, нужно ли обрабатывать изображение
     * Централизованная версия логики для всего приложения
     * 
     * @param context Контекст
     * @param uri URI изображения
     * @param forceProcess Принудительная обработка, даже если автосжатие отключено
     * @return true если изображение нужно обработать, false в противном случае
     */
    suspend fun shouldProcessImage(context: Context, uri: Uri, forceProcess: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = isProcessingRequired(context, uri, forceProcess)
            if (!result.processingRequired) {
                // Если причина пропуска - фото из мессенджера, мы все равно должны запустить воркер,
                // чтобы он мог записать EXIF-теги. Воркер сам пропустит сжатие.
                if (result.reason == ProcessingSkipReason.MESSENGER_PHOTO) {
                    LogUtil.processDebug("Изображение из мессенджера, разрешаем запуск воркера для записи EXIF.")
                    return@withContext true
                }
                LogUtil.processDebug("Изображение не требует обработки: ${result.reason}")
            }
            return@withContext result.processingRequired
        } catch (e: Exception) {
            LogUtil.error(uri, "CHECK_PROCESSING", "Ошибка при проверке необходимости обработки изображения", e)
            return@withContext false
        }
    }
    
    /**
     * Проверяет основные условия и настройки для обработки изображения
     * @return true если прошли все базовые проверки
     */
    private suspend fun passesBasicChecks(context: Context, uri: Uri, forceProcess: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверяем существование URI
            if (!UriUtil.isUriExistsSuspend(context, uri)) {
                LogUtil.processDebug("URI не существует: $uri")
                return@withContext false
            }
            
            // Проверяем, не является ли файл переименованным оригиналом
            val fileName = UriUtil.getFileNameFromUri(context, uri) ?: ""
            if (fileName.contains("_original.")) {
                LogUtil.processDebug("Файл является переименованным оригиналом, пропускаем: $uri")
                return@withContext false
            }
            
            // Проверяем, включено ли автоматическое сжатие
            val settingsManager = SettingsManager.getInstance(context)
            val isAutoEnabled = settingsManager.isAutoCompressionEnabled()
            
            // Если автосжатие отключено и нет флага принудительной обработки, возвращаем false
            if (!isAutoEnabled && !forceProcess) {
                LogUtil.processDebug("Автосжатие отключено и нет принудительной обработки: $uri")
                return@withContext false
            }
            
            // Проверяем, не является ли изображение скриншотом
            if (!settingsManager.shouldProcessScreenshots() && UriUtil.isScreenshot(context, uri)) {
                LogUtil.processDebug("Файл является скриншотом, обработка скриншотов отключена: $uri")
                return@withContext false
            }
            
            // Проверяем, не находится ли файл в директории приложения
            // Используем улучшенную проверку с нормализацией путей
            val path = UriUtil.getFilePathFromUri(context, uri)
            if (isInAppDirectoryNormalized(path)) {
                LogUtil.processDebug("Файл находится в директории приложения: ${normalizePath(path ?: "null")}")
                return@withContext false
            }
            
            // Проверяем, является ли файл временным или в процессе записи
            // Добавляем более гибкую проверку с учетом времени
            if (UriUtil.isFilePendingSuspend(context, uri)) {
                LogUtil.processDebug("Файл является временным или в процессе записи: $uri")
                // Проверяем, может быть файл уже был обработан, но все еще помечен как pending
                val fileName = UriUtil.getFileNameFromUri(context, uri) ?: ""
                if (fileName.contains("_compressed")) {
                    // Это может быть сжатая версия, пропускаем проверку pending
                    LogUtil.processDebug("Файл содержит '_compressed', возможно это результат предыдущей обработки: $uri")
                } else {
                    return@withContext false
                }
            }
            
            // Проверяем MIME тип
            val mimeType = UriUtil.getMimeType(context, uri)
            if (!isProcessableMimeType(mimeType)) {
                LogUtil.processDebug("Неподдерживаемый MIME тип: $mimeType для URI: $uri")
                return@withContext false
            }
            
            // Прошли все базовые проверки
            return@withContext true
        } catch (e: Exception) {
            LogUtil.errorWithException("CHECK_PROCESSING", e)
            return@withContext false
        }
    }
    
    /**
     * Проверка, было ли изображение уже обработано
     * Централизованная версия логики из разных частей приложения
     * @return true если изображение уже обработано, false в противном случае
     */
    suspend fun isAlreadyProcessed(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = isProcessingRequired(context, uri, false)
            return@withContext !result.processingRequired
        } catch (e: Exception) {
            LogUtil.errorWithException("CHECK_PROCESSING", e)
            return@withContext false
        }
    }
    
    /**
     * НОВЫЙ ЦЕНТРАЛИЗОВАННЫЙ МЕТОД
     * Проверяет, требуется ли обработка изображения
     * Объединяет различные проверки из разных частей приложения
     * 
     * @param context Контекст
     * @param uri URI изображения
     * @param forceProcess Принудительная обработка, даже если автосжатие отключено
     * @return результат проверки в виде объекта ProcessingCheckResult
     */
    suspend fun isProcessingRequired(context: Context, uri: Uri, forceProcess: Boolean = false): ProcessingCheckResult = withContext(Dispatchers.IO) {
        try {
            // Создаем результат по умолчанию
            val result = ProcessingCheckResult()
            
            // Проверяем базовые условия
            if (!passesBasicChecks(context, uri, forceProcess)) {
                result.processingRequired = false
                result.reason = ProcessingSkipReason.BASIC_CHECK_FAILED
                return@withContext result
            }

            val settingsManager = SettingsManager.getInstance(context)
            val path = UriUtil.getFilePathFromUri(context, uri) ?: ""
            val (isInAppDir, isMessengerImage) = OptimizedCacheUtil.checkDirectoryStatus(path, Constants.APP_DIRECTORY)
            
            // Проверяем, не является ли изображение файлом из мессенджера
            if (settingsManager.shouldIgnoreMessengerPhotos() && isMessengerImage) {
                result.processingRequired = false // Обработка (сжатие) не требуется
                result.reason = ProcessingSkipReason.MESSENGER_PHOTO
                return@withContext result
            }
            
            // Проверяем путь к файлу - если файл находится в директории приложения, считаем его обработанным
            if (isInAppDir) {
                result.processingRequired = false
                result.reason = ProcessingSkipReason.IN_APP_DIRECTORY
                return@withContext result
            }
            
            // Поиск сжатой версии в директории приложения по имени (только если режим замены отключен)
            if (!FileOperationsUtil.isSaveModeReplace(context)) {
                val compressedUri = FileOperationsUtil.findCompressedVersionByOriginalName(context, uri)
                if (compressedUri != null) {
                    result.processingRequired = false
                    result.reason = ProcessingSkipReason.COMPRESSED_VERSION_EXISTS
                    return@withContext result
                }
            }
            
            // Получаем размер файла для проверок
            val fileSize = UriUtil.getFileSize(context, uri)
            val modificationTimestamp = UriUtil.getFileLastModified(context, uri)
            
            // Проверяем кэшированные EXIF данные сначала
            val cachedExifData = OptimizedCacheUtil.getCachedExifData(uri, modificationTimestamp)
            val (isCompressed, quality, compressionTimestamp) = if (cachedExifData != null) {
                PerformanceMonitor.recordCacheHit("EXIF")
                Triple(cachedExifData.isCompressed, cachedExifData.quality, cachedExifData.compressionTimestamp)
            } else {
                PerformanceMonitor.recordCacheMiss("EXIF")
                // Выполняем EXIF запрос с измерением времени
                PerformanceMonitor.measureExifCheck {
                    val exifResult = ExifUtil.getCompressionMarker(context, uri)
                    // Кэшируем результат
                    OptimizedCacheUtil.cacheExifData(uri, exifResult.first, exifResult.second, exifResult.third, modificationTimestamp)
                    exifResult
                }
            }
            
            // Дополнительная проверка: если файл был изменен после кэширования EXIF, используем свежие данные
            result.hasCompressionMarker = isCompressed
            result.compressionQuality = quality
            result.compressionTimestamp = compressionTimestamp
            
            // Если файл имеет маркер сжатия, проверяем, не был ли он модифицирован после сжатия
            if (isCompressed) {
                result.fileModificationTimestamp = modificationTimestamp
                
                // Если мы не можем получить дату модификации, считаем что файл не был изменен
                if (modificationTimestamp == 0L) {
                    result.processingRequired = false
                    result.reason = ProcessingSkipReason.ALREADY_COMPRESSED
                    return@withContext result
                }
                
                // Проверяем, был ли файл модифицирован после сжатия
                if (modificationTimestamp > compressionTimestamp) {
                    // Файл был модифицирован после сжатия, требуется повторная обработка
                    val diffSeconds = (modificationTimestamp - compressionTimestamp) / 1000
                    // Допустимая погрешность в секундах для учета задержки между записью EXIF и обновлением времени модификации
                    val allowedTimeDifferenceSeconds = 20
                    
                    // Если разница меньше или равна допустимой, считаем, что файл не был модифицирован
                    if (diffSeconds <= allowedTimeDifferenceSeconds) {
                        // Файл не был модифицирован после сжатия (или отличается в пределах допустимой погрешности)
                        LogUtil.processDebug("Файл не был модифицирован после сжатия (разница $diffSeconds сек в пределах допустимой погрешности $allowedTimeDifferenceSeconds сек)")
                        result.processingRequired = false
                        result.reason = ProcessingSkipReason.ALREADY_COMPRESSED
                        return@withContext result
                    }
                    
                    LogUtil.processDebug("Файл был модифицирован после сжатия (разница $diffSeconds сек), требуется повторная обработка")
                    result.processingRequired = true
                    result.reason = ProcessingSkipReason.NONE
                } else {
                    // Файл не был модифицирован после сжатия
                    result.processingRequired = false
                    result.reason = ProcessingSkipReason.ALREADY_COMPRESSED
                    return@withContext result
                }
            }
            
            // Если файл достаточно мал, пропускаем его
            val optimumSize = Constants.OPTIMUM_FILE_SIZE
            if (fileSize < optimumSize) {
                result.processingRequired = false
                result.reason = ProcessingSkipReason.ALREADY_SMALL
                return@withContext result
            }
            
            // По умолчанию: файл требует обработки
            result.processingRequired = true
            result.reason = ProcessingSkipReason.NONE
            return@withContext result
        } catch (e: Exception) {
            LogUtil.errorWithException("CHECK_PROCESSING", e)
            // В случае ошибки возвращаем результат по умолчанию (требуется обработка)
            val result = ProcessingCheckResult()
            result.processingRequired = true
            result.reason = ProcessingSkipReason.NONE
            result.error = e
            return@withContext result
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
                mimeType.contains("png") ||
                mimeType.equals("image/heic", ignoreCase = true))
    }

    private fun isInAppDirectory(path: String): Boolean {
        return path.contains("/${Constants.APP_DIRECTORY}/") || 
               (path.contains("content://media/external/images/media") && 
                path.contains(Constants.APP_DIRECTORY))
    }

    /**
     * Проверяет, является ли изображение файлом из мессенджера.
     * @param path Путь к файлу
     * @return true, если файл из мессенджера, иначе false
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
     * Класс для хранения результатов проверки необходимости обработки
     */
    data class ProcessingCheckResult(
        var processingRequired: Boolean = true,
        var reason: ProcessingSkipReason = ProcessingSkipReason.NONE,
        var hasCompressionMarker: Boolean = false,
        var compressionQuality: Int = -1,
        var compressionTimestamp: Long = 0L,
        var fileModificationTimestamp: Long? = null,
        var error: Exception? = null
    )
    
    /**
     * Причины, по которым обработка изображения может быть пропущена
     */
    enum class ProcessingSkipReason {
        NONE,                    // Обработка требуется
        BASIC_CHECK_FAILED,      // Не прошли базовые проверки
        IN_APP_DIRECTORY,        // Файл находится в директории приложения
        COMPRESSED_VERSION_EXISTS, // Существует сжатая версия в директории приложения
        ALREADY_COMPRESSED,      // Файл уже сжат и не модифицирован
        ALREADY_SMALL,           // Файл уже имеет достаточно малый размер
        MESSENGER_PHOTO,         // Изображение из мессенджера, сжатие пропущено
    }
} 
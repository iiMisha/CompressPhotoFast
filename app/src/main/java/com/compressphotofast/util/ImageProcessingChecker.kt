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

/**
 * Централизованный класс для проверки необходимости обработки изображений
 * Предотвращает дублирование логики в разных частях приложения
 */
object ImageProcessingChecker {

    private val TAG = "ImageProcessingChecker"
    
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
            val path = UriUtil.getFilePathFromUri(context, uri) ?: ""
            if (isInAppDirectory(path)) {
                LogUtil.processDebug("Файл находится в директории приложения: $path")
                return@withContext false
            }
            
            // Проверяем, является ли файл временным или в процессе записи
            if (UriUtil.isFilePendingSuspend(context, uri)) {
                LogUtil.processDebug("Файл является временным или в процессе записи: $uri")
                return@withContext false
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
            
            // Проверяем путь к файлу - если файл находится в директории приложения, считаем его обработанным
            val path = UriUtil.getFilePathFromUri(context, uri) ?: ""
            if (isInAppDirectory(path)) {
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
            
            // Проверяем EXIF маркер сжатия
            val (isCompressed, quality, compressionTimestamp) = ExifUtil.getCompressionMarker(context, uri)
            result.hasCompressionMarker = isCompressed
            result.compressionQuality = quality
            result.compressionTimestamp = compressionTimestamp
            
            // Если файл имеет маркер сжатия, проверяем, не был ли он модифицирован после сжатия
            if (isCompressed) {
                // Получаем дату последней модификации файла
                val modificationTimestamp = UriUtil.getFileLastModified(context, uri)
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
                mimeType.contains("png"))
    }

    private fun isInAppDirectory(path: String): Boolean {
        return path.contains("/${Constants.APP_DIRECTORY}/") || 
               (path.contains("content://media/external/images/media") && 
                path.contains(Constants.APP_DIRECTORY))
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
    }
} 
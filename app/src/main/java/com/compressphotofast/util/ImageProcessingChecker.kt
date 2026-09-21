package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.FileOperationsUtil
import com.compressphotofast.util.UriUtil
import com.compressphotofast.util.OptimizedCacheUtil
import com.compressphotofast.util.PerformanceMonitor

/**
 * Централизованный класс для проверки необходимости обработки изображений
 * Предотвращает дублирование логики в разных частях приложения
 */
object ImageProcessingChecker {

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
            "/storage/emulated/0/pictures/compressphotofast/"
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
                LogUtil.debug("ImageProcessingChecker", "Изображение не требует обработки: ${result.reason}")
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
            if (!OptimizedCacheUtil.isProcessableMimeType(mimeType)) {
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

            val path = UriUtil.getFilePathFromUri(context, uri) ?: ""
            val isInAppDir = OptimizedCacheUtil.checkDirectoryStatus(path, Constants.APP_DIRECTORY)

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

            // Атомарное чтение/вычисление EXIF-данных с double-check locking.
            // Кэш валидируется по размеру файла: копирование/перенос (обновляющие
            // только дату модификации) не инвалидируют кэш, реальное изменение
            // содержимого — инвалидирует.
            val exifData = OptimizedCacheUtil.getOrComputeExifData(uri, fileSize) {
                PerformanceMonitor.recordCacheMiss("EXIF")
                PerformanceMonitor.measureExifCheck {
                    val marker = ExifUtil.getCompressionMarker(context, uri)
                    OptimizedCacheUtil.CachedExifData(
                        marker.isCompressed, marker.quality, marker.timestamp, marker.fileSize, fileSize
                    )
                }
            }
            if (exifData != null) {
                PerformanceMonitor.recordCacheHit("EXIF")
            }
            val isCompressed = exifData?.isCompressed ?: false
            val quality = exifData?.quality ?: -1
            val compressionTimestamp = exifData?.compressionTimestamp ?: 0L

            result.hasCompressionMarker = isCompressed
            result.compressionQuality = quality
            result.compressionTimestamp = compressionTimestamp

            // Файл с маркером сжатия повторно обрабатывается ТОЛЬКО при реальном
            // изменении содержимого, определяемом по размеру файла: если текущий
            // размер расходится с записанным в маркере сверх допуска
            // [Constants.MARKER_SIZE_TOLERANCE_BYTES], файл был пережат/отредактирован
            // после сжатия. Дрейф в пределах допуска — нормальное следствие
            // saveAttributes() при записи маркера и правок не означает. Если размер
            // совпадает или неизвестен (старый формат маркера, HEIC-маркер) —
            // доверяем маркеру и пропускаем файл.
            if (isCompressed) {
                val markerFileSize = exifData?.markerFileSize
                val contentModified = markerFileSize != null && fileSize > 0L &&
                    isMarkerSizeMismatch(fileSize, markerFileSize)

                if (contentModified) {
                    LogUtil.processDebug("Файл изменён после сжатия: размер $fileSize != $markerFileSize, требуется повторная обработка")
                    result.processingRequired = true
                    result.reason = ProcessingSkipReason.NONE
                } else {
                    result.processingRequired = false
                    result.reason = ProcessingSkipReason.ALREADY_COMPRESSED
                }
                return@withContext result
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
     * Сравнение фактического размера файла с размером из маркера с допуском
     * [Constants.MARKER_SIZE_TOLERANCE_BYTES]: дрейф saveAttributes() при записи
     * маркера (до ~1 КБ) правкой не считается, большее расхождение — признак
     * редактирования или внешнего пережатия файла
     */
    fun isMarkerSizeMismatch(fileSize: Long, markerFileSize: Long): Boolean =
        abs(fileSize - markerFileSize) > Constants.MARKER_SIZE_TOLERANCE_BYTES

    /**
     * Класс для хранения результатов проверки необходимости обработки
     */
    data class ProcessingCheckResult(
        var processingRequired: Boolean = true,
        var reason: ProcessingSkipReason = ProcessingSkipReason.NONE,
        var hasCompressionMarker: Boolean = false,
        var compressionQuality: Int = -1,
        var compressionTimestamp: Long = 0L,
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
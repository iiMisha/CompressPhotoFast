package com.compressphotofast.data

import android.app.ActivityManager
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import com.compressphotofast.util.Constants
import com.compressphotofast.util.LogUtil

/**
 * Утилитарный класс для работы с файлами и файловыми операциями
 */
object FileOperationsUtil {
    /**
     * Проверяет, включен ли режим замены файлов в настройках
     */
    fun isSaveModeReplace(context: Context): Boolean {
        try {
            return SettingsManager.getInstance(context).isSaveModeReplace()
        } catch (e: Exception) {
            LogUtil.errorWithException("Проверка режима замены", e)
            return false
        }
    }
    
    /**
     * Создает имя файла для сжатой версии
     * В режиме замены возвращает оригинальное имя, иначе добавляет суффикс _compressed
     *
     * Очищает двойные расширения (например, image.HEIC.jpg -> image_compressed.jpg)
     */
    fun createCompressedFileName(context: Context, originalName: String): String {
        // В режиме замены используем оригинальное имя файла
        if (isSaveModeReplace(context)) {
            LogUtil.processInfo("[FileOperationsUtil] Режим замены включён, используем оригинальное имя: $originalName")
            // Очищаем двойные расширения даже в режиме замены, но сохраняем последнее расширение
            val cleanName = cleanDoubleExtensions(originalName)
            val extension = toOutputExtension(getLastExtension(originalName))
            return if (extension.isNotEmpty()) "$cleanName$extension" else cleanName
        }

        // В режиме отдельного сохранения добавляем суффикс
        // Сначала очищаем двойные расширения
        val cleanName = cleanDoubleExtensions(originalName)
        val extension = toOutputExtension(getLastExtension(originalName))

        val compressedName = if (extension.isNotEmpty()) {
            "${cleanName}${Constants.COMPRESSED_FILE_SUFFIX}$extension"
        } else {
            "${cleanName}${Constants.COMPRESSED_FILE_SUFFIX}"
        }

        LogUtil.processInfo("[FileOperationsUtil] Режим отдельного сохранения: $originalName → $compressedName")
        return compressedName
    }

    /**
     * Преобразует расширение исходного файла в расширение выходного файла.
     *
     * HEIC/HEIF исходники всегда конвертируются в JPEG при сжатии, поэтому
     * их расширение заменяется на `.jpg`, чтобы имя файла, MIME-тип в MediaStore
     * и реальный формат байтов оставались согласованными.
     */
    private fun toOutputExtension(extension: String): String {
        return when (extension.lowercase()) {
            ".heic", ".heif" -> ".jpg"
            else -> extension
        }
    }

    /**
     * Расширения изображений, которые считаются частью «двойного расширения»
     * (например, `image.HEIC.jpg`).
     */
    private val IMAGE_EXTENSIONS = setOf(".heic", ".heif", ".jpg", ".jpeg", ".png", ".webp")

    /**
     * Очищает двойные расширения в имени файла
     * Например: image.HEIC.jpg -> image, photo.heif.jpeg -> photo
     *
     * Внутреннее расширение срезается, только если это известное расширение
     * изображения. Прочие точки — часть имени: `PXL_1.PORTRAIT.jpg` -> `PXL_1.PORTRAIT`.
     * ИНВАРИАНТ БЕЗОПАСНОСТИ: иначе имена разных файлов схлопываются
     * (`PXL_1.PORTRAIT.ORIGINAL.jpg` -> `PXL_1.PORTRAIT.jpg`), и в режиме замены
     * сжатый файл мог бы занять чужое имя.
     *
     * @param fileName Исходное имя файла
     * @return Имя файла без двойных расширений (только базовое имя)
     */
    internal fun cleanDoubleExtensions(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        if (lastDotIndex <= 0) return fileName

        val beforeLastDot = fileName.substring(0, lastDotIndex)
        val secondLastDot = beforeLastDot.lastIndexOf('.')
        val innerExtension = if (secondLastDot > 0) beforeLastDot.substring(secondLastDot).lowercase() else ""

        return if (innerExtension in IMAGE_EXTENSIONS) {
            val cleanName = beforeLastDot.substring(0, secondLastDot)
            LogUtil.debug("FileOperationsUtil", "Очистка двойного расширения: $fileName -> $cleanName")
            cleanName
        } else {
            beforeLastDot
        }
    }

    /**
     * Извлекает последнее расширение из имени файла
     * Например: image.HEIC.jpg -> .jpg, photo.png -> .png
     *
     * @param fileName Имя файла
     * @return Последнее расширение с точкой или пустая строка
     */
    private fun getLastExtension(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex > 0) {
            fileName.substring(lastDotIndex)
        } else {
            ""
        }
    }

    /**
     * Удаляет файл по URI с централизованной логикой для всех версий Android
     * @param forceDelete Если true, обходит проверку нахождения URI в обработке
     * @return Boolean - успешность удаления или IntentSender для запроса разрешения на Android 10+
     */
    suspend fun deleteFile(context: Context, uri: Uri, uriProcessingTracker: UriProcessingTracker, forceDelete: Boolean = false): Any? =
        // contentResolver.delete — IPC; вызывается в том числе из lifecycleScope Activity
        withContext(Dispatchers.IO) { deleteFileInternal(context, uri, uriProcessingTracker, forceDelete) }

    private fun deleteFileInternal(context: Context, uri: Uri, uriProcessingTracker: UriProcessingTracker, forceDelete: Boolean): Any? {
        if (!forceDelete && uriProcessingTracker.isProcessing(uri)) {
            LogUtil.processWarning("deleteFile: URI находится в обработке, удаление отменено: $uri")
            return false
        }
        try {
            LogUtil.processInfo("Начинаем удаление файла: $uri")

            // Если URI имеет фрагмент #renamed_original, удаляем этот фрагмент
            val cleanUri = if (uri.toString().contains("#renamed_original")) {
                val original = Uri.parse(uri.toString().replace("#renamed_original", ""))
                LogUtil.processInfo("URI содержит маркер #renamed_original, используем очищенный URI: $original")
                original
            } else {
                uri
            }

            try {
                LogUtil.processInfo("Удаляем файл через MediaStore (Android 10+)")
                val result = context.contentResolver.delete(cleanUri, null, null) > 0
                LogUtil.processInfo("Результат удаления через MediaStore: $result")
                if (!result) {
                    LogUtil.processWarning("Удаление через MediaStore не удалось для URI: $cleanUri")
                } else {
                    // Инвалидируем кэш URI после успешного удаления
                    UriUtil.invalidateUriExistsCache(cleanUri)
                }
                return result
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    LogUtil.processInfo("Android 11+: Запрашиваем разрешение на удаление через createDeleteRequest: $cleanUri")
                    try {
                        val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(cleanUri))
                        return pendingIntent.intentSender
                    } catch (ex: Exception) {
                        LogUtil.error(cleanUri, "Удаление", "Ошибка при создании createDeleteRequest", ex)
                        throw e
                    }
                } else if (e is android.app.RecoverableSecurityException) {
                    LogUtil.processInfo("Требуется разрешение пользователя для удаления файла: $cleanUri")
                    return e.userAction.actionIntent.intentSender
                } else {
                    LogUtil.error(cleanUri, "Удаление", "SecurityException при удалении файла", e)
                    throw e
                }
            } catch (e: Exception) {
                LogUtil.error(cleanUri, "Удаление", "Ошибка при удалении файла через MediaStore", e)
                return false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LogUtil.error(null, "Удаление", "Ошибка при удалении файла", e)
        }
        return false
    }
    
    
    /**
     * Проверяет наличие достаточной памяти для декодирования изображения
     *
     * @param context Контекст приложения
     * @param estimatedBytes Оценочный размер в байтах
     * @return true если достаточно памяти, иначе false
     */
    fun availableMemoryBytes(context: Context): Long {
        val runtime = Runtime.getRuntime()
        val heapAvailable = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())).coerceAtLeast(0L)
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager == null) return heapAvailable
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            minOf(heapAvailable, memoryInfo.availMem)
        } catch (e: Exception) {
            LogUtil.errorWithException("Проверка доступной памяти", e)
            heapAvailable
        }
    }

    fun hasEnoughMemory(context: Context, estimatedBytes: Long): Boolean {
        return try {
            val runtime = Runtime.getRuntime()
            val heapAvailable = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            // Учитываем одновременно process heap и системный low-memory signal.
            val reserve = 32L * 1024 * 1024
            val minRequired = estimatedBytes + reserve
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            val availableMem = if (activityManager != null) {
                activityManager.getMemoryInfo(memoryInfo)
                memoryInfo.availMem
            } else {
                Long.MAX_VALUE
            }
            val hasMemory = !memoryInfo.lowMemory &&
                availableMem >= minRequired && heapAvailable >= minRequired

            if (!hasMemory) {
                LogUtil.error(
                    null,
                    "Проверка памяти",
                    "Недостаточно памяти: требуется ${minRequired / 1024 / 1024}MB, " +
                            "доступно system=${availableMem / 1024 / 1024}MB, heap=${heapAvailable / 1024 / 1024}MB"
                )
            }

            hasMemory
        } catch (e: Exception) {
            LogUtil.errorWithException("Проверка памяти", e)
            false
        }
    }


    /**
     * Проверка валидности размера файла
     */
    fun isFileSizeValid(size: Long): Boolean {
        return size in Constants.MIN_FILE_SIZE..Constants.MAX_FILE_SIZE
    }
    
    /**
     * Разбивает имя файла на базовую часть и расширение
     * @param fileName Имя файла
     * @return Pair(baseName, extension) где extension включает точку (например ".jpg") или пустая строка
     */
    fun splitNameAndExtension(fileName: String): Pair<String, String> {
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex > 0) {
            fileName.substring(0, dotIndex) to fileName.substring(dotIndex)
        } else {
            fileName to ""
        }
    }

    /**
     * Находит сжатую версию файла в директории приложения по имени оригинального файла
     */
    suspend fun findCompressedVersionByOriginalName(
        context: Context,
        originalUri: Uri,
        knownFileName: String? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // Получаем имя оригинального файла
            val originalFileName = knownFileName ?: UriUtil.getFileNameFromUri(context, originalUri)
            if (originalFileName.isNullOrEmpty()) {
                LogUtil.debug("FileUtil", "Не удалось получить имя оригинального файла: $originalUri")
                return@withContext null
            }
            
            // Разбиваем имя файла на основную часть и расширение
            val (fileBaseName, fileExtension) = splitNameAndExtension(originalFileName)
            
            // Формируем запрос для поиска файлов в директории приложения
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            
            // Ищем любые файлы с совпадающим базовым именем и расширением,
            // но с возможными дополнительными символами между ними
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf(
                "%${Constants.APP_DIRECTORY}%",    // Ищем в директории приложения
                "$fileBaseName%$fileExtension"     // Любой суффикс
            )
            
            LogUtil.debug("FileUtil", "Ищем сжатую версию файла с паттерном '$fileBaseName%$fileExtension' в папке ${Constants.APP_DIRECTORY}")
            
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC" // Самые новые файлы первыми
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val id = cursor.getLong(idColumn)
                    val foundName = cursor.getString(nameColumn)
                    val compressedUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    
                    LogUtil.fileInfo(compressedUri, "Найдена сжатая версия для $originalFileName: $foundName")
                    return@withContext compressedUri
                }
            }
            
            LogUtil.debug("FileUtil", "Сжатая версия для файла '$originalFileName' не найдена")
            return@withContext null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LogUtil.errorWithException("Поиск сжатой версии файла", e)
            return@withContext null
        }
    }
    
    /**
     * Вычисляет процент уменьшения размера с защитой от деления на ноль
     * @return Процент уменьшения (0..100) или 0 если originalSize <= 0
     */
    fun computeSizeReductionPercent(originalSize: Long, compressedSize: Long): Float {
        if (originalSize <= 0) return 0f
        return ((originalSize - compressedSize).toFloat() / originalSize) * 100f
    }
    
    /**
     * Форматирует размер файла в удобочитаемый вид
     */
    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        
        return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    /**
     * Форматирует размер файла компактно: без пробела и с однобуквенными единицами (4.2М, 830К).
     * Для уведомлений; полные единицы доступны через [formatFileSize].
     */
    fun formatFileSizeCompact(size: Long): String {
        if (size <= 0) return "0Б"

        val units = arrayOf("Б", "К", "М", "Г", "Т")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()

        return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + units[digitGroups]
    }
    
    /**
     * Сокращает длинное имя файла, заменяя середину на "..."
     */
    fun truncateFileName(fileName: String, maxLength: Int = 25): String {
        if (fileName.length <= maxLength) return fileName
        
        val start = fileName.substring(0, maxLength / 2 - 2)
        val end = fileName.substring(fileName.length - maxLength / 2 + 1)
        return "$start...$end"
    }
}

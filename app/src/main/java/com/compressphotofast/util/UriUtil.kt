package com.compressphotofast.util

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Утилитарный класс для работы с URI и получения информации о файлах
 */
/**
 * Исключение, выбрасываемое когда файл существует, но находится в состоянии pending
 * и недоступен для чтения в данный момент (Only owner is able to interact with pending item).
 */
class PendingItemException(val uri: Uri, cause: Throwable) : Exception(cause.message, cause)

/**
 * Результат проверки существования URI с дополнительной информацией
 */
private data class UriExistsResult(
    val exists: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val reason: String? = null
) {
    fun isExpired(ttlMs: Long): Boolean = System.currentTimeMillis() - timestamp > ttlMs
}

object UriUtil {
    // Кэш результатов проверки существования URI
    private val uriExistsCache = ConcurrentHashMap<String, UriExistsResult>()

    private const val URI_EXISTS_CACHE_TTL = 10_000L // 10 минут
    private const val URI_EXISTS_CACHE_SIZE = 100 // максимум 100 URI в кэше

    /**
     * Получает полный путь к файлу из URI
     */
    fun getFilePathFromUri(context: Context, uri: Uri): String? {
        try {
            // Преобразуем MediaDocumentsUri, если необходимо
            val effectiveUri = convertMediaDocumentsUri(uri) ?: uri

            // На Android 10 (API 29) и выше MediaStore.Images.Media.DATA считается устаревшим
            // и может возвращать null, поэтому используем другой подход
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Для Android 10+ используем относительный путь и имя файла
                val projection = arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH
                )
                
                try {
                    context.contentResolver.query(
                        effectiveUri,
                        projection,
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                            val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                            
                            if (nameIndex != -1 && pathIndex != -1) {
                                // Проверяем на null, чтобы избежать ошибки "Reading a NULL string not supported here"
                                val fileName = if (cursor.isNull(nameIndex)) null else cursor.getString(nameIndex)
                                val relativePath = if (cursor.isNull(pathIndex)) null else cursor.getString(pathIndex)
                                
                                // Проверяем, что получили непустые значения
                                if (!fileName.isNullOrEmpty() && !relativePath.isNullOrEmpty()) {
                                    return "${Environment.getExternalStorageDirectory()}/$relativePath$fileName"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogUtil.warning(uri, "UriUtil", "Ошибка при получении пути через MediaStore: ${e.message}")
                }
                
                // Специальная обработка для Android 11 (API 30) - используем хелпер
                queryMediaStoreWithIdFallbackApi30(context, effectiveUri, projection)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                         val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                         val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)

                         if (nameIndex != -1 && pathIndex != -1 &&
                             !cursor.isNull(nameIndex) && !cursor.isNull(pathIndex)) {
                             val fileName = cursor.getString(nameIndex)
                             val relativePath = cursor.getString(pathIndex)

                             if (!fileName.isNullOrEmpty() && !relativePath.isNullOrEmpty()) {
                                 return "${Environment.getExternalStorageDirectory()}/$relativePath$fileName"
                             }
                         }
                     }
                }
                
                // Если не удалось получить путь через MediaStore, пробуем через lastPathSegment
                effectiveUri.lastPathSegment?.let { segment ->
                    if (segment.contains("/")) {
                        return segment
                    }
                }
                
                // Для content URI возвращаем сам URI в виде строки, чтобы можно было
                // проверить, находится ли файл в директории приложения
                return effectiveUri.toString()
            } else {
                // Для Android 9 и ниже используем старый подход
                val projection = arrayOf(MediaStore.Images.Media.DATA)
                context.contentResolver.query(
                    effectiveUri,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                        if (columnIndex != -1) {
                            // Проверяем на null, чтобы избежать ошибки "Reading a NULL string not supported here"
                            val path = if (cursor.isNull(columnIndex)) null else cursor.getString(columnIndex)
                            if (!path.isNullOrEmpty()) {
                                return path
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Получение пути", "Ошибка при получении пути к файлу из URI", e)
        }
        
        // Если все методы не сработали, возвращаем URI в виде строки
        return uri.toString()
    }
    
    /**
     * Получает имя файла из URI (расширенная версия)
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        try {
            // Преобразуем MediaDocumentsUri, если необходимо
            val effectiveUri = convertMediaDocumentsUri(uri) ?: uri

            // Пробуем получить имя через contentResolver
            var fileName: String? = null
            context.contentResolver.query(effectiveUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    if (displayNameIndex != -1 && !cursor.isNull(displayNameIndex)) {
                        fileName = cursor.getString(displayNameIndex)
                    }
                }
            }

            // Специальная обработка для Android 11 (API 30) - используем хелпер
            if (fileName == null) {
                queryMediaStoreWithIdFallbackApi30(context, effectiveUri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME))?.use { cursor ->
                     if (cursor.moveToFirst()) {
                         val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                         if (nameIndex != -1 && !cursor.isNull(nameIndex)) {
                             fileName = cursor.getString(nameIndex)
                         }
                     }
                }
            }

            // Если не удалось, пробуем через lastPathSegment
            if (fileName == null) {
                effectiveUri.lastPathSegment?.let { segment ->
                    if (segment.contains(".")) {
                        fileName = segment
                    } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && segment.toLongOrNull() != null) {
                        // Для Android 11, если lastPathSegment - это ID, пробуем получить имя через ID (вторая попытка через хелпер)
                         queryMediaStoreWithIdFallbackApi30(context, effectiveUri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME))?.use { cursor ->
                             if (cursor.moveToFirst()) {
                                 val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                                 if (nameIndex != -1 && !cursor.isNull(nameIndex)) {
                                     fileName = cursor.getString(nameIndex)
                                 }
                             }
                         }
                    }
                }
            }
            
            return fileName
        } catch (e: Exception) {
            LogUtil.warning(uri, "UriUtil", "Ошибка при получении имени файла из URI: ${e.message}")
            return null
        }
    }
    
    /**
     * Получает относительный путь из URI
     */
    fun getRelativePathFromUri(context: Context, uri: Uri): String? {
        try {
            val projection = arrayOf(MediaStore.Images.Media.RELATIVE_PATH)
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                    if (columnIndex != -1) {
                        return cursor.getString(columnIndex)
                    }
                }
            }
            
            // Попытка через fallback для Android 11
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                queryMediaStoreWithIdFallbackApi30(context, uri, arrayOf(MediaStore.Images.Media.RELATIVE_PATH))?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                        if (pathIndex != -1 && !cursor.isNull(pathIndex)) {
                            return cursor.getString(pathIndex)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.warning(uri, "UriUtil", "Ошибка при получении относительного пути из URI: ${e.message}")
        }
        return null
    }
    
    /**
     * Получает путь директории из URI изображения
     */
    fun getDirectoryFromUri(context: Context, uri: Uri): String {
        try {
            // Преобразуем MediaDocumentsUri, если необходимо
            val effectiveUri = convertMediaDocumentsUri(uri) ?: uri

            // Пытаемся получить RELATIVE_PATH для Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = getRelativePathFromUri(context, effectiveUri)
                if (!relativePath.isNullOrEmpty()) {
                    return getFormattedPathFromRelativePath(relativePath)
                }
            }
            
            // Если не удалось получить RELATIVE_PATH, пытаемся получить полный путь
            val path = getFilePathFromUri(context, effectiveUri)
            if (!path.isNullOrEmpty()) {
                val file = File(path)
                val parent = file.parentFile
                if (parent != null && parent.exists()) {
                    // Извлекаем относительный путь, определяя базовую директорию
                    val basePath = Environment.getExternalStorageDirectory().absolutePath
                    val relativePath = parent.absolutePath.replace(basePath, "").trim('/')
                    if (relativePath.isNotEmpty()) {
                        return relativePath
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.warning(uri, "UriUtil", "Ошибка при получении директории из URI: ${e.message}")
        }
        
        // Возвращаем стандартный путь, если не удалось определить директорию
        // Для режима замены используем Pictures без поддиректории приложения
        return if (FileOperationsUtil.isSaveModeReplace(context)) {
            Environment.DIRECTORY_PICTURES
        } else {
            "Pictures/${Constants.APP_DIRECTORY}"
        }
    }
    
    /**
     * Форматирует относительный путь из пути
     */
    private fun getFormattedPathFromRelativePath(relativePath: String): String {
        // Убираем завершающий слеш, если есть
        return if (relativePath.endsWith("/")) {
            relativePath.substring(0, relativePath.length - 1)
        } else {
            relativePath
        }
    }

    /**
     * Инвалидирует кэш для конкретного URI (при изменении файла)
     */
    fun invalidateUriExistsCache(uri: Uri) {
        uriExistsCache.remove(uri.toString())
        LogUtil.processDebug("Кэш URI инвалидирован: $uri")
    }

    /**
     * Оптимизированная проверка существования URI с использованием кэша
     */
    private suspend fun checkUriExistsOptimized(
        context: Context,
        uri: Uri
    ): UriExistsResult = withContext(Dispatchers.IO) {
        // Единый запрос для получения всех метаданных
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Images.Media.IS_PENDING,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media._ID
            )
        } else {
            arrayOf(
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media._ID
            )
        }

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@withContext UriExistsResult(false, reason = "not_found")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val isPending = cursor.getInt(0) == 1
                if (isPending) {
                    return@withContext UriExistsResult(false, reason = "pending")
                }
            }

            val size = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getLong(1)
            } else {
                cursor.getLong(0)
            }

            if (size < 0) {
                return@withContext UriExistsResult(false, reason = "negative_size")
            }

            return@withContext UriExistsResult(true)
        } ?: UriExistsResult(false, reason = "query_failed")
    }

    /**
     * Централизованная проверка существования файла
     */
    suspend fun isUriExistsSuspend(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val cacheKey = uri.toString()

        // Проверяем кэш
        val cached = uriExistsCache[cacheKey]
        if (cached != null && !cached.isExpired(URI_EXISTS_CACHE_TTL)) {
            LogUtil.processDebug("URI из кэша: $uri -> ${cached.exists}")
            return@withContext cached.exists
        }

        // Оптимизированная проверка
        val result = checkUriExistsOptimized(context, uri)

        // Кэшируем результат
        uriExistsCache[cacheKey] = result

        return@withContext result.exists
    }
    
    /**
     * Получает MIME тип файла по URI
     */
    fun getMimeType(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            LogUtil.warning(uri, "UriUtil", "Ошибка при получении MIME типа: ${e.message}")
            null
        }
    }
    
    /**
     * Получает размер файла по URI (синхронная версия для тестов)
     * @deprecated Используйте suspend версию {@link #getFileSize(Context, Uri)} для production кода
     */
    @Deprecated(
        "Используйте suspend версию getFileSize() для production кода",
        ReplaceWith("getFileSize(context, uri)")
    )
    fun getFileSizeSync(context: Context, uri: Uri): Long {
        return kotlinx.coroutines.runBlocking {
            getFileSize(context, uri)
        }
    }

    /**
     * Получает размер файла по URI (suspend версия для production кода)
     */
    suspend fun getFileSize(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            // Сначала пытаемся получить размер через META-данные
            val fileSize = try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            val size = cursor.getLong(sizeIndex)
                            if (size >= 0) size else -1L
                        } else {
                            -1L
                        }
                    } else {
                        -1L
                    }
                } ?: -1L
            } catch (e: java.lang.SecurityException) {
                LogUtil.debug("Получение размера", "Нет прав доступа к META-данным (${uri}): ${e.message}")
                -1L
            } catch (e: Exception) {
                LogUtil.debug("Получение размера", "Ошибка получения META-данных (${uri}): ${e.message}")
                -1L
            }

            // Если размер получен через META-данные и он валидный, возвращаем его
            if (fileSize > 0) {
                return@withContext fileSize
            } else if (fileSize == 0L) {
                // Файл может быть пустым, это нормальная ситуация
                return@withContext 0L
            }

            // Если не удалось получить через META-данные, пытаемся через InputStream
            // с проверкой существования файла
            val exists = try {
                isUriExistsSuspend(context, uri)
            } catch (e: Exception) {
                LogUtil.debug("Получение размера", "Ошибка проверки существования (${uri}): ${e.message}")
                false
            }

            if (exists) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val available = inputStream.available()
                        return@withContext if (available >= 0) available.toLong() else 0L
                    }
                } catch (e: java.io.FileNotFoundException) {
                    LogUtil.error(uri, "Получение размера", "Файл не найден при открытии потока: ${e.message}")
                    return@withContext 0L
                } catch (e: java.io.IOException) {
                    LogUtil.error(uri, "Получение размера", "Ошибка ввода/вывода при чтении потока: ${e.message}")
                    return@withContext 0L
                } catch (e: java.lang.SecurityException) {
                    LogUtil.error(uri, "Получение размера", "Нет прав доступа к файлу: ${e.message}")
                    return@withContext 0L
                } catch (e: Exception) {
                    LogUtil.error(uri, "Получение размера", "Неожиданная ошибка при чтении файла: ${e.message}")
                    return@withContext 0L
                }
            }

            // Если все попытки не удались, возвращаем 0 (индикатор ошибки)
            return@withContext 0L
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogUtil.error(uri, "Получение размера", "Критическая ошибка при получении размера файла", e)
            return@withContext 0L
        }
    }
    
    /**
     * Проверяет, находится ли файл в состоянии IS_PENDING
     * Если флаг установлен, но файл был добавлен давно (более 2 минут назад),
     * считаем что файл готов к обработке (игнорируем устаревший флаг)
     */
    fun isFilePending(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        try {
            // Получаем и IS_PENDING, и дату добавления файла, и размер
            val projection = arrayOf(
                MediaStore.Images.Media.IS_PENDING,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val pendingIndex = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                    val dateAddedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

                    if (pendingIndex != -1 && dateAddedIndex != -1 && sizeIndex != -1) {
                        val isPending = cursor.getInt(pendingIndex) == 1
                        val dateAdded = cursor.getLong(dateAddedIndex) // в секундах
                        val sizeIsNull = cursor.isNull(sizeIndex)
                        val size = if (sizeIsNull) 0L else cursor.getLong(sizeIndex)

                        // Если флаг IS_PENDING установлен, проверяем возраст файла
                        if (isPending) {
                            val currentTime = System.currentTimeMillis() / 1000 // текущее время в секундах
                            val ageSeconds = currentTime - dateAdded

                            // Если _size=NULL и файл физически существует - игнорируем is_pending
                            // (типичная ситуация для файлов, добавленных через adb/shell)
                            if (sizeIsNull || size == 0L) {
                                try {
                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                        if (stream.available() > 0) {
                                            LogUtil.processDebug("Файл имеет is_pending=1 и _size=${if (sizeIsNull) "NULL" else "0"}, но физически существует, игнорируем флаг: $uri")
                                            return false
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Файл не читается, считаем его pending для безопасности
                                    LogUtil.warning(uri, "isPending", "Ошибка при проверке is_pending, считаем файл pending: ${e.message}")
                                    return true  // При ошибке считаем файл pending для безопасности
                                }
                            }

                            // Если файл старше 1 минуты (60 секунд), игнорируем флаг IS_PENDING
                            // Это исправляет проблему с файлами, у которых остался устаревший флаг
                            if (ageSeconds > 60) {
                                LogUtil.processDebug("Файл имеет is_pending=1, но возраст $ageSeconds сек > 60 сек, игнорируем флаг: $uri")
                                return false
                            }

                            // Файл был добавлен недавно и имеет is_pending=1, считаем что pending
                            LogUtil.processDebug("Файл имеет is_pending=1 и возраст $ageSeconds сек, считаем временным: $uri")
                            return true
                        }
                    }
                }
            }

            return false
        } catch (e: Exception) {
            LogUtil.error(uri, "IS_PENDING проверка", "Ошибка при проверке IS_PENDING", e)
            return false
        }
    }
    
    /**
     * Корутинная версия проверки статуса IS_PENDING
     */
    suspend fun isFilePendingSuspend(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        return@withContext isFilePending(context, uri)
    }
    
    /**
     * Получает дату последнего изменения файла
     */
    suspend fun getFileLastModified(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            when (uri.scheme) {
                "content" -> {
                    // Специальная обработка для URI документов из галереи
                    if (uri.toString().startsWith("content://com.android.providers.media.documents/")) {
                        LogUtil.processDebug("Обнаружен URI документа медиа-провайдера, используем специальную обработку")
                        
                        // Получаем ID документа
                        try {
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
                                            LogUtil.processDebug("Дата модификации файла из MediaStore: ${Date(dateModified)} (${dateModified}ms)")
                                            return@withContext dateModified
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            LogUtil.error(null, "Получение docId", e)
                        }
                    }
                    
                    // Пробуем получить дату через MediaStore
                    val projection = arrayOf(MediaStore.MediaColumns.DATE_MODIFIED)
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                            if (dateIndex != -1) {
                                return@withContext cursor.getLong(dateIndex) * 1000 // Переводим в миллисекунды
                            }
                        }
                    }
                    
                    // Если не удалось через MediaStore, пробуем через DocumentFile
                    try {
                        val documentFile = DocumentFile.fromSingleUri(context, uri)
                        if (documentFile != null && documentFile.exists()) {
                            val lastModified = documentFile.lastModified()
                            if (lastModified > 0) {
                                LogUtil.processDebug("Дата модификации получена через DocumentFile: ${Date(lastModified)} (${lastModified}ms)")
                                return@withContext lastModified
                            }
                        }
                    } catch (e: Exception) {
                        LogUtil.error(null, "DocumentFile", e)
                    }
                }
                "file" -> {
                    // Если схема file, используем обычный File
                    val path = uri.path
                    if (path != null) {
                        val file = File(path)
                        if (file.exists()) {
                            return@withContext file.lastModified()
                        }
                    }
                }
            }
            
            return@withContext 0L
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogUtil.error(null, "Получение даты изменения файла", e)
            return@withContext 0L
        }
    }
    
    /**
     * Преобразует URI вида com.android.providers.media.documents в content:// URI
     */
    fun convertMediaDocumentsUri(uri: Uri): Uri? {
        if (uri.authority == "com.android.providers.media.documents") {
            LogUtil.debug("FileUtil", "Обнаружен URI MediaProvider Documents: $uri")
            try {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("image:")) {
                    val id = docId.split(":")[1].toLong()
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    LogUtil.debug("FileUtil", "Преобразованный URI: $contentUri")
                    return contentUri
                } else {
                     LogUtil.processWarning("Неподдерживаемый тип документа: $docId")
                }
            } catch (e: Exception) {
                 LogUtil.error(uri, "Преобразование URI", "Ошибка при преобразовании MediaDocumentsUri", e)
            }
        }
        return null
    }
    
    /**
     * Выполняет запрос к MediaStore по ID из lastPathSegment URI.
     * Используется как запасной вариант для Android 11 (API 30).
     */
    fun queryMediaStoreWithIdFallbackApi30(
        context: Context,
        uri: Uri,
        projection: Array<String>
    ): Cursor? {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.R) {
             return null // Только для API 30
        }
        try {
            val idString = uri.lastPathSegment
            val id = idString?.toLongOrNull()

            if (id != null) {
                 LogUtil.debug("FileUtil", "Android 11 fallback: Пытаемся запросить по ID=$id из URI: $uri")
                return context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Images.Media._ID} = ?",
                    arrayOf(id.toString()),
                    null
                )
            } else {
                 LogUtil.debug("FileUtil", "Android 11 fallback: Не удалось извлечь ID из lastPathSegment: $idString")
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Android 11 fallback", "Ошибка при запросе по ID", e)
        }
        return null
    }

    /**
     * Проверяет, является ли изображение скриншотом
     * Делегирует вызов методу в FileOperationsUtil
     */
    fun isScreenshot(context: Context, uri: Uri): Boolean {
        return FileOperationsUtil.isScreenshot(context, uri)
    }
} 
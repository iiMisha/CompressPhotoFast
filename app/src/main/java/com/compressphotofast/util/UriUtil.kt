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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/**
 * Утилитарный класс для работы с URI и получения информации о файлах
 */
object UriUtil {
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
                    LogUtil.error(uri, "Получение пути", "Ошибка при получении пути", e)
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
            LogUtil.error(uri, "Получение имени", "Ошибка при получении имени файла из URI", e)
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
            LogUtil.error(uri, "Получение пути", "Ошибка при получении относительного пути из URI", e)
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
            LogUtil.error(uri, "Получение директории", "Ошибка при получении директории из URI", e)
        }
        
        // Возвращаем стандартный путь, если не удалось определить директорию
        return "Pictures/${Constants.APP_DIRECTORY}"
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
     * Централизованная проверка существования файла
     */
    suspend fun isUriExistsSuspend(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверка через contentResolver
            context.contentResolver.getType(uri)?.let {
                return@withContext true
            }
            
            // Дополнительная проверка через InputStream
            try {
                context.contentResolver.openInputStream(uri)?.use { _ ->
                    return@withContext true
                }
            } catch (e: Exception) {
                // Игнорируем ошибку открытия потока
            }
            
            // Проверка для file:// URI через FileProvider
            if (uri.scheme == "file" || uri.scheme == "content") {
                val path = getFilePathFromUri(context, uri)
                if (path != null) {
                    val file = File(path)
                    return@withContext file.exists() && file.canRead()
                }
            }
            
            return@withContext false
        } catch (e: Exception) {
            LogUtil.error(uri, "Проверка существования", "Ошибка при проверке существования файла", e)
            return@withContext false
        }
    }
    
    /**
     * Получает MIME тип файла по URI
     */
    fun getMimeType(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            LogUtil.error(null, "Ошибка при получении MIME типа для $uri", e)
            null
        }
    }
    
    /**
     * Получает размер файла по URI
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        try {
            // Сначала пытаемся получить размер через META-данные
            val fileSize = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        cursor.getLong(sizeIndex)
                    } else {
                        -1L
                    }
                } else {
                    -1L
                }
            } ?: -1L
            
            // Если размер получен через META-данные, возвращаем его
            if (fileSize != -1L) {
                return fileSize
            }
            
            // Если не удалось получить через META-данные, пытаемся через InputStream
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                return inputStream.available().toLong()
            }
            
            // Если обе попытки не удались, возвращаем 0
            return 0L
        } catch (e: Exception) {
            LogUtil.error(uri, "Получение размера", "Ошибка при получении размера файла по URI", e)
            return 0L
        }
    }
    
    /**
     * Проверяет, находится ли файл в состоянии IS_PENDING
     */
    fun isFilePending(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        
        try {
            val projection = arrayOf(MediaStore.Images.Media.IS_PENDING)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val pendingIndex = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                    if (pendingIndex != -1) {
                        return cursor.getInt(pendingIndex) == 1
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
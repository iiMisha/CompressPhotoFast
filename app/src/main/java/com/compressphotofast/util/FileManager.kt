package com.compressphotofast.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import timber.log.Timber
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Централизованный менеджер для работы с файлами и URI
 * Объединяет дублирующийся код из FileUtil и FileInfoUtil
 */
object FileManager {
    // Константы для обработки имен файлов
    private const val COMPRESSED_SUFFIX = "_compressed"
    private const val CONTENT_SCHEME = "content"
    private const val FILE_SCHEME = "file"
    private const val TAG = "FileManager"
    
    // Кэш информации о файлах
    private val fileInfoCache = Collections.synchronizedMap(HashMap<String, FileInfo>())
    private const val FILE_INFO_CACHE_EXPIRATION = 5 * 60 * 1000L // 5 минут
    
    /**
     * Класс для хранения базовой информации о файле
     */
    data class FileInfo(
        val id: Long,
        val name: String,
        val size: Long,
        val date: Long,
        val mime: String,
        val path: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Получает имя файла из URI
     * @param context Контекст приложения
     * @param uri URI файла
     * @return Имя файла или null, если не удалось определить
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        return try {
            when (uri.scheme) {
                CONTENT_SCHEME -> {
                    // Пробуем получить имя через OpenableColumns
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) {
                                return cursor.getString(nameIndex)
                            }
                        }
                    }
                    
                    // Если не удалось через OpenableColumns, пробуем из последнего сегмента URI
                    uri.lastPathSegment
                }
                FILE_SCHEME -> {
                    // Если схема file, используем File для получения имени
                    val path = uri.path
                    if (path != null) {
                        File(path).name
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Получение имени файла", e)
            null
        }
    }
    
    /**
     * Получает расширение файла из URI
     * @param context Контекст приложения
     * @param uri URI файла
     * @return Расширение файла (без точки) или пустая строка
     */
    fun getExtensionFromUri(context: Context, uri: Uri): String {
        val fileName = getFileNameFromUri(context, uri) ?: return ""
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex != -1 && dotIndex < fileName.length - 1) {
            fileName.substring(dotIndex + 1).lowercase(Locale.getDefault())
        } else {
            // Если расширение не найдено, пытаемся определить через ContentResolver
            val mimeType = context.contentResolver.getType(uri)
            mimeType?.let {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(it) ?: ""
            } ?: ""
        }
    }
    
    /**
     * Получает MIME-тип файла из URI
     * @param context Контекст приложения
     * @param uri URI файла
     * @return MIME-тип или null, если не удалось определить
     */
    fun getMimeTypeFromUri(context: Context, uri: Uri): String? {
        return try {
            val contentResolver = context.contentResolver
            contentResolver.getType(uri) ?: getMimeTypeFromExtension(getExtensionFromUri(context, uri))
        } catch (e: Exception) {
            LogUtil.error(uri, "Получение MIME-типа", e)
            null
        }
    }
    
    /**
     * Получает MIME-тип из расширения файла
     * @param extension Расширение файла (без точки)
     * @return MIME-тип или null, если не удалось определить
     */
    fun getMimeTypeFromExtension(extension: String): String? {
        return if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase(Locale.getDefault()))
        } else {
            null
        }
    }
    
    /**
     * Получает размер файла из URI
     * @param context Контекст приложения
     * @param uri URI файла
     * @return Размер файла в байтах или 0, если не удалось определить
     */
    suspend fun getFileSizeFromUri(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            when (uri.scheme) {
                CONTENT_SCHEME -> {
                    // Пробуем получить размер через OpenableColumns
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex != -1) {
                                return@withContext cursor.getLong(sizeIndex)
                            }
                        }
                    }
                    
                    // Если не удалось через OpenableColumns, пробуем через InputStream
                    return@withContext context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
                }
                FILE_SCHEME -> {
                    // Если схема file, используем обычный File
                    val path = uri.path
                    if (path != null) {
                        val file = File(path)
                        if (file.exists()) {
                            return@withContext file.length()
                        }
                    }
                }
            }
            
            // Пробуем через InputStream как запасной вариант
            return@withContext context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
        } catch (e: Exception) {
            LogUtil.error(uri, "Получение размера файла", e)
            return@withContext 0L
        }
    }
    
    /**
     * Получает дату последнего изменения файла
     * @param context Контекст приложения
     * @param uri URI файла
     * @return Дата последнего изменения в миллисекундах или 0, если не удалось определить
     */
    suspend fun getFileLastModified(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            when (uri.scheme) {
                CONTENT_SCHEME -> {
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
                            LogUtil.error(uri, "Получение docId", e)
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
                        LogUtil.error(uri, "DocumentFile", e)
                    }
                }
                FILE_SCHEME -> {
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
            LogUtil.error(uri, "Получение даты изменения файла", e)
            return@withContext 0L
        }
    }
    
    /**
     * Полная информация о файле
     * @param context Контекст приложения
     * @param uri URI файла
     * @return Объект FileInfo или null при ошибке
     */
    suspend fun getFileInfo(context: Context, uri: Uri): FileInfo? = withContext(Dispatchers.IO) {
        try {
            val uriString = uri.toString()
            
            // Проверяем кэш
            val cachedInfo = fileInfoCache[uriString]
            if (cachedInfo != null && (System.currentTimeMillis() - cachedInfo.timestamp < FILE_INFO_CACHE_EXPIRATION)) {
                // Используем кэшированную информацию
                LogUtil.processDebug("Информация о файле (из кэша): ${cachedInfo.name}, размер=${cachedInfo.size}")
                return@withContext cachedInfo
            }
            
            // Если нет в кэше или кэш устарел, запрашиваем информацию
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.MIME_TYPE
            )
            
            var id = -1L
            var name: String? = null
            var size = -1L
            var date = -1L
            var mime: String? = null
            
            // Получаем базовую информацию через ContentResolver
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val dateIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                    val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    
                    if (idIndex != -1) id = cursor.getLong(idIndex)
                    if (nameIndex != -1) name = cursor.getString(nameIndex)
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                    if (dateIndex != -1) date = cursor.getLong(dateIndex) * 1000 // Переводим в миллисекунды
                    if (mimeIndex != -1) mime = cursor.getString(mimeIndex)
                }
            }
            
            // Если не удалось получить имя через ContentResolver, пробуем через OpenableColumns
            val fileName = if (name == null) {
                getFileNameFromUri(context, uri) ?: "unknown"
            } else {
                name
            }
            
            // Если не удалось получить размер через ContentResolver, пробуем через InputStream
            val fileSize = if (size <= 0) {
                getFileSizeFromUri(context, uri)
            } else {
                size
            }
            
            // Если не удалось получить MIME-тип через ContentResolver, пробуем через расширение
            val fileMime = if (mime == null) {
                getMimeTypeFromUri(context, uri) ?: "unknown"
            } else {
                mime
            }
            
            // Получаем путь к файлу
            val path = getFilePathFromUri(context, uri) ?: "unknown"
            
            // Создаем и кэшируем информацию о файле
            val result = FileInfo(id, fileName ?: "unknown", fileSize, date, fileMime ?: "unknown", path)
            fileInfoCache[uriString] = result
            
            LogUtil.fileInfo(uri, "ID=$id, Имя=${fileName ?: "неизвестно"}, Размер=$fileSize, Дата=$date, MIME=${fileMime ?: "неизвестно"}")
            
            return@withContext result
        } catch (e: Exception) {
            LogUtil.error(uri, "Получение информации о файле", e)
            return@withContext null
        }
    }
    
    /**
     * Получает путь к файлу из URI
     * @param context Контекст приложения
     * @param uri URI файла
     * @return Абсолютный путь к файлу или null, если не удалось определить
     */
    fun getFilePathFromUri(context: Context, uri: Uri): String? {
        try {
            when (uri.scheme) {
                FILE_SCHEME -> {
                    return uri.path
                }
                CONTENT_SCHEME -> {
                    // Для Android 11+ мы не можем получить реальный путь к файлу, но можем попробовать
                    // Получаем через MediaStore
                    val projection = arrayOf(MediaStore.Images.Media.DATA)
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                            if (columnIndex != -1) {
                                return cursor.getString(columnIndex)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Получение пути к файлу", e)
        }
        
        return null
    }
    
    /**
     * Создает временный файл для изображения
     * @param context Контекст приложения
     * @return Временный файл для изображения
     */
    fun createTempImageFile(context: Context): File {
        return File.createTempFile(
            "temp_image_",
            ".jpg",
            context.cacheDir
        )
    }
    
    /**
     * Форматирует размер файла в удобочитаемый вид
     * @param size Размер файла в байтах
     * @return Отформатированная строка с размером файла
     */
    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        
        return DecimalFormat("#,##0.#").format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }
    
    /**
     * Проверяет, является ли файл по URI изображением
     * @param context Контекст приложения
     * @param uri URI файла
     * @return true, если файл является изображением
     */
    fun isImageFile(context: Context, uri: Uri): Boolean {
        try {
            val mimeType = getMimeTypeFromUri(context, uri)
            return mimeType?.startsWith("image/") == true
        } catch (e: Exception) {
            LogUtil.error(uri, "Проверка типа файла", e)
            return false
        }
    }
    
    /**
     * Получает имя файла с уникальным суффиксом для сжатого изображения
     * @param originalName Исходное имя файла
     * @return Имя файла с суффиксом для сжатого изображения
     */
    fun getCompressedFileName(originalName: String): String {
        val dotIndex = originalName.lastIndexOf('.')
        return if (dotIndex != -1) {
            val nameWithoutExt = originalName.substring(0, dotIndex)
            val extension = originalName.substring(dotIndex)
            "$nameWithoutExt$COMPRESSED_SUFFIX$extension"
        } else {
            "$originalName$COMPRESSED_SUFFIX"
        }
    }
    
    /**
     * Проверяет, является ли файл в процессе записи (IS_PENDING = 1) в Android 10+
     * @param context Контекст приложения
     * @param uri URI файла
     * @return true, если файл в процессе записи
     */
    suspend fun isFilePending(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext false
        }
        
        try {
            val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING)
            
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val isPendingIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                    if (isPendingIndex != -1) {
                        return@withContext cursor.getInt(isPendingIndex) == 1
                    }
                }
            }
            
            return@withContext false
        } catch (e: Exception) {
            LogUtil.error(uri, "Проверка статуса записи файла", e)
            return@withContext false
        }
    }
    
    /**
     * Очищает кэш информации о файлах
     */
    fun clearFileInfoCache() {
        fileInfoCache.clear()
        LogUtil.processInfo("Кэш информации о файлах очищен")
    }
} 
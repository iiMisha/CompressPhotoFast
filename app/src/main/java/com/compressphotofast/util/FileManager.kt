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
    
    /**
     * Получает имя файла из URI
     * @param context Контекст приложения
     * @param uri URI файла
     * @return Имя файла или null, если не удалось определить
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        try {
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
                    
                    // Пробуем получить имя через последний сегмент пути
                    val path = uri.path
                    if (path != null) {
                        val lastSlashIndex = path.lastIndexOf('/')
                        if (lastSlashIndex != -1) {
                            return path.substring(lastSlashIndex + 1)
                        }
                    }
                }
                FILE_SCHEME -> {
                    // Если схема file, используем последний сегмент пути
                    val path = uri.path
                    if (path != null) {
                        val lastSlashIndex = path.lastIndexOf('/')
                        if (lastSlashIndex != -1) {
                            return path.substring(lastSlashIndex + 1)
                        }
                        return path
                    }
                }
                else -> {
                    // Пробуем получить имя через последний сегмент, как запасной вариант
                    val lastPathSegment = uri.lastPathSegment
                    if (lastPathSegment != null) {
                        return lastPathSegment
                    }
                }
            }
            
            // Если ничего не помогло, генерируем временное имя
            return "image_${System.currentTimeMillis()}.jpg"
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении имени файла из URI: ${e.message}")
            // Возвращаем временное имя при ошибке
            return "image_${System.currentTimeMillis()}.jpg"
        }
    }
    
    /**
     * Создает имя для сжатого файла на основе оригинального имени
     * @param originalName Оригинальное имя файла
     * @return Имя для сжатого файла
     */
    fun createCompressedFileName(originalName: String): String {
        val dotIndex = originalName.lastIndexOf('.')
        return if (dotIndex != -1) {
            // Вставляем суффикс перед расширением
            val name = originalName.substring(0, dotIndex)
            val extension = originalName.substring(dotIndex)
            "$name$COMPRESSED_SUFFIX$extension"
        } else {
            // Если нет расширения, просто добавляем суффикс
            "${originalName}${COMPRESSED_SUFFIX}.jpg"
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
            Timber.e(e, "Ошибка при получении MIME-типа: ${e.message}")
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
            Timber.e(e, "Ошибка при получении размера файла: ${e.message}")
            return@withContext 0L
        }
    }
    
    /**
     * Получает дату последнего изменения файла
     * @param context Контекст приложения
     * @param uri URI файла
     * @return Дата последнего изменения в миллисекундах или 0, если не удалось определить
     */
    suspend fun getFileModificationDate(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            // Для Content URI
            if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
                val projection = arrayOf(MediaStore.Images.Media.DATE_MODIFIED)
                
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                        // DATE_MODIFIED возвращает время в секундах, умножаем на 1000
                        return@withContext cursor.getLong(columnIndex) * 1000
                    }
                }
                
                // Если не смогли получить через MediaStore, пробуем через OpenableColumns и спец. колонки
                val projection2 = arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    "last_modified"
                )
                
                context.contentResolver.query(uri, projection2, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val lastModifiedIndex = cursor.getColumnIndex("last_modified")
                        if (lastModifiedIndex != -1) {
                            return@withContext cursor.getLong(lastModifiedIndex)
                        }
                    }
                }
            }
            
            // Для File URI
            if (ContentResolver.SCHEME_FILE == uri.scheme) {
                val path = uri.path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) {
                        return@withContext file.lastModified()
                    }
                }
            }
            
            // Если ничего не помогло, возвращаем текущее время
            return@withContext System.currentTimeMillis()
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении даты изменения файла: ${e.message}")
            return@withContext System.currentTimeMillis()
        }
    }
    
    /**
     * Форматирует размер файла в читаемый вид
     * @param size Размер в байтах
     * @return Отформатированная строка (например, "1.5 MB")
     */
    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        
        return DecimalFormat("#,##0.#").format(
            size / 1024.0.pow(digitGroups.toDouble())
        ) + " " + units[digitGroups]
    }
    
    /**
     * Записывает входной поток в файл
     */
    fun writeInputStreamToFile(inputStream: InputStream, outputFile: File): Boolean {
        return try {
            FileOutputStream(outputFile).use { output ->
                inputStream.copyTo(output)
            }
            Log.d(TAG, "Файл успешно записан: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при записи файла: ${e.message}")
            false
        } finally {
            try {
                inputStream.close()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при закрытии потока: ${e.message}")
            }
        }
    }
    
    /**
     * Создает временный файл в кэш-директории
     */
    fun createTempFile(context: Context, prefix: String, suffix: String): File {
        val cacheDir = context.cacheDir
        return File.createTempFile(prefix, suffix, cacheDir)
    }
    
    /**
     * Получает размер файла по URI
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { 
            return it.statSize
        }
        return 0L
    }
} 
package com.compressphotofast.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.HashMap
import android.provider.DocumentsContract

/**
 * Утилитарный класс для работы с файлами
 */
object FileUtil {

    private val processedUris = mutableSetOf<String>()
    private val processedFileNames = mutableMapOf<String, Uri>()
    
    /* 
     * ПРИМЕЧАНИЕ: Все методы работы с EXIF были перенесены в класс ExifUtil.
     * Пожалуйста, используйте методы из ExifUtil вместо методов в этом классе.
     */

    /**
     * Получение имени файла из URI
     */
    fun getFileName(contentResolver: ContentResolver, uri: Uri): String? {
        var fileName: String? = null
        try {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayNameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    if (displayNameIndex != -1 && !it.isNull(displayNameIndex)) {
                        fileName = it.getString(displayNameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении имени файла из URI: $uri")
        }
        
        return fileName
    }

    /**
     * Создает имя файла для сжатой версии
     * Теперь просто возвращает оригинальное имя файла
     */
    fun createCompressedFileName(originalName: String): String {
        return originalName
    }

    /**
     * Сохранение сжатого изображения в галерею
     * 
     * @return Pair<Uri?, Any?> - Первый элемент - URI сжатого изображения, второй - IntentSender для запроса разрешения на удаление
     */
    suspend fun saveCompressedImageToGallery(
        context: Context,
        compressedFile: File,
        fileName: String,
        originalUri: Uri
    ): Pair<Uri?, Any?> = withContext(Dispatchers.IO) {
        Timber.d("saveCompressedImageToGallery: начало сохранения файла: $fileName")
        Timber.d("saveCompressedImageToGallery: размер сжатого файла: ${compressedFile.length()} байт")
        Timber.d("saveCompressedImageToGallery: режим замены оригинальных файлов: ${isSaveModeReplace(context)}")
        Timber.d("saveCompressedImageToGallery: ручное сжатие: ${isManualCompression(context)}")
        
        try {
            // Используем оригинальное имя файла без изменений
            val finalFileName = fileName
            
            // Получаем путь к директории для сохранения
            val directory = if (isSaveModeReplace(context)) {
                // Если включен режим замены, сохраняем в той же директории
                getDirectoryFromUri(context, originalUri)
            } else {
                // Иначе сохраняем в директории приложения
                Constants.APP_DIRECTORY
            }
            
            Timber.d("saveCompressedImageToGallery: директория для сохранения: $directory")
            
            // Сохраняем файл
            val (savedUri, deleteIntentSender) = insertImageIntoMediaStore(
                context,
                compressedFile,
                finalFileName,
                directory
            )
            
            if (savedUri != null) {
                Timber.d("saveCompressedImageToGallery: файл успешно сохранен: $savedUri")
            } else {
                Timber.e("saveCompressedImageToGallery: не удалось сохранить файл")
            }
            
            return@withContext Pair(savedUri, deleteIntentSender)
        } catch (e: Exception) {
            Timber.e(e, "saveCompressedImageToGallery: ошибка при сохранении файла")
            return@withContext Pair(null, null)
        }
    }

    /**
     * Создает новую запись в MediaStore для сжатого изображения
     */
    private suspend fun insertImageIntoMediaStore(
        context: Context,
        compressedFile: File,
        fileName: String,
        directory: String
    ): Pair<Uri?, Any?> = withContext(Dispatchers.IO) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, directory)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            
            // Пробуем найти существующий файл с таким же именем и путем
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val existingUri = checkForExistingFile(context, fileName, directory)
                if (existingUri != null) {
                    try {
                        // Если файл существует, удаляем его перед созданием нового
                        Timber.d("Найден существующий файл с таким же именем, удаляем: $existingUri")
                        context.contentResolver.delete(existingUri, null, null)
                    } catch (e: Exception) {
                        Timber.e(e, "Ошибка при удалении существующего файла: $existingUri")
                    }
                }
            }
            
            // Создаем новую запись
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            
            if (uri != null) {
                // Копируем данные из временного файла
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    compressedFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                // На Android 10+ нужно обновить IS_PENDING флаг
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                }
                
                return@withContext Pair(uri, null)
            }
            
            return@withContext Pair(null, null)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при записи файла: ${e.message}")
            return@withContext Pair(null, null)
        }
    }

    /**
     * Проверяет наличие файла с таким же именем в указанной директории
     */
    private suspend fun checkForExistingFile(
        context: Context,
        fileName: String,
        directory: String
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(fileName, "$directory/")
            
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    return@withContext ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
            }
            
            return@withContext null
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при поиске существующего файла: ${e.message}")
            return@withContext null
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
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении относительного пути из URI")
        }
        return null
    }
    
    /**
     * Удаляет файл по URI
     * 
     * На Android 10+ (API 29+) возвращает PendingIntent, который необходимо обработать для получения разрешения
     * на удаление файла
     */
    fun deleteFile(context: Context, uri: Uri): Any? {
        try {
            // Проверяем наличие MANAGE_EXTERNAL_STORAGE для Android 11+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    // Если есть разрешение MANAGE_EXTERNAL_STORAGE, удаляем напрямую
                    val path = getFilePathFromUri(context, uri)
                    if (path != null) {
                        val file = File(path)
                        return file.delete()
                    }
                    // Если не удалось получить путь, пробуем через MediaStore
                    return context.contentResolver.delete(uri, null, null) > 0
                }
            }
            
            // Для Android 10+ используем MediaStore API с обработкой RecoverableSecurityException
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    return context.contentResolver.delete(uri, null, null) > 0
                } catch (e: SecurityException) {
                    if (e is android.app.RecoverableSecurityException) {
                        Timber.d("Требуется разрешение пользователя для удаления файла: $uri")
                        return e.userAction.actionIntent.intentSender
                    } else {
                        throw e
                    }
                }
            } else {
                // Для более старых версий получаем путь к файлу и удаляем его напрямую
                val path = getFilePathFromUri(context, uri)
                if (path != null) {
                    val file = File(path)
                    return file.delete()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при удалении файла: $uri")
        }
        return false
    }
    
    /**
     * Получает путь к файлу из URI
     */
    fun getFilePathFromUri(context: Context, uri: Uri): String? {
        try {
            // Специальная обработка для URI из MediaProvider Documents
            if (uri.authority == "com.android.providers.media.documents") {
                Timber.d("getFilePathFromUri: обнаружен URI MediaProvider Documents: $uri")
                
                // Извлекаем ID документа
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("image:")) {
                    // Извлекаем ID изображения
                    val id = docId.split(":")[1]
                    // Создаем новый URI для обращения к MediaStore
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toLong())
                    Timber.d("getFilePathFromUri: преобразованный URI: $contentUri")
                    
                    // Рекурсивно вызываем тот же метод для обработки преобразованного URI
                    return getFilePathFromUri(context, contentUri)
                }
            }
            
            // На Android 10 (API 29) и выше MediaStore.Images.Media.DATA считается устаревшим
            // и может возвращать null, поэтому используем другой подход
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Для Android 10+ используем относительный путь и имя файла
                val projection = arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH
                )
                
                context.contentResolver.query(
                    uri,
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
                
                // Если не удалось получить путь через MediaStore, пробуем через lastPathSegment
                uri.lastPathSegment?.let { segment ->
                    if (segment.contains("/")) {
                        return segment
                    }
                }
                
                // Для content URI возвращаем сам URI в виде строки, чтобы можно было
                // проверить, находится ли файл в директории приложения
                return uri.toString()
            } else {
                // Для Android 9 и ниже используем старый подход
                val projection = arrayOf(MediaStore.Images.Media.DATA)
                context.contentResolver.query(
                    uri,
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
            Timber.e(e, "Ошибка при получении пути к файлу из URI: $uri")
        }
        
        // Если все методы не сработали, возвращаем URI в виде строки
        return uri.toString()
    }

    /**
     * Получает имя файла из URI (расширенная версия)
     * В отличие от getFileName, дополнительно пробует получить имя из lastPathSegment,
     * если не удалось найти через MediaStore
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        try {
            // Специальная обработка для URI из MediaProvider Documents
            if (uri.authority == "com.android.providers.media.documents") {
                Timber.d("getFileNameFromUri: обнаружен URI MediaProvider Documents: $uri")
                
                // Извлекаем ID документа
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("image:")) {
                    // Извлекаем ID изображения
                    val id = docId.split(":")[1]
                    // Создаем новый URI для обращения к MediaStore
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toLong())
                    Timber.d("getFileNameFromUri: преобразованный URI: $contentUri")
                    
                    // Рекурсивно вызываем тот же метод для обработки преобразованного URI
                    return getFileNameFromUri(context, contentUri)
                }
            }
            
            // Сначала пробуем получить через простой метод
            var fileName = getFileName(context.contentResolver, uri)
            
            // Если не удалось, пробуем через lastPathSegment
            if (fileName == null) {
                uri.lastPathSegment?.let { segment ->
                    if (segment.contains(".")) {
                        fileName = segment
                    }
                }
            }
            
            return fileName
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении имени файла из URI")
            return null
        }
    }

    /**
     * Обрабатывает результат запроса удаления файла
     * 
     * Этот метод должен быть вызван из Activity.onActivityResult
     */
    fun handleDeleteFileRequest(resultCode: Int): Boolean {
        return resultCode == android.app.Activity.RESULT_OK
    }

    /**
     * Проверяет, включен ли режим замены файлов
     */
    fun isSaveModeReplace(context: Context): Boolean {
        return SettingsManager.getInstance(context).isSaveModeReplace()
    }

    /**
     * Проверяет, запущено ли сжатие вручную
     */
    fun isManualCompression(context: Context): Boolean {
        return !SettingsManager.getInstance(context).isAutoCompressionEnabled()
    }

    /**
     * Получает текущий режим сохранения из настроек
     */
    fun getSaveMode(context: Context): Int {
        return SettingsManager.getInstance(context).getSaveMode()
    }

    /**
     * Получает уровень качества сжатия из настроек
     */
    fun getCompressionQuality(context: Context): Int {
        return SettingsManager.getInstance(context).getCompressionQuality()
    }

    /**
     * Проверяет, можно ли удалить файл без запроса разрешения
     */
    fun canDeleteWithoutPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    /**
     * Получает путь директории из URI изображения
     */
    fun getDirectoryFromUri(context: Context, uri: Uri): String {
        try {
            // Специальная обработка для URI из MediaProvider Documents
            if (uri.authority == "com.android.providers.media.documents") {
                Timber.d("getDirectoryFromUri: обнаружен URI MediaProvider Documents: $uri")
                
                // Извлекаем ID документа
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("image:")) {
                    // Извлекаем ID изображения
                    val id = docId.split(":")[1]
                    // Создаем новый URI для обращения к MediaStore
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toLong())
                    Timber.d("getDirectoryFromUri: преобразованный URI: $contentUri")
                    
                    // Рекурсивно вызываем тот же метод для обработки преобразованного URI
                    return getDirectoryFromUri(context, contentUri)
                }
            }
            
            // Пытаемся получить RELATIVE_PATH для Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.Images.Media.RELATIVE_PATH)
                context.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH))
                        if (!relativePath.isNullOrEmpty()) {
                            // Убираем завершающий слеш
                            val path = if (relativePath.endsWith("/")) {
                                relativePath.substring(0, relativePath.length - 1)
                            } else {
                                relativePath
                            }
                            return path
                        }
                    }
                }
            }
            
            // Если не удалось получить RELATIVE_PATH, пытаемся получить полный путь
            val path = getFilePathFromUri(context, uri)
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
            Timber.e(e, "Ошибка при получении директории из URI: $uri")
        }
        
        // Возвращаем стандартный путь, если не удалось определить директорию
        return "${Environment.DIRECTORY_PICTURES}/${Constants.APP_DIRECTORY}"
    }

    /**
     * Проверяет, является ли файл временным (pending)
     */
    suspend fun isFilePending(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return@withContext cursor.getInt(0) == 1
                }
            }
            true // В случае ошибки считаем файл временным
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке состояния файла: $uri")
            true
        }
    }

    /**
     * Ожидает, пока файл станет доступным, используя таймер
     */
    suspend fun waitForFileAvailability(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val maxWaitTimeMs = 5000L // Максимальное время ожидания: 5 секунд
        val checkIntervalMs = 300L // Интервал проверки: 300 мс
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < maxWaitTimeMs) {
            // Проверяем размер файла
            val size = getFileSize(context, uri)
            if (size > 0 && !isFilePending(context, uri)) {
                // Файл доступен
                return@withContext true
            }
            
            // Файл недоступен, логируем и ждем
            val elapsedTime = System.currentTimeMillis() - startTime
            val remainingTime = maxWaitTimeMs - elapsedTime
            Timber.d("Файл недоступен (прошло ${elapsedTime}мс, осталось ${remainingTime}мс): размер = $size")
            
            // Ждем следующую проверку
            delay(checkIntervalMs)
        }
        
        Timber.d("Файл не стал доступен после ${maxWaitTimeMs}мс ожидания")
        return@withContext false
    }

    /**
     * Получает размер файла из MediaStore
     */
    suspend fun getFileSize(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.SIZE)
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    return@withContext cursor.getLong(sizeIndex)
                }
            }
            -1L
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении размера файла")
            -1L
        }
    }

    /**
     * Проверка валидности размера файла
     */
    fun isFileSizeValid(size: Long): Boolean {
        return size in Constants.MIN_FILE_SIZE..Constants.MAX_FILE_SIZE
    }

    /**
     * Создание временного файла для изображения
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
     */
    fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        }
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

    /**
     * Получает MIME тип файла по URI
     * @param context контекст
     * @param uri URI файла
     * @return MIME тип или null, если не удалось определить
     */
    fun getMimeType(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении MIME типа для $uri")
            null
        }
    }

    /**
     * Проверяет, является ли изображение скриншотом
     * @param context контекст
     * @param uri URI изображения
     * @return true если изображение является скриншотом, false в противном случае
     */
    fun isScreenshot(context: Context, uri: Uri): Boolean {
        try {
            // Получаем имя файла
            val fileName = getFileNameFromUri(context, uri)?.lowercase() ?: return false
            
            // Проверяем, содержит ли имя файла типичные для скриншотов паттерны
            return fileName.contains("screenshot") || 
                   fileName.contains("screen_shot") || 
                   fileName.contains("скриншот") || 
                   (fileName.contains("screen") && fileName.contains("shot"))
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке скриншота для $uri")
            return false
        }
    }
} 
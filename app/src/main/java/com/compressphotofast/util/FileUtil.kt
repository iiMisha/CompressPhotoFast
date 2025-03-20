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
import java.io.IOException
import java.io.ByteArrayOutputStream
import android.provider.OpenableColumns

/**
 * Утилитарный класс для работы с файлами
 */
object FileUtil {

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
     * Получает размер файла по URI
     * @param context контекст
     * @param uri URI файла
     * @return размер файла в байтах или 0 если не удалось определить
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
            Timber.e(e, "Ошибка при получении размера файла по URI: $uri")
            return 0L
        }
    }
    
    /**
     * Проверяет, находится ли файл в состоянии IS_PENDING
     * @param context контекст
     * @param uri URI файла
     * @return true если файл в состоянии IS_PENDING, false в противном случае
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
            // Если не удалось определить, предполагаем, что не в состоянии IS_PENDING
            return false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке состояния IS_PENDING у URI: $uri")
            return false
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
     */
    fun isScreenshot(context: Context, uri: Uri): Boolean {
        try {
            val fileName = getFileNameFromUri(context, uri)?.lowercase() ?: return false
            return fileName.contains("screenshot") || 
                   fileName.contains("screen_shot") || 
                   fileName.contains("скриншот") || 
                   (fileName.contains("screen") && fileName.contains("shot"))
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке скриншота для $uri")
            return false
        }
    }

    /**
     * Переименование исходного файла 
     * Используется перед созданием нового сжатого файла для гарантии сохранности оригинала
     * 
     * @param context Контекст приложения
     * @param uri URI исходного файла
     * @return URI переименованного файла или null при ошибке
     */
    suspend fun renameOriginalFile(
        context: Context,
        uri: Uri
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // Получаем информацию о файле
            val fileName = getFileNameFromUri(context, uri) ?: return@withContext null
            val fileExt = fileName.substringAfterLast(".", "")
            val fileBaseName = fileName.substringBeforeLast(".")
            
            // Создаем новое имя для оригинального файла, добавляя _original
            val backupFileName = "${fileBaseName}_original.${fileExt}"
            
            // Получаем директорию файла
            val directory = getDirectoryFromUri(context, uri) ?: return@withContext null
            
            // Обновляем запись в MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, backupFileName)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            
            // Обновляем файл через MediaStore
            context.contentResolver.update(uri, contentValues, null, null)
            
            // Завершаем IS_PENDING состояние, если необходимо
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
            
            Timber.d("Файл переименован: $fileName -> $backupFileName")
            return@withContext uri
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при переименовании файла: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Сохранение сжатого изображения напрямую из ByteArrayOutputStream
     * 
     * @param context Контекст приложения
     * @param outputStream ByteArrayOutputStream с сжатым изображением
     * @param fileName Имя файла для сохранения
     * @param directory Директория для сохранения
     * @param originalUri URI оригинального файла для получения EXIF данных
     * @param quality Качество сжатия (0-100)
     * @return URI сохраненного файла или null при ошибке
     */
    suspend fun saveCompressedImageFromStream(
        context: Context,
        outputStream: ByteArrayOutputStream,
        fileName: String,
        directory: String,
        originalUri: Uri,
        quality: Int = Constants.COMPRESSION_QUALITY_MEDIUM
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, directory)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            
            // Создаем новую запись
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Не удалось создать запись MediaStore")
            
            Timber.d("Создан URI для сжатого изображения: $uri")
            
            try {
                // Сначала записываем сжатое изображение
                context.contentResolver.openOutputStream(uri)?.use { outputFileStream ->
                    outputStream.writeTo(outputFileStream)
                    outputFileStream.flush()
                } ?: throw IOException("Не удалось открыть OutputStream")
                
                Timber.d("Данные изображения записаны в URI: $uri")
                
                // Сразу завершаем IS_PENDING состояние до обработки EXIF, чтобы файл стал доступен
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                    Timber.d("IS_PENDING статус сброшен для URI: $uri")
                }
                
                // Ждем, чтобы файл стал доступен в системе
                // Используем более длительное ожидание и проверяем, что файл действительно доступен
                val maxWaitTime = 2000L // Увеличиваем до 2 секунд
                val fileAvailable = waitForUriAvailability(context, uri, maxWaitTime)
                
                if (!fileAvailable) {
                    Timber.w("URI не стал доступен после ожидания: $uri")
                }
                
                // Копируем EXIF данные напрямую между URI
                var exifCopied = false
                try {
                    // Дополнительная задержка перед работой с EXIF
                    delay(300)
                    exifCopied = ExifUtil.copyExifDataBetweenUris(context, originalUri, uri)
                    Timber.d("Копирование EXIF данных между URI: ${if (exifCopied) "успешно" else "неудачно"}")
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при копировании EXIF данных между URI: ${e.message}")
                }
                
                // Добавляем маркер сжатия напрямую через URI
                var markerAdded = false
                try {
                    markerAdded = ExifUtil.markCompressedImageUri(context, uri, quality)
                    Timber.d("Добавление маркера сжатия в URI: ${if (markerAdded) "успешно" else "неудачно"}")
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при добавлении маркера сжатия в URI: ${e.message}")
                }
                
                // Проверяем успешность операций с EXIF
                if (exifCopied && markerAdded) {
                    Timber.d("EXIF данные и маркер сжатия успешно применены к URI: $uri")
                } else {
                    // Если первая попытка не удалась, делаем еще одну попытку после дополнительного ожидания
                    if (!exifCopied || !markerAdded) {
                        Timber.d("Первая попытка работы с EXIF была неудачной, выполняю повторную попытку после паузы")
                        delay(500) // Ждем еще полсекунды
                        
                        if (!exifCopied) {
                            try {
                                exifCopied = ExifUtil.copyExifDataBetweenUris(context, originalUri, uri)
                                Timber.d("Повторное копирование EXIF данных: ${if (exifCopied) "успешно" else "неудачно"}")
                            } catch (e: Exception) {
                                Timber.e(e, "Ошибка при повторном копировании EXIF данных: ${e.message}")
                            }
                        }
                        
                        if (!markerAdded) {
                            try {
                                markerAdded = ExifUtil.markCompressedImageUri(context, uri, quality)
                                Timber.d("Повторное добавление маркера сжатия: ${if (markerAdded) "успешно" else "неудачно"}")
                            } catch (e: Exception) {
                                Timber.e(e, "Ошибка при повторном добавлении маркера сжатия: ${e.message}")
                            }
                        }
                    }
                }
                
                // Верификация через чтение EXIF
                try {
                    delay(100) // Небольшая задержка перед проверкой
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val exif = ExifInterface(inputStream)
                        val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                        if (userComment?.contains("CompressPhotoFast_Compressed:$quality") == true) {
                            Timber.d("Финальная верификация успешна: маркер сжатия присутствует в URI")
                        } else {
                            Timber.w("Финальная верификация не удалась: маркер сжатия отсутствует в URI. UserComment: $userComment")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при финальной верификации: ${e.message}")
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при записи данных или обработке EXIF: ${e.message}")
                // Если произошла ошибка, изображение может быть сохранено частично, но без EXIF
            }
            
            return@withContext uri
            
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при сохранении сжатого изображения: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Проверяет доступность URI для операций с файлом, ожидая в течение указанного времени
     * 
     * @param context Контекст приложения
     * @param uri URI для проверки
     * @param maxWaitTimeMs Максимальное время ожидания в миллисекундах
     * @return true если URI стал доступен, false если время ожидания истекло
     */
    private suspend fun waitForUriAvailability(
        context: Context, 
        uri: Uri, 
        maxWaitTimeMs: Long = 1000
    ): Boolean = withContext(Dispatchers.IO) {
        Timber.d("Ожидание доступности URI: $uri, максимальное время: $maxWaitTimeMs мс")
        
        val startTime = System.currentTimeMillis()
        var isAvailable = false
        
        while (System.currentTimeMillis() - startTime < maxWaitTimeMs) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val available = inputStream.available()
                    inputStream.close()
                    
                    // Если доступны данные, считаем URI доступным
                    if (available > 0) {
                        Timber.d("URI стал доступен через ${System.currentTimeMillis() - startTime} мс, размер: $available байт")
                        isAvailable = true
                        break
                    } else {
                        Timber.d("URI открыт, но данные недоступны, повторная попытка...")
                    }
                } else {
                    Timber.d("Не удалось открыть поток для URI, повторная попытка...")
                }
            } catch (e: Exception) {
                Timber.d("Ошибка при проверке доступности URI: ${e.message}")
            }
            
            // Делаем паузу перед следующей попыткой
            delay(100)
        }
        
        if (!isAvailable) {
            Timber.w("Время ожидания истекло, URI не стал доступен за $maxWaitTimeMs мс")
        }
        
        return@withContext isAvailable
    }
} 
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
import com.compressphotofast.util.LogUtil
import android.content.Intent
import android.content.IntentSender
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.text.DecimalFormat

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
     * Добавляет суффикс _compressed к имени файла
     */
    fun createCompressedFileName(originalName: String): String {
        val dotIndex = originalName.lastIndexOf('.')
        return if (dotIndex > 0) {
            val baseName = originalName.substring(0, dotIndex)
            val extension = originalName.substring(dotIndex) // включая точку
            "${baseName}${Constants.COMPRESSED_FILE_SUFFIX}$extension"
        } else {
            "${originalName}${Constants.COMPRESSED_FILE_SUFFIX}"
        }
    }

    /**
     * Вставляет запись в MediaStore для изображения с проверкой существующих файлов
     * Централизованный метод для устранения дублирования кода
     */
    private suspend fun createMediaStoreEntry(
        context: Context,
        fileName: String,
        directory: String,
        mimeType: String = "image/jpeg"
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Нормализуем путь, добавляя "Pictures/" если путь не содержит "/"
                    val relativePath = if (directory.contains("/")) directory 
                                     else "Pictures/$directory"
                    
                    LogUtil.processInfo("Использую относительный путь для сохранения: $relativePath")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    // Для API < 29 нужно указать полный путь к файлу
                    val targetDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        directory
                    )
                    if (!targetDir.exists()) {
                        targetDir.mkdirs()
                    }
                    val filePath = File(targetDir, fileName).absolutePath
                    LogUtil.processInfo("Использую полный путь для сохранения (API < 29): $filePath")
                    put(MediaStore.Images.Media.DATA, filePath)
                }
            }
            
            // Проверка и обработка существующих файлов с таким именем, особая обработка для Android 11
            var finalFileName: String
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // Проверяем наличие файла с таким же именем в указанной директории
                    val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
                    val selectionArgs = arrayOf(fileName)
                    
                    var existingUri: Uri? = null
                    context.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Images.Media._ID),
                        selection,
                        selectionArgs,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idColumn = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                            if (idColumn != -1 && !cursor.isNull(idColumn)) {
                                val id = cursor.getLong(idColumn)
                                existingUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                                Timber.d("Найден существующий файл с таким же именем: $existingUri")
                            }
                        }
                    }
                    
                    // Если файл существует и включен режим замены, удаляем его
                    if (existingUri != null && isSaveModeReplace(context)) {
                        try {
                            context.contentResolver.delete(existingUri!!, null, null)
                            Timber.d("Существующий файл удален (режим замены)")
                        } catch (e: Exception) {
                            Timber.e(e, "Ошибка при удалении существующего файла: ${e.message}")
                        }
                    } else if (existingUri != null) {
                        // Если файл существует, но режим замены выключен, добавляем числовой индекс
                        val fileNameWithoutExt = fileName.substringBeforeLast(".")
                        val extension = if (fileName.contains(".")) ".${fileName.substringAfterLast(".")}" else ""
                        
                        // Проверяем существующие файлы и находим доступный индекс
                        var index = 1
                        var newFileName: String
                        var isFileExists = true
                        
                        while (isFileExists && index < 100) { // Ограничиваем до 100 попыток
                            newFileName = "${fileNameWithoutExt}_${index}${extension}"
                            
                            // Проверяем существует ли файл с таким именем
                            val existsSelection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
                            val existsArgs = arrayOf(newFileName)
                            val existsCursor = context.contentResolver.query(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                arrayOf(MediaStore.Images.Media._ID),
                                existsSelection,
                                existsArgs,
                                null
                            )
                            
                            isFileExists = (existsCursor?.count ?: 0) > 0
                            existsCursor?.close()
                            
                            if (!isFileExists) {
                                finalFileName = newFileName
                                contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
                                Timber.d("Режим замены выключен, используем новое имя с индексом: $finalFileName")
                                break
                            }
                            
                            index++
                        }
                        
                        // Если мы перебрали все индексы и не нашли свободный, используем временную метку как запасной вариант
                        if (isFileExists) {
                            val timestamp = System.currentTimeMillis()
                            finalFileName = "${fileNameWithoutExt}_${timestamp}${extension}"
                            contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
                            Timber.d("Не найден свободный индекс, используем временную метку: $finalFileName")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при проверке существующего файла: ${e.message}")
                }
            }
            
            // Создаем новую запись
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            
            if (uri != null) {
                Timber.d("Создан URI для изображения: $uri")
            } else {
                throw IOException("Не удалось создать запись MediaStore")
            }
            
            return@withContext uri
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при создании записи в MediaStore: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Сохраняет сжатое изображение из файла в галерею
     * Используя централизованную логику создания записи в MediaStore
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
            // Получаем путь к директории для сохранения
            val directory = if (isSaveModeReplace(context)) {
                // Если включен режим замены, сохраняем в той же директории
                getDirectoryFromUri(context, originalUri)
            } else {
                // Иначе сохраняем в директории приложения
                Constants.APP_DIRECTORY
            }
            
            Timber.d("saveCompressedImageToGallery: директория для сохранения: $directory")
            
            // Централизованное создание записи в MediaStore
            val uri = createMediaStoreEntry(context, fileName, directory ?: Constants.APP_DIRECTORY)
            
            if (uri == null) {
                Timber.e("saveCompressedImageToGallery: не удалось создать запись в MediaStore")
                return@withContext Pair(null, null)
            }
            
            // Копируем содержимое сжатого файла
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                compressedFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            // Завершаем IS_PENDING состояние
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
            
            // Возвращаем результат
            return@withContext Pair(uri, null)
        } catch (e: Exception) {
            Timber.e(e, "saveCompressedImageToGallery: ошибка при сохранении файла")
            return@withContext Pair(null, null)
        }
    }

    /**
     * Сохраняет сжатое изображение из потока
     * Использует централизованную логику создания записи в MediaStore 
     */
    suspend fun saveCompressedImageFromStream(
        context: Context,
        inputStream: InputStream,
        fileName: String,
        directory: String,
        originalUri: Uri,
        quality: Int = Constants.COMPRESSION_QUALITY_MEDIUM,
        exifDataMemory: Map<String, Any>? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // Централизованное создание записи в MediaStore
            val uri = createMediaStoreEntry(context, fileName, directory)
            
            if (uri == null) {
                LogUtil.error(originalUri, "Сохранение", "Не удалось создать запись в MediaStore")
                return@withContext null
            }
            
            try {
                // Записываем сжатое изображение
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        inputStream.copyTo(outputStream)
                    outputStream.flush()
                } ?: throw IOException("Не удалось открыть OutputStream")
                
                Timber.d("Данные изображения записаны в URI: $uri")
                
                // Сразу завершаем IS_PENDING состояние до обработки EXIF, чтобы файл стал доступен
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                    Timber.d("IS_PENDING статус сброшен для URI: $uri")
                }
                
                // Ждем, чтобы файл стал доступен в системе
                val maxWaitTime = 2000L
                val fileAvailable = waitForUriAvailability(context, uri, maxWaitTime)
                
                if (!fileAvailable) {
                    Timber.w("URI не стал доступен после ожидания: $uri")
                }
                
                // Специальная обработка для Android 11
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                    // Дополнительное ожидание для Android 11
                    delay(300)
                }
                
                // Делегируем всю работу с EXIF в ExifUtil
                val exifSuccess = ExifUtil.handleExifForSavedImage(
                    context, 
                    originalUri, 
                    uri, 
                    quality, 
                    exifDataMemory
                )
                
                Timber.d("Обработка EXIF данных: ${if (exifSuccess) "успешно" else "неудачно"}")
                
                return@withContext uri
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при записи данных: ${e.message}")
                // Если произошла ошибка, изображение может быть сохранено частично, но без EXIF
            }
            
            return@withContext uri
            
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при сохранении сжатого изображения: ${e.message}")
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
     * Централизованная проверка существования файла
     * Используется в разных методах вместо дублирования кода
     * 
     * @param context Контекст приложения
     * @param uri URI файла для проверки
     * @return true если файл существует, false в противном случае
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
            Timber.e(e, "Ошибка при проверке существования файла: $uri")
            return@withContext false
        }
    }

    /**
     * Удаляет файл по URI с централизованной логикой для всех версий Android
     * @return Boolean - успешность удаления или IntentSender для запроса разрешения на Android 10+
     */
    fun deleteFile(context: Context, uri: Uri): Any? {
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
            
            // Проверка разрешений и выбор способа удаления в зависимости от версии Android
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    LogUtil.processInfo("Удаляем файл через MediaStore (Android 10+)")
                    val result = context.contentResolver.delete(cleanUri, null, null) > 0
                    LogUtil.processInfo("Результат удаления через MediaStore: $result")
                    return result
                } catch (e: SecurityException) {
                    if (e is android.app.RecoverableSecurityException) {
                        LogUtil.processInfo("Требуется разрешение пользователя для удаления файла: $cleanUri")
                        return e.userAction.actionIntent.intentSender
                    } else {
                        throw e
                    }
                }
            } else {
                // Для более старых версий получаем путь к файлу и удаляем его напрямую
                val path = getFilePathFromUri(context, cleanUri)
                if (path != null) {
                    LogUtil.processInfo("Удаляем файл по пути (старый API): $path")
                    val file = File(path)
                    val result = file.delete()
                    LogUtil.processInfo("Результат удаления файла: $result")
                    return result
                }
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Удаление", "Ошибка при удалении файла", e)
        }
        return false
    }
    
    /**
     * Получает путь к файлу из URI
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
                        effectiveUri, // Используем effectiveUri
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
                    Timber.e(e, "Ошибка при получении пути: ${e.message}")
                }
                
                // Специальная обработка для Android 11 (API 30) - используем хелпер
                queryMediaStoreWithIdFallbackApi30(context, effectiveUri, projection)?.use { cursor -> // Используем effectiveUri
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
                effectiveUri.lastPathSegment?.let { segment -> // Используем effectiveUri
                    if (segment.contains("/")) {
                        return segment
                    }
                }
                
                // Для content URI возвращаем сам URI в виде строки, чтобы можно было
                // проверить, находится ли файл в директории приложения
                return effectiveUri.toString() // Используем effectiveUri
            } else {
                // Для Android 9 и ниже используем старый подход
                val projection = arrayOf(MediaStore.Images.Media.DATA)
                context.contentResolver.query(
                    effectiveUri, // Используем effectiveUri
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
            // Преобразуем MediaDocumentsUri, если необходимо
            val effectiveUri = convertMediaDocumentsUri(uri) ?: uri

            // Сначала пробуем получить через простой метод
            var fileName = getFileName(context.contentResolver, effectiveUri) // Используем effectiveUri

            // Специальная обработка для Android 11 (API 30) - используем хелпер
            if (fileName == null) {
                queryMediaStoreWithIdFallbackApi30(context, effectiveUri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME))?.use { cursor -> // Используем effectiveUri
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
                effectiveUri.lastPathSegment?.let { segment -> // Используем effectiveUri
                    if (segment.contains(".")) {
                        fileName = segment
                    } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && segment.toLongOrNull() != null) {
                        // Для Android 11, если lastPathSegment - это ID, пробуем получить имя через ID (вторая попытка через хелпер)
                         queryMediaStoreWithIdFallbackApi30(context, effectiveUri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME))?.use { cursor -> // Используем effectiveUri
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
        try {
            // Открываем настройки
        return SettingsManager.getInstance(context).isSaveModeReplace()
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении режима сохранения")
            return false // Значение по умолчанию
        }
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
            // Преобразуем MediaDocumentsUri, если необходимо
            val effectiveUri = convertMediaDocumentsUri(uri) ?: uri

            // Пытаемся получить RELATIVE_PATH для Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.Images.Media.RELATIVE_PATH)
                try {
                    context.contentResolver.query(
                        effectiveUri, // Используем effectiveUri
                        projection,
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                            if (pathIndex != -1 && !cursor.isNull(pathIndex)) {
                                val relativePath = cursor.getString(pathIndex)
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
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при запросе RELATIVE_PATH: ${e.message}")
                }
                
                // Специальная обработка для Android 11 (API 30) - используем хелпер
                queryMediaStoreWithIdFallbackApi30(context, effectiveUri, projection)?.use { cursor -> // Используем effectiveUri
                    if (cursor.moveToFirst()) {
                         val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                         if (pathIndex != -1 && !cursor.isNull(pathIndex)) {
                             val relativePath = cursor.getString(pathIndex)
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
            }
            
            // Если не удалось получить RELATIVE_PATH, пытаемся получить полный путь
            val path = getFilePathFromUri(context, effectiveUri) // Используем effectiveUri
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
        return "Pictures/${Constants.APP_DIRECTORY}"
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
            
            return false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке IS_PENDING: $uri", e)
            return false
        }
    }
    
    /**
     * Корутинная версия проверки статуса IS_PENDING
     * @param context контекст
     * @param uri URI файла
     * @return true если файл в состоянии IS_PENDING, false в противном случае
     */
    suspend fun isFilePendingSuspend(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        return@withContext isFilePending(context, uri)
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
        if (size <= 0) return "0 B"
        
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        
        return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
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
     * Переименовывает оригинальный файл, если это необходимо и возможно
     * 
     * Проверяет все условия:
     * 1. Не является ли URI из MediaDocumentProvider
     * 2. Включен ли режим замены (isSaveModeReplace)
     * 
     * @param context Контекст приложения
     * @param uri URI исходного файла
     * @return URI переименованного файла или null, если переименование не требуется или произошла ошибка
     */
    suspend fun renameOriginalFileIfNeeded(
        context: Context,
        uri: Uri
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // Проверяем тип URI
            val isMediaDocumentsUri = uri.authority == "com.android.providers.media.documents"
            
            // Проверяем режим сохранения
            val isSaveModeReplace = isSaveModeReplace(context)
            
            // Если URI из MediaDocumentProvider или режим замены выключен,
            // возвращаем исходный URI без переименования
            if (isMediaDocumentsUri || !isSaveModeReplace) {
                if (isMediaDocumentsUri) {
                    LogUtil.uriInfo(uri, "URI относится к MediaDocumentsProvider, пропускаем переименование")
                } else if (!isSaveModeReplace) {
                    LogUtil.uriInfo(uri, "Режим замены выключен, пропускаем переименование")
                }
                return@withContext uri
            }
            
            // Если все условия выполнены, переименовываем файл
            val backupUri = renameOriginalFile(context, uri)
            if (backupUri == null) {
                LogUtil.error(uri, "Переименование", "Не удалось переименовать оригинальный файл")
                return@withContext null
            }
            
            LogUtil.fileOperation(uri, "Переименование", "Оригинал → ${backupUri}")
            return@withContext backupUri
        } catch (e: Exception) {
            LogUtil.error(uri, "Переименование", "Ошибка при переименовании", e)
            return@withContext null
        }
    }

    /**
     * Переименовывает оригинальный файл, добавляя суффикс "_original" к имени файла.
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
            
            // Создаем новый URI, чтобы отличать его от исходного
            // Это позволит правильно определять, что файл был переименован
            val newUri = Uri.parse(uri.toString() + "#renamed_original")
            return@withContext newUri
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при переименовании файла: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Проверяет, существует ли URI в системе
     * @param context контекст
     * @param uri URI для проверки
     * @return true если URI существует, false в противном случае
     */
    fun isUriExists(context: Context, uri: Uri): Boolean {
        try {
            val exists = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                cursor.count > 0
            } ?: false
            
            if (!exists) {
                Timber.d("URI не существует: $uri")
            }
            
            return exists
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке существования URI: $uri")
            return false
        }
    }

    /**
     * Находит сжатую версию файла в директории приложения по имени оригинального файла
     * @param context контекст приложения
     * @param originalUri URI оригинального файла
     * @return URI сжатой версии файла или null, если не найдена
     */
    suspend fun findCompressedVersionByOriginalName(context: Context, originalUri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            // Получаем имя оригинального файла
            val originalFileName = getFileNameFromUri(context, originalUri)
            if (originalFileName.isNullOrEmpty()) {
                Timber.d("Не удалось получить имя оригинального файла: $originalUri")
                return@withContext null
            }
            
            // Разбиваем имя файла на основную часть и расширение
            val dotIndex = originalFileName.lastIndexOf('.')
            val fileBaseName: String
            val fileExtension: String
            
            if (dotIndex > 0) {
                fileBaseName = originalFileName.substring(0, dotIndex)
                fileExtension = originalFileName.substring(dotIndex) // включая точку
            } else {
                fileBaseName = originalFileName
                fileExtension = ""
            }
            
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
            
            Timber.d("Ищем сжатую версию файла с паттерном '$fileBaseName%$fileExtension' в папке ${Constants.APP_DIRECTORY}")
            
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
                    
                    Timber.i("Найдена сжатая версия для $originalFileName: $compressedUri (имя файла: $foundName)")
                    return@withContext compressedUri
                }
            }
            
            Timber.d("Сжатая версия для файла '$originalFileName' не найдена")
            return@withContext null
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при поиске сжатой версии файла: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Преобразует URI вида com.android.providers.media.documents в content:// URI
     * @param uri Исходный URI
     * @return Преобразованный content:// URI или null, если преобразование невозможно или не требуется
     */
    private fun convertMediaDocumentsUri(uri: Uri): Uri? {
        if (uri.authority == "com.android.providers.media.documents") {
            Timber.d("convertMediaDocumentsUri: обнаружен URI MediaProvider Documents: $uri")
            try {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("image:")) {
                    val id = docId.split(":")[1].toLong()
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    Timber.d("convertMediaDocumentsUri: преобразованный URI: $contentUri")
                    return contentUri
                } else {
                     Timber.w("convertMediaDocumentsUri: Неподдерживаемый тип документа: $docId")
                }
            } catch (e: Exception) {
                 Timber.e(e, "Ошибка при преобразовании MediaDocumentsUri: $uri")
            }
        }
        return null
    }

    /**
     * Выполняет запрос к MediaStore по ID из lastPathSegment URI.
     * Используется как запасной вариант для Android 11 (API 30).
     * @param context Контекст
     * @param uri URI, из которого будет извлечен ID
     * @param projection Необходимые колонки для запроса
     * @return Cursor с результатами запроса или null, если ID не найден, произошла ошибка или версия Android не 11.
     */
    private fun queryMediaStoreWithIdFallbackApi30(
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
                 Timber.d("Android 11 fallback: Пытаемся запросить по ID=$id из URI: $uri")
                return context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Images.Media._ID} = ?",
                    arrayOf(id.toString()),
                    null
                )
            } else {
                 Timber.d("Android 11 fallback: Не удалось извлечь ID из lastPathSegment: $idString")
            }
        } catch (e: Exception) {
            Timber.e(e, "Android 11 fallback: Ошибка при запросе по ID: ${e.message}")
        }
        return null
    }

    /**
     * Вставляет изображение в MediaStore
     * @param context Контекст приложения
     * @param file Файл с изображением
     * @param fileName Имя файла
     * @param directory Директория для сохранения
     * @param mimeType MIME-тип файла
     * @return URI вставленного изображения или null при ошибке
     */
    suspend fun insertImageIntoMediaStore(
        context: Context,
        file: File,
        fileName: String,
        directory: String,
        mimeType: String = "image/jpeg"
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // Проверяем существование файла
            if (!file.exists()) {
                LogUtil.error(null, "MediaStore", "Файл не существует: ${file.absolutePath}")
                return@withContext null
            }
            
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$directory")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: throw IOException("Ошибка при вставке в MediaStore")
            
            // Копируем данные
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                file.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IOException("Не удалось открыть выходной поток")
            
            // Завершаем операцию для Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
            
            LogUtil.fileOperation(uri, "MediaStore", "Успешно сохранено в медиатеку: $fileName")
            return@withContext uri
        } catch (e: Exception) {
            LogUtil.error(null, "MediaStore", "Ошибка при вставке в медиатеку", e)
            return@withContext null
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
                        val documentFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
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
            LogUtil.error(uri, "Получение даты изменения файла", e)
            return@withContext 0L
        }
    }

    /**
     * Возвращает ByteArray из входного потока
     */
    fun streamToByteArray(inputStream: InputStream): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        try {
            val buffer = ByteArray(1024)
            var length: Int
            while (inputStream.read(buffer).also { length = it } != -1) {
                byteArrayOutputStream.write(buffer, 0, length)
            }
            return byteArrayOutputStream.toByteArray()
        } finally {
            try {
                byteArrayOutputStream.close()
            } catch (_: Exception) {
                // Игнорируем ошибку закрытия
            }
        }
    }

    /**
     * Проверяет, включен ли режим замены оригинальных файлов
     */
    fun isSaveModeReplace(): Boolean {
        try {
            // В этой версии контекст отсутствует, поэтому мы не можем использовать SettingsManager напрямую
            // Вместо этого можно использовать AppContext или другой способ получения контекста
            // Временное решение - возвращаем значение по умолчанию
            Timber.d("Контекст отсутствует для isSaveModeReplace, возвращаем значение по умолчанию")
            return false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении режима сохранения")
            return false
        }
    }
} 
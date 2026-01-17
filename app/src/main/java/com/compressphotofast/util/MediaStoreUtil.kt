package com.compressphotofast.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Утилитарный класс для работы с MediaStore и сохранением файлов
 */
object MediaStoreUtil {
    /**
     * Вставляет запись в MediaStore для изображения с проверкой существующих файлов
     */
    suspend fun createMediaStoreEntry(
        context: Context,
        fileName: String,
        directory: String,
        mimeType: String = "image/jpeg",
        originalUri: Uri? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relativePath = if (FileOperationsUtil.isSaveModeReplace(context) && originalUri != null) {
                        // В режиме замены используем оригинальную директорию файла
                        val originalDirectory = UriUtil.getDirectoryFromUri(context, originalUri)
                        LogUtil.processInfo("Режим замены, использую оригинальную директорию: $originalDirectory")
                        originalDirectory
                    } else if (directory.isEmpty()) {
                        // Если директория пуста (например, для режима замены в корневой директории)
                        Environment.DIRECTORY_PICTURES
                    } else if (directory.startsWith(Environment.DIRECTORY_PICTURES)) {
                        // Если директория уже начинается с Pictures (например, Pictures или Pictures/Album), используем как есть
                        directory
                    } else if (directory.contains("/")) {
                        // Если директория уже содержит полный путь (например, Downloads/MyAlbum)
                        "${Environment.DIRECTORY_PICTURES}/$directory"
                    } else {
                        // Если указана только поддиректория, добавляем "Pictures/"
                        "${Environment.DIRECTORY_PICTURES}/$directory"
                    }
                    
                    LogUtil.processInfo("Использую относительный путь для сохранения: $relativePath")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    // Для API < 29 нужно указать полный путь к файлу
                    val targetDir = if (FileOperationsUtil.isSaveModeReplace(context) && originalUri != null) {
                        // В режиме замены используем оригинальную директорию
                        val originalDirectory = UriUtil.getDirectoryFromUri(context, originalUri)
                        File(Environment.getExternalStorageDirectory(), originalDirectory)
                    } else {
                        File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                            directory
                        )
                    }
                    
                    if (!targetDir.exists()) {
                        targetDir.mkdirs()
                    }
                    val filePath = File(targetDir, fileName).absolutePath
                    LogUtil.processInfo("Использую полный путь для сохранения (API < 29): $filePath")
                    put(MediaStore.Images.Media.DATA, filePath)
                }
            }
            
            // Проверяем нужно ли обрабатывать конфликты имен файлов
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
                                LogUtil.debug("MediaStore", "Найден существующий файл с таким же именем: $existingUri")
                            }
                        }
                    }
                    
                    // Если файл существует и включен режим замены, удаляем его
                    if (existingUri != null && FileOperationsUtil.isSaveModeReplace(context)) {
                        LogUtil.processInfo("[MediaStore] Найден существующий файл с именем '$fileName', включен режим замены - удаляем")
                        try {
                            context.contentResolver.delete(existingUri!!, null, null)
                            LogUtil.debug("MediaStore", "Существующий файл удален (режим замены)")
                        } catch (e: Exception) {
                            LogUtil.error(existingUri, "MediaStore", "Ошибка при удалении существующего файла", e)
                            LogUtil.processWarning("[MediaStore] ВНИМАНИЕ: Не удалось удалить существующий файл в режиме замены!")
                        }
                    } else if (existingUri != null) {
                        // Если файл существует, но режим замены выключен, добавляем числовой индекс
                        LogUtil.processInfo("[MediaStore] Найден существующий файл с именем '$fileName', режим замены выключен - добавляем индекс")
                        val fileNameWithoutExt = fileName.substringBeforeLast(".")
                        val extension = if (fileName.contains(".")) ".${fileName.substringAfterLast(".")}" else ""
                        
                        // Проверяем существующие файлы и находим доступный индекс
                        var index = 1
                        var isFileExists = true
                        
                        while (isFileExists && index < 100) { // Ограничиваем до 100 попыток
                            val newFileName = "${fileNameWithoutExt}_${index}${extension}"
                            
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
                                // Обновляем имя файла в contentValues
                                contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, newFileName)
                                LogUtil.debug("MediaStore", "Режим замены выключен, используем новое имя с индексом: $newFileName")
                                break
                            }
                            
                            index++
                        }
                        
                        // Если мы перебрали все индексы и не нашли свободный, используем временную метку
                        if (isFileExists) {
                            val timestamp = System.currentTimeMillis()
                            val timeBasedName = "${fileNameWithoutExt}_${timestamp}${extension}"
                            contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, timeBasedName)
                            LogUtil.debug("MediaStore", "Не найден свободный индекс, используем временную метку: $timeBasedName")
                        }
                    }
                } catch (e: Exception) {
                    LogUtil.errorWithException("Проверка существующего файла", e)
                }
            }
            
            // Создаем новую запись
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            
            if (uri != null) {
                LogUtil.debug("MediaStore", "Создан URI для изображения: $uri")
            } else {
                throw IOException("Не удалось создать запись MediaStore")
            }
            
            return@withContext uri
        } catch (e: Exception) {
            LogUtil.errorWithException("Создание записи в MediaStore", e)
            return@withContext null
        }
    }
    
    /**
     * Сбрасывает флаг IS_PENDING
     */
    suspend fun clearIsPendingFlag(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
            LogUtil.debug("MediaStore", "IS_PENDING статус сброшен для URI: $uri")
        }
    }
    
    /**
     * Вставляет изображение в MediaStore
     */
    suspend fun insertImageIntoMediaStore(
        context: Context,
        file: File,
        fileName: String,
        directory: String,
        mimeType: String = "image/jpeg",
        originalUri: Uri? = null
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
                    val relativePath = if (FileOperationsUtil.isSaveModeReplace(context) && originalUri != null) {
                        // В режиме замены используем оригинальную директорию файла
                        val originalDirectory = UriUtil.getDirectoryFromUri(context, originalUri)
                        LogUtil.processInfo("Режим замены, использую оригинальную директорию: $originalDirectory")
                        originalDirectory
                    } else if (directory.isEmpty()) {
                        Environment.DIRECTORY_PICTURES
                    } else if (directory.startsWith(Environment.DIRECTORY_PICTURES)) {
                        // Если директория уже начинается с Pictures (например, Pictures или Pictures/Album), используем как есть
                        directory
                    } else if (directory.contains("/")) {
                        // Если директория уже содержит полный путь (например, Downloads/MyAlbum)
                        "${Environment.DIRECTORY_PICTURES}/$directory"
                    } else {
                        // Если указана только поддиректория, добавляем "Pictures/"
                        "${Environment.DIRECTORY_PICTURES}/$directory"
                    }
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
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
     * Сохраняет сжатое изображение из файла в галерею
     */
    suspend fun saveCompressedImageToGallery(
        context: Context,
        compressedFile: File,
        fileName: String,
        originalUri: Uri
    ): Pair<Uri?, Any?> = withContext(Dispatchers.IO) {
        LogUtil.debug("FileUtil", "Начало сохранения файла: $fileName")
        LogUtil.debug("FileUtil", "Размер сжатого файла: ${compressedFile.length()} байт")
        LogUtil.debug("FileUtil", "Режим замены оригинальных файлов: ${FileOperationsUtil.isSaveModeReplace(context)}")
        
        try {
            // Получаем путь к директории для сохранения
            val directory = if (FileOperationsUtil.isSaveModeReplace(context)) {
                // Если включен режим замены, сохраняем в той же директории
                UriUtil.getDirectoryFromUri(context, originalUri)
            } else {
                // Иначе сохраняем в директории приложения
                Constants.APP_DIRECTORY
            }
            
            LogUtil.debug("FileUtil", "Директория для сохранения: $directory")
            
            // Централизованное создание записи в MediaStore
            val uri = createMediaStoreEntry(context, fileName, directory, originalUri = originalUri)
            
            if (uri == null) {
                LogUtil.errorSimple("FileUtil", "Не удалось создать запись в MediaStore")
                return@withContext Pair(null, null)
            }
            
            // Копируем содержимое сжатого файла
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                compressedFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            // Завершаем IS_PENDING состояние
            clearIsPendingFlag(context, uri)
            
            // Возвращаем результат
            return@withContext Pair(uri, null)
        } catch (e: Exception) {
            LogUtil.errorWithException("Сохранение файла в галерею", e)
            return@withContext Pair(null, null)
        }
    }
    
    /**
     * Сохраняет сжатое изображение из потока
     *
     * @param context Контекст приложения
     * @param inputStream Входной поток с сжатым изображением
     * @param fileName Имя файла для сохранения
     * @param directory Директория для сохранения
     * @param originalUri URI исходного файла
     * @param quality Качество сжатия
     * @param exifDataMemory EXIF данные для сохранения
     * @param mimeType MIME тип для сохранения (по умолчанию "image/jpeg")
     */
    suspend fun saveCompressedImageFromStream(
        context: Context,
        inputStream: InputStream,
        fileName: String,
        directory: String,
        originalUri: Uri,
        quality: Int = Constants.COMPRESSION_QUALITY_MEDIUM,
        exifDataMemory: Map<String, Any>? = null,
        mimeType: String = "image/jpeg"
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            LogUtil.debug("FileUtil", "Директория для сохранения: $directory")
            LogUtil.processInfo("[MediaStoreUtil] Создание записи MediaStore для файла: $fileName (режим замены: ${FileOperationsUtil.isSaveModeReplace(context)})")
            LogUtil.debug("FileUtil", "MIME тип для сохранения: $mimeType")

            // Централизованное создание записи в MediaStore с правильным MIME типом
            val uri = createMediaStoreEntry(context, fileName, directory, mimeType, originalUri)
            
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
                
                LogUtil.debug("FileUtil", "Данные изображения записаны в URI: $uri")
                
                // Сразу завершаем IS_PENDING состояние до обработки EXIF, чтобы файл стал доступен
                clearIsPendingFlag(context, uri)
                
                // Ждем, чтобы файл стал доступен в системе
                val maxWaitTime = 2000L
                val fileAvailable = waitForUriAvailability(context, uri, maxWaitTime)
                
                if (!fileAvailable) {
                    LogUtil.processWarning("URI не стал доступен после ожидания: $uri")
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
                
                LogUtil.debug("FileUtil", "Обработка EXIF данных: ${if (exifSuccess) "успешно" else "неудачно"}")
                
                return@withContext uri
            } catch (e: Exception) {
                LogUtil.errorWithException("Запись данных изображения", e)
                // Если произошла ошибка, изображение может быть сохранено частично, но без EXIF
            }
            
            return@withContext uri
            
        } catch (e: Exception) {
            LogUtil.errorWithException("Сохранение сжатого изображения", e)
            return@withContext null
        }
    }
    
    /**
     * Проверяет доступность URI для операций с файлом, ожидая в течение указанного времени
     */
    suspend fun waitForUriAvailability(
        context: Context, 
        uri: Uri, 
        maxWaitTimeMs: Long = 1000
    ): Boolean = withContext(Dispatchers.IO) {
        LogUtil.debug("FileUtil", "Ожидание доступности URI: $uri, максимальное время: $maxWaitTimeMs мс")
        
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
                        LogUtil.debug("FileUtil", "URI стал доступен через ${System.currentTimeMillis() - startTime} мс, размер: $available байт")
                        isAvailable = true
                        break
                    } else {
                        LogUtil.debug("FileUtil", "URI открыт, но данные недоступны, повторная попытка...")
                    }
                } else {
                    LogUtil.debug("FileUtil", "Не удалось открыть поток для URI, повторная попытка...")
                }
            } catch (e: Exception) {
                LogUtil.debug("FileUtil", "Ошибка при проверке доступности URI: ${e.message}")
            }
            
            // Делаем паузу перед следующей попыткой
            delay(100)
        }
        
        if (!isAvailable) {
            LogUtil.processWarning("Время ожидания истекло, URI не стал доступен за $maxWaitTimeMs мс")
        }
        
        return@withContext isAvailable
    }
}
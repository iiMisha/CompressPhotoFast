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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
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

                // Устанавливаем DATE_ADDED и DATE_MODIFIED для корректной работы на всех устройствах
                MediaStoreDateUtil.setCreationTimestamp(this, System.currentTimeMillis())
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
                            }
                        }
                    }
                    
                    // Если файл существует и включен режим замены, удаляем его
                    if (existingUri != null && FileOperationsUtil.isSaveModeReplace(context)) {
                        try {
                            context.contentResolver.delete(existingUri!!, null, null)
                        } catch (e: Exception) {
                            LogUtil.error(existingUri, "MediaStore", "Ошибка при удалении существующего файла в режиме замены", e)
                            LogUtil.processWarning("[MediaStore] ВНИМАНИЕ: Не удалось удалить существующий файл, будет создан дубликат!")
                            // Метрика для отслеживания проблем с удалением
                            StatsTracker.recordDeleteFailure(existingUri)
                        }
                    } else if (existingUri != null) {
                        // Если файл существует, но режим замены выключен, добавляем числовой индекс
                        val fileNameWithoutExt = fileName.substringBeforeLast(".")
                        val extension = if (fileName.contains(".")) ".${fileName.substringAfterLast(".")}" else ""

                        // OPTIMIZED: Генерируем ВСЕ возможные имена для проверки (до 100)
                        val fileNamesToCheck = mutableListOf(fileName)
                        for (i in 1 until 100) {
                            fileNamesToCheck.add("${fileNameWithoutExt}_${i}${extension}")
                        }

                        // OPTIMIZED: ОДИН запрос с IN clause для проверки всех имён сразу
                        val placeholders = fileNamesToCheck.map { "?" }.joinToString(",")
                        val batchSelection = "${MediaStore.Images.Media.DISPLAY_NAME} IN ($placeholders)"
                        val batchArgs = fileNamesToCheck.toTypedArray()

                        val existingNames = mutableSetOf<String>()
                        context.contentResolver.query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                            batchSelection,
                            batchArgs,
                            null
                        )?.use { cursor ->
                            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                            while (cursor.moveToNext()) {
                                existingNames.add(cursor.getString(nameColumn))
                            }
                        }

                        // Находим первый свободный индекс
                        var foundFreeName = false
                        for ((index, name) in fileNamesToCheck.withIndex()) {
                            if (!existingNames.contains(name)) {
                                contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, name)
                                foundFreeName = true
                                break
                            }
                        }

                        // Если все 100 индексов заняты, используем временную метку
                        if (!foundFreeName) {
                            val timestamp = System.currentTimeMillis()
                            val timeBasedName = "${fileNameWithoutExt}_${timestamp}${extension}"
                            contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, timeBasedName)
                        }
                    }
                } catch (e: Exception) {
                    LogUtil.errorWithException("Проверка существующего файла", e)
                }
            }
            
            // Создаем новую запись
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            
            if (uri == null) {
                throw IOException("Не удалось создать запись MediaStore")
            }
            
            return@withContext uri
        } catch (e: Exception) {
            LogUtil.errorWithException("Создание записи в MediaStore", e)
            return@withContext null
        }
    }

    /**
     * Создает запись в MediaStore с поддержкой режима обновления (без появления "~2")
     * Возвращает Pair<Uri, Boolean>, где:
     * - Uri: URI файла (существующего или нового)
     * - Boolean: true если режим обновления (overwrite), false если режим создания
     *
     * В режиме замены возвращает URI существующего файла WITHOUT удаления,
     * что позволяет напрямую перезаписать его через OutputStream без race condition
     */
    suspend fun createMediaStoreEntryV2(
        context: Context,
        fileName: String,
        directory: String,
        mimeType: String = "image/jpeg",
        originalUri: Uri? = null
    ): Pair<Uri?, Boolean> = withContext(Dispatchers.IO) {
        try {
            val isReplaceMode = FileOperationsUtil.isSaveModeReplace(context)

            // КРИТИЧЕСКО: Вычисляем targetRelativePath СРАЗУ, чтобы использовать в запросах
            // Это устраняет дублирование кода и обеспечивает правильный поиск файла
            val targetRelativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var path = if (isReplaceMode && originalUri != null) {
                    val originalDirectory = UriUtil.getDirectoryFromUri(context, originalUri)
                    originalDirectory
                } else if (directory.isEmpty()) {
                    Environment.DIRECTORY_PICTURES
                } else if (directory.startsWith(Environment.DIRECTORY_PICTURES)) {
                    directory
                } else if (directory.contains("/")) {
                    "${Environment.DIRECTORY_PICTURES}/$directory"
                } else {
                    "${Environment.DIRECTORY_PICTURES}/$directory"
                }

                // КРИТИЧЕСКО: Нормализуем путь - добавляем слеш в конце, если его нет
                // MediaStore хранит RELATIVE_PATH с завершающим слешем (например "Pictures/")
                if (!path.endsWith("/")) {
                    path = "$path/"
                }

                path
            } else {
                "" // Не используется для Android < 10
            }

            // Проверяем наличие файла с таким же именем (только для Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // Пакетная проверка (группируем с другими файлами из той же директории)
                    val existingFiles = batchCheckFilesExist(
                        context,
                        listOf(fileName),
                        targetRelativePath
                    )
                    val existingUri = existingFiles[fileName]

                    // Если файл существует И режим замены: возвращаем existingUri с флагом true
                    // НЕ удаляем файл здесь - будем перезаписывать напрямую через OutputStream
                    if (shouldUseUpdatePath(existingUri, isReplaceMode)) {
                        return@withContext Pair(existingUri, true) // true = режим обновления
                    }

                    // Если файл существует, но режим НЕ замены: добавляем числовой индекс
                    if (existingUri != null && !isReplaceMode) {
                        // Логика добавления индекса будет ниже при создании contentValues
                    }

                } catch (e: Exception) {
                    LogUtil.errorWithException("Проверка существующего файла", e)
                }
            }

            // Создаем contentValues для нового файла
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // ИСПРАВЛЕНИЕ: Используем targetRelativePath, вычисленный выше
                    put(MediaStore.Images.Media.RELATIVE_PATH, targetRelativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    // Для API < 29
                    val targetDir = if (isReplaceMode && originalUri != null) {
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

                // Устанавливаем DATE_ADDED и DATE_MODIFIED для корректной работы на всех устройствах
                MediaStoreDateUtil.setCreationTimestamp(this, System.currentTimeMillis())
            }

            // Обработка конфликта имен для режима отдельной папки (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isReplaceMode) {
                try {
                    var existingUri: Uri? = null
                    // ИСПРАВЛЕНИЕ: Используем OR условие для проверки обоих вариантов пути
                    val pathWithoutSlash = targetRelativePath.trimEnd('/')
                    val pathWithSlash = if (!pathWithoutSlash.endsWith("/")) "$pathWithoutSlash/" else pathWithoutSlash

                    val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND (${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?)"
                    val selectionArgs = arrayOf(fileName, pathWithSlash, pathWithoutSlash)

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
                            }
                        }
                    }

                    if (existingUri != null) {
                        val fileNameWithoutExt = fileName.substringBeforeLast(".")
                        val extension = if (fileName.contains(".")) ".${fileName.substringAfterLast(".")}" else ""

                        // OPTIMIZED: Генерируем ВСЕ возможные имена для проверки (до 100)
                        val fileNamesToCheck = mutableListOf(fileName)
                        for (i in 1 until 100) {
                            fileNamesToCheck.add("${fileNameWithoutExt}_${i}${extension}")
                        }

                        // OPTIMIZED: ОДИН запрос с IN clause для проверки всех имён сразу
                        val placeholders = fileNamesToCheck.map { "?" }.joinToString(",")
                        val batchSelection = "${MediaStore.Images.Media.DISPLAY_NAME} IN ($placeholders) AND (${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?)"
                        val batchArgs = fileNamesToCheck.toTypedArray() + arrayOf(pathWithSlash, pathWithoutSlash)

                        val existingNames = mutableSetOf<String>()
                        context.contentResolver.query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                            batchSelection,
                            batchArgs,
                            null
                        )?.use { cursor ->
                            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                            while (cursor.moveToNext()) {
                                existingNames.add(cursor.getString(nameColumn))
                            }
                        }

                        // Находим первый свободный индекс
                        var foundFreeName = false
                        for (name in fileNamesToCheck) {
                            if (!existingNames.contains(name)) {
                                contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, name)
                                foundFreeName = true
                                break
                            }
                        }

                        // Если все 100 индексов заняты, используем временную метку
                        if (!foundFreeName) {
                            val timestamp = System.currentTimeMillis()
                            val timeBasedName = "${fileNameWithoutExt}_${timestamp}${extension}"
                            contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, timeBasedName)
                        }
                    }
                } catch (e: Exception) {
                    LogUtil.errorWithException("Обработка конфликта имен", e)
                }
            }

            // Создаем новую запись
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (uri == null) {
                throw IOException("Не удалось создать запись MediaStore")
            }

            return@withContext Pair(uri, false) // false = режим создания

        } catch (e: Exception) {
            LogUtil.errorWithException("Создание записи в MediaStore V2", e)
            return@withContext Pair(null, false)
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

                // Устанавливаем DATE_ADDED и DATE_MODIFIED для корректной работы на всех устройствах
                MediaStoreDateUtil.setCreationTimestamp(this, System.currentTimeMillis())
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
        try {
            // Получаем путь к директории для сохранения
            val directory = if (FileOperationsUtil.isSaveModeReplace(context)) {
                // Если включен режим замены, сохраняем в той же директории
                UriUtil.getDirectoryFromUri(context, originalUri)
            } else {
                // Иначе сохраняем в директории приложения
                Constants.APP_DIRECTORY
            }

            // Используем новую версию с поддержкой режима обновления
            val (uri, isUpdateMode) = createMediaStoreEntryV2(context, fileName, directory, "image/jpeg", originalUri)

            if (uri == null) {
                return@withContext Pair(null, null)
            }

            if (isUpdateMode) {
                // Режим замены: перезаписываем существующий файл напрямую
                // Сбрасываем IS_PENDING флаг перед обновлением
                clearIsPendingFlag(context, uri)

                // Перезаписываем файл напрямую
                compressedFile.inputStream().use { inputStream ->
                    val updateSuccess = safeUpdateExistingFile(context, uri, inputStream)

                    if (!updateSuccess) {
                        return@withContext Pair(null, null)
                    }
                }
            } else {
                // Режим создания: копируем в новый файл
                // Копируем содержимое сжатого файла
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    compressedFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }

            // Завершаем IS_PENDING состояние
            clearIsPendingFlag(context, uri)

            // Инвалидируем кэш URI после успешного сохранения
            UriUtil.invalidateUriExistsCache(uri)

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
            // Используем новую версию с поддержкой режима обновления
            val (uri, isUpdateMode) = createMediaStoreEntryV2(context, fileName, directory, mimeType, originalUri)

            if (uri == null) {
                LogUtil.error(originalUri, "Сохранение", "Не удалось создать запись в MediaStore")
                return@withContext null
            }

            try {
                if (isUpdateMode) {
                    // Режим замены: перезаписываем существующий файл напрямую
                    // Сбрасываем IS_PENDING флаг перед обновлением (если он был установлен)
                    clearIsPendingFlag(context, uri)

                    // Перезаписываем файл напрямую
                    val updateSuccess = safeUpdateExistingFile(context, uri, inputStream)

                    if (!updateSuccess) {
                        // Fallback: пытаемся создать новый файл
                        val fallbackResult = createMediaStoreEntry(context, "${fileName}_fallback", directory, mimeType, originalUri)
                        if (fallbackResult != null) {
                            try {
                                context.contentResolver.openOutputStream(fallbackResult)?.use { outputStream ->
                                    inputStream.resetOrCopy(context).use { resetStream ->
                                        // Используем потоковое копирование вместо readBytes() для избежания OOM
                                        resetStream.copyTo(outputStream, bufferSize = 8192)
                                    }
                                }
                                clearIsPendingFlag(context, fallbackResult)
                                return@withContext fallbackResult
                            } catch (e: Exception) {
                                LogUtil.error(originalUri, "Сохранение через fallback", "❌ Критическая ошибка при сохранении через fallback: ${e.message}", e)
                                NotificationUtil.showErrorNotification(
                                    context = context,
                                    title = "Ошибка сохранения",
                                    message = "Не удалось сохранить сжатое изображение через fallback. Попробуйте ещё раз."
                                )
                                return@withContext null
                            }
                        }
                        return@withContext null
                    }

                } else {
                    // Режим создания: записываем в новый файл
                    // Записываем сжатое изображение
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        inputStream.copyTo(outputStream)
                        outputStream.flush()
                    } ?: throw IOException("Не удалось открыть OutputStream")
                }

                // Сразу завершаем IS_PENDING состояние до обработки EXIF, чтобы файл стал доступен
                clearIsPendingFlag(context, uri)

                // Ждем, чтобы файл стал доступен в системе
                val maxWaitTime = 2000L
                waitForUriAvailability(context, uri, maxWaitTime)

                // Специальная обработка для Android 11
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                    // Дополнительное ожидание для Android 11
                    delay(300)
                }

                // Делегируем всю работу с EXIF в ExifUtil
                ExifUtil.handleExifForSavedImage(
                    context,
                    originalUri,
                    uri,
                    quality,
                    exifDataMemory
                )

                // Инвалидируем кэш URI после успешного сохранения
                UriUtil.invalidateUriExistsCache(uri)

                return@withContext uri
            } catch (e: Exception) {
                LogUtil.errorWithException("Запись данных изображения", e)
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
        val startTime = System.currentTimeMillis()
        var isAvailable = false

        while (System.currentTimeMillis() - startTime < maxWaitTimeMs) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val available = inputStream.available()
                    inputStream.close()

                    if (available > 0) {
                        isAvailable = true
                        break
                    }
                }
            } catch (e: Exception) {
                LogUtil.warning(uri, "MediaStore", "Ошибка при проверке доступности URI: ${e.message}")
            }

            delay(100)
        }

        return@withContext isAvailable
    }

    /**
     * Безопасно обновляет существующий файл, перезаписывая его данными из входного потока
     * Используется в режиме замены для исключения race condition и появления "~2" в именах
     *
     * @param context Контекст приложения
     * @param existingUri URI существующего файла для обновления
     * @param inputData Входной поток с новыми данными
     * @return true если обновление успешно, false в противном случае
     */
    private suspend fun safeUpdateExistingFile(
        context: Context,
        existingUri: Uri,
        inputData: InputStream
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Используем "wt" (write + truncate) чтобы файл был усечен до записи
            context.contentResolver.openOutputStream(existingUri, "wt")?.use { outputStream ->
                inputData.copyTo(outputStream)
                outputStream.flush()
            } ?: throw IOException("Не удалось открыть OutputStream для URI: $existingUri")
            true
        } catch (e: FileNotFoundException) {
            LogUtil.error(existingUri, "MediaStore", "Файл не найден при обновлении: ${e.message}", e)
            false
        } catch (e: IOException) {
            LogUtil.error(existingUri, "MediaStore", "Ошибка обновления файла", e)
            false
        } catch (e: Exception) {
            LogUtil.error(existingUri, "MediaStore", "Ошибка обновления файла", e)
            false
        }
    }

    /**
     * Определяет, следует ли использовать путь обновления (overwrite) вместо создания нового файла
     *
     * @param existingUri URI существующего файла (null если файл не существует)
     * @param isReplaceMode Включен ли режим замены
     * @return true если нужно использовать обновление, false если создавать новый файл
     */
    private fun shouldUseUpdatePath(
        existingUri: Uri?,
        isReplaceMode: Boolean
    ): Boolean = existingUri != null && isReplaceMode

    /**
     * Вспомогательный extension метод для InputStream
     * Пытается сбросить поток в начало (если поддерживается), иначе использует временный файл
     * Используется в fallback сценариях при повторной попытке записи
     * 
     * OPTIMIZED: Использует временный файл вместо чтения в память для избежания OOM
     * при обработке больших изображений (50MP+)
     */
    private fun InputStream.resetOrCopy(context: Context): InputStream {
        return try {
            // Проверяем, поддерживает ли поток mark/reset
            if (markSupported()) {
                reset()
                this
            } else {
                // Используем временный файл вместо памяти для избежания OOM
                val tempFile = File.createTempFile("stream_cache", ".tmp", context.cacheDir)
                tempFile.deleteOnExit()
                java.io.FileOutputStream(tempFile).use { output ->
                    this.copyTo(output, bufferSize = 8192)
                }
                tempFile.inputStream()
            }
        } catch (e: Exception) {
            // Fallback: используем временный файл
            val tempFile = File.createTempFile("stream_cache_fallback", ".tmp", context.cacheDir)
            tempFile.deleteOnExit()
            java.io.FileOutputStream(tempFile).use { output ->
                this.copyTo(output, bufferSize = 8192)
            }
            tempFile.inputStream()
        }
    }

    /**
     * Пакетная проверка конфликтов имен файлов в MediaStore
     *
     * Использует один запрос с IN clause для проверки всех файлов одновременно,
     * что значительно снижает количество запросов при пакетной обработке.
     *
     * Пример: для 100 файлов выполняется 1 запрос вместо 100 отдельных запросов.
     *
     * @param context Контекст приложения
     * @param fileNames Список имен файлов для проверки
     * @param relativePath Относительный путь для фильтрации (только для Android 10+).
     *                     Если null, проверяется без учета пути (может давать ложные срабатывания).
     * @return Map где ключ = имя файла, значение = существующий Uri (или null если конфликта нет)
     */
    suspend fun checkFileNameConflictsBatch(
        context: Context,
        fileNames: List<String>,
        relativePath: String? = null
    ): Map<String, Uri?> = withContext(Dispatchers.IO) {
        if (fileNames.isEmpty()) return@withContext emptyMap()

        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME
            )

            // Создаем плейсхолдеры для каждого имени файла (?, ?, ?, ...)
            val placeholders = fileNames.map { "?" }.joinToString(",")

            // Формируем условие выборки
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
                // Для Android 10+ проверяем с учетом пути
                val pathWithoutSlash = relativePath.trimEnd('/')
                val pathWithSlash = if (!pathWithoutSlash.endsWith("/")) "$pathWithoutSlash/" else pathWithoutSlash
                "${MediaStore.Images.Media.DISPLAY_NAME} IN ($placeholders) AND (${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?)"
            } else {
                // Для Android < 10 или если путь не указан
                "${MediaStore.Images.Media.DISPLAY_NAME} IN ($placeholders)"
            }

            // Формируем аргументы выборки
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
                val pathWithoutSlash = relativePath.trimEnd('/')
                val pathWithSlash = if (!pathWithoutSlash.endsWith("/")) "$pathWithoutSlash/" else pathWithoutSlash
                fileNames.toTypedArray() + arrayOf(pathWithSlash, pathWithoutSlash)
            } else {
                fileNames.toTypedArray()
            }

            val conflicts = mutableMapOf<String, Uri?>()

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn)
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    conflicts[name] = uri
                }
            }

            // Для файлов без конфликтов добавляем null
            fileNames.forEach { fileName ->
                if (!conflicts.containsKey(fileName)) {
                    conflicts[fileName] = null
                }
                }

            conflicts
        } catch (e: Exception) {
            LogUtil.error(Uri.EMPTY, "MediaStore", "Критическая ошибка при проверке конфликтов имён: ${e.message}", e)
            // Возвращаем пустую карту, но логируем ошибку для мониторинга
            fileNames.associateWith { null }
        }
    }

    /**
     * Пакетная проверка существования файлов
     *
     * @param context Контекст приложения
     * @param fileNames Список имен файлов для проверки
     * @param relativePath Относительный путь для фильтрации (только для Android 10+)
     * @return Map где ключ = имя файла, значение = Uri (существующий) или null
     */
    suspend fun batchCheckFilesExist(
        context: Context,
        fileNames: List<String>,
        relativePath: String? = null
    ): Map<String, Uri?> = withContext(Dispatchers.IO) {
        if (fileNames.isEmpty()) return@withContext emptyMap<String, Uri?>()

        try {
            val pathWithoutSlash = relativePath?.trimEnd('/')
            val pathWithSlash = if (!pathWithoutSlash.isNullOrEmpty() && !pathWithoutSlash.endsWith("/")) {
                "$pathWithoutSlash/"
            } else {
                pathWithoutSlash ?: ""
            }

            val placeholders = fileNames.map { "?" }.joinToString(",")

            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
                "${MediaStore.Images.Media.DISPLAY_NAME} IN ($placeholders) AND " +
                "(${MediaStore.Images.Media.RELATIVE_PATH} = ? OR " +
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?)"
            } else {
                "${MediaStore.Images.Media.DISPLAY_NAME} IN ($placeholders)"
            }

            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
                (fileNames + listOf(pathWithSlash, pathWithoutSlash)).toTypedArray()
            } else {
                fileNames.toTypedArray()
            }

            val results = mutableMapOf<String, Uri?>()

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn)
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    results[name] = uri
                }
            }

            // Для файлов не найденных в запросе добавляем null
            fileNames.forEach { fileName ->
                if (!results.containsKey(fileName)) {
                    results[fileName] = null
                }
            }

            results
        } catch (e: Exception) {
            LogUtil.error(Uri.EMPTY, "MediaStore", "Ошибка при пакетной проверке", e)
            // Fallback к поодиночным запросам
            fileNames.associateWith { null }
        }
    }
}
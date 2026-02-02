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

                            isFileExists = context.contentResolver.query(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                arrayOf(MediaStore.Images.Media._ID),
                                existsSelection,
                                existsArgs,
                                null
                            )?.use { cursor ->
                                (cursor.count ?: 0) > 0
                            } ?: false

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
                    LogUtil.processInfo("Режим замены, использую оригинальную директорию: $originalDirectory")
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
                    // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: ищем файл С УЧЕТОМ директории (DISPLAY_NAME + RELATIVE_PATH)
                    // Используем OR условие для проверки обоих вариантов пути (со слешем и без)
                    val pathWithoutSlash = targetRelativePath.trimEnd('/')
                    val pathWithSlash = if (!pathWithoutSlash.endsWith("/")) "$pathWithoutSlash/" else pathWithoutSlash

                    LogUtil.debug("MediaStore", "Поиск файла: fileName='$fileName', pathWithSlash='$pathWithSlash', pathWithoutSlash='$pathWithoutSlash'")

                    val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND (${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?)"
                    val selectionArgs = arrayOf(fileName, pathWithSlash, pathWithoutSlash)

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
                                LogUtil.debug("MediaStore", "Найден существующий файл в директории $targetRelativePath: $existingUri")
                            }
                        }
                    }

                    // Если файл существует И режим замены: возвращаем existingUri с флагом true
                    // НЕ удаляем файл здесь - будем перезаписывать напрямую через OutputStream
                    if (shouldUseUpdatePath(existingUri, isReplaceMode)) {
                        LogUtil.processInfo("[MediaStore] Режим замены: используем существующий URI=$existingUri без удаления")
                        return@withContext Pair(existingUri, true) // true = режим обновления
                    }

                    // Если файл существует, но режим НЕ замены: добавляем числовой индекс
                    if (existingUri != null && !isReplaceMode) {
                        LogUtil.processInfo("[MediaStore] Режим отдельной папки: файл существует, добавляем индекс")
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
                    LogUtil.processInfo("Использую относительный путь для сохранения: $targetRelativePath")
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

                        var index = 1
                        var isFileExists = true

                        while (isFileExists && index < 100) {
                            val newFileName = "${fileNameWithoutExt}_${index}${extension}"

                            // ИСПРАВЛЕНИЕ: Проверяем существование файла с новым именем в той же директории
                            val existsSelection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND (${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?)"
                            val existsArgs = arrayOf(newFileName, pathWithSlash, pathWithoutSlash)

                            isFileExists = context.contentResolver.query(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                arrayOf(MediaStore.Images.Media._ID),
                                existsSelection,
                                existsArgs,
                                null
                            )?.use { cursor ->
                                (cursor.count ?: 0) > 0
                            } ?: false

                            if (!isFileExists) {
                                contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, newFileName)
                                LogUtil.debug("MediaStore", "Режим отдельной папки, используем имя с индексом: $newFileName")
                                break
                            }

                            index++
                        }

                        if (isFileExists) {
                            val timestamp = System.currentTimeMillis()
                            val timeBasedName = "${fileNameWithoutExt}_${timestamp}${extension}"
                            contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, timeBasedName)
                            LogUtil.debug("MediaStore", "Не найден свободный индекс, используем временную метку: $timeBasedName")
                        }
                    }
                } catch (e: Exception) {
                    LogUtil.errorWithException("Обработка конфликта имен", e)
                }
            }

            // Создаем новую запись
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                LogUtil.debug("MediaStore", "Создан новый URI: $uri")
                LogUtil.processInfo("[MediaStore] Режим отдельной папки: создаем новый файл")
            } else {
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

            // Используем новую версию с поддержкой режима обновления
            val (uri, isUpdateMode) = createMediaStoreEntryV2(context, fileName, directory, "image/jpeg", originalUri)

            if (uri == null) {
                LogUtil.errorSimple("FileUtil", "Не удалось создать запись в MediaStore")
                return@withContext Pair(null, null)
            }

            if (isUpdateMode) {
                // Режим замены: перезаписываем существующий файл напрямую
                LogUtil.processInfo("[MediaStore] saveCompressedImageToGallery: режим обновления для URI: $uri")

                // Сбрасываем IS_PENDING флаг перед обновлением
                clearIsPendingFlag(context, uri)

                // Перезаписываем файл напрямую
                compressedFile.inputStream().use { inputStream ->
                    val updateSuccess = safeUpdateExistingFile(context, uri, inputStream)

                    if (!updateSuccess) {
                        LogUtil.error(uri, "Сохранение", "Не удалось обновить существующий файл в saveCompressedImageToGallery")
                        return@withContext Pair(null, null)
                    }

                    LogUtil.debug("FileUtil", "Файл успешно перезаписан в saveCompressedImageToGallery: $uri")
                }
            } else {
                // Режим создания: копируем в новый файл
                LogUtil.processInfo("[MediaStore] saveCompressedImageToGallery: режим создания для URI: $uri")

                // Копируем содержимое сжатого файла
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    compressedFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                LogUtil.debug("FileUtil", "Файл успешно создан в saveCompressedImageToGallery: $uri")
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

            // Используем новую версию с поддержкой режима обновления
            val (uri, isUpdateMode) = createMediaStoreEntryV2(context, fileName, directory, mimeType, originalUri)

            if (uri == null) {
                LogUtil.error(originalUri, "Сохранение", "Не удалось создать запись в MediaStore")
                return@withContext null
            }

            try {
                if (isUpdateMode) {
                    // Режим замены: перезаписываем существующий файл напрямую
                    LogUtil.processInfo("[MediaStore] Используем режим обновления (overwrite) для URI: $uri")

                    // Сбрасываем IS_PENDING флаг перед обновлением (если он был установлен)
                    clearIsPendingFlag(context, uri)

                    // Перезаписываем файл напрямую
                    val updateSuccess = safeUpdateExistingFile(context, uri, inputStream)

                    if (!updateSuccess) {
                        LogUtil.error(uri, "Сохранение", "Не удалось обновить существующий файл")
                        // Fallback: пытаемся создать новый файл
                        LogUtil.processWarning("[MediaStore] Fallback: пробуем создать новый файл")
                        val fallbackResult = createMediaStoreEntry(context, "${fileName}_fallback", directory, mimeType, originalUri)
                        if (fallbackResult != null) {
                            context.contentResolver.openOutputStream(fallbackResult)?.use { outputStream ->
                                inputStream.resetOrCopy()
                                outputStream.write(inputStream.readBytes())
                            }
                            clearIsPendingFlag(context, fallbackResult)
                            return@withContext fallbackResult
                        }
                        return@withContext null
                    }

                    LogUtil.debug("FileUtil", "Файл успешно перезаписан: $uri")

                } else {
                    // Режим создания: записываем в новый файл
                    LogUtil.processInfo("[MediaStore] Используем режим создания для URI: $uri")

                    // Записываем сжатое изображение
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        inputStream.copyTo(outputStream)
                        outputStream.flush()
                    } ?: throw IOException("Не удалось открыть OutputStream")

                    LogUtil.debug("FileUtil", "Данные изображения записаны в URI: $uri")
                }

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
                    LogUtil.debug("FileUtil", "Не удалось открыть поток для URI, повторная попытку...")
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
            LogUtil.processInfo("[MediaStore] Режим замены: обновляем существующий URI=$existingUri")
            context.contentResolver.openOutputStream(existingUri)?.use { outputStream ->
                val bytesWritten = inputData.copyTo(outputStream)
                outputStream.flush()
                LogUtil.debug("MediaStore", "Обновление файла завершено: $existingUri, размер=${bytesWritten}KB")
            } ?: throw IOException("Не удалось открыть OutputStream для URI: $existingUri")
            true
        } catch (e: FileNotFoundException) {
            LogUtil.processWarning("[MediaStore] Файл был удален извне: $existingUri")
            false
        } catch (e: IOException) {
            LogUtil.error(existingUri, "MediaStore", "Ошибка обновления файла (IOException)", e)
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
     * Пытается сбросить поток в начало (если поддерживается), иначе читает все данные в память
     * Используется в fallback сценариях при повторной попытке записи
     */
    private fun InputStream.resetOrCopy(): InputStream {
        return try {
            // Проверяем, поддерживает ли поток mark/reset
            if (markSupported()) {
                reset()
                this
            } else {
                // Если не поддерживается, читаем все данные и создаем новый поток
                val bytes = readBytes()
                ByteArrayInputStream(bytes)
            }
        } catch (e: Exception) {
            // Если reset не сработал, читаем все данные
            val bytes = readBytes()
            ByteArrayInputStream(bytes)
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
                    LogUtil.debug("MediaStore", "Обнаружен конфликт имени файла: $name -> $uri")
                }
            }

            // Для файлов без конфликтов добавляем null
            fileNames.forEach { fileName ->
                if (!conflicts.containsKey(fileName)) {
                    conflicts[fileName] = null
                }
            }

            LogUtil.debug("MediaStore", "Пакетная проверка завершена: проверено ${fileNames.size} файлов, найдено конфликтов: ${conflicts.values.count { it != null }}")

            conflicts
        } catch (e: Exception) {
            LogUtil.errorWithException("Пакетная проверка конфликтов имен", e)
            // В случае ошибки возвращаем пустую карту (все файлы без конфликтов)
            fileNames.associateWith { null }
        }
    }
}
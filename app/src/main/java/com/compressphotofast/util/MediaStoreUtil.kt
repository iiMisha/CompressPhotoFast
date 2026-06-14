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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Утилитарный класс для работы с MediaStore и сохранением файлов
 */
object MediaStoreUtil {

    /**
     * Мьютексы по originalUri для предотвращения конкурентной записи
     * в один и тот же файл из разных потоков/путей обработки.
     * Ключ — строковое представление originalUri.
     */
    private val fileSaveLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Максимальное количество хранимых мьютексов (для ограничения памяти)
     */
    private const val MAX_SAVE_LOCKS = 200

    /**
     * Получает или создаёт Mutex для данного ключа.
     * Автоматически очищает старые мьютексы при превышении лимита.
     */
    private fun getSaveLock(key: String): Mutex {
        val mutex = fileSaveLocks.getOrPut(key) { Mutex() }
        if (fileSaveLocks.size > MAX_SAVE_LOCKS) {
            val toRemove = fileSaveLocks.entries
                .filter { !it.value.isLocked }
                .take(fileSaveLocks.size - MAX_SAVE_LOCKS / 2)
                .map { it.key }
            toRemove.forEach { fileSaveLocks.remove(it) }
        }
        return mutex
    }

    /**
     * Формирует пару вариантов относительного пути (без слэша / со слэшем на конце)
     * для запросов к MediaStore, проверяющих RELATIVE_PATH.
     */
    private fun buildPathVariants(relativePath: String): Pair<String, String> {
        val pathWithoutSlash = relativePath.trimEnd('/')
        val pathWithSlash = if (!pathWithoutSlash.endsWith("/")) "$pathWithoutSlash/" else pathWithoutSlash
        return Pair(pathWithSlash, pathWithoutSlash)
    }

    /**
     * Вычисляет относительный путь для сохранения файла в MediaStore
     *
     * @param context Контекст приложения
     * @param isReplaceMode Режим замены оригинала
     * @param originalUri URI оригинального файла (для режима замены)
     * @param directory Базовая директория для сохранения
     * @return Относительный путь для Android 10+, пустая строка для Android < 10
     */
    private fun buildTargetRelativePath(
        context: Context,
        isReplaceMode: Boolean,
        originalUri: Uri?,
        directory: String
    ): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ""
        }

        var path = if (isReplaceMode && originalUri != null) {
            // В режиме замены используем оригинальную директорию файла
            UriUtil.getDirectoryFromUri(context, originalUri)
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

        // КРИТИЧЕСКО: Нормализуем путь - добавляем слеш в конце, если его нет
        // MediaStore хранит RELATIVE_PATH с завершающим слешем (например "Pictures/")
        if (!path.endsWith("/")) {
            path = "$path/"
        }

        return path
    }

    /**
     * Обрабатывает конфликты имен файлов, генерируя уникальное имя
     *
     * Использует пакетную проверку с IN clause для оптимизации запросов к MediaStore.
     * Генерирует имена вида "имя_1.ext", "имя_2.ext" до "имя_99.ext".
     * Если все индексы заняты, использует временную метку.
     *
     * @param context Контекст приложения
     * @param fileName Имя файла для проверки
     * @param contentValues ContentValues для обновления DISPLAY_NAME
     * @param targetRelativePath Целевой относительный путь (для Android 10+)
     */
    private suspend fun handleFileNameConflict(
        context: Context,
        fileName: String,
        contentValues: ContentValues,
        targetRelativePath: String
    ) = withContext(Dispatchers.IO) {
        try {
            var existingUri: Uri? = null

            // Проверяем наличие файла с таким же именем
            val (pathWithSlash, pathWithoutSlash) = buildPathVariants(targetRelativePath)
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // ИСПРАВЛЕНИЕ: Используем OR условие для проверки обоих вариантов пути
                "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND (${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?)"
            } else {
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            }

            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(fileName, pathWithSlash, pathWithoutSlash)
            } else {
                arrayOf(fileName)
            }

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
                val batchSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "${MediaStore.Images.Media.DISPLAY_NAME} IN ($placeholders) AND (${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?)"
                } else {
                    "${MediaStore.Images.Media.DISPLAY_NAME} IN ($placeholders)"
                }

                val batchArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    fileNamesToCheck.toTypedArray() + arrayOf(pathWithSlash, pathWithoutSlash)
                } else {
                    fileNamesToCheck.toTypedArray()
                }

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
            val isReplaceMode = FileOperationsUtil.isSaveModeReplace(context)
            val targetRelativePath = buildTargetRelativePath(context, isReplaceMode, originalUri, directory)

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, targetRelativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    // Для API < 29 нужно указать полный путь к файлу
                    val targetDir = if (isReplaceMode && originalUri != null) {
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
                    // Если включен режим замены, удаляем существующий файл
                    val existingFiles = batchCheckFilesExist(context, listOf(fileName), targetRelativePath)
                    val existingUri = existingFiles[fileName]

                    if (existingUri != null && isReplaceMode) {
                        try {
                            context.contentResolver.delete(existingUri, null, null)
                        } catch (e: Exception) {
                            LogUtil.error(existingUri, "MediaStore", "Ошибка при удалении существующего файла в режиме замены", e)
                            LogUtil.processWarning("[MediaStore] ВНИМАНИЕ: Не удалось удалить существующий файл, будет создан дубликат!")
                            StatsTracker.recordDeleteFailure(existingUri)
                        }
                    } else if (existingUri != null) {
                        // Если файл существует, но режим замены выключен, обрабатываем конфликт
                        handleFileNameConflict(context, fileName, contentValues, targetRelativePath)
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
            val targetRelativePath = buildTargetRelativePath(context, isReplaceMode, originalUri, directory)

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
                    // (будет обработано ниже при создании contentValues)

                } catch (e: Exception) {
                    LogUtil.errorWithException("Проверка существующего файла", e)
                }
            }

            // Создаем contentValues для нового файла
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                handleFileNameConflict(context, fileName, contentValues, targetRelativePath)
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

            val isReplaceMode = FileOperationsUtil.isSaveModeReplace(context)
            val targetRelativePath = buildTargetRelativePath(context, isReplaceMode, originalUri, directory)

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, targetRelativePath)
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
        // ЗАЩИТА ОТ КОНКУРЕНТНОЙ ЗАПИСИ: Mutex по целевому пути гарантирует,
        // что два потока не будут одновременно записывать в один и тот же файл.
        // Это defense-in-depth на случай, если разные исходные файлы 
        // имеют одинаковое целевое имя.
        val isReplaceMode = FileOperationsUtil.isSaveModeReplace(context)
        val targetRelativePath = buildTargetRelativePath(context, isReplaceMode, originalUri, directory)
        val lockKey = "$targetRelativePath$fileName"

        val saveLock = getSaveLock(lockKey)
        saveLock.withLock {
            saveCompressedImageFromStreamInternal(
                context, inputStream, fileName, directory, originalUri, quality, exifDataMemory, mimeType
            )
        }
    }

    /**
     * Внутренняя реализация сохранения сжатого изображения из потока.
     * Вызывается из saveCompressedImageFromStream() под защитой Mutex.
     */
    private suspend fun saveCompressedImageFromStreamInternal(
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
            val bytes = ByteArrayOutputStream().use { buf ->
                inputStream.copyTo(buf)
                buf.toByteArray()
            }

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
                    val updateSuccess = safeUpdateExistingFile(context, uri, ByteArrayInputStream(bytes))

                    if (!updateSuccess) {
                        // Fallback: пытаемся создать новый файл
                        val fallbackResult = createMediaStoreEntry(context, "${fileName}_fallback", directory, mimeType, originalUri)
                        if (fallbackResult != null) {
                            try {
                                context.contentResolver.openOutputStream(fallbackResult)?.use { outputStream ->
                                    ByteArrayInputStream(bytes).use { dataStream ->
                                        dataStream.copyTo(outputStream, bufferSize = 8192)
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
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        ByteArrayInputStream(bytes).copyTo(outputStream)
                        outputStream.flush()
                    } ?: throw IOException("Не удалось открыть OutputStream")
                }

                // ВЕРИФИКАЦИЯ ЦЕЛОСТНОСТИ перед снятием IS_PENDING
                // Повреждённый файл НЕ ДОЛЖЕН стать видимым в галерее
                val isValid = verifyImageIntegrity(context, uri)
                if (!isValid) {
                    LogUtil.error(originalUri, "Сохранение", "КРИТИЧЕСКАЯ ОШИБКА: Записанный файл повреждён, удаляем из MediaStore: $uri")
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (deleteEx: Exception) {
                        LogUtil.error(uri, "Cleanup", "Не удалось удалить повреждённый файл из MediaStore", deleteEx)
                    }
                    NotificationUtil.showErrorNotification(
                        context,
                        "Ошибка сохранения",
                        "Сжатый файл был повреждён и удалён"
                    )
                    return@withContext null
                }

                // Файл верифицирован — снимаем IS_PENDING, делая его видимым
                clearIsPendingFlag(context, uri)

                // Ждем, чтобы файл стал доступен в системе
                val maxWaitTime = 2000L
                waitForUriAvailability(context, uri, maxWaitTime)

                // Специальная обработка для Android 11
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                    delay(Constants.MEDIASTORE_ANDROID11_DELAY_MS)
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
                // При ошибке записи удаляем незавершённую запись из MediaStore
                try {
                    context.contentResolver.delete(uri, null, null)
                    LogUtil.error(uri, "Cleanup", "Незавершённая запись удалена из MediaStore после ошибки")
                } catch (deleteEx: Exception) {
                    LogUtil.error(uri, "Cleanup", "Не удалось удалить незавершённую запись", deleteEx)
                }
                return@withContext null
            }

        } catch (e: Exception) {
            LogUtil.errorWithException("Сохранение сжатого изображения", e)
            return@withContext null
        }
    }

    /**
     * Верифицирует целостность сохранённого изображения
     * Использует BitmapFactory.decodeStream с inJustDecodeBounds=true для проверки
     * что файл содержит валидное изображение (корректные заголовки JPEG/PNG)
     *
     * @param context Контекст приложения
     * @param uri URI сохранённого файла
     * @return true если изображение корректно декодируется, false если повреждено
     */
    private suspend fun verifyImageIntegrity(context: Context, uri: Uri): Boolean =
        ImageCompressionUtil.verifyImageIntegrity(context, uri)

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
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    if (inputStream.read() != -1) {
                        isAvailable = true
                    }
                }
                if (isAvailable) break
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
            // Используем ParcelFileDescriptor "rwt" (read-write-truncate) для надёжной перезаписи
            // openOutputStream("wt") может работать ненадёжно на некоторых устройствах (Android 12+)
            context.contentResolver.openFileDescriptor(existingUri, "rwt")?.use { pfd ->
                java.io.FileOutputStream(pfd.fileDescriptor).use { outputStream ->
                    inputData.copyTo(outputStream)
                    outputStream.flush()
                }
            } ?: throw IOException("Не удалось открыть FileDescriptor для URI: $existingUri")
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

    /**
     * Очищает stale IS_PENDING=1 записи, оставшиеся после краша приложения.
     * Удаляет только записи, принадлежащие данному приложению.
     * Вызывается при запуске BackgroundMonitoringService.
     */
    suspend fun cleanupStalePendingEntries(context: Context) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext

        try {
            val fiveMinutesAgo = (System.currentTimeMillis() / 1000) - 300

            val selection = buildString {
                append("${MediaStore.Images.Media.IS_PENDING} = 1 AND ")
                append("${MediaStore.Images.Media.DATE_ADDED} < ?")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    append(" AND ${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ?")
                }
            }
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                arrayOf(fiveMinutesAgo.toString(), context.packageName)
            } else {
                arrayOf(fiveMinutesAgo.toString())
            }

            val staleUris = mutableListOf<Uri>()
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    staleUris.add(
                        ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        )
                    )
                }
            }

            for (uri in staleUris) {
                try {
                    context.contentResolver.delete(uri, null, null)
                    LogUtil.processDebug("Удалена stale IS_PENDING запись: $uri")
                } catch (e: Exception) {
                    LogUtil.warning(uri, "Cleanup", "Не удалось удалить stale IS_PENDING запись: ${e.message}")
                }
            }

            if (staleUris.isNotEmpty()) {
                LogUtil.processInfo("Очищено ${staleUris.size} stale IS_PENDING записей от предыдущих сессий")
            }
        } catch (e: Exception) {
            LogUtil.error(Uri.EMPTY, "Cleanup", "Ошибка при очистке stale pending записей", e)
        }
    }
}

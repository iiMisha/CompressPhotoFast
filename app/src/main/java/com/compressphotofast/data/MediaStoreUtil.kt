package com.compressphotofast.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import com.compressphotofast.util.Constants
import com.compressphotofast.util.FileIoUtil
import com.compressphotofast.util.LogUtil

/**
 * Утилитарный класс для работы с MediaStore и сохранением файлов
 */
object MediaStoreUtil {

    /**
     * Причина неудачного сохранения. Уведомление пользователю показывает
     * вызывающая сторона: слой хранения не знает о UI.
     */
    enum class SaveFailure {
        /** Новый файл не прошёл верификацию и удалён. */
        CORRUPTED_OUTPUT,
        /** Откат перезаписи не удался — backup остаётся для recovery при следующем старте. */
        ROLLBACK_FAILED,
        /** Прочие ошибки: оригинал не изменён или восстановлен. */
        OTHER
    }

    sealed interface SaveResult {
        /** [size] — точная длина опубликованного файла (сверена при записи). */
        data class Saved(val uri: Uri, val size: Long) : SaveResult
        data class Failed(val reason: SaveFailure) : SaveResult
    }

    /** Новый файл повреждён до снятия IS_PENDING. */
    private class CorruptedOutputException(message: String) : IOException(message)

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
     * @return Относительный путь (RELATIVE_PATH) с завершающим слешем
     */
    private fun buildTargetRelativePath(
        context: Context,
        isReplaceMode: Boolean,
        originalUri: Uri?,
        directory: String
    ): String {
        var path = if (isReplaceMode && originalUri != null) {
            // В режиме замены используем оригинальную директорию файла; вызывающая
            // сторона обычно уже знает её из снимка MediaStore — без лишнего запроса
            directory.ifEmpty { UriUtil.getDirectoryFromUri(context, originalUri) }
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
     * Подбирает свободное имя файла в целевой директории и записывает его в DISPLAY_NAME.
     *
     * Одним запросом с IN clause проверяет исходное имя и варианты "имя_1.ext" … "имя_99.ext";
     * выбирается первое свободное. Если все заняты, используется временная метка.
     *
     * @param context Контекст приложения
     * @param fileName Желаемое имя файла
     * @param contentValues ContentValues для обновления DISPLAY_NAME
     * @param targetRelativePath Целевой относительный путь
     */
    private suspend fun handleFileNameConflict(
        context: Context,
        fileName: String,
        contentValues: ContentValues,
        targetRelativePath: String
    ) = withContext(Dispatchers.IO) {
        try {
            val (pathWithSlash, pathWithoutSlash) = buildPathVariants(targetRelativePath)
            val (fileNameWithoutExt, extension) = FileOperationsUtil.splitNameAndExtension(fileName)

            // Почти всегда желаемое имя свободно: сначала дешёвый запрос одного имени,
            // пачка вариантов — только при конфликте.
            val existingNames = mutableSetOf<String>()
            fun queryExisting(names: List<String>) {
                val placeholders = names.joinToString(",") { "?" }
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                    "${MediaStore.Images.Media.DISPLAY_NAME} IN ($placeholders) AND (${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?)",
                    names.toTypedArray() + arrayOf(pathWithSlash, pathWithoutSlash),
                    null
                )?.use { cursor ->
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        existingNames.add(cursor.getString(nameColumn))
                    }
                }
            }

            val fileNamesToCheck = mutableListOf(fileName)
            queryExisting(fileNamesToCheck)
            if (fileName in existingNames) {
                for (i in 1 until 100) {
                    fileNamesToCheck.add("${fileNameWithoutExt}_${i}${extension}")
                }
                queryExisting(fileNamesToCheck.drop(1))
            }

            val freeName = fileNamesToCheck.firstOrNull { it !in existingNames }
                ?: "${fileNameWithoutExt}_${System.currentTimeMillis()}${extension}"
            contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, freeName)
        } catch (e: Exception) {
            LogUtil.errorWithException("Обработка конфликта имен", e)
        }
    }

    /**
     * Вставляет новую pending-запись в MediaStore.
     * ИНВАРИАНТ БЕЗОПАСНОСТИ: существующий файл никогда не удаляется до записи новых
     * данных — при конфликте имён генерируется уникальное имя.
     */
    private suspend fun insertPendingEntry(
        context: Context,
        fileName: String,
        mimeType: String,
        targetRelativePath: String
    ): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, targetRelativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            // Устанавливаем DATE_ADDED и DATE_MODIFIED для корректной работы на всех устройствах
            MediaStoreDateUtil.setCreationTimestamp(this, System.currentTimeMillis())
        }

        handleFileNameConflict(context, fileName, contentValues, targetRelativePath)

        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Не удалось создать запись MediaStore")
    }

    /**
     * Вставляет новую запись в MediaStore для изображения (всегда создаёт новый файл)
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
            insertPendingEntry(context, fileName, mimeType, targetRelativePath)
        } catch (e: Exception) {
            LogUtil.errorWithException("Создание записи в MediaStore", e)
            null
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
        val isReplaceMode = FileOperationsUtil.isSaveModeReplace(context)
        val targetRelativePath = try {
            buildTargetRelativePath(context, isReplaceMode, originalUri, directory)
        } catch (e: Exception) {
            LogUtil.errorWithException("Создание записи в MediaStore V2", e)
            return@withContext Pair(null, false)
        }
        createMediaStoreEntryV2(context, fileName, mimeType, originalUri, isReplaceMode, targetRelativePath)
    }

    private suspend fun createMediaStoreEntryV2(
        context: Context,
        fileName: String,
        mimeType: String,
        originalUri: Uri?,
        isReplaceMode: Boolean,
        targetRelativePath: String
    ): Pair<Uri?, Boolean> = withContext(Dispatchers.IO) {
        try {
            // Вне режима замены существующий файл не перезаписывается — конфликт имён
            // решает handleFileNameConflict, отдельный запрос не нужен
            if (isReplaceMode) {
                try {
                    val existingUri = batchCheckFilesExist(context, listOf(fileName), targetRelativePath)[fileName]
                    // Файл существует, это сам оригинал И режим замены: возвращаем existingUri с флагом true.
                    // НЕ удаляем файл здесь - будем перезаписывать напрямую через OutputStream
                    if (shouldUseUpdatePath(existingUri, originalUri, isReplaceMode)) {
                        return@withContext Pair(existingUri, true) // true = режим обновления
                    }
                    if (existingUri != null) {
                        LogUtil.warning(
                            originalUri, "Replace",
                            "Имя $fileName занято другим файлом ($existingUri) — перезапись запрещена, создаём новый файл"
                        )
                    }
                } catch (e: Exception) {
                    LogUtil.errorWithException("Проверка существующего файла", e)
                }
            }

            Pair(insertPendingEntry(context, fileName, mimeType, targetRelativePath), false) // false = режим создания
        } catch (e: Exception) {
            LogUtil.errorWithException("Создание записи в MediaStore V2", e)
            Pair(null, false)
        }
    }

    /**
     * Сбрасывает флаг IS_PENDING
     */
    suspend fun clearIsPendingFlag(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val contentValues = ContentValues()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, contentValues, null, null)
    }

    /**
     * Сохраняет сжатое изображение из потока
     *
     * @param context Контекст приложения
     * @param compressedFile Сжатый JPEG-artifact в cacheDir (удаляет вызывающая сторона)
     * @param fileName Имя файла для сохранения
     * @param directory Директория для сохранения
     * @param originalUri URI исходного файла
     * @param quality Качество сжатия (поле маркера)
     * @param exifDataMemory EXIF исходника, пишется в artifact до публикации
     * @param mimeType MIME тип для сохранения (по умолчанию "image/jpeg")
     * @param originalFileSize Исходный размер файла до сжатия (для поля origSize маркера)
     * @param pixelsTransformed ориентация уже применена к пикселям: в копию пишется Orientation=NORMAL
     * @return [SaveResult.Saved] с URI сохранённого файла или [SaveResult.Failed] с причиной
     */
    suspend fun saveCompressedImageFromFile(
        context: Context,
        compressedFile: File,
        fileName: String,
        directory: String,
        originalUri: Uri,
        quality: Int = Constants.COMPRESSION_QUALITY_MEDIUM,
        exifDataMemory: Map<String, Any>? = null,
        mimeType: String = "image/jpeg",
        originalFileSize: Long? = null,
        pixelsTransformed: Boolean = false
    ): SaveResult = withContext(Dispatchers.IO) {
        // ЗАЩИТА ОТ КОНКУРЕНТНОЙ ЗАПИСИ: Mutex по целевому пути гарантирует,
        // что два потока не будут одновременно записывать в один и тот же файл.
        // Это defense-in-depth на случай, если разные исходные файлы 
        // имеют одинаковое целевое имя.
        val isReplaceMode = FileOperationsUtil.isSaveModeReplace(context)
        val targetRelativePath = buildTargetRelativePath(context, isReplaceMode, originalUri, directory)
        val lockKey = "$targetRelativePath$fileName"

        val saveLock = getSaveLock(lockKey)
        saveLock.withLock {
            saveCompressedImageFromFileInternal(
                context, compressedFile, fileName, isReplaceMode, targetRelativePath, originalUri, quality,
                exifDataMemory, mimeType, originalFileSize, pixelsTransformed
            )
        }
    }

    /**
     * Внутренняя реализация сохранения сжатого изображения из потока.
     * Вызывается из saveCompressedImageFromFile() под защитой Mutex.
     */
    private suspend fun saveCompressedImageFromFileInternal(
        context: Context,
        compressedFile: File,
        fileName: String,
        isReplaceMode: Boolean,
        targetRelativePath: String,
        originalUri: Uri,
        quality: Int,
        exifDataMemory: Map<String, Any>?,
        mimeType: String,
        originalFileSize: Long?,
        pixelsTransformed: Boolean
    ): SaveResult = withContext(Dispatchers.IO) {
        try {
            // EXIF и маркер (с точным размером) пишутся в локальный artifact до публикации:
            // в MediaStore файл попадает одной durable-записью, без saveAttributes() поверх
            val exifOk = ExifUtil.writeExifToArtifact(
                compressedFile, exifDataMemory ?: emptyMap(), quality, originalFileSize, pixelsTransformed
            )
            if (!exifOk && isReplaceMode) {
                // Оригинал с метаданными будет заменён/удалён — без EXIF это потеря GPS/дат
                LogUtil.error(originalUri, "Сохранение", "EXIF не записан в artifact — замена оригинала отменена")
                return@withContext SaveResult.Failed(SaveFailure.OTHER)
            }
            // Artifact на диске читается повторно, если replace-mode уходит в fallback-запись
            val request = SaveRequest(compressedFile, originalUri, mimeType)

            // Используем новую версию с поддержкой режима обновления
            val (uri, isUpdateMode) =
                createMediaStoreEntryV2(context, fileName, mimeType, originalUri, isReplaceMode, targetRelativePath)

            if (uri == null) {
                LogUtil.error(originalUri, "Сохранение", "Не удалось создать запись в MediaStore")
                return@withContext SaveResult.Failed(SaveFailure.OTHER)
            }

            if (!isUpdateMode) {
                return@withContext saveToNewEntry(context, uri, request)
            }

            // Режим замены: перезаписываем оригинал на месте.
            // Защита от потери правок: если оригинал изменился после чтения, его
            // сжатая версия устарела — не перезаписываем.
            if (!isFileUnchanged(context, uri, originalFileSize)) {
                LogUtil.warning(uri, "Replace", "Оригинал изменён во время обработки — перезапись отменена")
                return@withContext SaveResult.Failed(SaveFailure.OTHER)
            }

            // КРИТИЧЕСКО: перед перезаписью создаём durable backup оригинала в noBackupFilesDir,
            // чтобы восстановить его при ошибке/прерывании записи. Без backup kill посередине
            // truncate-записи привёл бы к необратимой потере оригинала.
            val backupFile = BackupRegistry.createBackup(context, uri, "replace_backup_")
            if (backupFile == null) {
                // ИНВАРИАНТ БЕЗОПАСНОСТИ: без backup оригинал не перезаписываем.
                // Сохраняем сжатую версию в новый файл (с уникальным именем) через
                // общий путь верификации и EXIF — оригинал остаётся нетронутым.
                LogUtil.warning(uri, "Replace", "Backup оригинала не создан — перезапись отменена, сохраняем в новый файл")
                val newUri = insertPendingEntry(context, fileName, mimeType, targetRelativePath)
                return@withContext saveToNewEntry(context, newUri, request)
            }

            replaceInPlace(context, uri, backupFile, request)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LogUtil.errorWithException("Сохранение сжатого изображения", e)
            return@withContext SaveResult.Failed(SaveFailure.OTHER)
        }
    }

    /**
     * Параметры сохранения сжатого изображения.
     */
    private class SaveRequest(
        val cacheFile: File,
        val originalUri: Uri,
        val mimeType: String
    )

    /**
     * Записывает готовый artifact (EXIF и маркер уже внутри) в новую pending-запись:
     * запись с fsync и сверкой длины → верификация → снятие IS_PENDING.
     * При любой ошибке запись удаляется (это новый файл, не файл пользователя).
     */
    private suspend fun saveToNewEntry(context: Context, uri: Uri, request: SaveRequest): SaveResult {
        try {
            val written = writeDurably(context, uri, request.cacheFile, "w")
            if (written != request.cacheFile.length()) {
                throw IOException("Записано $written из ${request.cacheFile.length()} байт")
            }

            // ВЕРИФИКАЦИЯ ЦЕЛОСТНОСТИ перед снятием IS_PENDING
            // Повреждённый файл НЕ ДОЛЖЕН стать видимым в галерее
            if (!ImageIntegrityUtil.verifyImageIntegrity(context, uri)) {
                throw CorruptedOutputException("Записанный файл повреждён")
            }

            // Файл верифицирован — снимаем IS_PENDING, делая его видимым
            clearIsPendingFlag(context, uri)
            UriUtil.invalidateUriExistsCache(uri)
            return SaveResult.Saved(uri, written)
        } catch (e: Exception) {
            LogUtil.error(request.originalUri, "Сохранение", "Ошибка записи нового файла: ${e.message}", e)
            withContext(NonCancellable) {
                try {
                    context.contentResolver.delete(uri, null, null)
                    LogUtil.error(uri, "Cleanup", "Незавершённая запись удалена из MediaStore после ошибки")
                } catch (deleteEx: Exception) {
                    LogUtil.error(uri, "Cleanup", "Не удалось удалить незавершённую запись", deleteEx)
                }
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
            val reason = if (e is CorruptedOutputException) SaveFailure.CORRUPTED_OUTPUT else SaveFailure.OTHER
            return SaveResult.Failed(reason)
        }
    }

    /**
     * Перезаписывает оригинал на месте под защитой [backupFile].
     *
     * Artifact уже содержит EXIF и маркер, поэтому перезапись — одна durable-запись.
     * Backup освобождается только после полного успеха (запись, верификация). При любой ошибке файл откатывается к backup;
     * если откат не удался, backup остаётся в реестре и будет применён
     * [BackupRecoveryHelper] при следующем старте — файл пользователя
     * никогда не удаляется.
     */
    private suspend fun replaceInPlace(
        context: Context,
        uri: Uri,
        backupFile: File,
        request: SaveRequest
    ): SaveResult {
        var success = false
        var rollbackFailed = false
        try {
            // Сбрасываем IS_PENDING флаг перед обновлением (если он был установлен)
            clearIsPendingFlag(context, uri)

            // ВНИМАНИЕ: всё время до завершения fsync файл на диске усечён
            // и виден галереям. Логируем длительность окна для диагностики.
            val writeStartMs = System.currentTimeMillis()
            val written = writeDurably(context, uri, request.cacheFile, "rwt")
            LogUtil.processInfo("[Replace] Окно частичной записи (truncate→fsync): ${System.currentTimeMillis() - writeStartMs}мс")
            if (written != request.cacheFile.length()) {
                throw IOException("Записано $written из ${request.cacheFile.length()} байт")
            }
            if (!ImageIntegrityUtil.verifyImageIntegrity(context, uri)) {
                throw IOException("Перезаписанный файл повреждён")
            }

            // Принудительно синхронизируем запись MediaStore (размер, DATE_MODIFIED),
            // чтобы галереи инвалидировали кэш миниатюр.
            refreshMediaStoreEntry(context, uri, request.mimeType)
            UriUtil.invalidateUriExistsCache(uri)
            success = true
        } catch (e: Exception) {
            LogUtil.error(uri, "Replace", "Перезапись не удалась: ${e.message}", e)
            if (e is kotlinx.coroutines.CancellationException) throw e
        } finally {
            withContext(NonCancellable) {
                if (success) {
                    BackupRegistry.releaseBackup(context, backupFile)
                } else if (BackupRegistry.rollback(context, uri, backupFile)) {
                    LogUtil.warning(uri, "Replace", "Оригинал восстановлен из backup")
                    BackupRegistry.releaseBackup(context, backupFile)
                    refreshMediaStoreEntry(context, uri, request.mimeType)
                    UriUtil.invalidateUriExistsCache(uri)
                } else {
                    LogUtil.error(uri, "Replace", "Не удалось восстановить оригинал — backup сохранён для восстановления при следующем запуске")
                    rollbackFailed = true
                }
            }
        }
        return when {
            success -> SaveResult.Saved(uri, request.cacheFile.length())
            rollbackFailed -> SaveResult.Failed(SaveFailure.ROLLBACK_FAILED)
            else -> SaveResult.Failed(SaveFailure.OTHER)
        }
    }

    /**
     * Проверяет, что размер файла совпадает с зафиксированным в начале обработки.
     * Неизвестный размер (ошибка чтения или отсутствие ожидания) не считается изменением.
     */
    suspend fun isFileUnchanged(context: Context, uri: Uri, expectedSize: Long?): Boolean {
        if (expectedSize == null || expectedSize <= 0L) return true
        val currentSize = try { UriUtil.getFileSize(context, uri) } catch (e: Exception) { -1L }
        return currentSize <= 0L || currentSize == expectedSize
    }

    /**
     * Записывает файл в URI через ParcelFileDescriptor с fsync.
     * Режим "rwt" используется вместо openOutputStream("wt"), который ненадёжен
     * на некоторых устройствах (Android 12+). Провал fsync — ошибка записи.
     *
     * @return количество записанных байт
     */
    private fun writeDurably(context: Context, uri: Uri, source: File, mode: String): Long {
        val pfd = context.contentResolver.openFileDescriptor(uri, mode)
            ?: throw IOException("Не удалось открыть FileDescriptor для URI: $uri")
        return pfd.use {
            FileOutputStream(it.fileDescriptor).use { output ->
                val count = source.inputStream().use { input -> input.copyTo(output, FileIoUtil.COPY_BUFFER_SIZE) }
                output.flush()
                it.fileDescriptor.sync()
                count
            }
        }
    }

    /**
     * Синхронизирует запись MediaStore с фактическим содержимым файла после
     * перезаписи на месте (replace-режим). Запрос scan обновляет _size и
     * DATE_MODIFIED в MediaProvider, что заставляет галереи перегенерировать
     * миниатюры и исключает показ миниатюр, снятых из частично записанного файла.
     */
    private suspend fun refreshMediaStoreEntry(context: Context, uri: Uri, mimeType: String) =
        withContext(Dispatchers.IO) {
            try {
                val filePath = UriUtil.getFilePathFromUri(context, uri)
                if (filePath != null) {
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(filePath),
                        arrayOf(mimeType),
                        null
                    )
                    LogUtil.processInfo("[Replace] MediaScannerConnection.scanFile выполнен для $uri")
                } else {
                    LogUtil.warning(uri, "Replace", "Не удалось получить путь для scan после перезаписи")
                }
            } catch (e: Exception) {
                // Некритично: MediaProvider обычно обновляет метаданные сам при
                // закрытии дескриптора. Ошибка скана не должна валить сохранение.
                LogUtil.warning(uri, "Replace", "Не удалось выполнить scan после перезаписи: ${e.message}")
            }
        }

    /**
     * Определяет, следует ли использовать путь обновления (overwrite) вместо создания нового файла
     *
     * @param existingUri URI существующего файла (null если файл не существует)
     * @param isReplaceMode Включен ли режим замены
     * @return true если нужно использовать обновление, false если создавать новый файл
     */
    internal fun shouldUseUpdatePath(
        existingUri: Uri?,
        originalUri: Uri?,
        isReplaceMode: Boolean
    ): Boolean = existingUri != null && isReplaceMode && isSameMediaItem(existingUri, originalUri)

    /**
     * Сравнивает два URI MediaStore по ID записи (варианты тома
     * `external`/`external_primary` дают разные строки для одного файла).
     * ИНВАРИАНТ БЕЗОПАСНОСТИ: перезапись на месте допустима только для самого
     * оригинала — совпадение имени не означает, что это тот же файл.
     */
    internal fun isSameMediaItem(first: Uri?, second: Uri?): Boolean {
        if (first == null || second == null) return false
        if (first.authority != MediaStore.AUTHORITY || second.authority != MediaStore.AUTHORITY) return false
        return try {
            ContentUris.parseId(first) == ContentUris.parseId(second)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Пакетная проверка существования файлов
     *
     * @param context Контекст приложения
     * @param fileNames Список имен файлов для проверки
     * @param relativePath Относительный путь для фильтрации
     * @return Map где ключ = имя файла, значение = Uri (существующий) или null
     */
    suspend fun batchCheckFilesExist(
        context: Context,
        fileNames: List<String>,
        relativePath: String? = null
    ): Map<String, Uri?> = withContext(Dispatchers.IO) {
        if (fileNames.isEmpty()) return@withContext emptyMap<String, Uri?>()

        try {
            val placeholders = fileNames.joinToString(",") { "?" }

            val selection: String
            val selectionArgs: Array<String>
            if (relativePath != null) {
                val (pathWithSlash, pathWithoutSlash) = buildPathVariants(relativePath)
                selection = "${MediaStore.Images.Media.DISPLAY_NAME} IN ($placeholders) AND " +
                    "(${MediaStore.Images.Media.RELATIVE_PATH} = ? OR " +
                    "${MediaStore.Images.Media.RELATIVE_PATH} = ?)"
                selectionArgs = (fileNames + listOf(pathWithSlash, pathWithoutSlash)).toTypedArray()
            } else {
                selection = "${MediaStore.Images.Media.DISPLAY_NAME} IN ($placeholders)"
                selectionArgs = fileNames.toTypedArray()
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

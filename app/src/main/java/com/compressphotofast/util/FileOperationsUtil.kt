package com.compressphotofast.util

import android.app.ActivityManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DecimalFormat
import java.util.Date

/**
 * Утилитарный класс для работы с файлами и файловыми операциями
 */
object FileOperationsUtil {
    /**
     * Проверяет, включен ли режим замены файлов в настройках
     */
    fun isSaveModeReplace(context: Context): Boolean {
        try {
            return SettingsManager.getInstance(context).isSaveModeReplace()
        } catch (e: Exception) {
            LogUtil.errorWithException("Проверка режима замены", e)
            return false
        }
    }
    
    /**
     * Создает имя файла для сжатой версии
     * В режиме замены возвращает оригинальное имя, иначе добавляет суффикс _compressed
     *
     * Очищает двойные расширения (например, image.HEIC.jpg -> image_compressed.jpg)
     */
    fun createCompressedFileName(context: Context, originalName: String): String {
        // В режиме замены используем оригинальное имя файла
        if (isSaveModeReplace(context)) {
            LogUtil.processInfo("[FileOperationsUtil] Режим замены включён, используем оригинальное имя: $originalName")
            // Очищаем двойные расширения даже в режиме замены
            return cleanDoubleExtensions(originalName)
        }

        // В режиме отдельного сохранения добавляем суффикс
        // Сначала очищаем двойные расширения
        val cleanName = cleanDoubleExtensions(originalName)
        val extension = getLastExtension(originalName)

        val compressedName = if (extension.isNotEmpty()) {
            "${cleanName}${Constants.COMPRESSED_FILE_SUFFIX}$extension"
        } else {
            "${cleanName}${Constants.COMPRESSED_FILE_SUFFIX}"
        }

        LogUtil.processInfo("[FileOperationsUtil] Режим отдельного сохранения: $originalName → $compressedName")
        return compressedName
    }

    /**
     * Очищает двойные расширения в имени файла
     * Например: image.HEIC.jpg -> image, photo.heif.jpeg -> photo
     *
     * @param fileName Исходное имя файла
     * @return Имя файла без двойных расширений (только базовое имя)
     */
    private fun cleanDoubleExtensions(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        if (lastDotIndex <= 0) return fileName

        val beforeLastDot = fileName.substring(0, lastDotIndex)
        val secondLastDot = beforeLastDot.lastIndexOf('.')

        return if (secondLastDot > 0) {
            // Есть двойное расширение, возвращаем имя до второй точки
            val cleanName = beforeLastDot.substring(0, secondLastDot)
            LogUtil.debug("FileOperationsUtil", "Очистка двойного расширения: $fileName -> $cleanName")
            cleanName
        } else {
            // Двойного расширения нет, возвращаем как есть
            beforeLastDot
        }
    }

    /**
     * Извлекает последнее расширение из имени файла
     * Например: image.HEIC.jpg -> .jpg, photo.png -> .png
     *
     * @param fileName Имя файла
     * @return Последнее расширение с точкой или пустая строка
     */
    private fun getLastExtension(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex > 0) {
            fileName.substring(lastDotIndex)
        } else {
            ""
        }
    }

    /**
     * Удаляет файл по URI с централизованной логикой для всех версий Android
     * @param forceDelete Если true, обходит проверку нахождения URI в обработке
     * @return Boolean - успешность удаления или IntentSender для запроса разрешения на Android 10+
     */
    suspend fun deleteFile(context: Context, uri: Uri, uriProcessingTracker: UriProcessingTracker, forceDelete: Boolean = false): Any? {
        if (!forceDelete && uriProcessingTracker.isProcessing(uri)) {
            LogUtil.processWarning("deleteFile: URI находится в обработке, удаление отменено: $uri")
            return false
        }
        try {
            LogUtil.processInfo("Начинаем удаление файла: $uri")

            // Двойная проверка существования файла перед удалением
            if (!UriUtil.isUriExistsSuspend(context, uri)) {
                LogUtil.processWarning("deleteFile: URI не существует (первая проверка), удаление отменено: $uri")
                return false
            }

            // Небольшая задержка для предотвращения race condition
            delay(50)

            // Повторная проверка существования файла
            if (!UriUtil.isUriExistsSuspend(context, uri)) {
                LogUtil.processWarning("deleteFile: URI не существует (вторая проверка), удаление отменено: $uri")
                return false
            }

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
                    if (!result) {
                        LogUtil.processWarning("Удаление через MediaStore не удалось для URI: $cleanUri")
                    }
                    return result
                } catch (e: SecurityException) {
                    if (e is android.app.RecoverableSecurityException) {
                        LogUtil.processInfo("Требуется разрешение пользователя для удаления файла: $cleanUri")
                        return e.userAction.actionIntent.intentSender
                    } else {
                        LogUtil.error(cleanUri, "Удаление", "SecurityException при удалении файла", e)
                        throw e
                    }
                } catch (e: Exception) {
                    LogUtil.error(cleanUri, "Удаление", "Ошибка при удалении файла через MediaStore", e)
                    return false
                }
            } else {
                // Для более старых версий получаем путь к файлу и удаляем его напрямую
                val path = UriUtil.getFilePathFromUri(context, cleanUri)
                if (path != null) {
                    LogUtil.processInfo("Удаляем файл по пути (старый API): $path")
                    val file = File(path)
                    val result = file.delete()
                    LogUtil.processInfo("Результат удаления файла: $result")
                    if (!result) {
                        LogUtil.processWarning("Удаление файла по пути не удалось: $path")
                    }
                    return result
                } else {
                    LogUtil.processWarning("Не удалось получить путь к файлу для URI: $cleanUri")
                    return false
                }
            }
        } catch (e: Exception) {
            LogUtil.error(null, "Удаление", "Ошибка при удалении файла", e)
        }
        return false
    }
    
    
    /**
     * Создание временного файла для изображения
     */
    fun createTempImageFile(context: Context): File {
        try {
            // Проверяем доступность cacheDir
            val cacheDir = context.cacheDir
            if (!cacheDir.exists()) {
                LogUtil.error(null, "Cache директория", "Cache директория не существует: ${cacheDir.absolutePath}")
                throw IOException("Cache директория недоступна")
            }

            if (!cacheDir.canWrite()) {
                LogUtil.error(null, "Cache директория", "Нет прав на запись в cache директорию: ${cacheDir.absolutePath}")
                throw IOException("Нет прав на запись в cache директорию")
            }

            return File.createTempFile(
                "temp_image_",
                ".jpg",
                cacheDir
            )
        } catch (e: java.io.IOException) {
            LogUtil.error(null, "Создание временного файла", "Ошибка создания временного файла: ${e.message}")
            throw e
        } catch (e: java.lang.SecurityException) {
            LogUtil.error(null, "Создание временного файла", "Нет прав для создания временного файла: ${e.message}")
            throw e
        } catch (e: Exception) {
            LogUtil.error(null, "Создание временного файла", "Неожиданная ошибка при создании временного файла: ${e.message}")
            throw IOException("Не удалось создать временный файл", e)
        }
    }

    /**
     * Проверяет наличие достаточного места на диске для операции
     *
     * @param context Контекст приложения
     * @param requiredBytes Требуемое количество байт
     * @param targetDir Целевая директория (по умолчанию cacheDir)
     * @return true если достаточно места, иначе false
     */
    fun hasEnoughDiskSpace(
        context: Context,
        requiredBytes: Long,
        targetDir: File? = null
    ): Boolean {
        return try {
            val dir = targetDir ?: context.cacheDir
            val stat = StatFs(dir.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong

            // Оставляем запас 50MB
            val minRequired = requiredBytes + (50 * 1024 * 1024)
            val hasSpace = availableBytes >= minRequired

            if (!hasSpace) {
                LogUtil.error(
                    null,
                    "Проверка дискового пространства",
                    "Недостаточно места: требуется ${minRequired / 1024 / 1024}MB, " +
                            "доступно ${availableBytes / 1024 / 1024}MB"
                )
            }

            hasSpace
        } catch (e: Exception) {
            LogUtil.errorWithException("Проверка дискового пространства", e)
            // При ошибке считаем что места достаточно (fail-safe)
            true
        }
    }

    /**
     * Проверяет наличие достаточной памяти для декодирования изображения
     *
     * @param context Контекст приложения
     * @param estimatedBytes Оценочный размер в байтах
     * @return true если достаточно памяти, иначе false
     */
    fun hasEnoughMemory(context: Context, estimatedBytes: Long): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memoryInfo)

            val availableMem = memoryInfo.availMem
            // Оставляем запас 100MB для системы
            val minRequired = estimatedBytes + (100 * 1024 * 1024)
            val hasMemory = availableMem >= minRequired

            if (!hasMemory) {
                LogUtil.error(
                    null,
                    "Проверка памяти",
                    "Недостаточно памяти: требуется ${minRequired / 1024 / 1024}MB, " +
                            "доступно ${availableMem / 1024 / 1024}MB"
                )
            }

            hasMemory
        } catch (e: Exception) {
            LogUtil.errorWithException("Проверка памяти", e)
            true // fail-safe
        }
    }

    /**
     * Создает временный файл с валидацией ресурсов
     *
     * Проверяет доступное место на диске перед созданием файла.
     * Использует Result pattern для детализации ошибок.
     *
     * @param context Контекст приложения
     * @return FileOperationResult с временным файлом или ошибкой
     */
    suspend fun createTempImageFileValidated(context: Context): FileOperationResult<File> = withContext(Dispatchers.IO) {
        try {
            // Проверка доступности cacheDir
            val cacheDir = context.cacheDir
            if (!cacheDir.exists()) {
                LogUtil.error(null, "Cache директория", "Cache директория не существует")
                return@withContext FileOperationResult.Error(
                    FileErrorType.IO_ERROR,
                    "Cache директория недоступна"
                )
            }

            // Проверка места на диске (ориентировочно 50MB для временного файла)
            val estimatedSize = 50 * 1024 * 1024L
            if (!hasEnoughDiskSpace(context, estimatedSize, cacheDir)) {
                return@withContext FileOperationResult.Error(
                    FileErrorType.DISK_FULL,
                    "Недостаточно места на диске для создания временного файла"
                )
            }

            // Создаем временный файл
            val tempFile = File.createTempFile("temp_image_", ".jpg", cacheDir)
            LogUtil.processInfo("Создан временный файл: ${tempFile.absolutePath}")

            return@withContext FileOperationResult.Success(tempFile)
        } catch (e: IOException) {
            LogUtil.errorWithException("Создание временного файла", e)
            return@withContext FileOperationResult.Error(
                FileErrorType.IO_ERROR,
                "Ошибка при создании временного файла: ${e.message}",
                e
            )
        } catch (e: Exception) {
            LogUtil.errorWithException("Создание временного файла", e)
            return@withContext FileOperationResult.Error(
                FileErrorType.UNKNOWN,
                "Неожиданная ошибка: ${e.message}",
                e
            )
        }
    }

    /**
     * Проверка валидности размера файла
     */
    fun isFileSizeValid(size: Long): Boolean {
        return size in Constants.MIN_FILE_SIZE..Constants.MAX_FILE_SIZE
    }
    
    /**
     * Находит сжатую версию файла в директории приложения по имени оригинального файла
     */
    suspend fun findCompressedVersionByOriginalName(context: Context, originalUri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            // Получаем имя оригинального файла
            val originalFileName = UriUtil.getFileNameFromUri(context, originalUri)
            if (originalFileName.isNullOrEmpty()) {
                LogUtil.debug("FileUtil", "Не удалось получить имя оригинального файла: $originalUri")
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
            
            LogUtil.debug("FileUtil", "Ищем сжатую версию файла с паттерном '$fileBaseName%$fileExtension' в папке ${Constants.APP_DIRECTORY}")
            
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
                    
                    LogUtil.fileInfo(compressedUri, "Найдена сжатая версия для $originalFileName: $foundName")
                    return@withContext compressedUri
                }
            }
            
            LogUtil.debug("FileUtil", "Сжатая версия для файла '$originalFileName' не найдена")
            return@withContext null
        } catch (e: Exception) {
            LogUtil.errorWithException("Поиск сжатой версии файла", e)
            return@withContext null
        }
    }
    
    /**
     * Проверяет, является ли изображение скриншотом
     */
    fun isScreenshot(context: Context, uri: Uri): Boolean {
        try {
            val fileName = UriUtil.getFileNameFromUri(context, uri)?.lowercase() ?: return false
            return fileName.contains("screenshot") || 
                   fileName.contains("screen_shot") || 
                   fileName.contains("скриншот") || 
                   (fileName.contains("screen") && fileName.contains("shot"))
        } catch (e: Exception) {
            LogUtil.error(null, "Ошибка при проверке скриншота для $uri", e)
            return false
        }
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
} 
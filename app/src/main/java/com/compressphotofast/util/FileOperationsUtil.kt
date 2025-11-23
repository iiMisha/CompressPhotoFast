package com.compressphotofast.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
     */
    fun createCompressedFileName(context: Context, originalName: String): String {
        // В режиме замены используем оригинальное имя файла
        if (isSaveModeReplace(context)) {
            LogUtil.processInfo("[FileOperationsUtil] Режим замены включён, используем оригинальное имя: $originalName")
            return originalName
        }
        
        // В режиме отдельного сохранения добавляем суффикс
        val dotIndex = originalName.lastIndexOf('.')
        val compressedName = if (dotIndex > 0) {
            val baseName = originalName.substring(0, dotIndex)
            val extension = originalName.substring(dotIndex) // включая точку
            "${baseName}${Constants.COMPRESSED_FILE_SUFFIX}$extension"
        } else {
            "${originalName}${Constants.COMPRESSED_FILE_SUFFIX}"
        }
        
        LogUtil.processInfo("[FileOperationsUtil] Режим отдельного сохранения, добавляем суффикс: $originalName → $compressedName")
        return compressedName
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
                val path = UriUtil.getFilePathFromUri(context, cleanUri)
                if (path != null) {
                    LogUtil.processInfo("Удаляем файл по пути (старый API): $path")
                    val file = File(path)
                    val result = file.delete()
                    LogUtil.processInfo("Результат удаления файла: $result")
                    return result
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
        return File.createTempFile(
            "temp_image_",
            ".jpg",
            context.cacheDir
        )
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
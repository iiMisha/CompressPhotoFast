package com.compressphotofast.util

import android.content.ContentResolver
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Утилитарный класс для работы с файлами
 */
object FileUtil {

    /**
     * Получение имени файла из URI
     */
    fun getFileName(contentResolver: ContentResolver, uri: Uri): String? {
        var fileName: String? = null
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        
        cursor?.use {
            if (it.moveToFirst()) {
                val displayNameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    fileName = it.getString(displayNameIndex)
                }
            }
        }
        
        return fileName
    }

    /**
     * Проверяет, содержит ли URI маркеры сжатого изображения
     * @deprecated Используйте ImageTrackingUtil.isImageProcessed вместо этого метода
     */
    @Deprecated("Используйте ImageTrackingUtil.isImageProcessed для проверки статуса сжатия", 
                ReplaceWith("ImageTrackingUtil.isImageProcessed(context, uri)"))
    fun isAlreadyCompressed(uri: Uri): Boolean {
        val path = uri.toString().lowercase()
        return path.contains("_compressed") || 
               path.contains("_сжатое") || 
               path.contains("_small")
    }

    /**
     * Создает имя файла для сжатой версии
     */
    fun createCompressedFileName(originalName: String): String {
        val extension = originalName.substringAfterLast(".", "")
        val nameWithoutExt = originalName.substringBeforeLast(".")
        return "${nameWithoutExt}_compressed.$extension"
    }

    /**
     * Сохраняет сжатое изображение в галерею
     * 
     * @return Pair<Uri?, Any?> - первый элемент - URI сжатого изображения, второй - IntentSender для запроса разрешения на удаление оригинала
     */
    suspend fun saveCompressedImageToGallery(
        context: Context,
        compressedFile: File,
        fileName: String,
        originalUri: Uri
    ): Pair<Uri?, Any?> {
        try {
            // Получаем режим сохранения из SharedPreferences
            val prefs = context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
            val isReplaceModeEnabled = prefs.getBoolean(Constants.PREF_SAVE_MODE, false)
            
            Timber.d("saveCompressedImageToGallery: режим замены оригинальных файлов: ${if (isReplaceModeEnabled) "включен" else "выключен"}")
            
            // Проверяем, что имя файла не содержит уже маркер сжатия
            val hasCompressionMarker = ImageTrackingUtil.COMPRESSION_MARKERS.any { marker ->
                fileName.lowercase().contains(marker.lowercase())
            }
            
            // Базовое имя файла (без маркера сжатия, если он уже есть)
            val originalNameWithoutExtension = fileName.substringBeforeLast(".")
            val extension = fileName.substringAfterLast(".", "jpg")
            
            val baseNameWithoutMarker = if (hasCompressionMarker) {
                ImageTrackingUtil.COMPRESSION_MARKERS.fold(originalNameWithoutExtension) { acc, marker ->
                    acc.replace(marker, "")
                }
            } else {
                originalNameWithoutExtension
            }
            
            // Создаем имя для сжатого файла
            var finalFileName: String
            var counter = 1
            
            if (isReplaceModeEnabled) {
                // В режиме замены используем оригинальное имя файла
                finalFileName = "${baseNameWithoutMarker}.$extension"
                
                // Проверяем существование файла с таким именем только для логов
                val exists = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media._ID} != ?",
                    arrayOf(finalFileName, originalUri.lastPathSegment ?: ""),
                    null
                )?.use { cursor -> cursor.count > 0 } ?: false
                
                if (exists) {
                    Timber.d("В режиме замены обнаружен файл с таким же именем, но продолжаем использовать оригинальное имя: $finalFileName")
                }
            } else {
                // В режиме сохранения в отдельной папке добавляем маркер сжатия
                finalFileName = "${baseNameWithoutMarker}${Constants.COMPRESSED_SUFFIX}.$extension"
                
                // Проверяем существование файла с таким именем и увеличиваем счетчик при необходимости
                while (true) {
                    val testFileName = if (counter == 1) finalFileName else {
                        val baseName = finalFileName.substringBeforeLast(".")
                        val ext = finalFileName.substringAfterLast(".", "")
                        "${baseName}_$counter.$ext"
                    }
                    
                    val exists = context.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Images.Media._ID),
                        "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                        arrayOf(testFileName),
                        null
                    )?.use { cursor -> cursor.count > 0 } ?: false

                    if (!exists) {
                        finalFileName = testFileName
                        break
                    }
                    counter++
                }
            }
            
            Timber.d("saveCompressedImageToGallery: сохранение сжатого файла с именем: $finalFileName")

            // Получаем путь оригинального файла
            val originalPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getRelativePathFromUri(context, originalUri)
            } else {
                null
            }
            
            // Создаем запись в MediaStore
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DESCRIPTION, "Compressed")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (isReplaceModeEnabled && originalPath != null) {
                        // В режиме замены используем путь оригинального файла
                        put(MediaStore.Images.Media.RELATIVE_PATH, originalPath)
                        Timber.d("Используем относительный путь оригинального файла: $originalPath")
                    } else if (!isReplaceModeEnabled) {
                        // В режиме сохранения в отдельной папке используем директорию приложения
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + Constants.APP_DIRECTORY)
                        Timber.d("Используем путь приложения: ${Environment.DIRECTORY_PICTURES}/${Constants.APP_DIRECTORY}")
                    } else {
                        // Если не удалось получить путь, используем директорию оригинального файла или стандартную
                        val originalFilePath = getFilePathFromUri(context, originalUri)
                        if (originalFilePath != null) {
                            val parentPath = File(originalFilePath).parent
                            if (parentPath != null) {
                                // Извлекаем относительный путь из абсолютного
                                val storagePath = Environment.getExternalStorageDirectory().path
                                if (parentPath.startsWith(storagePath)) {
                                    val relativePath = parentPath.substring(storagePath.length + 1)
                                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                                    Timber.d("Используем извлеченный относительный путь: $relativePath")
                                } else {
                                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                                    Timber.d("Используем стандартный путь изображений")
                                }
                            } else {
                                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                                Timber.d("Используем стандартный путь изображений (не удалось получить родительскую директорию)")
                            }
                        } else {
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                            Timber.d("Используем стандартный путь изображений (не удалось получить путь файла)")
                        }
                    }
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            // Сохраняем файл
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            var deletePendingIntent: Any? = null
            
            if (uri != null) {
                // Копируем содержимое файла
                try {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        compressedFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    
                    // Копируем EXIF данные
                    copyExifData(context, originalUri, uri)
                    
                    // Завершаем запись
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                    }
                    
                    // Если включен режим замены, удаляем оригинальный файл
                    if (isReplaceModeEnabled) {
                        try {
                            Timber.d("saveCompressedImageToGallery: удаление оригинального файла: $originalUri")
                            deletePendingIntent = deleteFile(context, originalUri)
                            // Если функция вернула не boolean, значит требуется запрос разрешения
                            if (deletePendingIntent !is Boolean) {
                                Timber.d("saveCompressedImageToGallery: требуется запрос разрешения на удаление")
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Ошибка при удалении оригинального файла")
                        }
                    }
                    
                    return Pair(uri, deletePendingIntent)
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при записи файла: ${e.message}")
                    // Если произошла ошибка, удаляем созданную запись
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (deleteException: Exception) {
                        Timber.e(deleteException, "Ошибка при удалении неполной записи")
                    }
                }
            } else {
                Timber.e("Не удалось создать URI для сжатого изображения")
            }
            return Pair(null, null)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при сохранении сжатого изображения: ${e.message}")
            return Pair(null, null)
        }
    }

    /**
     * Копирует EXIF данные из оригинального изображения в сжатое
     */
    private fun copyExifData(context: Context, sourceUri: Uri, destUri: Uri) {
        try {
            // Вместо создания двух ExifInterface из потоков, мы сначала получим файловые пути
            val sourceInputPfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
            val destOutputPfd = context.contentResolver.openFileDescriptor(destUri, "rw")
            
            if (sourceInputPfd != null && destOutputPfd != null) {
                try {
                    val sourceExif = ExifInterface(sourceInputPfd.fileDescriptor)
                    val destExif = ExifInterface(destOutputPfd.fileDescriptor)
                    
                    // Копируем все EXIF теги
                    val tags = arrayOf(
                        ExifInterface.TAG_DATETIME,
                        ExifInterface.TAG_EXPOSURE_TIME,
                        ExifInterface.TAG_FLASH,
                        ExifInterface.TAG_FOCAL_LENGTH,
                        ExifInterface.TAG_GPS_ALTITUDE,
                        ExifInterface.TAG_GPS_ALTITUDE_REF,
                        ExifInterface.TAG_GPS_DATESTAMP,
                        ExifInterface.TAG_GPS_LATITUDE,
                        ExifInterface.TAG_GPS_LATITUDE_REF,
                        ExifInterface.TAG_GPS_LONGITUDE,
                        ExifInterface.TAG_GPS_LONGITUDE_REF,
                        ExifInterface.TAG_GPS_PROCESSING_METHOD,
                        ExifInterface.TAG_GPS_TIMESTAMP,
                        ExifInterface.TAG_IMAGE_LENGTH,
                        ExifInterface.TAG_IMAGE_WIDTH,
                        ExifInterface.TAG_MAKE,
                        ExifInterface.TAG_MODEL,
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.TAG_SUBSEC_TIME,
                        ExifInterface.TAG_WHITE_BALANCE
                    )
                    
                    // Копируем все доступные теги
                    for (tag in tags) {
                        val value = sourceExif.getAttribute(tag)
                        if (value != null) {
                            destExif.setAttribute(tag, value)
                        }
                    }
                    
                    // Сохраняем изменения
                    destExif.saveAttributes()
                    Timber.d("EXIF данные успешно скопированы")
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при копировании EXIF данных")
                } finally {
                    try {
                        sourceInputPfd.close()
                    } catch (e: Exception) {
                        Timber.e(e, "Ошибка при закрытии дескриптора исходного файла")
                    }
                    try {
                        destOutputPfd.close()
                    } catch (e: Exception) {
                        Timber.e(e, "Ошибка при закрытии дескриптора файла назначения")
                    }
                }
            } else {
                Timber.e("Не удалось получить файловые дескрипторы для копирования EXIF данных")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при копировании EXIF данных: ${e.message}")
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
                        return cursor.getString(columnIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении пути к файлу из URI")
        }
        return null
    }

    /**
     * Обрабатывает результат запроса удаления файла
     * 
     * Этот метод должен быть вызван из Activity.onActivityResult
     */
    fun handleDeleteFileRequest(resultCode: Int): Boolean {
        return resultCode == android.app.Activity.RESULT_OK
    }
} 
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * Создает имя файла для сжатой версии
     * Теперь просто возвращает оригинальное имя файла
     */
    fun createCompressedFileName(originalName: String): String {
        return originalName
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
            
            // Используем оригинальное имя файла
            val finalFileName = fileName
            
            Timber.d("saveCompressedImageToGallery: сохранение сжатого файла с именем: $finalFileName")

            // Получаем путь оригинального файла
            val originalPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getRelativePathFromUri(context, originalUri)
            } else {
                null
            }
            
            // Если включен режим замены, сначала удаляем оригинальный файл
            var deletePendingIntent: Any? = null
            if (isReplaceModeEnabled) {
                try {
                    Timber.d("saveCompressedImageToGallery: удаление оригинального файла перед созданием нового: $originalUri")
                    deletePendingIntent = deleteFile(context, originalUri)
                    // Если функция вернула не boolean, значит требуется запрос разрешения
                    if (deletePendingIntent !is Boolean) {
                        Timber.d("saveCompressedImageToGallery: требуется запрос разрешения на удаление")
                        // Возвращаем результат с запросом разрешения
                        return Pair(null, deletePendingIntent)
                    }
                    
                    // Даем системе время на обработку удаления
                    kotlinx.coroutines.delay(300)
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при удалении оригинального файла")
                }
            }
            
            // Создаем запись в MediaStore
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DESCRIPTION, "Compressed")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (originalPath != null) {
                        // Всегда используем путь оригинального файла
                        put(MediaStore.Images.Media.RELATIVE_PATH, originalPath)
                        Timber.d("Используем относительный путь оригинального файла: $originalPath")
                    } else {
                        // Только если не удалось получить путь оригинального файла, используем директорию приложения
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + Constants.APP_DIRECTORY)
                        Timber.d("Используем путь приложения: ${Environment.DIRECTORY_PICTURES}/${Constants.APP_DIRECTORY}")
                    }
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            // Сохраняем файл
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            
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
     * Метод для использования между двумя URI
     */
    fun copyExifData(context: Context, sourceUri: Uri, destUri: Uri) {
        try {
            // Вместо создания двух ExifInterface из потоков, мы сначала получим файловые пути
            val sourceInputPfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
            val destOutputPfd = context.contentResolver.openFileDescriptor(destUri, "rw")
            
            if (sourceInputPfd != null && destOutputPfd != null) {
                try {
                    val sourceExif = ExifInterface(sourceInputPfd.fileDescriptor)
                    val destExif = ExifInterface(destOutputPfd.fileDescriptor)
                    
                    // Копируем все EXIF теги, используя общий метод
                    copyExifTags(sourceExif, destExif)
                    
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
     * Копирует EXIF данные из URI источника в файл назначения
     * Метод для использования между URI и File
     */
    suspend fun copyExifDataFromUriToFile(context: Context, sourceUri: Uri, destinationFile: File) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                val sourceExif = ExifInterface(inputStream)
                val destinationExif = ExifInterface(destinationFile.absolutePath)

                // Копируем все EXIF теги, используя общий метод
                copyExifTags(sourceExif, destinationExif)

                // Сохраняем изменения
                destinationExif.saveAttributes()
                Timber.d("EXIF данные успешно скопированы из URI в файл")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при копировании EXIF данных: ${e.message}")
        }
    }
    
    /**
     * Проверяет, поддерживается ли тег EXIF в текущей версии Android
     * 
     * @param tag название тега
     * @return true если тег существует в ExifInterface, false в противном случае
     */
    private fun isExifTagAvailable(tag: String): Boolean {
        return try {
            // Пытаемся получить доступ к полю через рефлексию
            ExifInterface::class.java.getField(tag)
            true
        } catch (e: NoSuchFieldException) {
            Timber.d("EXIF тег $tag не поддерживается в текущей версии Android")
            false
        } catch (e: Exception) {
            Timber.d("Ошибка при проверке EXIF тега $tag: ${e.message}")
            false
        }
    }

    /**
     * Общий метод для копирования тегов EXIF между двумя объектами ExifInterface
     */
    private fun copyExifTags(sourceExif: ExifInterface, destExif: ExifInterface) {
        // Список всех тегов EXIF, которые нужно копировать
        val allPossibleTags = arrayOf(
            // Базовые теги времени и даты
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            
            // Теги экспозиции и съемки
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_EXPOSURE_INDEX,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            
            // Технические параметры камеры
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            // Заменяем устаревшие теги ISO на современные
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_SENSITIVITY_TYPE,
            ExifInterface.TAG_ISO_SPEED,
            ExifInterface.TAG_ISO_SPEED_LATITUDE_YYY,
            ExifInterface.TAG_ISO_SPEED_LATITUDE_ZZZ,
            
            // Информация о вспышке
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FLASH_ENERGY,
            
            // GPS данные
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            
            // Информация об изображении
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_RESOLUTION_UNIT,
            ExifInterface.TAG_X_RESOLUTION,
            ExifInterface.TAG_Y_RESOLUTION,
            ExifInterface.TAG_COMPRESSION,
            ExifInterface.TAG_BITS_PER_SAMPLE,
            
            // Информация о камере
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION,
            
            // Информация о сцене и режимах
            ExifInterface.TAG_SCENE_TYPE,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_SUBJECT_AREA,
            ExifInterface.TAG_SUBJECT_DISTANCE,
            ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
            ExifInterface.TAG_SUBJECT_LOCATION,
            
            // Информация о цвете и балансе белого
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_WHITE_POINT,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_COMPONENTS_CONFIGURATION,
            
            // Другие параметры обработки изображения
            ExifInterface.TAG_CONTRAST,
            ExifInterface.TAG_SATURATION,
            ExifInterface.TAG_SHARPNESS,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_GAIN_CONTROL,
            
            // Дополнительные пользовательские данные
            ExifInterface.TAG_MAKER_NOTE,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_COPYRIGHT
        )
        
        // Фильтруем теги, доступные в текущей версии Android
        val availableTags = allPossibleTags.filter { tag ->
            try {
                // Безопасная проверка - пробуем получить значение тега, 
                // даже если оно null, это показывает, что тег поддерживается
                sourceExif.getAttribute(tag)
                true
            } catch (e: Exception) {
                false
            }
        }
        
        // Логируем информацию о поддерживаемых тегах
        Timber.d("Доступно ${availableTags.size} из ${allPossibleTags.size} тегов EXIF")
        
        // Копируем все доступные теги
        for (tag in availableTags) {
            try {
                val value = sourceExif.getAttribute(tag)
                if (value != null) {
                    destExif.setAttribute(tag, value)
                }
            } catch (e: Exception) {
                // Если возникла ошибка при копировании конкретного тега, просто пропускаем его
                Timber.d("Не удалось скопировать тег EXIF: $tag - ${e.message}")
            }
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
     * Получает имя файла из URI (расширенная версия)
     * В отличие от getFileName, дополнительно пробует получить имя из lastPathSegment,
     * если не удалось найти через MediaStore
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        try {
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
} 
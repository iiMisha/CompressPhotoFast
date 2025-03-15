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
import androidx.exifinterface.media.ExifInterface
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.HashMap
import android.provider.DocumentsContract

/**
 * Утилитарный класс для работы с файлами
 */
object FileUtil {

    private val saveMutex = Mutex()
    private val processedUris = mutableSetOf<String>()
    private val processedFileNames = mutableMapOf<String, Uri>()
    
    // Кэш для результатов проверки EXIF-маркеров (URI -> результат проверки)
    private val exifCheckCache = Collections.synchronizedMap(HashMap<String, Boolean>())
    // Время жизни кэша EXIF (5 минут)
    private val exifCacheExpiration = 5 * 60 * 1000L
    // Время последнего обновления кэша
    private val exifCacheTimestamps = Collections.synchronizedMap(HashMap<String, Long>())

    // Константы для EXIF маркировки
    private const val EXIF_USER_COMMENT = ExifInterface.TAG_USER_COMMENT
    private const val EXIF_COMPRESSION_MARKER = "CompressPhotoFast_Compressed"
    private const val EXIF_COMPRESSION_LEVEL = "CompressPhotoFast_Quality"

    /**
     * Добавляет маркер сжатия и уровень компрессии в EXIF-метаданные изображения
     * @param filePath путь к файлу
     * @param quality уровень качества (0-100)
     * @return true если маркировка успешна, false в противном случае
     */
    suspend fun markCompressedImage(filePath: String, quality: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val exif = ExifInterface(filePath)
            
            // Проверяем текущее значение UserComment перед изменением
            val oldUserComment = exif.getAttribute(EXIF_USER_COMMENT)
            
            // Добавляем маркер сжатия и уровень компрессии в один тег UserComment
            val markerWithQuality = "${EXIF_COMPRESSION_MARKER}:$quality"
            exif.setAttribute(EXIF_USER_COMMENT, markerWithQuality)
            
            // Сохраняем EXIF данные
            exif.saveAttributes()
            
            // Проверяем, что маркер был установлен правильно
            val newExif = ExifInterface(filePath)
            val newUserComment = newExif.getAttribute(EXIF_USER_COMMENT)
            
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при добавлении EXIF маркера сжатия: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Добавляет маркер сжатия и уровень компрессии в EXIF-метаданные изображения
     * @param context контекст
     * @param uri URI изображения
     * @param quality уровень качества (0-100)
     * @return true если маркировка успешна, false в противном случае
     */
    suspend fun markCompressedImage(context: Context, uri: Uri, quality: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return@withContext false
            
            // Сначала проверяем текущее значение UserComment
            var oldUserComment: String? = null
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val oldExif = ExifInterface(inputStream)
                oldUserComment = oldExif.getAttribute(EXIF_USER_COMMENT)
            }
            Timber.d("Текущий EXIF маркер для $uri: $oldUserComment")
            
            pfd.use { fileDescriptor ->
                val exif = ExifInterface(fileDescriptor.fileDescriptor)
                
                // Добавляем маркер сжатия и уровень компрессии в один тег UserComment
                val markerWithQuality = "${EXIF_COMPRESSION_MARKER}:$quality"
                exif.setAttribute(EXIF_USER_COMMENT, markerWithQuality)
                
                // Сохраняем EXIF данные
                exif.saveAttributes()
            }
            
            // Проверяем, что маркер был установлен правильно
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val newExif = ExifInterface(inputStream)
                val newUserComment = newExif.getAttribute(EXIF_USER_COMMENT)
                Timber.d("EXIF маркер сжатия установлен в URI: $uri с качеством: $quality. Записанное значение: $newUserComment")
            }
            
            return@withContext true
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при добавлении EXIF маркера сжатия в URI: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Проверяет, является ли изображение сжатым по EXIF-метаданным
     * @param context контекст
     * @param uri URI изображения
     * @return true если изображение сжато, false в противном случае
     */
    suspend fun isCompressedByExif(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val uriString = uri.toString()
            
            // Проверяем кэш
            val cachedResult = exifCheckCache[uriString]
            val lastChecked = exifCacheTimestamps[uriString] ?: 0L
            val isCacheValid = cachedResult != null && (System.currentTimeMillis() - lastChecked < exifCacheExpiration)
            
            if (isCacheValid) {
                val isCompressed = cachedResult ?: false
                if (isCompressed) {
                    Timber.d("Изображение по URI $uri уже сжато (из кэша)")
                }
                return@withContext isCompressed
            }
            
            // Если нет в кэше или кэш устарел, выполняем проверку
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                
                // Проверяем маркер сжатия в UserComment
                val userComment = exif.getAttribute(EXIF_USER_COMMENT)
                val isCompressed = userComment?.startsWith(EXIF_COMPRESSION_MARKER) == true
                
                // Кэшируем результат
                exifCheckCache[uriString] = isCompressed
                exifCacheTimestamps[uriString] = System.currentTimeMillis()
                
                return@withContext isCompressed
            }
            
            // Если не удалось открыть поток, сохраняем отрицательный результат в кэше
            exifCheckCache[uriString] = false
            exifCacheTimestamps[uriString] = System.currentTimeMillis()
            
            return@withContext false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке EXIF маркера сжатия: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Получает уровень компрессии из EXIF-метаданных изображения
     * @param context контекст
     * @param uri URI изображения
     * @return уровень качества (0-100) или null если информация не найдена
     */
    suspend fun getCompressionLevel(context: Context, uri: Uri): Int? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                
                // Получаем информацию о качестве из UserComment
                val userComment = exif.getAttribute(EXIF_USER_COMMENT)
                if (userComment?.startsWith(EXIF_COMPRESSION_MARKER) == true) {
                    val quality = userComment.substringAfter("$EXIF_COMPRESSION_MARKER:").toIntOrNull()
                    Timber.d("Получен уровень компрессии из EXIF: $quality для URI: $uri")
                    return@withContext quality
                }
                
                return@withContext null
            }
            
            return@withContext null
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении уровня компрессии из EXIF: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Верифицирует, что указанный в EXIF уровень компрессии соответствует ожидаемому значению
     * @param context контекст
     * @param uri URI изображения
     * @param expectedQuality ожидаемый уровень качества (0-100)
     * @return true если уровень компрессии соответствует, false в противном случае
     */
    suspend fun verifyExifCompressionLevel(context: Context, uri: Uri, expectedQuality: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val actualQuality = getCompressionLevel(context, uri)
            
            if (actualQuality == expectedQuality) {
                Timber.d("Верификация EXIF: уровень компрессии соответствует ожидаемому: $actualQuality")
                return@withContext true
            } else {
                Timber.e("Верификация EXIF: уровень компрессии ($actualQuality) не соответствует ожидаемому ($expectedQuality)")
                return@withContext false
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при верификации уровня компрессии в EXIF: ${e.message}")
            return@withContext false
        }
    }

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
     * Теперь просто возвращает оригинальное имя файла
     */
    fun createCompressedFileName(originalName: String): String {
        return originalName
    }

    /**
     * Создает уникальное имя файла для ручного сжатия
     */
    private fun createUniqueFileName(originalName: String): String {
        val baseName = originalName.substringBeforeLast(".")
        val extension = originalName.substringAfterLast(".", "")
        val timestamp = System.currentTimeMillis()
        return "${baseName}_${timestamp}.$extension"
    }

    /**
     * Сохранение сжатого изображения в галерею
     * 
     * @return Pair<Uri?, Any?> - Первый элемент - URI сжатого изображения, второй - IntentSender для запроса разрешения на удаление
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
            // Используем оригинальное имя файла без изменений
            val finalFileName = fileName
            
            // Получаем путь к директории для сохранения
            val directory = if (isSaveModeReplace(context)) {
                // Если включен режим замены, сохраняем в той же директории
                getDirectoryFromUri(context, originalUri)
            } else {
                // Иначе сохраняем в директории приложения
                Constants.APP_DIRECTORY
            }
            
            Timber.d("saveCompressedImageToGallery: директория для сохранения: $directory")
            
            // Сохраняем файл
            val (savedUri, deleteIntentSender) = insertImageIntoMediaStore(
                context,
                compressedFile,
                finalFileName,
                directory
            )
            
            if (savedUri != null) {
                Timber.d("saveCompressedImageToGallery: файл успешно сохранен: $savedUri")
            } else {
                Timber.e("saveCompressedImageToGallery: не удалось сохранить файл")
            }
            
            return@withContext Pair(savedUri, deleteIntentSender)
        } catch (e: Exception) {
            Timber.e(e, "saveCompressedImageToGallery: ошибка при сохранении файла")
            return@withContext Pair(null, null)
        }
    }

    /**
     * Безопасное удаление оригинального файла с обработкой ошибок
     */
    private suspend fun safeDeleteOriginalFile(context: Context, uri: Uri): Any? = withContext(Dispatchers.IO) {
        try {
            // Получаем путь к файлу
            val path = getFilePathFromUri(context, uri)
            if (path.isNullOrEmpty()) {
                Timber.d("Не удалось получить путь к файлу для удаления: $uri")
                return@withContext null
            }
            
            // Проверяем, существует ли файл в файловой системе
            val file = File(path)
            if (!file.exists()) {
                Timber.d("Файл не существует по пути: $path")
            }
            
            // Пробуем удалить через MediaStore
            val result = deleteFile(context, uri)
            
            if (result is Boolean && result) {
                Timber.d("Файл успешно удален через MediaStore без запроса разрешения: $uri")
                return@withContext true
            }
            
            return@withContext result
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при удалении оригинального файла: $uri")
            return@withContext null
        }
    }

    /**
     * Создает новую запись в MediaStore для сжатого изображения
     */
    private suspend fun insertImageIntoMediaStore(
        context: Context,
        compressedFile: File,
        fileName: String,
        directory: String
    ): Pair<Uri?, Any?> = withContext(Dispatchers.IO) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, directory)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            
            // Пробуем найти существующий файл с таким же именем и путем
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val existingUri = checkForExistingFile(context, fileName, directory)
                if (existingUri != null) {
                    try {
                        // Если файл существует, удаляем его перед созданием нового
                        Timber.d("Найден существующий файл с таким же именем, удаляем: $existingUri")
                        context.contentResolver.delete(existingUri, null, null)
                    } catch (e: Exception) {
                        Timber.e(e, "Ошибка при удалении существующего файла: $existingUri")
                    }
                }
            }
            
            // Создаем новую запись
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            
            if (uri != null) {
                // Копируем данные из временного файла
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    compressedFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                // На Android 10+ нужно обновить IS_PENDING флаг
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                }
                
                return@withContext Pair(uri, null)
            }
            
            return@withContext Pair(null, null)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при записи файла: ${e.message}")
            return@withContext Pair(null, null)
        }
    }

    /**
     * Проверяет наличие файла с таким же именем в указанной директории
     */
    private suspend fun checkForExistingFile(
        context: Context,
        fileName: String,
        directory: String
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(fileName, "$directory/")
            
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    return@withContext ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
            }
            
            return@withContext null
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при поиске существующего файла: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Копирует EXIF данные из оригинального изображения в сжатое
     * Метод для использования между двумя URI
     */
    fun copyExifData(context: Context, sourceUri: Uri, destUri: Uri) {
        try {
            // Проверяем существование исходного URI
            try {
                val checkCursor = context.contentResolver.query(sourceUri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                val sourceExists = checkCursor?.use { it.count > 0 } ?: false
                
                if (!sourceExists) {
                    Timber.d("Исходный URI не существует, пропускаем копирование EXIF: $sourceUri")
                    return
                }
            } catch (e: Exception) {
                Timber.d("Не удалось проверить существование исходного URI: $sourceUri, ${e.message}")
                return
            }
            
            // Вместо создания двух ExifInterface из потоков, мы сначала получим файловые пути
            val sourceInputPfd = try {
                context.contentResolver.openFileDescriptor(sourceUri, "r")
            } catch (e: FileNotFoundException) {
                Timber.e(e, "Исходный файл не найден при попытке копирования EXIF: %s", sourceUri.toString())
                return
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при открытии исходного файла для EXIF: %s", sourceUri.toString())
                return
            }
            
            val destOutputPfd = try {
                context.contentResolver.openFileDescriptor(destUri, "rw")
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при открытии файла назначения для EXIF: $destUri")
                sourceInputPfd?.close()
                return
            }
            
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
                sourceInputPfd?.close()
                destOutputPfd?.close()
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
            // Специальная обработка для URI из MediaProvider Documents
            if (uri.authority == "com.android.providers.media.documents") {
                Timber.d("getFilePathFromUri: обнаружен URI MediaProvider Documents: $uri")
                
                // Извлекаем ID документа
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("image:")) {
                    // Извлекаем ID изображения
                    val id = docId.split(":")[1]
                    // Создаем новый URI для обращения к MediaStore
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toLong())
                    Timber.d("getFilePathFromUri: преобразованный URI: $contentUri")
                    
                    // Рекурсивно вызываем тот же метод для обработки преобразованного URI
                    return getFilePathFromUri(context, contentUri)
                }
            }
            
            // На Android 10 (API 29) и выше MediaStore.Images.Media.DATA считается устаревшим
            // и может возвращать null, поэтому используем другой подход
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Для Android 10+ используем относительный путь и имя файла
                val projection = arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH
                )
                
                context.contentResolver.query(
                    uri,
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
                
                // Если не удалось получить путь через MediaStore, пробуем через lastPathSegment
                uri.lastPathSegment?.let { segment ->
                    if (segment.contains("/")) {
                        return segment
                    }
                }
                
                // Для content URI возвращаем сам URI в виде строки, чтобы можно было
                // проверить, находится ли файл в директории приложения
                return uri.toString()
            } else {
                // Для Android 9 и ниже используем старый подход
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
            // Специальная обработка для URI из MediaProvider Documents
            if (uri.authority == "com.android.providers.media.documents") {
                Timber.d("getFileNameFromUri: обнаружен URI MediaProvider Documents: $uri")
                
                // Извлекаем ID документа
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("image:")) {
                    // Извлекаем ID изображения
                    val id = docId.split(":")[1]
                    // Создаем новый URI для обращения к MediaStore
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toLong())
                    Timber.d("getFileNameFromUri: преобразованный URI: $contentUri")
                    
                    // Рекурсивно вызываем тот же метод для обработки преобразованного URI
                    return getFileNameFromUri(context, contentUri)
                }
            }
            
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

    /**
     * Ищет существующий файл в указанной директории
     */
    private fun findExistingFileUri(context: Context, fileName: String, relativePath: String): Uri? {
        try {
            // Проверяем кэш
            val cacheKey = "$relativePath/$fileName"
            processedFileNames[cacheKey]?.let { cachedUri ->
                Timber.d("Найден кэшированный URI для $cacheKey")
                return cachedUri
            }

            val baseName = fileName.substringBeforeLast(".")
            val extension = fileName.substringAfterLast(".", "")
            
            // Создаем точный шаблон для поиска файла
            val exactPattern = "$baseName.$extension"
            val similarPattern = "$baseName%.$extension"
            
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND (${MediaStore.Images.Media.DISPLAY_NAME} = ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"
            } else {
                "${MediaStore.Images.Media.DISPLAY_NAME} = ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            }
            
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(relativePath, exactPattern, similarPattern)
            } else {
                arrayOf(exactPattern, similarPattern)
            }
            
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED
                ),
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
                    val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                    
                    // Проверяем, является ли файл сжатым (размер меньше 1MB)
                    if (size < 1024 * 1024) {
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        Timber.d("Найден существующий сжатый файл: $name, размер: ${size / 1024}KB, дата: $dateAdded")
                        
                        // Сохраняем в кэш
                        processedFileNames[cacheKey] = uri
                        processedUris.add(uri.toString())
                        
                        return uri
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при поиске существующего файла")
        }
        return null
    }

    /**
     * Проверяет, включен ли режим замены файлов
     */
    fun isSaveModeReplace(context: Context): Boolean {
        return SettingsManager.getInstance(context).isSaveModeReplace()
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
            // Специальная обработка для URI из MediaProvider Documents
            if (uri.authority == "com.android.providers.media.documents") {
                Timber.d("getDirectoryFromUri: обнаружен URI MediaProvider Documents: $uri")
                
                // Извлекаем ID документа
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("image:")) {
                    // Извлекаем ID изображения
                    val id = docId.split(":")[1]
                    // Создаем новый URI для обращения к MediaStore
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toLong())
                    Timber.d("getDirectoryFromUri: преобразованный URI: $contentUri")
                    
                    // Рекурсивно вызываем тот же метод для обработки преобразованного URI
                    return getDirectoryFromUri(context, contentUri)
                }
            }
            
            // Пытаемся получить RELATIVE_PATH для Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.Images.Media.RELATIVE_PATH)
                context.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH))
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
            
            // Если не удалось получить RELATIVE_PATH, пытаемся получить полный путь
            val path = getFilePathFromUri(context, uri)
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
        return "${Environment.DIRECTORY_PICTURES}/${Constants.APP_DIRECTORY}"
    }

    /**
     * Проверяет, является ли файл временным (pending)
     */
    suspend fun isFilePending(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.IS_PENDING)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return@withContext cursor.getInt(0) == 1
                }
            }
            true // В случае ошибки считаем файл временным
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке состояния файла: $uri")
            true
        }
    }

    /**
     * Ожидает, пока файл станет доступным, используя таймер
     */
    suspend fun waitForFileAvailability(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val maxWaitTimeMs = 5000L // Максимальное время ожидания: 5 секунд
        val checkIntervalMs = 300L // Интервал проверки: 300 мс
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < maxWaitTimeMs) {
            // Проверяем размер файла
            val size = getFileSize(context, uri)
            if (size > 0 && !isFilePending(context, uri)) {
                // Файл доступен
                return@withContext true
            }
            
            // Файл недоступен, логируем и ждем
            val elapsedTime = System.currentTimeMillis() - startTime
            val remainingTime = maxWaitTimeMs - elapsedTime
            Timber.d("Файл недоступен (прошло ${elapsedTime}мс, осталось ${remainingTime}мс): размер = $size")
            
            // Ждем следующую проверку
            delay(checkIntervalMs)
        }
        
        Timber.d("Файл не стал доступен после ${maxWaitTimeMs}мс ожидания")
        return@withContext false
    }

    /**
     * Получает размер файла из MediaStore
     */
    suspend fun getFileSize(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.SIZE)
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    return@withContext cursor.getLong(sizeIndex)
                }
            }
            -1L
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении размера файла")
            -1L
        }
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
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        }
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
     * @param context контекст
     * @param uri URI изображения
     * @return true если изображение является скриншотом, false в противном случае
     */
    fun isScreenshot(context: Context, uri: Uri): Boolean {
        try {
            // Получаем имя файла
            val fileName = getFileNameFromUri(context, uri)?.lowercase() ?: return false
            
            // Проверяем, содержит ли имя файла типичные для скриншотов паттерны
            return fileName.contains("screenshot") || 
                   fileName.contains("screen_shot") || 
                   fileName.contains("скриншот") || 
                   (fileName.contains("screen") && fileName.contains("shot"))
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке скриншота для $uri")
            return false
        }
    }
} 
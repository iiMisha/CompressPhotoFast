package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.FileOutputStream
import java.io.FileInputStream
import java.util.Collections
import java.util.HashMap

/**
 * Утилитарный класс для работы с EXIF метаданными изображений
 */
object ExifUtil {
    
    // Константы для EXIF маркировки
    private const val EXIF_USER_COMMENT = ExifInterface.TAG_USER_COMMENT
    private const val EXIF_COMPRESSION_MARKER = "CompressPhotoFast_Compressed"
    
    /** 
     * Список важных EXIF тегов для копирования
     * Обязательно включает теги для метаданных камеры, даты/времени, GPS, экспозиции, и др.
     */
    private val TAG_LIST = arrayOf(
        // Теги даты и времени
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        
        // Теги камеры и устройства
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ORIENTATION,
        
        // Теги вспышки и режимов съемки
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        
        // GPS теги
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_TIMESTAMP,
        
        // Теги экспозиции и параметров съемки
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_EXPOSURE_INDEX,
        
        // Теги диафрагмы и фокусировки
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_F_NUMBER,        // Добавлен тег F
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        
        // Теги ISO и баланса белого
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_LIGHT_SOURCE,
        
        // Прочие теги
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION,
        ExifInterface.TAG_SHARPNESS,
        ExifInterface.TAG_SUBJECT_DISTANCE_RANGE
    )
    
    // Кэш для результатов проверки EXIF-маркеров (URI -> результат проверки)
    private val exifCheckCache = Collections.synchronizedMap(HashMap<String, Boolean>())
    // Время жизни кэша EXIF (5 минут)
    private const val EXIF_CACHE_EXPIRATION = 5 * 60 * 1000L
    // Время последнего обновления кэша
    private val exifCacheTimestamps = Collections.synchronizedMap(HashMap<String, Long>())
    
    /**
     * Добавляет маркер сжатия в EXIF-метаданные файла
     * @param filePath путь к файлу
     * @param quality уровень качества (0-100)
     * @return true если маркировка успешна, false в противном случае
     */
    suspend fun markCompressedImage(filePath: String, quality: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверяем существование файла
            val file = File(filePath)
            if (!file.exists()) {
                Timber.e("Файл не существует при попытке установить маркер сжатия: $filePath")
                return@withContext false
            }
            
            // Проверяем, доступен ли файл для чтения и записи
            if (!file.canRead() || !file.canWrite()) {
                Timber.e("Файл недоступен для чтения/записи при установке маркера сжатия: $filePath")
                return@withContext false
            }
            
            // Читаем текущие EXIF данные
            val exif: ExifInterface
            try {
                exif = ExifInterface(filePath)
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при открытии файла для EXIF: $filePath")
                return@withContext false
            }
            
            try {
                // Получаем текущее значение UserComment
                val currentComment = exif.getAttribute(EXIF_USER_COMMENT)
                
                // Получаем текущее время в миллисекундах
                val dateTimeMs = System.currentTimeMillis()
                
                // Добавляем маркер сжатия, уровень компрессии и дату сжатия в миллисекундах
                val markerWithQualityAndDate = "${EXIF_COMPRESSION_MARKER}:$quality:$dateTimeMs"
                exif.setAttribute(EXIF_USER_COMMENT, markerWithQualityAndDate)
                
                // Сохраняем изменения
                exif.saveAttributes()
                
                // Проверяем, что маркер был установлен
                var verificationSuccess = false
                try {
                    val newExif = ExifInterface(filePath)
                    val newUserComment = newExif.getAttribute(EXIF_USER_COMMENT)
                    if (newUserComment?.contains(EXIF_COMPRESSION_MARKER) == true) {
                        verificationSuccess = true
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при верификации маркера сжатия в файле: $filePath")
                }
                
                return@withContext verificationSuccess
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при установке EXIF маркера сжатия: ${e.message}")
                return@withContext false
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при добавлении EXIF маркера сжатия в файл: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Добавляет маркер сжатия в EXIF-метаданные изображения
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
            
            // Получаем текущее время в миллисекундах
            val dateTimeMs = System.currentTimeMillis()
            
            pfd.use { fileDescriptor ->
                val exif = ExifInterface(fileDescriptor.fileDescriptor)
                
                // Добавляем маркер сжатия, уровень компрессии и дату сжатия в миллисекундах
                val markerWithQualityAndDate = "${EXIF_COMPRESSION_MARKER}:$quality:$dateTimeMs"
                exif.setAttribute(EXIF_USER_COMMENT, markerWithQualityAndDate)
                
                // Сохраняем EXIF данные
                exif.saveAttributes()
            }
            
            // Проверяем, что маркер был установлен правильно
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val newExif = ExifInterface(inputStream)
                val newUserComment = newExif.getAttribute(EXIF_USER_COMMENT)
                Timber.d("EXIF маркер сжатия установлен в URI: $uri с качеством: $quality и датой: $dateTimeMs. Записанное значение: $newUserComment")
            }
            
            // Очищаем кэш для данного URI
            clearCacheForUri(uri.toString())
            
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
    suspend fun isImageCompressed(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val uriString = uri.toString()
            
            // Проверяем кэш
            val cachedResult = exifCheckCache[uriString]
            val lastChecked = exifCacheTimestamps[uriString] ?: 0L
            val isCacheValid = cachedResult != null && (System.currentTimeMillis() - lastChecked < EXIF_CACHE_EXPIRATION)
            
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
            Timber.e(e, "Ошибка при проверке EXIF маркера сжатия в URI: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Получает уровень качества сжатия из EXIF-метаданных
     * @param context контекст
     * @param uri URI изображения
     * @return уровень качества (0-100) или null, если маркер не найден
     */
    suspend fun getCompressionQualityFromExif(context: Context, uri: Uri): Int? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                
                // Получаем значение UserComment
                val userComment = exif.getAttribute(EXIF_USER_COMMENT)
                
                // Проверяем, содержит ли оно маркер сжатия
                if (userComment?.startsWith(EXIF_COMPRESSION_MARKER) == true) {
                    // Извлекаем уровень качества (второй элемент после разделения по ":")
                    val parts = userComment.split(":")
                    if (parts.size >= 2) {
                        return@withContext parts[1].trim().toIntOrNull()
                    }
                }
            }
            return@withContext null
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении уровня качества из EXIF: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Копирует EXIF данные из исходного URI в файл назначения
     * @param context контекст
     * @param sourceUri URI исходного изображения
     * @param destinationFile файл назначения
     * @return true если копирование успешно, false в противном случае
     */
    suspend fun copyExifDataFromUriToFile(context: Context, sourceUri: Uri, destinationFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // Проверяем, существует ли файл назначения
            if (!destinationFile.exists()) {
                Timber.w("Файл назначения не существует: ${destinationFile.absolutePath}")
                return@withContext false
            }
            
            // Получаем EXIF данные из исходника
            val sourceExif = try {
                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                    ExifInterface(inputStream)
                }
            } catch (e: Exception) {
                Timber.e(e, "Не удалось открыть источник для чтения EXIF: $sourceUri")
                return@withContext false
            }
            
            if (sourceExif == null) {
                Timber.e("Не удалось получить EXIF данные из источника: $sourceUri")
                return@withContext false
            }
            
            // Пытаемся открыть файл назначения
            val destinationExif = try {
                ExifInterface(destinationFile.absolutePath)
            } catch (e: Exception) {
                Timber.e(e, "Не удалось открыть файл назначения для записи EXIF: ${destinationFile.absolutePath}")
                return@withContext false
            }
            
            // Логируем GPS данные до копирования
            val sourceLatLong = sourceExif.latLong
            if (sourceLatLong != null) {
                Timber.d("GPS данные в исходном файле: широта=${sourceLatLong[0]}, долгота=${sourceLatLong[1]}")
            } else {
                // Проверяем наличие отдельных GPS тегов
                val gpsLatitude = sourceExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                val gpsLatitudeRef = sourceExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
                val gpsLongitude = sourceExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
                val gpsLongitudeRef = sourceExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
                
                if (gpsLatitude != null || gpsLatitudeRef != null || gpsLongitude != null || gpsLongitudeRef != null) {
                    Timber.d("GPS теги в исходном файле: LAT=$gpsLatitude, LAT_REF=$gpsLatitudeRef, LONG=$gpsLongitude, LONG_REF=$gpsLongitudeRef")
                } else {
                    Timber.d("GPS данные в исходном файле отсутствуют")
                }
            }
            
            // Копируем все EXIF теги, используя общий метод
            try {
                copyExifTags(sourceExif, destinationExif)
                
                // Сохраняем изменения
                destinationExif.saveAttributes()
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при копировании или сохранении EXIF данных: ${e.message}")
                return@withContext false
            }
            
            // Проверяем, что GPS данные были скопированы
            val destLatLong = destinationExif.latLong
            if (destLatLong != null) {
                Timber.d("GPS данные в сжатом файле после копирования: широта=${destLatLong[0]}, долгота=${destLatLong[1]}")
            } else {
                Timber.d("GPS данные в сжатом файле отсутствуют после копирования")
            }
            
            // Проверяем, что основные теги были скопированы
            val sourceMake = sourceExif.getAttribute(ExifInterface.TAG_MAKE)
            val destMake = destinationExif.getAttribute(ExifInterface.TAG_MAKE)
            val sourceDateTime = sourceExif.getAttribute(ExifInterface.TAG_DATETIME)
            val destDateTime = destinationExif.getAttribute(ExifInterface.TAG_DATETIME)
            
            // Если хотя бы один важный тег был скопирован, считаем операцию успешной
            val copySuccessful = (destLatLong != null && sourceLatLong != null) || 
                               (sourceMake == destMake && sourceMake != null) ||
                               (sourceDateTime == destDateTime && sourceDateTime != null)
            
            if (copySuccessful) {
                Timber.d("EXIF данные успешно скопированы из URI в файл")
            } else {
                Timber.w("Не удалось подтвердить копирование EXIF данных")
            }
            
            return@withContext copySuccessful
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при копировании EXIF данных: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Копирует EXIF данные между двумя URI
     * @param context контекст
     * @param sourceUri URI исходного изображения
     * @param destUri URI изображения назначения
     */
    suspend fun copyExifData(context: Context, sourceUri: Uri, destUri: Uri) = withContext(Dispatchers.IO) {
        try {
            // Проверяем существование исходного URI
            try {
                val checkCursor = context.contentResolver.query(sourceUri, null, null, null, null)
                val sourceExists = checkCursor?.use { it.count > 0 } ?: false
                
                if (!sourceExists) {
                    Timber.d("Исходный URI не существует, пропускаем копирование EXIF: $sourceUri")
                    return@withContext
                }
            } catch (e: Exception) {
                Timber.d("Не удалось проверить существование исходного URI: $sourceUri, ${e.message}")
                return@withContext
            }
            
            val sourceInputPfd = try {
                context.contentResolver.openFileDescriptor(sourceUri, "r")
            } catch (e: FileNotFoundException) {
                Timber.e(e, "Исходный файл не найден при попытке копирования EXIF: %s", sourceUri.toString())
                return@withContext
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при открытии исходного файла для EXIF: %s", sourceUri.toString())
                return@withContext
            }
            
            val destOutputPfd = try {
                context.contentResolver.openFileDescriptor(destUri, "rw")
            } catch (e: FileNotFoundException) {
                Timber.e(e, "Файл назначения не найден при попытке копирования EXIF: %s", destUri.toString())
                sourceInputPfd?.close()
                return@withContext
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при открытии файла назначения для EXIF: $destUri")
                sourceInputPfd?.close()
                return@withContext
            }
            
            if (sourceInputPfd != null && destOutputPfd != null) {
                try {
                    val sourceExif = ExifInterface(sourceInputPfd.fileDescriptor)
                    val destExif = ExifInterface(destOutputPfd.fileDescriptor)
                    
                    // Логируем GPS данные до копирования
                    val sourceLatLong = sourceExif.latLong
                    if (sourceLatLong != null) {
                        Timber.d("GPS данные в исходном файле: широта=${sourceLatLong[0]}, долгота=${sourceLatLong[1]}")
                    } else {
                        // Проверяем наличие отдельных GPS тегов
                        val gpsLatitude = sourceExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                        val gpsLatitudeRef = sourceExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
                        val gpsLongitude = sourceExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
                        val gpsLongitudeRef = sourceExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
                        
                        if (gpsLatitude != null || gpsLatitudeRef != null || gpsLongitude != null || gpsLongitudeRef != null) {
                            Timber.d("GPS теги в исходном файле: LAT=$gpsLatitude, LAT_REF=$gpsLatitudeRef, LONG=$gpsLongitude, LONG_REF=$gpsLongitudeRef")
                        } else {
                            Timber.d("GPS данные в исходном файле отсутствуют")
                        }
                    }
                    
                    // Копируем все EXIF теги, используя общий метод
                    copyExifTags(sourceExif, destExif)
                    
                    // Сохраняем изменения
                    destExif.saveAttributes()
                    
                    // Проверяем, что GPS данные были скопированы
                    val destLatLong = destExif.latLong
                    if (destLatLong != null) {
                        Timber.d("GPS данные в сжатом файле после копирования: широта=${destLatLong[0]}, долгота=${destLatLong[1]}")
                    } else {
                        Timber.d("GPS данные в сжатом файле отсутствуют после копирования")
                    }
                    
                    Timber.d("EXIF данные успешно скопированы между URI")
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при копировании EXIF данных: ${e.message}")
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
     * Проверяет наличие базовых EXIF тегов в изображении по URI
     * @param context контекст
     * @param uri URI изображения
     * @return true если найдены базовые EXIF теги, false в противном случае
     */
    suspend fun hasBasicExifTags(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                // Проверяем наличие основных тегов
                val hasBasicTags = exif.getAttribute(ExifInterface.TAG_DATETIME) != null ||
                                 exif.getAttribute(ExifInterface.TAG_MAKE) != null ||
                                 exif.getAttribute(ExifInterface.TAG_MODEL) != null
                
                // Проверяем наличие GPS тегов более комплексно
                val gpsLatitude = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                val gpsLatitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
                val gpsLongitude = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
                val gpsLongitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
                
                val hasGpsTags = gpsLatitude != null || gpsLatitudeRef != null || 
                               gpsLongitude != null || gpsLongitudeRef != null || 
                               exif.latLong != null
                
                if (hasGpsTags) {
                    Timber.d("URI $uri содержит GPS теги:")
                    if (gpsLatitude != null) Timber.d("TAG_GPS_LATITUDE: $gpsLatitude")
                    if (gpsLatitudeRef != null) Timber.d("TAG_GPS_LATITUDE_REF: $gpsLatitudeRef")
                    if (gpsLongitude != null) Timber.d("TAG_GPS_LONGITUDE: $gpsLongitude")
                    if (gpsLongitudeRef != null) Timber.d("TAG_GPS_LONGITUDE_REF: $gpsLongitudeRef")
                    
                    val latLong = exif.latLong
                    if (latLong != null) {
                        Timber.d("Координаты через latLong: широта=${latLong[0]}, долгота=${latLong[1]}")
                    } else {
                        Timber.d("GPS теги присутствуют, но координаты не читаются через latLong")
                    }
                }
                
                return@withContext hasBasicTags
            }
            return@withContext false
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке EXIF тегов: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Проверяет наличие базовых EXIF тегов в файле
     * @param file файл для проверки
     * @return true если найдены базовые EXIF теги, false в противном случае
     */
    fun hasBasicExifTags(file: File): Boolean {
        try {
            val exif = ExifInterface(file.absolutePath)
            // Проверяем наличие основных тегов
            val hasBasicTags = exif.getAttribute(ExifInterface.TAG_DATETIME) != null ||
                             exif.getAttribute(ExifInterface.TAG_MAKE) != null ||
                             exif.getAttribute(ExifInterface.TAG_MODEL) != null
            
            // Проверяем наличие GPS тегов более комплексно
            val gpsLatitude = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
            val gpsLatitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
            val gpsLongitude = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
            val gpsLongitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
            
            val hasGpsTags = gpsLatitude != null || gpsLatitudeRef != null || 
                           gpsLongitude != null || gpsLongitudeRef != null || 
                           exif.latLong != null
            
            if (hasGpsTags) {
                Timber.d("Файл содержит GPS теги:")
                if (gpsLatitude != null) Timber.d("TAG_GPS_LATITUDE: $gpsLatitude")
                if (gpsLatitudeRef != null) Timber.d("TAG_GPS_LATITUDE_REF: $gpsLatitudeRef")
                if (gpsLongitude != null) Timber.d("TAG_GPS_LONGITUDE: $gpsLongitude")
                if (gpsLongitudeRef != null) Timber.d("TAG_GPS_LONGITUDE_REF: $gpsLongitudeRef")
                
                val latLong = exif.latLong
                if (latLong != null) {
                    Timber.d("Координаты через latLong: широта=${latLong[0]}, долгота=${latLong[1]}")
                } else {
                    Timber.d("GPS теги присутствуют, но координаты не читаются через latLong")
                }
            }
            
            return hasBasicTags
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке EXIF тегов файла: ${e.message}")
            return false
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
            val actualQuality = getCompressionQualityFromExif(context, uri)
            
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
     * Очищает кэш для указанного URI
     * @param uriString строковое представление URI
     */
    fun clearCacheForUri(uriString: String) {
        exifCheckCache.remove(uriString)
        exifCacheTimestamps.remove(uriString)
        Timber.d("Кэш EXIF очищен для URI: $uriString")
    }
    
    /**
     * Копирует EXIF теги между двумя объектами ExifInterface
     * @param sourceExif исходный ExifInterface
     * @param destExif ExifInterface назначения
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
        
        // Копируем GPS данные специальным образом
        copyGpsData(sourceExif, destExif)
        
        // Фильтруем теги, доступные в текущей версии Android
        val availableTags = allPossibleTags.filter { tag ->
            try {
                sourceExif.getAttribute(tag)
                true
            } catch (e: Exception) {
                false
            }
        }
        
        // Копируем все доступные теги
        for (tag in availableTags) {
            try {
                val value = sourceExif.getAttribute(tag)
                if (value != null) {
                    destExif.setAttribute(tag, value)
                }
            } catch (e: Exception) {
                // Если возникла ошибка при копировании конкретного тега, просто пропускаем его
            }
        }
    }
    
    /**
     * Специальная функция для копирования GPS данных между объектами ExifInterface.
     * GPS-данные требуют особой обработки из-за их формата.
     */
    private fun copyGpsData(sourceExif: ExifInterface, destExif: ExifInterface) {
        try {
            // Сначала пробуем получить координаты через latLong
            val latLong = sourceExif.latLong
            if (latLong != null) {
                destExif.setLatLong(latLong[0], latLong[1])
                
                // Копируем дополнительные GPS теги
                val altitude = sourceExif.getAltitude(0.0)
                if (!altitude.isNaN()) {
                    destExif.setAltitude(altitude)
                }
            } else {
                // Если latLong не работает, пробуем скопировать теги напрямую
                val gpsLatitude = sourceExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                val gpsLatitudeRef = sourceExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
                val gpsLongitude = sourceExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
                val gpsLongitudeRef = sourceExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
                
                // Проверяем, что значения не нулевые и не "0/1,0/1,0/1"
                if (gpsLatitude != null && gpsLatitudeRef != null && 
                    gpsLongitude != null && gpsLongitudeRef != null &&
                    !gpsLatitude.matches(Regex("0/1,0/1,0/1")) &&
                    !gpsLongitude.matches(Regex("0/1,0/1,0/1"))) {
                    
                    destExif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, gpsLatitude)
                    destExif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, gpsLatitudeRef)
                    destExif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, gpsLongitude)
                    destExif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, gpsLongitudeRef)
                }
            }
            
            // Копируем остальные GPS теги, если они не нулевые и не дефолтные
            val additionalGpsTags = arrayOf(
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                ExifInterface.TAG_GPS_VERSION_ID
            )
            
            for (tag in additionalGpsTags) {
                val value = sourceExif.getAttribute(tag)
                if (value != null && !value.matches(Regex("0/1|0|\\?+|^\\s*$"))) {
                    destExif.setAttribute(tag, value)
                }
            }
            
            // Проверяем результат копирования
            val destLatLong = destExif.latLong
            if (destLatLong != null) {
                Timber.d("GPS координаты успешно скопированы: широта=${destLatLong[0]}, долгота=${destLatLong[1]}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при копировании GPS данных: ${e.message}")
        }
    }
    
    /**
     * Получает дату сжатия из EXIF данных изображения
     * @param context контекст
     * @param uri URI изображения
     * @return дата сжатия в миллисекундах или null, если она не найдена
     */
    suspend fun getCompressionDateFromExif(context: Context, uri: Uri): Long? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                
                // Получаем значение UserComment
                val userComment = exif.getAttribute(EXIF_USER_COMMENT) ?: return@withContext null
                
                // Проверяем, содержит ли оно маркер сжатия
                if (userComment.startsWith(EXIF_COMPRESSION_MARKER)) {
                    val parts = userComment.split(":")
                    if (parts.size >= 3) {
                        // Извлекаем timestamp в миллисекундах
                        try {
                            val timestamp = parts[2].toLong()
                            Timber.d("Найден EXIF маркер с timestamp: $timestamp")
                            return@withContext timestamp
                        } catch (e: NumberFormatException) {
                            // Если не удалось преобразовать в Long, возможно это старый формат
                            Timber.e(e, "Ошибка при преобразовании даты сжатия в Long: ${parts[2]}")
                            return@withContext null
                        }
                    }
                }
                return@withContext null
            }
            return@withContext null
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении даты сжатия из EXIF: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Копирование EXIF данных между двумя URI
     *
     * @param context Контекст приложения
     * @param sourceUri Исходный URI (откуда копировать EXIF)
     * @param destinationUri URI назначения (куда копировать EXIF)
     * @return true если копирование успешно, false в противном случае
     */
    fun copyExifDataBetweenUris(context: Context, sourceUri: Uri, destinationUri: Uri): Boolean {
        if (sourceUri == destinationUri) {
            Timber.w("URI источника и назначения одинаковы, копирование не требуется")
            return false
        }

        Timber.d("Копирование EXIF данных из $sourceUri в $destinationUri")
        
        var sourceInputStream: java.io.InputStream? = null
        var destFd: android.os.ParcelFileDescriptor? = null
        
        try {
            // Открываем исходный URI для чтения EXIF
            sourceInputStream = context.contentResolver.openInputStream(sourceUri)
                ?: throw IOException("Не удалось открыть входной поток для исходного URI")
                
            val sourceExif = ExifInterface(sourceInputStream)
            
            // Логируем важные данные об исходном изображении
            Timber.d("--- Исходные EXIF данные ---")
            Timber.d("Модель камеры: ${sourceExif.getAttribute(ExifInterface.TAG_MODEL)}")
            Timber.d("Производитель: ${sourceExif.getAttribute(ExifInterface.TAG_MAKE)}")
            Timber.d("Диафрагма (F): ${sourceExif.getAttribute(ExifInterface.TAG_F_NUMBER)}")
            Timber.d("Экспозиция (EV): ${sourceExif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)}")
            
            // Логируем GPS данные исходного изображения
            val hasGpsSource = sourceExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null &&
                          sourceExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE) != null
            Timber.d("Исходный URI содержит GPS данные: $hasGpsSource")
            
            // Открываем URI назначения для записи EXIF
            val pfd = context.contentResolver.openFileDescriptor(destinationUri, "rw")
                ?: throw IOException("Не удалось получить дескриптор файла для URI назначения")
                
            destFd = pfd
            val destExif = ExifInterface(pfd.fileDescriptor)
            
            // Копируем все важные EXIF теги
            var tagsCopied = 0
            for (tag in TAG_LIST) {
                val value = sourceExif.getAttribute(tag)
                if (value != null) {
                    destExif.setAttribute(tag, value)
                    tagsCopied++
                }
            }
            
            Timber.d("Всего скопировано тегов: $tagsCopied из ${TAG_LIST.size}")
            
            // Сохраняем изменения в EXIF
            destExif.saveAttributes()
            
            // Проверяем успешность копирования
            var success = false
            
            // Закрываем предыдущие ресурсы перед повторным открытием
            sourceInputStream.close()
            sourceInputStream = null
            pfd.close()
            destFd = null
            
            // Открываем обновленный URI для проверки
            context.contentResolver.openInputStream(destinationUri)?.use { input ->
                val verifiedExif = ExifInterface(input)
                
                // Получаем исходный ExifInterface снова для проверки
                context.contentResolver.openInputStream(sourceUri)?.use { srcInput ->
                    val srcExifForVerify = ExifInterface(srcInput)
                    success = verifyExifCopy(srcExifForVerify, verifiedExif)
                }
            }
            
            return success
            
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при копировании EXIF данных между URI: ${e.message}")
            return false
        } finally {
            // Закрываем все ресурсы
            try { sourceInputStream?.close() } catch (e: Exception) { 
                Timber.e(e, "Ошибка при закрытии входного потока") 
            }
            try { destFd?.close() } catch (e: Exception) { 
                Timber.e(e, "Ошибка при закрытии дескриптора файла") 
            }
        }
    }

    /**
     * Добавление маркера сжатия в EXIF данные по URI
     *
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param quality Качество сжатия
     * @return true если маркер добавлен успешно, false в противном случае
     */
    fun markCompressedImageUri(context: Context, uri: Uri, quality: Int): Boolean {
        Timber.d("Добавление маркера сжатия в URI: $uri")
        
        var pfd: android.os.ParcelFileDescriptor? = null
        
        try {
            // Открываем URI для записи EXIF
            val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("Не удалось получить дескриптор файла для URI")
                
            pfd = fileDescriptor
            
            // Добавляем маркер сжатия через ExifInterface
            val exif = ExifInterface(fileDescriptor.fileDescriptor)
            val marker = "$EXIF_COMPRESSION_MARKER:$quality"
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, marker)
            exif.saveAttributes()
            
            // Закрываем дескриптор файла перед проверкой
            fileDescriptor.close()
            pfd = null
            
            // Проверяем, успешно ли добавлен маркер
            var success = false
            context.contentResolver.openInputStream(uri)?.use { input ->
                val verifiedExif = ExifInterface(input)
                val userComment = verifiedExif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                success = userComment == marker
                Timber.d("Проверка маркера сжатия: ${if (success) "успешно" else "неудачно"}, UserComment: $userComment")
            }
            
            return success
            
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при добавлении маркера сжатия в URI: ${e.message}")
            return false
        } finally {
            // Закрываем дескриптор, если он всё еще открыт
            try { pfd?.close() } catch (e: Exception) { 
                Timber.e(e, "Ошибка при закрытии дескриптора файла") 
            }
        }
    }
    
    /**
     * Проверяет успешность копирования EXIF данных между двумя объектами ExifInterface
     *
     * @param sourceExif Исходный ExifInterface (откуда копировались данные)
     * @param destExif ExifInterface назначения (куда копировались данные)
     * @return true если копирование было успешным, false в противном случае
     */
    private fun verifyExifCopy(sourceExif: ExifInterface, destExif: ExifInterface): Boolean {
        // Критические теги, наличие хотя бы одного из которых считается успешным копированием
        val criticalTags = arrayOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY
        )
        
        var criticalTagCopied = false
        for (tag in criticalTags) {
            val sourceValue = sourceExif.getAttribute(tag)
            val destValue = destExif.getAttribute(tag)
            if (sourceValue != null && sourceValue == destValue) {
                criticalTagCopied = true
            }
        }
        
        // Подсчитываем количество успешно скопированных тегов из общего списка
        var tagsCopied = 0
        var totalTags = 0
        for (tag in TAG_LIST) {
            val sourceValue = sourceExif.getAttribute(tag)
            val destValue = destExif.getAttribute(tag)
            if (sourceValue != null) {
                totalTags++
                if (sourceValue == destValue) {
                    tagsCopied++
                }
            }
        }
        
        // Считаем копирование успешным, если:
        // 1) Скопирован хотя бы один критический тег, ИЛИ
        // 2) Скопировано более половины всех тегов
        return criticalTagCopied || (totalTags > 0 && tagsCopied >= totalTags / 2)
    }

    /**
     * Проверяет, было ли изображение уже сжато нашим приложением
     *
     * @param context Контекст приложения
     * @param uri URI изображения для проверки
     * @return Пара (Boolean, Int), где Boolean - было ли изображение сжато, 
     *         Int - качество сжатия или null если не сжато
     */
    fun checkIsCompressed(context: Context, uri: Uri): Pair<Boolean, Int?> {
        Timber.d("Проверка, было ли изображение сжато: $uri")
        
        try {
            var isCompressed = false
            var quality: Int? = null
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                try {
                    val exif = ExifInterface(inputStream)
                    val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                    
                    if (comment != null && comment.startsWith(EXIF_COMPRESSION_MARKER)) {
                        // Извлекаем качество сжатия из маркера
                        val parts = comment.split(":")
                        if (parts.size >= 2) {
                            val qualityStr = parts[1].trim()
                            quality = try {
                                qualityStr.toInt()
                            } catch (e: NumberFormatException) {
                                Timber.w("Не удалось извлечь качество из маркера сжатия: $comment")
                                null
                            }
                        }
                        
                        isCompressed = true
                        Timber.d("Обнаружен маркер сжатия, качество: $quality")
                    } else {
                        Timber.d("Маркер сжатия не найден. UserComment: $comment")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка при чтении EXIF данных из URI: ${e.message}")
                }
            } ?: Timber.w("Не удалось открыть входной поток для URI: $uri")
            
            return Pair(isCompressed, quality)
            
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке сжатия изображения: ${e.message}")
            return Pair(false, null)
        }
    }
} 
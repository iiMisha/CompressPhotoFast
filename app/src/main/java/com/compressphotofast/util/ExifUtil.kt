package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.util.Collections
import java.util.HashMap

/**
 * Утилитарный класс для работы с EXIF метаданными изображений
 */
object ExifUtil {
    
    // Константы для EXIF маркировки
    private const val EXIF_USER_COMMENT = ExifInterface.TAG_USER_COMMENT
    private const val EXIF_COMPRESSION_MARKER = "CompressPhotoFast_Compressed"
    private const val EXIF_COMPRESSION_LEVEL = "CompressPhotoFast_Quality"
    
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
            // Читаем текущие EXIF данные
            val exif = ExifInterface(filePath)
            
            // Получаем текущее значение UserComment
            val currentComment = exif.getAttribute(EXIF_USER_COMMENT)
            Timber.d("Текущий EXIF маркер для $filePath: $currentComment")
            
            // Добавляем маркер сжатия и уровень компрессии
            val markerWithQuality = "${EXIF_COMPRESSION_MARKER}:$quality"
            exif.setAttribute(EXIF_USER_COMMENT, markerWithQuality)
            
            // Сохраняем изменения
            exif.saveAttributes()
            
            // Проверяем, что маркер был установлен
            val newExif = ExifInterface(filePath)
            val newUserComment = newExif.getAttribute(EXIF_USER_COMMENT)
            Timber.d("EXIF маркер сжатия установлен в файл: $filePath с качеством: $quality. Записанное значение: $newUserComment")
            
            return@withContext true
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
                    // Извлекаем уровень качества
                    val qualityStr = userComment.substringAfter(":").trim()
                    return@withContext qualityStr.toIntOrNull()
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
                    
                    // Копируем все EXIF теги, используя общий метод
                    copyExifTags(sourceExif, destExif)
                    
                    // Сохраняем изменения
                    destExif.saveAttributes()
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
     * Проверяет наличие базовых EXIF тегов в изображении
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
     * @param file файл изображения
     * @return true если найдены базовые EXIF теги, false в противном случае
     */
    fun hasBasicExifTags(file: File): Boolean {
        try {
            val exif = ExifInterface(file.absolutePath)
            // Проверяем наличие основных тегов
            val hasBasicTags = exif.getAttribute(ExifInterface.TAG_DATETIME) != null ||
                             exif.getAttribute(ExifInterface.TAG_MAKE) != null ||
                             exif.getAttribute(ExifInterface.TAG_MODEL) != null
            
            return hasBasicTags
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при проверке EXIF тегов файла: ${e.message}")
            return false
        }
    }
    
    /**
     * Очищает кэш для указанного URI
     * @param uriString строковое представление URI
     */
    fun clearCacheForUri(uriString: String) {
        exifCheckCache.remove(uriString)
        exifCacheTimestamps.remove(uriString)
    }
    
    /**
     * Очищает весь кэш EXIF
     */
    fun clearCache() {
        exifCheckCache.clear()
        exifCacheTimestamps.clear()
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
} 
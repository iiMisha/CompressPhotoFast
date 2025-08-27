package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.drew.imaging.ImageMetadataReader
import com.drew.lang.GeoLocation
import com.drew.metadata.Metadata
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.Directory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Утилитарный класс для работы с метаданными через библиотеку metadata-extractor
 * Предоставляет более надежную альтернативу Android ExifInterface для GPS данных
 */
object MetadataExtractorUtil {
    
    /**
     * Данные GPS координат
     */
    data class GpsData(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double? = null,
        val latitudeRef: String,
        val longitudeRef: String,
        val altitudeRef: Int? = null,
        val timestamp: String? = null,
        val datestamp: String? = null,
        val processingMethod: String? = null
    )
    
    /**
     * Извлекает GPS данные из изображения с помощью metadata-extractor
     * @param context Контекст приложения
     * @param uri URI изображения
     * @return GpsData или null, если GPS данные отсутствуют
     */
    suspend fun extractGpsData(context: Context, uri: Uri): GpsData? = withContext(Dispatchers.IO) {
        try {
            LogUtil.processInfo("🔍 MetadataExtractor: Начинаем извлечение GPS данных из $uri")
            
            // ANDROID 10+ FIX: используем MediaStore.setRequireOriginal() для получения оригинальных EXIF данных
            val finalUri = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && 
                    uri.toString().startsWith("content://media/")) {
                    val originalUri = MediaStore.setRequireOriginal(uri)
                    LogUtil.processInfo("🔧 ANDROID 10+ FIX: Использую MediaStore.setRequireOriginal() для доступа к исходным GPS данным")
                    LogUtil.processInfo("🔧 Оригинальный URI: $uri")
                    LogUtil.processInfo("🔧 RequireOriginal URI: $originalUri")
                    originalUri
                } else {
                    LogUtil.processInfo("🔍 Используется исходный URI (Android < 10 или не MediaStore URI)")
                    uri
                }
            } catch (e: Exception) {
                LogUtil.processWarning("⚠️ Ошибка при получении оригинального URI, используем исходный: ${e.message}")
                uri
            }
            
            context.contentResolver.openInputStream(finalUri)?.use { inputStream ->
                val metadata = ImageMetadataReader.readMetadata(inputStream)
                
                // ДИАГНОСТИКА EMUI: логируем все найденные директории
                LogUtil.processInfo("🔍 MetadataExtractor: Найдено ${metadata.directories.count()} директорий метаданных")
                for (directory in metadata.directories) {
                    LogUtil.processInfo("🔍 MetadataExtractor: Директория '${directory.name}' с ${directory.tagCount} тегами")
                    if (directory.hasErrors()) {
                        for (error in directory.errors) {
                            LogUtil.processWarning("⚠️ MetadataExtractor: Ошибка в ${directory.name}: $error")
                        }
                    }
                }
                
                // Ищем GPS директорию
                val gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
                if (gpsDirectory == null) {
                    LogUtil.processInfo("❌ MetadataExtractor: GPS директория не найдена")
                    return@withContext null
                }
                
                LogUtil.processInfo("✅ MetadataExtractor: GPS директория найдена с ${gpsDirectory.tagCount} тегами")
                
                // ДИАГНОСТИКА EMUI: детально логируем все GPS теги
                LogUtil.processInfo("🔍 MetadataExtractor: Детальный анализ GPS тегов:")
                for (tag in gpsDirectory.tags) {
                    val tagName = gpsDirectory.getTagName(tag.tagType)
                    val description = gpsDirectory.getDescription(tag.tagType)
                    LogUtil.processInfo("  📍 $tagName = '$description'")
                }
                
                // Извлекаем GPS данные через GeoLocation (более надежный способ)
                val geoLocation = gpsDirectory.geoLocation
                LogUtil.processInfo("🔍 MetadataExtractor: GeoLocation результат = ${if (geoLocation != null) "lat=${geoLocation.latitude}, lng=${geoLocation.longitude}, isZero=${geoLocation.isZero}" else "null"}")
                
                if (geoLocation != null && !geoLocation.isZero) {
                    LogUtil.processInfo("✅ MetadataExtractor: Валидные GPS координаты найдены через GeoLocation")
                    
                    // Дополнительные GPS теги
                    val latitudeRef = gpsDirectory.getString(GpsDirectory.TAG_LATITUDE_REF) ?: ""
                    val longitudeRef = gpsDirectory.getString(GpsDirectory.TAG_LONGITUDE_REF) ?: ""
                    val altitude = if (gpsDirectory.hasTagName(GpsDirectory.TAG_ALTITUDE)) {
                        gpsDirectory.getDoubleObject(GpsDirectory.TAG_ALTITUDE)
                    } else null
                    val altitudeRef = if (gpsDirectory.hasTagName(GpsDirectory.TAG_ALTITUDE_REF)) {
                        gpsDirectory.getInt(GpsDirectory.TAG_ALTITUDE_REF)
                    } else null
                    val timestamp = gpsDirectory.getString(GpsDirectory.TAG_TIME_STAMP)
                    val datestamp = gpsDirectory.getString(GpsDirectory.TAG_DATE_STAMP)
                    val processingMethod = gpsDirectory.getString(GpsDirectory.TAG_PROCESSING_METHOD)
                    
                    val gpsData = GpsData(
                        latitude = geoLocation.latitude,
                        longitude = geoLocation.longitude,
                        altitude = altitude,
                        latitudeRef = latitudeRef,
                        longitudeRef = longitudeRef,
                        altitudeRef = altitudeRef,
                        timestamp = timestamp,
                        datestamp = datestamp,
                        processingMethod = processingMethod
                    )
                    
                    LogUtil.processInfo("✅ MetadataExtractor: GPS данные успешно извлечены - lat=${gpsData.latitude}, lng=${gpsData.longitude}")
                    LogUtil.processInfo("✅ MetadataExtractor: Reference теги - latRef='$latitudeRef', lngRef='$longitudeRef'")
                    return@withContext gpsData
                }
                
                // EMUI FALLBACK: попробуем извлечь данные вручную из отдельных тегов
                LogUtil.processInfo("⚠️ MetadataExtractor: GeoLocation пуст или нулевой, пробуем EMUI-совместимое извлечение из отдельных тегов")
                return@withContext extractGpsFromIndividualTags(gpsDirectory)
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "MetadataExtractor GPS извлечение", e)
        }
        
        return@withContext null
    }
    
    /**
     * Извлекает GPS данные из отдельных тегов GPS директории (EMUI-совместимый метод)
     */
    private fun extractGpsFromIndividualTags(gpsDirectory: GpsDirectory): GpsData? {
        try {
            LogUtil.processInfo("🔍 MetadataExtractor: EMUI-совместимое извлечение GPS из отдельных тегов")
            
            // Проверяем наличие основных GPS тегов
            val latitudeArray = gpsDirectory.getRationalArray(GpsDirectory.TAG_LATITUDE)
            val longitudeArray = gpsDirectory.getRationalArray(GpsDirectory.TAG_LONGITUDE)
            val latitudeRef = gpsDirectory.getString(GpsDirectory.TAG_LATITUDE_REF)
            val longitudeRef = gpsDirectory.getString(GpsDirectory.TAG_LONGITUDE_REF)
            
            LogUtil.processInfo("🔍 GPS теги: latArray=${latitudeArray?.size ?: "null"}, lngArray=${longitudeArray?.size ?: "null"}")
            LogUtil.processInfo("🔍 GPS refs: latRef='${latitudeRef ?: "null"}', lngRef='${longitudeRef ?: "null"}'")
            
            if (latitudeArray != null && longitudeArray != null && 
                latitudeArray.size >= 3 && longitudeArray.size >= 3) {
                
                LogUtil.processInfo("🔍 GPS arrays детали:")
                LogUtil.processInfo("  Latitude: [${latitudeArray[0]}, ${latitudeArray[1]}, ${latitudeArray[2]}]")
                LogUtil.processInfo("  Longitude: [${longitudeArray[0]}, ${longitudeArray[1]}, ${longitudeArray[2]}]")
                
                // Конвертируем из degrees/minutes/seconds в десятичные координаты
                val latitude = convertDmsToDecimal(latitudeArray, latitudeRef ?: "")
                val longitude = convertDmsToDecimal(longitudeArray, longitudeRef ?: "")
                
                LogUtil.processInfo("🔍 Конвертированные координаты: lat=$latitude, lng=$longitude")
                
                // EMUI FIX: считаем валидными координаты даже если reference теги пусты, но координаты не нулевые
                if (latitude != 0.0 || longitude != 0.0) {
                    val altitude = if (gpsDirectory.hasTagName(GpsDirectory.TAG_ALTITUDE)) {
                        gpsDirectory.getDoubleObject(GpsDirectory.TAG_ALTITUDE)
                    } else null
                    val altitudeRef = if (gpsDirectory.hasTagName(GpsDirectory.TAG_ALTITUDE_REF)) {
                        gpsDirectory.getInt(GpsDirectory.TAG_ALTITUDE_REF)
                    } else null
                    
                    // EMUI FIX: если reference теги пусты, пытаемся определить их по знаку координат
                    val finalLatRef = if (latitudeRef.isNullOrEmpty()) {
                        if (latitude >= 0) "N" else "S"
                    } else latitudeRef
                    
                    val finalLngRef = if (longitudeRef.isNullOrEmpty()) {
                        if (longitude >= 0) "E" else "W"
                    } else longitudeRef
                    
                    LogUtil.processInfo("🔧 EMUI FIX: установлены reference теги - latRef='$finalLatRef', lngRef='$finalLngRef'")
                    
                    val gpsData = GpsData(
                        latitude = latitude,
                        longitude = longitude,
                        altitude = altitude,
                        latitudeRef = finalLatRef,
                        longitudeRef = finalLngRef,
                        altitudeRef = altitudeRef,
                        timestamp = gpsDirectory.getString(GpsDirectory.TAG_TIME_STAMP),
                        datestamp = gpsDirectory.getString(GpsDirectory.TAG_DATE_STAMP),
                        processingMethod = gpsDirectory.getString(GpsDirectory.TAG_PROCESSING_METHOD)
                    )
                    
                    LogUtil.processInfo("✅ MetadataExtractor: GPS данные извлечены из отдельных тегов - lat=$latitude, lng=$longitude")
                    LogUtil.processInfo("✅ MetadataExtractor: Reference теги восстановлены - latRef='$finalLatRef', lngRef='$finalLngRef'")
                    return gpsData
                } else {
                    LogUtil.processInfo("❌ MetadataExtractor: Координаты нулевые (lat=$latitude, lng=$longitude)")
                }
            } else {
                LogUtil.processInfo("❌ MetadataExtractor: Недостаточно GPS данных в массивах")
            }
            
            LogUtil.processInfo("❌ MetadataExtractor: GPS теги найдены, но координаты извлечь не удалось")
            return null
            
        } catch (e: Exception) {
            LogUtil.error(null, "MetadataExtractor: ошибка извлечения GPS из отдельных тегов", e)
            return null
        }
    }
    
    /**
     * Конвертирует координаты из формата degrees/minutes/seconds в десятичный
     */
    private fun convertDmsToDecimal(dmsArray: Array<com.drew.lang.Rational>, ref: String): Double {
        val degrees = dmsArray[0].toDouble()
        val minutes = dmsArray[1].toDouble()
        val seconds = dmsArray[2].toDouble()
        
        var decimal = degrees + (minutes / 60.0) + (seconds / 3600.0)
        
        // Применяем знак в зависимости от reference
        if (ref.equals("S", ignoreCase = true) || ref.equals("W", ignoreCase = true)) {
            decimal = -decimal
        }
        
        return decimal
    }
    
    /**
     * Получает все доступные метаданные из изображения для диагностики
     * @param context Контекст приложения  
     * @param uri URI изображения
     * @return Map с метаданными для отладки
     */
    suspend fun extractAllMetadataForDiagnostics(context: Context, uri: Uri): Map<String, Any> = withContext(Dispatchers.IO) {
        val metadataMap = mutableMapOf<String, Any>()
        
        try {
            LogUtil.processInfo("MetadataExtractor: диагностика всех метаданных из $uri")
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val metadata = ImageMetadataReader.readMetadata(inputStream)
                
                for (directory in metadata.directories) {
                    val directoryName = directory.name
                    metadataMap["directory_$directoryName"] = directory.tagCount
                    
                    // Особое внимание GPS директории
                    if (directory is GpsDirectory) {
                        LogUtil.processInfo("MetadataExtractor: анализ GPS директории")
                        metadataMap["gps_tag_count"] = directory.tagCount
                        metadataMap["gps_has_location"] = directory.geoLocation != null
                        metadataMap["gps_location_is_zero"] = directory.geoLocation?.isZero ?: true
                        
                        // Детальная диагностика GPS тегов
                        for (tag in directory.tags) {
                            val tagName = directory.getTagName(tag.tagType)
                            val description = directory.getDescription(tag.tagType)
                            metadataMap["gps_$tagName"] = description ?: "null"
                            LogUtil.processInfo("MetadataExtractor GPS: $tagName = $description")
                        }
                    }
                    
                    // Логируем все обнаруженные ошибки в директориях
                    if (directory.hasErrors()) {
                        for (error in directory.errors) {
                            LogUtil.processWarning("MetadataExtractor: ошибка в $directoryName - $error")
                        }
                    }
                }
                
                LogUtil.processInfo("MetadataExtractor: найдено ${metadata.directories.count()} директорий метаданных")
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "MetadataExtractor диагностика", e)
            metadataMap["error"] = e.message ?: "unknown error"
        }
        
        return@withContext metadataMap
    }
    
    /**
     * Проверяет, поддерживает ли metadata-extractor данный тип файла
     */
    fun isSupportedImageFormat(context: Context, uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            when {
                mimeType?.startsWith("image/jpeg") == true -> true
                mimeType?.startsWith("image/jpg") == true -> true
                mimeType?.startsWith("image/tiff") == true -> true
                mimeType?.startsWith("image/png") == true -> true
                mimeType?.startsWith("image/webp") == true -> true
                else -> false
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "MetadataExtractor проверка формата", e)
            false
        }
    }
}
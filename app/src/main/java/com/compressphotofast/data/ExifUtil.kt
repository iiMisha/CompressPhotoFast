package com.compressphotofast.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import com.compressphotofast.util.LogUtil
import android.content.ContentValues
import java.util.concurrent.ConcurrentHashMap
import com.compressphotofast.util.Constants
import com.compressphotofast.util.FileIoUtil

/**
 * Утилитарный класс для работы с EXIF метаданными изображений
 */
object ExifUtil {

    // Фаза 2 маркера на локальном artifact: запись фиксированной ширины обычно
    // стабилизирует длину с первой попытки
    private const val MAX_ARTIFACT_MARKER_WRITES = 3

    // GPS-теги для копирования/проверки/применения
    private val GPS_TAGS = arrayOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP
    )

    /**
     * TTL-aware LruCache implementation
     * Кэш с поддержкой времени жизни (TTL) для записей
     */
    private class TtlLruCache<K, V>(
        maxSize: Int,
        private val ttlMs: Long
    ) : LruCache<K, V>(maxSize) {
        private val timestamps = ConcurrentHashMap<K, Long>()

        override fun entryRemoved(evicted: Boolean, key: K, oldValue: V, newValue: V?) {
            if (evicted || newValue == null) timestamps.remove(key)
        }

        fun isExpired(key: K): Boolean {
            val timestamp = timestamps[key] ?: return true
            return System.currentTimeMillis() - timestamp > ttlMs
        }

        fun putWithTimestamp(key: K, value: V?): V? {
            synchronized(this) {
                if (value != null) {
                    timestamps[key] = System.currentTimeMillis()
                }
                return put(key, value)
            }
        }

        fun getWithTtl(key: K): V? {
            synchronized(this) {
                return if (isExpired(key)) {
                    remove(key)
                    null
                } else {
                    get(key)
                }
            }
        }
    }

    // Кэш для хранения считанных EXIF-данных. Ключ - String URI, значение - карта с данными.
    // Размер кэша вычисляется на основе доступной памяти (1/8 от maxMemory)
    private val exifDataCache = TtlLruCache<String, Map<String, Any>>(
        maxSize = calculateCacheSize(),
        ttlMs = 10 * 60 * 1000L // 10 минут
    )

    /**
     * Вычисляет оптимальный размер кэша на основе доступной памяти
     * Использует 1/8 от maxMemory, минимум 20 элементов
     */
    private fun calculateCacheSize(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val cacheSize = (maxMemory / 8 / 1024).toInt() // Примерная оценка размера одной записи ~1KB
        return maxOf(cacheSize, 20) // Минимум 20 элементов
    }

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

    // Суффикс для HEIC файлов, которым не удалось добавить EXIF-маркер

    /**
     * Проверяет, является ли файл HEIC/HEIF форматом
     * @param context Контекст приложения
     * @param uri URI изображения
     * @return true если файл HEIC/HEIF
     */
    private fun isHeicFile(context: Context, uri: Uri): Boolean {
        return UriUtil.isHeicMimeType(UriUtil.getMimeType(context, uri))
    }

    /**
     * Получает displayName и dateModified из MediaStore для HEIC файла
     * @param context Контекст приложения
     * @param uri URI изображения
     * @return Pair<String?, Long?> где первый элемент - displayName, второй - dateModified в миллисекундах
     */
    private suspend fun getHeicDisplayNameAndDate(
        context: Context,
        uri: Uri
    ): Pair<String?, Long?> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        var displayName: String? = null
        var dateModified: Long? = null

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                displayName = cursor.getString(nameIndex)
                if (dateIndex >= 0 && !cursor.isNull(dateIndex)) {
                    dateModified = cursor.getLong(dateIndex).secondsToMillis() // Конвертируем секунды в миллисекунды
                }
            }
        }

        return@withContext Pair(displayName, dateModified)
    }

    /**
     * Добавляет суффикс _compressed к имени HEIC файла через MediaStore
     * Используется как fallback, когда не удается сохранить EXIF-маркер в HEIC
     * @param context Контекст приложения
     * @param uri URI HEIC файла
     * @return true если переименование успешно
     */
    private suspend fun markHeicFileAsCompressed(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            LogUtil.processInfo("Попытка сохранить EXIF для HEIC не удалась, переименовываем файл")

            // Получаем текущий displayName из MediaStore
            val (currentDisplayName, _) = getHeicDisplayNameAndDate(context, uri)

            if (currentDisplayName == null) {
                LogUtil.error(uri, "Переименование HEIC", "Не удалось получить текущее имя файла")
                return@withContext false
            }

            // Проверяем, нет ли уже суффикса
            if (CompressionMarker.hasHeicCompressedSuffix(currentDisplayName)) {
                LogUtil.processInfo("У файла уже есть суффикс _compressed: $currentDisplayName")
                return@withContext true
            }

            // Формируем новое имя: добавляем _compressed перед расширением
            val (nameWithoutExt, extension) = FileOperationsUtil.splitNameAndExtension(currentDisplayName!!)
            val newDisplayName = if (extension.isNotEmpty()) {
                "$nameWithoutExt${CompressionMarker.HEIC_COMPRESSED_SUFFIX}$extension"
            } else {
                "$currentDisplayName${CompressionMarker.HEIC_COMPRESSED_SUFFIX}"
            }

            LogUtil.processInfo("Переименование: $currentDisplayName -> $newDisplayName")

            // Обновляем MediaStore
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, newDisplayName)
            }

            val updated = context.contentResolver.update(uri, values, null, null)
            if (updated > 0) {
                LogUtil.processInfo("✅ HEIC файл успешно переименован: $newDisplayName")
                return@withContext true
            } else {
                LogUtil.error(uri, "Переименование HEIC", "Не удалось обновить MediaStore")
                return@withContext false
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Переименование HEIC", e)
            return@withContext false
        }
    }

    /**
     * Получает объект ExifInterface для заданного URI
     * @param context Контекст приложения
     * @param uri URI изображения
     * @return ExifInterface или null при ошибке
     */
    fun getExifInterface(context: Context, uri: Uri): ExifInterface? {
        try {
            // ANDROID 10+ FIX: используем MediaStore.setRequireOriginal() для получения оригинальных EXIF данных
            val finalUri = try {
                if (uri.toString().startsWith("content://media/")) {
                    MediaStore.setRequireOriginal(uri)
                } else {
                    uri
                }
            } catch (e: IllegalStateException) {
                LogUtil.processDebug("EXIF: setRequireOriginal failed для pending-файла, используем обычный URI: $uri")
                uri
            } catch (e: Exception) {
                LogUtil.processWarning("Ошибка при получении оригинального URI для EXIF, используем исходный: ${e.message}")
                uri
            }

            try {
                context.contentResolver.openInputStream(finalUri)?.use { inputStream ->
                    return ExifInterface(inputStream)
                }
            } catch (e: FileNotFoundException) {
                LogUtil.error(uri, "Получение EXIF", "Файл не найден: ${e.message}")
                return null
            } catch (e: IllegalStateException) {
                LogUtil.processDebug("EXIF: файл в состоянии pending, пропускаем: $uri")
                return null
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Получение EXIF", e)
        }
        return null
    }
    
    /**
     * Копирует все важные EXIF-теги между двумя изображениями
     * @param context Контекст приложения
     * @param sourceUri URI исходного изображения
     * @param destinationUri URI целевого изображения
     * @return true если копирование успешно
     */
    suspend fun copyExifData(context: Context, sourceUri: Uri, destinationUri: Uri): Boolean = withContext(Dispatchers.IO) {
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            LogUtil.processInfo("Копирование EXIF данных: $sourceUri -> $destinationUri")

            // Получаем ExifInterface для исходного изображения
            val sourceExif = getExifInterface(context, sourceUri) ?: return@withContext false

            // Получаем ExifInterface для целевого изображения
            // ВАЖНО: Не закрываем ParcelFileDescriptor до завершения saveAttributes()
            pfd = try {
                context.contentResolver.openFileDescriptor(destinationUri, "rw")
            } catch (e: Exception) {
                LogUtil.error(destinationUri, "Открытие ExifInterface", e)
                return@withContext false
            } ?: return@withContext false

            val destExif = ExifInterface(pfd.fileDescriptor)

            // Копируем все теги
            copyExifTags(sourceExif, destExif)

            // Сохраняем изменения (под защитой durable backup)
            try {
                LogUtil.processInfo("Вызываем saveAttributes() для сохранения EXIF данных")
                if (!guardedExifWrite(context, destinationUri) { destExif.saveAttributes() }) {
                    return@withContext false
                }
                LogUtil.processInfo("saveAttributes() выполнен успешно")

                // Теперь можно закрыть дескриптор
                pfd.close()
                pfd = null

                // Верификация GPS данных после сохранения
                val savedExif = getExifInterface(context, destinationUri)
                if (savedExif != null) {
                    val gpsTagsAfterSave = checkGpsTagsAvailability(savedExif)
                    LogUtil.processInfo("GPS теги после сохранения: $gpsTagsAfterSave")
                    if (gpsTagsAfterSave.isNotEmpty()) {
                        LogUtil.processInfo("✓ GPS данные успешно сохранены в файл")
                    } else {
                        LogUtil.processWarning("⚠ GPS данные не найдены в сохраненном файле")
                    }
                } else {
                    LogUtil.processWarning("Не удалось открыть сохраненный файл для верификации GPS")
                }

                LogUtil.processInfo("EXIF данные успешно скопированы")
            } catch (e: Exception) {
                LogUtil.error(destinationUri, "Сохранение EXIF", e)
                return@withContext false
            }

            // Проверяем успех копирования
            val verificationResult = verifyExifCopy(sourceExif, getExifInterface(context, destinationUri) ?: return@withContext false)

            if (verificationResult) {
                LogUtil.processInfo("Проверка EXIF копирования успешна")
            } else {
                LogUtil.processInfo("Проверка EXIF копирования не прошла")
            }

            return@withContext verificationResult
        } catch (e: Exception) {
            LogUtil.error(sourceUri, "Копирование EXIF", e)
            return@withContext false
        } finally {
            // Гарантированно закрываем дескриптор при любом исходе
            pfd?.close()
        }
    }
    
    /**
     * Копирует EXIF-теги между двумя объектами ExifInterface
     * @param sourceExif Исходный объект ExifInterface
     * @param destExif Целевой объект ExifInterface
     */
    private fun copyExifTags(sourceExif: ExifInterface, destExif: ExifInterface) {
        // Копируем GPS данные через специальную функцию
        copyGpsData(sourceExif, destExif)

        // Копируем остальные теги
        var tagsCopied = 0
        var exifErrors = 0
        for (tag in TAG_LIST) {
            try {
                val value = sourceExif.getAttribute(tag)
                if (value != null) {
                    destExif.setAttribute(tag, value)
                    tagsCopied++
                }
            } catch (e: Exception) {
                exifErrors++
                LogUtil.warning(null, "EXIF", "Не удалось скопировать тег $tag: ${e.message}")
            }
        }

        if (exifErrors > 0) {
            LogUtil.warning(null, "EXIF", "Обнаружено $exifErrors ошибок при копировании EXIF тегов")
        }

        LogUtil.processInfo("Скопировано $tagsCopied EXIF-тегов")
    }
    
    /**
     * Копирует GPS-данные между двумя объектами ExifInterface
     * @param sourceExif Исходный объект ExifInterface
     * @param destExif Целевой объект ExifInterface
     */
    private fun copyGpsData(sourceExif: ExifInterface, destExif: ExifInterface) {
        try {
            LogUtil.processInfo("Начинаем копирование GPS данных")

            // Сначала проверяем наличие GPS тегов в исходном файле
            val gpsTagsAvailable = checkGpsTagsAvailability(sourceExif)
            LogUtil.processInfo("GPS теги в исходном файле: $gpsTagsAvailable")

            if (gpsTagsAvailable.isEmpty()) {
                LogUtil.processInfo("GPS данные в исходном файле отсутствуют")
                return
            }

            // Используем приоритетный метод: копирование отдельных GPS-тегов
            var gpsTagsCopied = 0
            var gpsErrors = 0
            var detailedGpsInfo = StringBuilder("Копирование GPS тегов:\n")

            for (tag in GPS_TAGS) {
                try {
                    val value = sourceExif.getAttribute(tag)
                    if (value != null && value.isNotEmpty()) {
                        destExif.setAttribute(tag, value)
                        gpsTagsCopied++
                        detailedGpsInfo.append("  $tag: $value\n")
                        LogUtil.processInfo("GPS тег скопирован: $tag = $value")
                    } else {
                        detailedGpsInfo.append("  $tag: пусто/null\n")
                    }
                } catch (e: Exception) {
                    gpsErrors++
                    LogUtil.error(null, "Копирование GPS тега $tag", e)
                    detailedGpsInfo.append("  $tag: ошибка - ${e.message}\n")
                }
            }

            LogUtil.processInfo(detailedGpsInfo.toString().trimEnd())

            if (gpsErrors > 0) {
                LogUtil.warning(null, "EXIF", "Обнаружено $gpsErrors ошибок при копировании GPS тегов")
            }

            if (gpsTagsCopied > 0) {
                LogUtil.processInfo("Успешно скопировано $gpsTagsCopied GPS-тегов через setAttribute")

                // Дополнительная проверка: пробуем также setLatLong как backup
                try {
                    val latLong = sourceExif.latLong
                    if (latLong != null) {
                        LogUtil.processInfo("Дополнительно проверяем latLong API: широта=${latLong[0]}, долгота=${latLong[1]}")
                        // Не перезаписываем уже скопированные теги, только логируем для сравнения
                    }
                } catch (e: Exception) {
                    LogUtil.processInfo("latLong API недоступен для исходного файла: ${e.message}")
                }
            } else {
                LogUtil.processWarning("Не удалось скопировать ни одного GPS тега")

                // Fallback: пробуем latLong API
                try {
                    val latLong = sourceExif.latLong
                    if (latLong != null) {
                        destExif.setLatLong(latLong[0], latLong[1])

                        // Копируем высоту
                        val altitude = sourceExif.getAltitude(0.0)
                        if (!altitude.isNaN()) {
                            destExif.setAltitude(altitude)
                        }

                        LogUtil.processInfo("GPS данные скопированы через latLong API: широта=${latLong[0]}, долгота=${latLong[1]}")
                    } else {
                        LogUtil.processWarning("Не удалось скопировать GPS данные ни одним из методов")
                    }
                } catch (e: Exception) {
                    LogUtil.error(null, "Fallback копирование через latLong API", e)
                }
            }
        } catch (e: Exception) {
            LogUtil.error(null, "Копирование GPS данных", e)
        }
    }
    
    /**
     * Проверяет наличие GPS тегов в ExifInterface
     * @param exif Объект ExifInterface для проверки
     * @return Список доступных GPS тегов
     */
    private fun checkGpsTagsAvailability(exif: ExifInterface): List<String> {
        val availableTags = mutableListOf<String>()
        
        for (tag in GPS_TAGS) {
            try {
                val value = exif.getAttribute(tag)
                if (value != null && value.isNotEmpty()) {
                    availableTags.add(tag)
                }
            } catch (e: Exception) {
                LogUtil.warning(null, "EXIF", "Ошибка при проверке GPS тега $tag: ${e.message}")
                // Игнорируем ошибки при проверке отдельных тегов
            }
        }
        
        return availableTags
    }
    
    /**
     * Проверяет успешность копирования EXIF-тегов
     * @param sourceExif Исходный объект ExifInterface
     * @param destExif Целевой объект ExifInterface
     * @return true если копирование успешно
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
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_FOCAL_LENGTH
        )

        var criticalTagCopied = false
        val verificationDetails = StringBuilder("Проверка критических тегов:\n")

        for (tag in criticalTags) {
            val sourceValue = sourceExif.getAttribute(tag)
            val destValue = destExif.getAttribute(tag)
            val status = when {
                sourceValue == null -> "отсутствует в источнике"
                destValue == null -> "отсутствует в назначении"
                sourceValue == destValue -> "✓ совпадает"
                else -> "✗ не совпадает (источник: '$sourceValue', назначение: '$destValue')"
            }
            verificationDetails.append("  $tag: $status\n")

            if (sourceValue != null && sourceValue == destValue) {
                criticalTagCopied = true
            }
        }

        LogUtil.processInfo(verificationDetails.toString().trimEnd())

        // Подсчитываем общее количество скопированных тегов
        var tagsCopied = 0
        var totalTags = 0
        var tagsMismatched = 0
        val tagsDetails = StringBuilder("Проверка всех тегов из TAG_LIST:\n")

        for (tag in TAG_LIST) {
            val sourceValue = sourceExif.getAttribute(tag)
            if (sourceValue != null) {
                totalTags++
                val destValue = destExif.getAttribute(tag)
                when {
                    sourceValue == destValue -> {
                        tagsCopied++
                        tagsDetails.append("  ✓ $tag: совпадает\n")
                    }
                    destValue == null -> {
                        tagsDetails.append("  ✗ $tag: отсутствует в назначении (был '$sourceValue')\n")
                    }
                    else -> {
                        // Тег есть в обоих, но значения разные
                        tagsMismatched++
                        tagsDetails.append("  ✗ $tag: не совпадает (источник: '$sourceValue', назначение: '$destValue')\n")
                    }
                }
            }
        }

        LogUtil.processInfo(tagsDetails.toString().trimEnd())
        LogUtil.processInfo("Результат верификации: criticalTagCopied=$criticalTagCopied, totalTags=$totalTags, tagsCopied=$tagsCopied, tagsMismatched=$tagsMismatched")

        // Копирование успешно, если:
        // 1. Скопирован хотя бы один критический тег, ИЛИ
        // 2. Скопировано более половины всех тегов, ИЛИ
        // 3. В исходном файле нет тегов для копирования (totalTags = 0) - операция completed успешно
        val result = criticalTagCopied || (totalTags > 0 && tagsCopied >= totalTags / 2) || totalTags == 0
        LogUtil.processInfo("Итоговая проверка верификации: $result")
        return result
    }
    
    /**
     * Считывает EXIF-данные в память для последующего применения
     * @param context Контекст приложения
     * @param uri URI изображения
     * @return Карта с EXIF-тегами и их значениями
     */
    suspend fun readExifDataToMemory(context: Context, uri: Uri): Map<String, Any> = withContext(Dispatchers.IO) {
        val uriString = uri.toString()
        // Сначала проверяем кэш
        exifDataCache.getWithTtl(uriString)?.let {
            LogUtil.processInfo("Чтение EXIF данных из кэша для $uri")
            return@withContext it
        }

        val exifData = mutableMapOf<String, Any>()
        try {
            LogUtil.processInfo("Чтение EXIF данных из $uri в память (кэш не найден)")
            
            val exif = getExifInterface(context, uri) ?: return@withContext exifData
            
            // Сохраняем все теги
            for (tag in TAG_LIST) {
                val value = exif.getAttribute(tag)
                if (value != null) {
                    exifData[tag] = value
                }
            }
            
            // === ДИАГНОСТИКА РАЗРЕШЕНИЙ ===
            try {
                val hasMediaLocationPermission =
                    context.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                
                LogUtil.permissionsInfo("📋 ДИАГНОСТИКА РАЗРЕШЕНИЙ для $uri:")
                LogUtil.permissionsInfo("  - Android версия: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                LogUtil.permissionsInfo("  - ACCESS_MEDIA_LOCATION: ${if (hasMediaLocationPermission) "✅ ПРЕДОСТАВЛЕНО" else "❌ ОТСУТСТВУЕТ"}")
                LogUtil.permissionsInfo("  - URI тип: ${if (uri.toString().startsWith("content://media/")) "MediaStore" else "Другой"}")
                
                if (!hasMediaLocationPermission) {
                    LogUtil.permissionsWarning("⚠️ КРИТИЧНО: Разрешение ACCESS_MEDIA_LOCATION отсутствует - GPS данные будут скрыты системой!")
                }
            } catch (e: Exception) {
                LogUtil.permissionsError("Ошибка проверки разрешений", e)
            }
            
            // === GPS ИЗВЛЕЧЕНИЕ ЧЕРЕЗ EXIFINTERFACE ===
            LogUtil.processInfo("🔍 GPS ИЗВЛЕЧЕНИЕ: Используем ExifInterface с поддержкой MediaStore.setRequireOriginal()")
            
            val latLong = exif.latLong
            LogUtil.processInfo("🔍 GPS результат: ${if (latLong != null) "lat=${latLong[0]}, lng=${latLong[1]}" else "null"}")
            
            if (latLong != null) {
                exifData["HAS_GPS"] = true
                exifData["GPS_LAT"] = latLong[0]
                exifData["GPS_LONG"] = latLong[1]
                
                val altitude = exif.getAltitude(0.0)
                if (!altitude.isNaN()) {
                    exifData["GPS_ALT"] = altitude
                }
                
                LogUtil.processInfo("✅ GPS данные получены через ExifInterface: lat=${latLong[0]}, lng=${latLong[1]}")
            } else {
                LogUtil.processInfo("⚠️ GPS данные не найдены в EXIF")
            }
            
            // === ДОБАВЛЕНИЕ ДАТЫ ОЦИФРОВКИ ИЗ МЕТАДАННЫХ ФАЙЛА ===
            LogUtil.processInfo("🕒 ПРОВЕРКА ДАТ: Проверяем наличие дат в EXIF и добавляем дату оцифровки при необходимости")
            addDigitizedDateFromFileMetadata(context, uri, exif, exifData)
            
            LogUtil.processInfo("Прочитано ${exifData.size} EXIF-тегов, сохраняем в кэш")
            // Сохраняем в кэш
            if (exifData.isNotEmpty()) {
                exifDataCache.putWithTimestamp(uriString, exifData)
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Чтение EXIF в память", e)
            // В случае ошибки (например, FileNotFoundException), выбрасываем исключение дальше,
            // чтобы вызывающий код мог его обработать.
            throw e
        }
        
        return@withContext exifData
    }
    
    /**
     * Применяет сохраненные EXIF-данные к изображению
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param exifData Карта с EXIF-тегами и их значениями
     * @param quality Качество сжатия для добавления маркера (null, если не нужно)
     * @param originalFileSize исходный размер файла до сжатия для поля origSize маркера
     * @return true если применение успешно
     */
    suspend fun applyExifFromMemory(
        context: Context, 
        uri: Uri, 
        exifData: Map<String, Any>, 
        quality: Int? = null,
        originalFileSize: Long? = null,
        pixelsTransformed: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!UriUtil.isUriExistsSuspend(context, uri)) {
                LogUtil.processWarning("applyExifFromMemory: URI не существует, применение EXIF отменено: $uri")
                return@withContext false
            }
            // LogUtil.processInfo("Применение ${exifData.size} EXIF-тегов к $uri")
 
             // 1. Сохраняем исходную дату модификации только для режима отдельной папки.
             // В режиме замены восстановление старой date_modified запрещено:
             // кэши миниатюр галерей ключуются на (id, date_modified), и совпадение
             // даты с оригиналом оставляет навсегда закэшированной миниатюру,
             // случайно снятую галереей из частично перезаписанного файла.
             val isReplaceMode = FileOperationsUtil.isSaveModeReplace(context)
             val originalLastModified = if (!isReplaceMode) {
                 val lastModified = UriUtil.getFileLastModified(context, uri)
                 LogUtil.processInfo("Сохранена исходная дата модификации: $lastModified")
                 lastModified
             } else {
                 0L
             }
            
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                
                val appliedTags = applyTags(exif, exifData, pixelsTransformed)

                // Добавляем маркер сжатия, если нужно.
                // Фаза 1: маркер с нулевой заглушкой размера фиксированной ширины;
                // фактический размер дописывается второй фазой после saveAttributes().
                var markerTimestamp = 0L
                if (quality != null) {
                    markerTimestamp = System.currentTimeMillis()
                    val compressionInfo = CompressionMarker.build(quality, markerTimestamp, null, originalFileSize)
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, compressionInfo)
                    LogUtil.processInfo("Добавлен маркер сжатия: $compressionInfo")
                }
                
                // 2. Сохраняем изменения в EXIF под защитой durable backup.
                // ИНВАРИАНТ БЕЗОПАСНОСТИ: saveAttributes() перезаписывает файл на месте;
                // без backup запись пропускается, файл остаётся целым (без маркера/тегов).
                if (!guardedExifWrite(context, uri) { exif.saveAttributes() }) {
                    return@withContext false
                }
                LogUtil.processInfo("Применено $appliedTags EXIF-тегов к $uri")

                // Фаза 2: заменяем нулевую заглушку размера в маркере на фактический
                // размер файла. Размер записывается одной записью: возможный дрейф
                // saveAttributes() (до ~1 КБ) покрывается допуском
                // [Constants.MARKER_SIZE_TOLERANCE_BYTES] в проверке повторной обработки.
                if (quality != null && markerTimestamp > 0L) {
                    writeActualSizeMarker(context, uri, quality, markerTimestamp, originalFileSize)
                }
                
                // 3. Восстанавливаем исходную дату модификации только вне режима замены.
                // В replace-режиме оставляем актуальную дату, чтобы MediaStore и
                // галереи перегенерировали миниатюры после перезаписи файла.
                if (!isReplaceMode && originalLastModified > 0) {
                    MediaStoreDateUtil.restoreModifiedDate(context, uri, originalLastModified)
                }

                // НОВАЯ ПРОВЕРКА: Верифицируем что маркер сжатия сохранен
                if (quality != null) {
                    val marker = getCompressionMarker(context, uri)
                    if (!marker.isCompressed) {
                        LogUtil.error(uri, "Запись EXIF", "EXIF маркер не был сохранен")
                        return@withContext false
                    }
                    // Диагностика внешних изменений: расхождение фактического размера
                    // с записанным сверх допуска означает модификацию файла после
                    // фазы 2 (внешним процессом или ресканом) — фиксируем для отладки.
                    val actualSize = getActualFileSizeOnDisk(context, uri)
                    if (marker.fileSize != null && actualSize != null &&
                        kotlin.math.abs(actualSize - marker.fileSize) > Constants.MARKER_SIZE_TOLERANCE_BYTES
                    ) {
                        LogUtil.error(
                            uri, "Запись EXIF",
                            "Размер в маркере (${marker.fileSize}) расходится с фактическим ($actualSize) сверх допуска — файл изменён после записи маркера"
                        )
                    }
                    LogUtil.processInfo("✅ EXIF маркер успешно сохранен и верифицирован")
                }

                return@withContext true
            }
            
            LogUtil.error(uri, "Запись EXIF", "Не удалось открыть файл для записи EXIF")
            return@withContext false
        } catch (e: Exception) {
            LogUtil.error(uri, "Применение EXIF из памяти", e)

            // Если это HEIC файл и ошибка связана с невозможностью сохранить EXIF,
            // используем fallback - переименование файла
            if (isHeicFile(context, uri) &&
                (e.message?.contains("only supports saving attributes for JPEG, PNG, and WebP") == true ||
                 e is java.io.IOException)) {

                LogUtil.processInfo("HEIC файл: попытка добавить маркер через переименование")
                val renameSuccess = markHeicFileAsCompressed(context, uri)

                if (renameSuccess) {
                    // Для HEIC считаем операцию успешной, даже если EXIF не применился
                    // Маркер будет добавлен через суффикс в имени файла
                    LogUtil.processInfo("✅ HEIC файл помечен как сжатый через переименование")
                    return@withContext true
                }
            }

            return@withContext false
        }
    }

    /**
     * Переносит теги из [exifData] (результат [readExifDataToMemory]) в [exif]:
     * строковые теги, GPS и, при [pixelsTransformed], Orientation=NORMAL.
     * @return число применённых строковых тегов
     */
    private fun applyTags(exif: ExifInterface, exifData: Map<String, Any>, pixelsTransformed: Boolean): Int {
        // Применяем все текстовые теги
        var appliedTags = 0
        for ((tag, value) in exifData) {
            if (value is String) {
                exif.setAttribute(tag, value)
                appliedTags++
            }
        }
        
        // После трансформации пикселей в compressImageToStream устанавливаем orientation = NORMAL.
        // ИНВАРИАНТ: для нетронутого оригинала (маркер пропуска/неудалённый оригинал)
        // ориентацию менять нельзя — иначе фото будет отображаться повёрнутым.
        if (pixelsTransformed) {
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            LogUtil.debug("EXIF", "Ориентация установлена в NORMAL (изображение трансформировано)")
        }
        
        // Применяем GPS-данные, если они есть
        var gpsTagsApplied = 0
        // LogUtil.processInfo("Начинаем применение GPS данных из памяти")
        
        if (exifData.containsKey("HAS_GPS") && exifData.containsKey("GPS_LAT") && exifData.containsKey("GPS_LONG")) {
            // Метод 1: Используем setLatLong API (если latLong работал при чтении)
            val lat = exifData["GPS_LAT"] as Double
            val lng = exifData["GPS_LONG"] as Double
            exif.setLatLong(lat, lng)
            
            if (exifData.containsKey("GPS_ALT")) {
                val alt = exifData["GPS_ALT"] as Double
                exif.setAltitude(alt)
            }
            
            // УЛУЧШЕНИЕ: применяем также reference теги, если они были получены через metadata-extractor
            if (exifData.containsKey("GPS_LAT_REF")) {
                val latRef = exifData["GPS_LAT_REF"] as String
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, latRef)
                LogUtil.processInfo("Применен GPS latitude reference: $latRef")
            }
            if (exifData.containsKey("GPS_LONG_REF")) {
                val lngRef = exifData["GPS_LONG_REF"] as String
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, lngRef)
                LogUtil.processInfo("Применен GPS longitude reference: $lngRef")
            }
            if (exifData.containsKey("GPS_ALT_REF")) {
                val altRef = exifData["GPS_ALT_REF"] as String
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, altRef)
                LogUtil.processInfo("Применен GPS altitude reference: $altRef")
            }
            if (exifData.containsKey("GPS_TIMESTAMP")) {
                val timestamp = exifData["GPS_TIMESTAMP"] as String
                exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, timestamp)
                LogUtil.processInfo("Применен GPS timestamp: $timestamp")
            }
            if (exifData.containsKey("GPS_DATESTAMP")) {
                val datestamp = exifData["GPS_DATESTAMP"] as String
                exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, datestamp)
                LogUtil.processInfo("Применен GPS datestamp: $datestamp")
            }
            if (exifData.containsKey("GPS_PROCESSING_METHOD")) {
                val processingMethod = exifData["GPS_PROCESSING_METHOD"] as String
                exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, processingMethod)
                LogUtil.processInfo("Применен GPS processing method: $processingMethod")
            }
            
            LogUtil.processInfo("Применены GPS-данные через setLatLong API + reference теги: lat=$lat, lng=$lng")
            gpsTagsApplied++
        } else {
            // Метод 2: Применяем отдельные GPS теги
            for (tag in GPS_TAGS) {
                if (exifData.containsKey(tag)) {
                    val value = exifData[tag] as String
                    exif.setAttribute(tag, value)
                    gpsTagsApplied++
                    LogUtil.processInfo("GPS тег применен: $tag = $value")
                }
            }
            
            if (gpsTagsApplied > 0) {
                LogUtil.processInfo("Применено $gpsTagsApplied GPS-тегов через setAttribute")
            } else {
                // LogUtil.processInfo("GPS данные отсутствуют в памяти")
            }
        }
        return appliedTags
    }

    /**
     * Записывает EXIF и маркер сжатия в локальный JPEG-artifact до его публикации
     * в MediaStore. Двухфазная запись маркера выполняется на локальном файле:
     * фаза 1 — теги и заглушка размера, фаза 2 — фактический [File.length], пока
     * длина не стабилизируется (строка маркера фиксированной ширины, обычно одна
     * запись). Artifact затем копируется в MediaStore байт-в-байт, поэтому size в
     * маркере совпадает с размером опубликованного файла без backup и fsync на
     * каждую запись EXIF.
     *
     * При сбое ExifInterface восстанавливает исходный файл, artifact остаётся
     * валидным JPEG без EXIF.
     *
     * @return true, если маркер записан и его size в пределах допуска от длины файла
     */
    suspend fun writeExifToArtifact(
        file: File,
        exifData: Map<String, Any>,
        quality: Int,
        originalFileSize: Long?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val markerTimestamp = System.currentTimeMillis()
            val exif = ExifInterface(file)
            val appliedTags = applyTags(exif, exifData, pixelsTransformed = true)
            exif.setAttribute(
                ExifInterface.TAG_USER_COMMENT,
                CompressionMarker.build(quality, markerTimestamp, null, originalFileSize)
            )
            exif.saveAttributes()

            var size = file.length()
            repeat(MAX_ARTIFACT_MARKER_WRITES) {
                val sized = ExifInterface(file)
                sized.setAttribute(
                    ExifInterface.TAG_USER_COMMENT,
                    CompressionMarker.build(quality, markerTimestamp, size, originalFileSize)
                )
                sized.saveAttributes()
                val actual = file.length()
                if (actual == size) {
                    LogUtil.processInfo("EXIF artifact: $appliedTags тегов, маркер с размером $size")
                    return@withContext true
                }
                size = actual
            }
            val marker = CompressionMarker.parse(ExifInterface(file).getAttribute(ExifInterface.TAG_USER_COMMENT))
            val markerSize = marker?.fileSize
            val ok = markerSize != null &&
                kotlin.math.abs(file.length() - markerSize) <= Constants.MARKER_SIZE_TOLERANCE_BYTES
            if (!ok) {
                LogUtil.processWarning("EXIF artifact: размер в маркере ($markerSize) не сошёлся с ${file.length()}")
            }
            ok
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LogUtil.errorWithException("EXIF artifact", e)
            false
        }
    }

    /**
     * Фактический размер файла на диске, полученный напрямую через дескриптор
     * файла (fstat), в обход кэша MediaStore: OpenableColumns.SIZE может быть
     * устаревшим до завершения рескана после перезаписи файла.
     *
     * Каскад: fstat → ParcelFileDescriptor.statSize → подсчёт чтением потока.
     * Перехват Throwable вокруг fstat нужен, т.к. в JVM-среде (Robolectric)
     * нативный вызов может быть недоступен.
     *
     * @return размер в байтах или null, если размер определить не удалось
     */
    private fun getActualFileSizeOnDisk(context: Context, uri: Uri): Long? {
        val fromDescriptor = FileIoUtil.getDescriptorSize(context, uri)
        if (fromDescriptor != null) {
            return fromDescriptor
        }

        // Финальный fallback: подсчёт чтением потока (медленный, но универсальный)
        return try {
            UriUtil.countStreamBytes(context, uri)?.takeIf { it > 0L }
        } catch (e: Exception) {
            LogUtil.warning(uri, "Маркер сжатия", "Не удалось определить фактический размер файла: ${e.message}")
            null
        }
    }

    /**
     * Фаза 2 записи маркера: заменяет нулевую заглушку размера в уже записанном
     * маркере на фактический размер файла — одной записью.
     *
     * ExifInterface.saveAttributes() пересобирает EXIF-сегменты и может изменить
     * размер файла даже при записи строки маркера той же длины (эмпирически
     * наблюдается дрейф в пределах ~1 КБ). Точное совпадение не требуется:
     * расхождение в пределах [Constants.MARKER_SIZE_TOLERANCE_BYTES] при проверке
     * повторной обработки игнорируется, поэтому итеративная запись не нужна.
     *
     * После записи выполняется контрольная сверка: если расхождение всё же
     * превышает допуск (экзотический EXIF), выполняется одна корректирующая
     * перезапись с фактическим размером — строка маркера фиксированной ширины,
     * поэтому вторая запись стабилизирована. Дальнейших попыток нет: остаточное
     * расхождение фиксируется в логе, а разрешение ситуации берёт на себя
     * допуск чекера (файл с превышающим допуск маркером будет однократно
     * пересжат, после чего маркер запишется заново).
     *
     * I/O: каждая запись (их максимум две) выполняется под защитой собственного
     * durable backup с верификацией целостности ([guardedExifWrite]).
     */
    private suspend fun writeActualSizeMarker(
        context: Context,
        uri: Uri,
        quality: Int,
        markerTimestamp: Long,
        originalFileSize: Long?
    ): Boolean = withContext(Dispatchers.IO) {
        val sizeBeforeWrite = getActualFileSizeOnDisk(context, uri)
        if (sizeBeforeWrite == null || sizeBeforeWrite <= 0L) {
            LogUtil.warning(uri, "Маркер сжатия", "Не удалось получить размер файла, маркер остаётся без размера")
            return@withContext false
        }

        val writeOk = writeMarkerWithSize(context, uri, quality, markerTimestamp, sizeBeforeWrite, originalFileSize)
        if (!writeOk) {
            return@withContext false
        }

        val actualAfterWrite = getActualFileSizeOnDisk(context, uri)
        if (actualAfterWrite != null &&
            kotlin.math.abs(actualAfterWrite - sizeBeforeWrite) > Constants.MARKER_SIZE_TOLERANCE_BYTES
        ) {
            // Дрейф saveAttributes() оказался больше допуска: одна корректирующая
            // запись с фактическим размером. Фиксированная ширина строки маркера
            // гарантирует, что длина записи не изменится и файл стабилизируется
            LogUtil.processInfo(
                "Маркер сжатия: дрейф saveAttributes() ${sizeBeforeWrite} → $actualAfterWrite сверх допуска, корректирующая запись"
            )
            writeMarkerWithSize(context, uri, quality, markerTimestamp, actualAfterWrite, originalFileSize)
        } else {
            LogUtil.processInfo("✅ Размер файла $sizeBeforeWrite записан в маркер сжатия")
        }
        return@withContext true
    }

    /**
     * Одна запись маркера с заданным значением поля размера под защитой
     * [guardedExifWrite] (backup, верификация, откат при сбое).
     *
     * @return true если маркер записан и файл цел
     */
    private suspend fun writeMarkerWithSize(
        context: Context,
        uri: Uri,
        quality: Int,
        markerTimestamp: Long,
        size: Long,
        originalFileSize: Long?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val markerWithSize = CompressionMarker.build(quality, markerTimestamp, size, originalFileSize)
            guardedExifWrite(context, uri) {
                // Дескриптор не открыт — маркер не записан: обязаны вернуть false
                // (через исключение), иначе вызывающий код сочтёт запись успешной
                val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                    ?: throw IOException("Не удалось открыть дескриптор для записи маркера")
                pfd.use {
                    val exif = ExifInterface(it.fileDescriptor)
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, markerWithSize)
                    exif.saveAttributes()
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LogUtil.error(uri, "Маркер сжатия", "Не удалось записать размер файла в маркер", e)
            return@withContext false
        }
    }

    /**
     * Перезаписывает файл на месте ([write], обычно `saveAttributes()`) под защитой
     * durable backup ([BackupRegistry.createBackup]).
     *
     * - backup не создан → запись не выполняется, возвращается false;
     * - после записи файл не проходит верификацию → откат, false;
     * - [write] бросил исключение → откат (только если файл действительно изменён),
     *   исключение пробрасывается.
     *
     * ИНВАРИАНТ БЕЗОПАСНОСТИ: backup освобождается только при успехе или успешном
     * откате; иначе остаётся в реестре для [BackupRecoveryHelper].
     */
    private suspend fun guardedExifWrite(context: Context, uri: Uri, write: suspend () -> Unit): Boolean {
        val backupFile = BackupRegistry.createBackup(context, uri, "exif_backup_")
        if (backupFile == null) {
            LogUtil.warning(uri, "EXIF backup", "Backup не создан — запись EXIF отменена для защиты файла от повреждения")
            return false
        }
        var keepBackup = false
        try {
            write()
            if (ImageIntegrityUtil.verifyImageIntegrity(context, uri)) {
                return true
            }
            LogUtil.error(uri, "EXIF верификация", "Файл повреждён после записи EXIF, откатываем из backup")
            keepBackup = !rollbackExifWrite(context, uri, backupFile)
            return false
        } catch (e: Throwable) {
            LogUtil.error(uri, "EXIF save", "Запись EXIF упала, откатываем файл из backup: ${e.message}")
            keepBackup = !rollbackExifWrite(context, uri, backupFile)
            throw e
        } finally {
            if (!keepBackup) {
                BackupRegistry.releaseBackup(context, backupFile)
            }
        }
    }

    private suspend fun rollbackExifWrite(context: Context, uri: Uri, backupFile: File): Boolean =
        withContext(NonCancellable) {
            val restored = BackupRegistry.rollback(context, uri, backupFile)
            if (restored) {
                LogUtil.processInfo("✅ Файл совпадает с backup после отката записи EXIF")
            } else {
                LogUtil.error(uri, "EXIF restore", "Откат не удался — backup сохранён для восстановления при следующем запуске")
            }
            restored
        }

    /**
     * Добавляет маркер сжатия к изображению
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param quality Качество сжатия
     * @return true если маркер успешно добавлен
     */
    suspend fun markCompressedImage(context: Context, uri: Uri, quality: Int, originalFileSize: Long? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            LogUtil.processInfo("Добавление маркера сжатия: $uri, качество=$quality")

            val markerTimestamp = System.currentTimeMillis()

            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)

                // Фаза 1: маркер с нулевой заглушкой размера фиксированной ширины
                val markerPlaceholder = CompressionMarker.build(quality, markerTimestamp, null, originalFileSize)

                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, markerPlaceholder)
                if (!guardedExifWrite(context, uri) { exif.saveAttributes() }) {
                    return@withContext false
                }

                LogUtil.processInfo("Маркер сжатия успешно добавлен")
            } ?: run {
                LogUtil.error(uri, "Запись маркера", "Не удалось открыть файл для добавления маркера")
                return@withContext false
            }

            // Фаза 2: запись фактического размера файла в маркер одной записью
            // (дрейф saveAttributes() покрывается допуском в чекере)
            writeActualSizeMarker(context, uri, quality, markerTimestamp, originalFileSize)

            return@withContext true
        } catch (e: Exception) {
            LogUtil.error(uri, "Добавление маркера сжатия", e)
            return@withContext false
        }
    }
    
    /**
     * Получает информацию о сжатии из тега UserComment
     * @param context Контекст приложения
     * @param uri URI изображения
     * @return [CompressionMarkerInfo] с флагом сжатия, качеством, временной меткой
     *         и размером файла из маркера (null, если размер неизвестен:
     *         старый формат, HEIC-маркер или заглушка двухфазной записи)
     */
    suspend fun getCompressionMarker(context: Context, uri: Uri): CompressionMarkerInfo {
        try {
            // Сначала проверяем HEIC файлы с суффиксом _compressed в имени
            if (isHeicFile(context, uri)) {
                val (displayName, dateModified) = withContext(Dispatchers.IO) {
                    getHeicDisplayNameAndDate(context, uri)
                }

                // Проверяем суффикс _compressed в имени HEIC файла
                if (CompressionMarker.hasHeicCompressedSuffix(displayName)) {
                    LogUtil.processDebug("Найден HEIC маркер сжатия в имени файла: $displayName для URI: $uri")
                    // Для HEIC с суффиксом возвращаем качество по умолчанию (85) и дату модификации
                    return CompressionMarkerInfo(true, 85, dateModified ?: System.currentTimeMillis(), null)
                }
            }

            // Стандартная проверка EXIF маркера для всех форматов
            val exifInterface = getExifInterface(context, uri) ?: return CompressionMarkerInfo.NOT_COMPRESSED
            val userComment = exifInterface.getAttribute(ExifInterface.TAG_USER_COMMENT)
            try {
                CompressionMarker.parse(userComment)?.let { marker ->
                    LogUtil.processDebug(
                        "Найден EXIF маркер с timestamp: ${marker.timestamp}, размер: ${marker.fileSize}, " +
                            "исходный размер: ${marker.originalFileSize} для URI: $uri"
                    )
                    return marker
                }
            } catch (e: NumberFormatException) {
                LogUtil.error(uri, "Парсинг маркера", "Ошибка при парсинге маркера сжатия: $userComment", e)
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "EXIF", "Ошибка при получении маркера сжатия", e)
        }

        return CompressionMarkerInfo.NOT_COMPRESSED
    }

    /**
     * Централизованный метод для обработки EXIF данных при сохранении сжатого изображения
     * 
     * @param context Контекст приложения
     * @param sourceUri URI исходного изображения
     * @param destinationUri URI сохраненного изображения
     * @param quality Качество сжатия
     * @param exifDataMemory Предварительно загруженные EXIF данные или null
     * @param originalFileSize Исходный размер файла до сжатия (для поля origSize маркера)
     * @return true если обработка EXIF данных успешна, false в противном случае
     */
    suspend fun handleExifForSavedImage(
        context: Context, 
        sourceUri: Uri, 
        destinationUri: Uri, 
        quality: Int,
        exifDataMemory: Map<String, Any>? = null,
        originalFileSize: Long? = null
    ): Boolean = withContext(Dispatchers.IO) {
        var exifSuccess = false
        
        try {
            // Используем заранее загруженные EXIF данные, если они доступны
            if (exifDataMemory != null && exifDataMemory.isNotEmpty()) {
                try {
                    exifSuccess = applyExifFromMemory(
                        context, destinationUri, exifDataMemory, quality, originalFileSize,
                        pixelsTransformed = true
                    )
                    // Целостность после каждой записи проверяет guardedExifWrite,
                    // итоговую — вызывающий MediaStoreUtil
                    LogUtil.processInfo("Применение EXIF данных из памяти: ${if (exifSuccess) "успешно" else "неудачно"}")
                } catch (e: Exception) {
                    LogUtil.error(destinationUri, "EXIF", "Ошибка при применении EXIF данных из памяти", e)
                }
            } else {
                // Если заранее загруженных данных нет, пробуем скопировать EXIF обычным способом
                try {
                    // Дополнительная задержка перед работой с EXIF
                    delay(Constants.EXIF_COPY_DELAY_MS)
                    exifSuccess = copyExifData(context, sourceUri, destinationUri)
                    LogUtil.processDebug("Копирование EXIF данных между URI: ${if (exifSuccess) "успешно" else "неудачно"}")
                    
                    if (!exifSuccess) {
                        LogUtil.processWarning("Не удалось скопировать EXIF данные, пробуем добавить только маркер сжатия")
                        exifSuccess = markCompressedImage(context, destinationUri, quality, originalFileSize)
                    }
                } catch (e: Exception) {
                    LogUtil.error(sourceUri, "Копирование EXIF", e)
                }
            }
            
            return@withContext exifSuccess
        } catch (e: Exception) {
            LogUtil.error(sourceUri, "Обработка EXIF данных", e)
            return@withContext false
        }
    }
    
    /**
     * Записывает EXIF данные из памяти в изображение
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param exifData Карта с EXIF-тегами и их значениями
     * @return true если запись успешна
     */
    suspend fun writeExifDataFromMemory(
        context: Context, 
        uri: Uri, 
        exifData: Map<String, Any>,
        quality: Int? = null,
        originalFileSize: Long? = null
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext applyExifFromMemory(context, uri, exifData, quality, originalFileSize)
    }
    
    /**
     * Проверяет наличие любых дат в EXIF данных
     * @param exif Объект ExifInterface для проверки
     * @return true если найдена хотя бы одна дата (съемки, редактирования, GPS или оцифровки)
     */
    private fun checkDateAvailability(exif: ExifInterface): Boolean {
        val dateTags = arrayOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,  // Дата съемки
            ExifInterface.TAG_DATETIME,           // Дата редактирования  
            ExifInterface.TAG_GPS_DATESTAMP,      // Дата GPS
            ExifInterface.TAG_DATETIME_DIGITIZED  // Дата оцифровки
        )
        
        for (tag in dateTags) {
            val value = exif.getAttribute(tag)
            if (!value.isNullOrEmpty()) {
                LogUtil.processInfo("Найдена дата в теге $tag: $value")
                return true
            }
        }
        
        LogUtil.processInfo("Даты съемки, редактирования, GPS и оцифровки отсутствуют в EXIF")
        return false
    }
    
    /**
     * Форматирует дату в формат EXIF (yyyy:MM:dd HH:mm:ss)
     * @param timestamp Временная метка в миллисекундах
     * @return Строка даты в формате EXIF
     */
    private fun formatDateForExif(timestamp: Long): String {
        val formatter = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }
    
    /**
     * Добавляет даты оцифровки и редактирования из метаданных файла, если все даты в EXIF отсутствуют
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param exif Объект ExifInterface
     * @param exifData Карта EXIF данных для добавления дат
     */
    private suspend fun addDigitizedDateFromFileMetadata(
        context: Context,
        uri: Uri,
        exif: ExifInterface,
        exifData: MutableMap<String, Any>
    ) {
        try {
            // Проверяем, есть ли уже какие-то даты в EXIF
            if (checkDateAvailability(exif)) {
                LogUtil.processInfo("Даты уже присутствуют в EXIF, пропускаем добавление даты оцифровки")
                return
            }
            
            LogUtil.processInfo("Получаем дату модификации файла для установки как дату оцифровки")
            
            // Получаем дату модификации файла
            val fileModificationDate = UriUtil.getFileLastModified(context, uri)
            
            if (fileModificationDate > 0) {
                val formattedDate = formatDateForExif(fileModificationDate)
                
                // Добавляем даты оцифровки и редактирования в карту EXIF данных
                exifData[ExifInterface.TAG_DATETIME_DIGITIZED] = formattedDate
                exifData[ExifInterface.TAG_DATETIME] = formattedDate
                
                LogUtil.processInfo("Добавлены даты из метаданных файла: оцифровки и редактирования = $formattedDate (исходная метка: ${Date(fileModificationDate)})")
            } else {
                LogUtil.processWarning("Не удалось получить дату модификации файла для URI: $uri")
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "Добавление даты оцифровки из метаданных файла", e)
        }
    }
} 

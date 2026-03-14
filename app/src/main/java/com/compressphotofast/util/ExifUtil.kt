package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.FileOutputStream
import java.io.FileInputStream
import java.util.Collections
import java.util.Date
import java.util.HashMap
import java.text.SimpleDateFormat
import java.util.Locale
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.ImageProcessingChecker
import com.compressphotofast.util.UriUtil
import com.compressphotofast.util.FileOperationsUtil
import com.compressphotofast.util.MediaStoreUtil
import android.content.ContentValues
import java.util.concurrent.ConcurrentHashMap

/**
 * Утилитарный класс для работы с EXIF метаданными изображений
 */
object ExifUtil {

    // Константы для EXIF маркировки
    private const val EXIF_USER_COMMENT = ExifInterface.TAG_USER_COMMENT
    private const val EXIF_COMPRESSION_MARKER = "CompressPhotoFast_Compressed"

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
            if (value != null) {
                timestamps[key] = System.currentTimeMillis()
            }
            return put(key, value)
        }

        fun getWithTtl(key: K): V? {
            return if (isExpired(key)) {
                remove(key)
                null
            } else {
                get(key)
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

    // Кэш для результатов проверки EXIF-маркеров (URI -> результат проверки)
    // Используем TtlLruCache для ограничения размера, предотвращения утечек памяти и автоматического устаревания
    private val exifCheckCache = TtlLruCache<String, Boolean>(
        maxSize = 100,
        ttlMs = 10 * 60 * 1000L // 10 минут
    )

    // Суффикс для HEIC файлов, которым не удалось добавить EXIF-маркер
    private const val HEIC_COMPRESSED_SUFFIX = "_compressed"

    /**
     * Проверяет, является ли файл HEIC/HEIF форматом
     * @param context Контекст приложения
     * @param uri URI изображения
     * @return true если файл HEIC/HEIF
     */
    private fun isHeicFile(context: Context, uri: Uri): Boolean {
        val mimeType = UriUtil.getMimeType(context, uri)
        return mimeType?.equals("image/heic", ignoreCase = true) == true ||
               mimeType?.equals("image/heif", ignoreCase = true) == true
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
                    dateModified = cursor.getLong(dateIndex) * 1000 // Конвертируем секунды в миллисекунды
                }
            }
        }

        return@withContext Pair(displayName, dateModified)
    }

    /**
     * Проверяет, есть ли у HEIC файла суффикс _compressed
     * @param displayName Имя файла (displayName из MediaStore)
     * @return true если имя содержит суффикс _compressed перед расширением
     */
    private fun hasHeicCompressedSuffix(displayName: String?): Boolean {
        if (displayName == null) return false

        // Проверяем наличие суффикса _compressed перед расширением .heic или .heif
        return displayName.contains(Regex("""_compressed\.(heic|heif)$""", RegexOption.IGNORE_CASE))
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
            if (hasHeicCompressedSuffix(currentDisplayName)) {
                LogUtil.processInfo("У файла уже есть суффикс _compressed: $currentDisplayName")
                return@withContext true
            }

            // Формируем новое имя: добавляем _compressed перед расширением
            val dotIndex = currentDisplayName!!.lastIndexOf('.')
            val newDisplayName = if (dotIndex > 0) {
                val nameWithoutExt = currentDisplayName!!.substring(0, dotIndex)
                val extension = currentDisplayName!!.substring(dotIndex)
                "$nameWithoutExt$HEIC_COMPRESSED_SUFFIX$extension"
            } else {
                "$currentDisplayName$HEIC_COMPRESSED_SUFFIX"
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    uri.toString().startsWith("content://media/")) {
                    val originalUri = MediaStore.setRequireOriginal(uri)
                    // LogUtil.processDebug("🔧 ExifInterface: Использую MediaStore.setRequireOriginal() для $uri")
                    originalUri
                } else {
                    uri
                }
            } catch (e: Exception) {
                LogUtil.processWarning("⚠️ Ошибка при получении оригинального URI для EXIF, используем исходный: ${e.message}")
                uri
            }

            try {
                context.contentResolver.openInputStream(finalUri)?.use { inputStream ->
                    return ExifInterface(inputStream)
                }
            } catch (e: FileNotFoundException) {
                LogUtil.error(uri, "Получение EXIF", "Файл не найден: ${e.message}")
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

            // Сохраняем изменения
            try {
                LogUtil.processInfo("Вызываем saveAttributes() для сохранения EXIF данных")
                destExif.saveAttributes()
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

            for (tag in arrayOf(
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_DATESTAMP
            )) {
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
        val gpsTagsToCheck = arrayOf(
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
        
        for (tag in gpsTagsToCheck) {
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
                val hasMediaLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
                
                LogUtil.permissionsInfo("📋 ДИАГНОСТИКА РАЗРЕШЕНИЙ для $uri:")
                LogUtil.permissionsInfo("  - Android версия: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                LogUtil.permissionsInfo("  - ACCESS_MEDIA_LOCATION: ${if (hasMediaLocationPermission) "✅ ПРЕДОСТАВЛЕНО" else "❌ ОТСУТСТВУЕТ"}")
                LogUtil.permissionsInfo("  - URI тип: ${if (uri.toString().startsWith("content://media/")) "MediaStore" else "Другой"}")
                
                if (!hasMediaLocationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
     * @return true если применение успешно
     */
    suspend fun applyExifFromMemory(
        context: Context, 
        uri: Uri, 
        exifData: Map<String, Any>, 
        quality: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!UriUtil.isUriExistsSuspend(context, uri)) {
                LogUtil.processWarning("applyExifFromMemory: URI не существует, применение EXIF отменено: $uri")
                return@withContext false
            }
            // LogUtil.processInfo("Применение ${exifData.size} EXIF-тегов к $uri")
 
             // 1. Сохраняем исходную дату модификации
             val originalLastModified = UriUtil.getFileLastModified(context, uri)
            LogUtil.processInfo("Сохранена исходная дата модификации: $originalLastModified")
            
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                
                // Применяем все текстовые теги
                var appliedTags = 0
                for ((tag, value) in exifData) {
                    if (value is String) {
                        exif.setAttribute(tag, value)
                        appliedTags++
                    }
                }
                
                // После трансформации пикселей в compressImageToStream устанавливаем orientation = NORMAL
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                LogUtil.debug("EXIF", "Ориентация установлена в NORMAL (изображение трансформировано)")
                
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
                    val gpsTagsToApply = arrayOf(
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
                    
                    for (tag in gpsTagsToApply) {
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
                
                // Добавляем маркер сжатия, если нужно
                if (quality != null) {
                    val timestamp = System.currentTimeMillis()
                    val compressionInfo = "$EXIF_COMPRESSION_MARKER:$quality:$timestamp"
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, compressionInfo)
                    LogUtil.processInfo("Добавлен маркер сжатия: $compressionInfo")
                }
                
                // 2. Сохраняем изменения в EXIF
                exif.saveAttributes()
                LogUtil.processInfo("Применено $appliedTags EXIF-тегов к $uri")
                
                // 3. Восстанавливаем исходную дату модификации
                if (originalLastModified > 0) {
                    MediaStoreDateUtil.restoreModifiedDate(context, uri, originalLastModified)
                }

                // === ДИАГНОСТИКА GPS ДАННЫХ ПОСЛЕ СОХРАНЕНИЯ ===
                // LogUtil.processInfo("🔍 ПРОВЕРКА GPS: Верификация сохраненных данных")
                
                // Проверяем, что GPS данные действительно записались
                /* try {
                    val savedGpsLatLong = exif.latLong
                    LogUtil.processInfo("🔍 GPS latLong после сохранения: ${if (savedGpsLatLong != null) "lat=${savedGpsLatLong[0]}, lng=${savedGpsLatLong[1]}" else "null"}")
                    
                    // Проверяем отдельные GPS теги после сохранения
                    var savedGpsTags = 0
                    val savedGpsDetails = StringBuilder("🔍 GPS теги после сохранения:\n")
                    
                    for (tag in arrayOf(
                        ExifInterface.TAG_GPS_LATITUDE,
                        ExifInterface.TAG_GPS_LATITUDE_REF,
                        ExifInterface.TAG_GPS_LONGITUDE,
                        ExifInterface.TAG_GPS_LONGITUDE_REF,
                        ExifInterface.TAG_GPS_ALTITUDE,
                        ExifInterface.TAG_GPS_ALTITUDE_REF,
                        ExifInterface.TAG_GPS_PROCESSING_METHOD,
                        ExifInterface.TAG_GPS_TIMESTAMP,
                        ExifInterface.TAG_GPS_DATESTAMP
                    )) {
                        val savedValue = exif.getAttribute(tag)
                        if (savedValue != null && savedValue.isNotEmpty()) {
                            savedGpsTags++
                            savedGpsDetails.append("  ✅ $tag = '$savedValue'\n")
                        } else {
                            savedGpsDetails.append("  ❌ $tag = ${if (savedValue == null) "null" else "пусто"}\n")
                        }
                    }
                    
                    LogUtil.processInfo(savedGpsDetails.toString().trimEnd())
                    LogUtil.processInfo("📊 GPS тегов после сохранения: $savedGpsTags из 9")
                    
                    if (savedGpsTags == 0) {
                        LogUtil.processInfo("❌ GPS данные потеряны при сохранении!")
                    } else if (gpsTagsApplied > 0 && savedGpsTags != gpsTagsApplied) {
                        LogUtil.processInfo("⚠️ Количество GPS тегов изменилось: применено $gpsTagsApplied, сохранено $savedGpsTags")
                    } else {
                        LogUtil.processInfo("✅ GPS данные успешно сохранены ($savedGpsTags тегов)")
                    }
                    
                } catch (gpsE: Exception) {
                    LogUtil.processInfo("❌ Ошибка проверки GPS после сохранения: ${gpsE.message}")
                } */

                // НОВАЯ ПРОВЕРКА: Верифицируем что маркер сжатия сохранен
                if (quality != null) {
                    val (markerSaved, _, _) = getCompressionMarker(context, uri)
                    if (!markerSaved) {
                        LogUtil.error(uri, "Запись EXIF", "EXIF маркер не был сохранен")
                        return@withContext false
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
     * Получает информацию о сжатии из EXIF-тегов
     * @param context Контекст приложения
     * @param uri URI изображения
     * @return Triple<Boolean, Int, Long>, где:
     *   - Boolean: было ли изображение сжато
     *   - Int: качество сжатия или -1
     *   - Long: временная метка сжатия или 0
     */
    suspend fun getCompressionInfo(context: Context, uri: Uri): Triple<Boolean, Int, Long> = withContext(Dispatchers.IO) {
        try {
            // Сначала проверяем HEIC файлы с суффиксом _compressed
            if (isHeicFile(context, uri)) {
                val (displayName, dateModified) = getHeicDisplayNameAndDate(context, uri)

                if (hasHeicCompressedSuffix(displayName)) {
                    LogUtil.processDebug("Найден HEIC маркер сжатия в имени: $displayName")
                    return@withContext Triple(true, 85, dateModified ?: System.currentTimeMillis())
                }
            }

            // Стандартная проверка EXIF для всех форматов
            val exif = getExifInterface(context, uri) ?: return@withContext Triple(false, -1, 0L)

            val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
            if (userComment != null && userComment.startsWith(EXIF_COMPRESSION_MARKER)) {
                val parts = userComment.split(":")
                if (parts.size >= 3) {
                    try {
                        val quality = parts[1].toInt()
                        val timestamp = parts[2].toLong()
                        LogUtil.processDebug("Найден EXIF маркер сжатия в URI $uri: качество=$quality, время=${Date(timestamp)}")
                        return@withContext Triple(true, quality, timestamp)
                    } catch (e: NumberFormatException) {
                        LogUtil.error(uri, "Парсинг маркера сжатия", e)
                    }
                }
            }

            return@withContext Triple(false, -1, 0L)
        } catch (e: Exception) {
            LogUtil.error(uri, "Получение информации о сжатии", e)
            return@withContext Triple(false, -1, 0L)
        }
    }
    
    /**
     * Добавляет маркер сжатия к изображению
     * @param context Контекст приложения
     * @param uri URI изображения
     * @param quality Качество сжатия
     * @return true если маркер успешно добавлен
     */
    suspend fun markCompressedImage(context: Context, uri: Uri, quality: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            LogUtil.processInfo("Добавление маркера сжатия: $uri, качество=$quality")
            
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                
                val timestamp = System.currentTimeMillis()
                val markerWithInfo = "$EXIF_COMPRESSION_MARKER:$quality:$timestamp"
                
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, markerWithInfo)
                exif.saveAttributes()
                
                LogUtil.processInfo("Маркер сжатия успешно добавлен")
                return@withContext true
            }
            
            LogUtil.error(uri, "Запись маркера", "Не удалось открыть файл для добавления маркера")
            return@withContext false
        } catch (e: Exception) {
            LogUtil.error(uri, "Добавление маркера сжатия", e)
            return@withContext false
        }
    }
    
    /**
     * Проверяет, было ли изображение сжато ранее
     * @param context Контекст приложения
     * @param uri URI изображения
     * @return true если изображение было сжато ранее
     */
    suspend fun isImageCompressed(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val (isCompressed, _, _) = getCompressionInfo(context, uri)
        return@withContext isCompressed
    }
    
    /**
     * Получает информацию о сжатии из тега UserComment (не suspend вариант для обратной совместимости)
     * @param context Контекст приложения
     * @param uri URI изображения
     * @return Triple<Boolean, Int, Long> где:
     *   - Boolean: было ли изображение сжато ранее
     *   - Int: качество сжатия или -1, если неизвестно
     *   - Long: временная метка сжатия в миллисекундах или 0L, если неизвестно
     */
    suspend fun getCompressionMarker(context: Context, uri: Uri): Triple<Boolean, Int, Long> {
        try {
            // Сначала проверяем HEIC файлы с суффиксом _compressed в имени
            if (isHeicFile(context, uri)) {
                val (displayName, dateModified) = withContext(Dispatchers.IO) {
                    getHeicDisplayNameAndDate(context, uri)
                }

                // Проверяем суффикс _compressed в имени HEIC файла
                if (hasHeicCompressedSuffix(displayName)) {
                    LogUtil.processDebug("Найден HEIC маркер сжатия в имени файла: $displayName для URI: $uri")
                    // Для HEIC с суффиксом возвращаем качество по умолчанию (85) и дату модификации
                    return Triple(true, 85, dateModified ?: System.currentTimeMillis())
                }
            }

            // Стандартная проверка EXIF маркера для всех форматов
            val exifInterface = getExifInterface(context, uri) ?: return Triple(false, -1, 0L)

            // Получаем UserComment
            val userComment = exifInterface.getAttribute(ExifInterface.TAG_USER_COMMENT)
            if (userComment.isNullOrEmpty()) {
                return Triple(false, -1, 0L)
            }

            // Проверяем, содержит ли UserComment наш маркер
            if (userComment.startsWith(EXIF_COMPRESSION_MARKER)) {
                // Разбираем информацию из маркера: CompressPhotoFast_Compressed:70:1742629672908
                val parts = userComment.split(":")
                if (parts.size >= 3) {
                    try {
                        val quality = parts[1].toInt()
                        val timestamp = parts[2].toLong()
                        LogUtil.processDebug("Найден EXIF маркер с timestamp: $timestamp для URI: $uri")
                        return Triple(true, quality, timestamp)
                    } catch (e: NumberFormatException) {
                        LogUtil.error(uri, "Парсинг маркера", "Ошибка при парсинге маркера сжатия: $userComment", e)
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "EXIF", "Ошибка при получении маркера сжатия", e)
        }

        return Triple(false, -1, 0L)
    }
    
    /**
     * Suspend версия getCompressionMarker.
     * @see getCompressionMarker
     */
    suspend fun getCompressionMarkerSuspend(context: Context, uri: Uri): Triple<Boolean, Int, Long> =
        getCompressionMarker(context, uri)
    
    /**
     * Централизованный метод для обработки EXIF данных при сохранении сжатого изображения
     * 
     * @param context Контекст приложения
     * @param sourceUri URI исходного изображения
     * @param destinationUri URI сохраненного изображения
     * @param quality Качество сжатия
     * @param exifDataMemory Предварительно загруженные EXIF данные или null
     * @return true если обработка EXIF данных успешна, false в противном случае
     */
    suspend fun handleExifForSavedImage(
        context: Context, 
        sourceUri: Uri, 
        destinationUri: Uri, 
        quality: Int,
        exifDataMemory: Map<String, Any>? = null
    ): Boolean = withContext(Dispatchers.IO) {
        var exifSuccess = false
        
        try {
            // Используем заранее загруженные EXIF данные, если они доступны
            if (exifDataMemory != null && exifDataMemory.isNotEmpty()) {
                try {
                    exifSuccess = applyExifFromMemory(context, destinationUri, exifDataMemory, quality)
                    LogUtil.processInfo("Применение EXIF данных из памяти: ${if (exifSuccess) "успешно" else "неудачно"}")
                } catch (e: Exception) {
                    LogUtil.error(destinationUri, "EXIF", "Ошибка при применении EXIF данных из памяти", e)
                }
            } else {
                // Если заранее загруженных данных нет, пробуем скопировать EXIF обычным способом
                try {
                    // Дополнительная задержка перед работой с EXIF
                    delay(300)
                    exifSuccess = copyExifData(context, sourceUri, destinationUri)
                    LogUtil.processDebug("Копирование EXIF данных между URI: ${if (exifSuccess) "успешно" else "неудачно"}")
                    
                    if (!exifSuccess) {
                        LogUtil.processWarning("Не удалось скопировать EXIF данные, пробуем добавить только маркер сжатия")
                        exifSuccess = markCompressedImage(context, destinationUri, quality)
                    }
                } catch (e: Exception) {
                    LogUtil.error(sourceUri, "Копирование EXIF", e)
                }
            }
            
            // Финальная верификация EXIF данных
            try {
                delay(100) // Небольшая задержка перед проверкой
                context.contentResolver.openInputStream(destinationUri)?.use { input ->
                    val exif = ExifInterface(input)
                    val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                    if (userComment?.contains("$EXIF_COMPRESSION_MARKER:$quality") == true) {
                        LogUtil.processDebug("Финальная верификация успешна: маркер сжатия присутствует в URI")
                    } else {
                        LogUtil.processWarning("Финальная верификация не удалась: маркер сжатия отсутствует в URI. UserComment: $userComment")
                    }
                }
            } catch (e: Exception) {
                LogUtil.error(destinationUri, "Верификация EXIF", e)
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
        quality: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext applyExifFromMemory(context, uri, exifData, quality)
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

    /**
     * Очищает все EXIF кэши для освобождения памяти
     * Должен вызываться при завершении пакетной обработки или при нехватке памяти
     */
    fun clearExifCaches() {
        exifDataCache.evictAll()
        exifCheckCache.evictAll()
        LogUtil.processInfo("EXIF кэши очищены")
    }
} 
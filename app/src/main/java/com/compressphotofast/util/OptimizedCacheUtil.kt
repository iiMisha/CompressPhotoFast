package com.compressphotofast.util

import android.net.Uri
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Оптимизированные кэши для улучшения производительности обработки изображений
 * Использует LRU-алгоритм и потокобезопасные операции
 */
object OptimizedCacheUtil {

    // Максимальные размеры кэшей
    private const val DIRECTORY_CACHE_SIZE = 500
    private const val EXIF_CACHE_SIZE = 1000
    private const val PATH_PATTERN_CACHE_SIZE = 200

    // Время жизни кэшированных данных
    private const val DIRECTORY_CACHE_TTL = 30 * 60 * 1000L // 30 минут
    private const val EXIF_CACHE_TTL = 10 * 60 * 1000L // 10 минут
    private const val PATH_PATTERN_CACHE_TTL = 60 * 60 * 1000L // 1 час

    /**
     * Кэш для результатов проверки директорий (приложение, мессенджеры и т.д.)
     */
    private val directoryCache = object : LruCache<String, CachedDirectoryResult>(DIRECTORY_CACHE_SIZE) {
        override fun sizeOf(key: String, value: CachedDirectoryResult): Int = 1
    }

    /**
     * Кэш для EXIF-данных файлов
     */
    private val exifCache = object : LruCache<String, CachedExifData>(EXIF_CACHE_SIZE) {
        override fun sizeOf(key: String, value: CachedExifData): Int = 1
    }

    /**
     * Кэш для результатов проверки паттернов путей (мессенджеры, скриншоты и т.д.)
     */
    private val pathPatternCache = object : LruCache<String, CachedPatternResult>(PATH_PATTERN_CACHE_SIZE) {
        override fun sizeOf(key: String, value: CachedPatternResult): Int = 1
    }

    // Блокировки для потокобезопасного доступа к кэшам
    private val directoryCacheLock = ReentrantReadWriteLock()
    private val exifCacheLock = ReentrantReadWriteLock()
    private val pathPatternCacheLock = ReentrantReadWriteLock()

    /**
     * Предкомпилированные паттерны для быстрой проверки мессенджеров
     */
    private val messengerPatterns = arrayOf(
        "/whatsapp/",
        "/telegram/",
        "/viber/",
        "/messenger/",
        "/messages/",
        "pictures/messages/"
    )

    /**
     * Предкомпилированные паттерны для проверки скриншотов
     */
    private val screenshotPatterns = arrayOf(
        "screenshot",
        "screen_shot",
        "скриншот"
    )

    /**
     * Класс для кэширования результатов проверки директорий
     */
    private data class CachedDirectoryResult(
        val isInAppDirectory: Boolean,
        val isMessengerImage: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > DIRECTORY_CACHE_TTL
    }

    /**
     * Класс для кэширования EXIF-данных
     */
    data class CachedExifData(
        val isCompressed: Boolean,
        val quality: Int,
        val compressionTimestamp: Long,
        val fileModificationTime: Long,
        val cacheTimestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - cacheTimestamp > EXIF_CACHE_TTL
        
        /**
         * Проверяет актуальность EXIF-данных на основе времени модификации файла
         */
        fun isStaleFor(currentModificationTime: Long): Boolean {
            return currentModificationTime > fileModificationTime
        }
    }

    /**
     * Класс для кэширования результатов проверки паттернов
     */
    private data class CachedPatternResult(
        val result: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > PATH_PATTERN_CACHE_TTL
    }

    /**
     * Проверяет, находится ли файл в директории приложения или является изображением из мессенджера
     * Использует кэширование для оптимизации повторных проверок
     */
    fun checkDirectoryStatus(filePath: String, appDirectory: String): Pair<Boolean, Boolean> {
        val cacheKey = filePath

        directoryCacheLock.read {
            val cached = directoryCache.get(cacheKey)
            if (cached != null && !cached.isExpired()) {
                return Pair(cached.isInAppDirectory, cached.isMessengerImage)
            }
        }

        // Вычисляем результат
        val isInAppDirectory = checkIsInAppDirectory(filePath, appDirectory)
        val isMessengerImage = checkIsMessengerImage(filePath)

        // Кэшируем результат
        directoryCacheLock.write {
            directoryCache.put(cacheKey, CachedDirectoryResult(isInAppDirectory, isMessengerImage))
        }

        return Pair(isInAppDirectory, isMessengerImage)
    }

    /**
     * Быстрая проверка на принадлежность к директории приложения
     */
    private fun checkIsInAppDirectory(path: String, appDirectory: String): Boolean {
        return path.contains("/$appDirectory/") || 
               (path.contains("content://media/external/images/media") && 
                path.contains(appDirectory))
    }

    /**
     * Оптимизированная проверка на изображение из мессенджера
     * Использует предкомпилированные паттерны для быстрого сравнения
     */
    private fun checkIsMessengerImage(path: String): Boolean {
        val lowercasedPath = path.lowercase()
        
        // Исключаем документы, которые могут быть переданы в высоком качестве
        if (lowercasedPath.contains("/documents/")) {
            return false
        }
        
        // Быстрая проверка с предкомпилированными паттернами
        for (pattern in messengerPatterns) {
            if (lowercasedPath.contains(pattern)) {
                return true
            }
        }
        
        return false
    }

    /**
     * Кэширует EXIF-данные для быстрого доступа
     */
    fun cacheExifData(
        uri: Uri, 
        isCompressed: Boolean, 
        quality: Int, 
        compressionTimestamp: Long,
        fileModificationTime: Long
    ) {
        val cacheKey = uri.toString()
        val exifData = CachedExifData(
            isCompressed, 
            quality, 
            compressionTimestamp,
            fileModificationTime
        )
        
        exifCacheLock.write {
            exifCache.put(cacheKey, exifData)
        }
        
        LogUtil.processDebug("EXIF-данные закэшированы для $uri")
    }

    /**
     * Получает кэшированные EXIF-данные
     * Улучшенная версия с инвалидацией при изменении файла
     */
    fun getCachedExifData(uri: Uri, currentModificationTime: Long): CachedExifData? {
        val cacheKey = uri.toString()

        exifCacheLock.read {
            val cached = exifCache.get(cacheKey)

            // Если кэш есть, проверяем актуальность
            if (cached != null && !cached.isExpired()) {

                // НОВАЯ ПРОВЕРКА: файл модифицирован после кэширования?
                if (currentModificationTime > 0L && cached.isStaleFor(currentModificationTime)) {
                    // Файл был модифицирован - кэш устарел, удаляем
                    LogUtil.processDebug("EXIF-кэш устарел (файл модифицирован): $uri")
                    // Выходим из read lock для получения write lock
                } else {
                    // Кэш актуален
                    LogUtil.processDebug("EXIF-данные получены из кэша для $uri")
                    return cached
                }
            }
        }

        // Если мы здесь, значит нужно удалить устаревший кэш или его нет
        if (currentModificationTime > 0L) {
            exifCacheLock.write {
                val cached = exifCache.get(cacheKey)
                if (cached != null && cached.isStaleFor(currentModificationTime)) {
                    exifCache.remove(cacheKey)
                    LogUtil.processDebug("EXIF-кэш удален (файл модифицирован): $uri")
                }
            }
        }

        return null
    }

    /**
     * Проверяет, является ли файл скриншотом, используя кэширование паттернов
     */
    fun isScreenshot(fileName: String): Boolean {
        val lowerFileName = fileName.lowercase()
        val cacheKey = "screenshot:$lowerFileName"
        
        pathPatternCacheLock.read {
            val cached = pathPatternCache.get(cacheKey)
            if (cached != null && !cached.isExpired()) {
                return cached.result
            }
        }
        
        // Вычисляем результат
        val isScreenshot = screenshotPatterns.any { pattern ->
            lowerFileName.contains(pattern)
        }
        
        // Кэшируем результат
        pathPatternCacheLock.write {
            pathPatternCache.put(cacheKey, CachedPatternResult(isScreenshot))
        }
        
        return isScreenshot
    }

    /**
     * Оптимизированная проверка MIME-типа с кэшированием
     */
    fun isProcessableMimeType(mimeType: String?): Boolean {
        if (mimeType == null) return false
        
        val cacheKey = "mimetype:$mimeType"
        
        pathPatternCacheLock.read {
            val cached = pathPatternCache.get(cacheKey)
            if (cached != null && !cached.isExpired()) {
                return cached.result
            }
        }
        
        // Быстрая проверка без использования contains() для простых случаев
        val isProcessable = when {
            mimeType.startsWith("image/jpeg") -> true
            mimeType.startsWith("image/jpg") -> true  
            mimeType.startsWith("image/png") -> true
            mimeType.startsWith("image/") -> mimeType.contains("jpeg") || mimeType.contains("jpg") || mimeType.contains("png")
            else -> false
        }
        
        // Кэшируем результат
        pathPatternCacheLock.write {
            pathPatternCache.put(cacheKey, CachedPatternResult(isProcessable))
        }
        
        return isProcessable
    }

    /**
     * Предзагрузка кэшированных данных для списка путей (для предиктивного кэширования)
     */
    fun preloadDirectoryCache(filePaths: List<String>, appDirectory: String) {
        // Группируем пути по директориям для более эффективного кэширования
        val directoryGroups = filePaths.groupBy { path ->
            val lastSlash = path.lastIndexOf('/')
            if (lastSlash > 0) path.substring(0, lastSlash) else path
        }
        
        directoryGroups.forEach { (directory, paths) ->
            // Предзагружаем данные для директории
            val isAppDir = checkIsInAppDirectory(directory, appDirectory)
            val isMessengerDir = checkIsMessengerImage(directory)
            
            paths.forEach { path ->
                directoryCacheLock.write {
                    directoryCache.put(path, CachedDirectoryResult(isAppDir, isMessengerDir))
                }
            }
        }
        
        LogUtil.processDebug("Предзагружен кэш для ${directoryGroups.size} директорий (${filePaths.size} файлов)")
    }

    /**
     * Очищает устаревшие записи из всех кэшей
     */
    fun cleanupExpiredEntries() {
        var totalCleaned = 0
        
        // Очищаем кэш директорий
        directoryCacheLock.write {
            val keysToRemove = mutableListOf<String>()
            val snapshot = directoryCache.snapshot()
            
            for ((key, value) in snapshot) {
                if (value.isExpired()) {
                    keysToRemove.add(key)
                }
            }
            
            keysToRemove.forEach { key ->
                directoryCache.remove(key)
            }
            totalCleaned += keysToRemove.size
        }
        
        // Очищаем кэш EXIF-данных
        exifCacheLock.write {
            val keysToRemove = mutableListOf<String>()
            val snapshot = exifCache.snapshot()
            
            for ((key, value) in snapshot) {
                if (value.isExpired()) {
                    keysToRemove.add(key)
                }
            }
            
            keysToRemove.forEach { key ->
                exifCache.remove(key)
            }
            totalCleaned += keysToRemove.size
        }
        
        // Очищаем кэш паттернов
        pathPatternCacheLock.write {
            val keysToRemove = mutableListOf<String>()
            val snapshot = pathPatternCache.snapshot()
            
            for ((key, value) in snapshot) {
                if (value.isExpired()) {
                    keysToRemove.add(key)
                }
            }
            
            keysToRemove.forEach { key ->
                pathPatternCache.remove(key)
            }
            totalCleaned += keysToRemove.size
        }
        
        if (totalCleaned > 0) {
            LogUtil.processDebug("Очищено $totalCleaned устаревших записей из кэшей")
        }
    }

    /**
     * Полная очистка всех кэшей
     */
    fun clearAllCaches() {
        directoryCacheLock.write { directoryCache.evictAll() }
        exifCacheLock.write { exifCache.evictAll() }
        pathPatternCacheLock.write { pathPatternCache.evictAll() }
        
        LogUtil.processDebug("Все кэши очищены")
    }

    /**
     * Получение статистики по всем кэшам
     */
    fun getCacheStats(): String {
        val dirStats = directoryCacheLock.read { 
            "Директории: ${directoryCache.size()}/$DIRECTORY_CACHE_SIZE" 
        }
        val exifStats = exifCacheLock.read { 
            "EXIF: ${exifCache.size()}/$EXIF_CACHE_SIZE" 
        }
        val pathStats = pathPatternCacheLock.read { 
            "Паттерны: ${pathPatternCache.size()}/$PATH_PATTERN_CACHE_SIZE" 
        }
        
        return "Кэши: $dirStats, $exifStats, $pathStats"
    }

    /**
     * Принудительная очистка кэша EXIF для конкретного URI (при изменении файла)
     */
    fun invalidateExifCache(uri: Uri) {
        val cacheKey = uri.toString()
        exifCacheLock.write {
            exifCache.remove(cacheKey)
        }
        LogUtil.processDebug("EXIF-кэш очищен для $uri")
    }
}
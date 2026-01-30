package com.compressphotofast.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Утилитарный класс для эффективной пакетной работы с MediaStore
 * Оптимизирует количество запросов к ContentResolver путем группировки операций
 */
object BatchMediaStoreUtil {

    // Кэш метаданных файлов для избежания повторных запросов
    private val metadataCache = ConcurrentHashMap<String, CachedFileMetadata>()
    
    // Максимальный размер кэша метаданных
    private const val MAX_CACHE_SIZE = 1000
    
    // Время жизни кэшированных данных (5 минут)
    private const val CACHE_TTL = 5 * 60 * 1000L
    
    // Максимальный размер батча для одного запроса
    private const val MAX_BATCH_SIZE = 50

    /**
     * Класс для хранения кэшированных метаданных файла
     */
    private data class CachedFileMetadata(
        val metadata: FileMetadata,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > CACHE_TTL
    }

    /**
     * Класс для хранения метаданных файла
     */
    data class FileMetadata(
        val uri: Uri,
        val size: Long = -1,
        val isPending: Boolean = false,
        val displayName: String? = null,
        val mimeType: String? = null,
        val lastModified: Long = 0L,
        val relativePath: String? = null
    )

    /**
     * Пакетное получение метаданных для списка URI
     * Объединяет множественные запросы к MediaStore в один эффективный запрос
     * 
     * @param context Контекст приложения
     * @param uris Список URI для получения метаданных
     * @return Карта URI -> FileMetadata, где null означает недоступность файла
     */
    suspend fun getBatchFileMetadata(
        context: Context, 
        uris: List<Uri>
    ): Map<Uri, FileMetadata?> = withContext(Dispatchers.IO) {
        try {
            val result = mutableMapOf<Uri, FileMetadata?>()
            val uncachedUris = mutableListOf<Uri>()
            
            // Проверяем кэш для уменьшения количества запросов к MediaStore
            for (uri in uris) {
                val cached = metadataCache[uri.toString()]
                if (cached != null && !cached.isExpired()) {
                    result[uri] = cached.metadata
                    LogUtil.processDebug("Метаданные получены из кэша: $uri")
                } else {
                    uncachedUris.add(uri)
                    // Удаляем устаревшую запись из кэша
                    if (cached?.isExpired() == true) {
                        metadataCache.remove(uri.toString())
                    }
                }
            }
            
            // Если все данные были в кэше, возвращаем результат
            if (uncachedUris.isEmpty()) {
                return@withContext result
            }
            
            // Обрабатываем некэшированные URI батчами
            val batches = uncachedUris.chunked(MAX_BATCH_SIZE)
            
            for (batch in batches) {
                val batchMetadata = getBatchMetadataFromMediaStore(context, batch)
                result.putAll(batchMetadata)
                
                // Кэшируем полученные метаданные
                batchMetadata.forEach { (uri, metadata) ->
                    if (metadata != null) {
                        cacheMetadata(uri, metadata)
                    }
                }
            }
            
            // Очищаем кэш если он стал слишком большим
            cleanupCacheIfNeeded()
            
            LogUtil.processDebug("Пакетное получение метаданных завершено: ${uris.size} URI, ${uncachedUris.size} запросов к MediaStore")
            return@withContext result
        } catch (e: Exception) {
            LogUtil.error(null, "BATCH_METADATA", "Ошибка при пакетном получении метаданных", e)
            return@withContext emptyMap()
        }
    }

    /**
     * Получение метаданных из MediaStore для батча URI
     */
    private suspend fun getBatchMetadataFromMediaStore(
        context: Context, 
        uris: List<Uri>
    ): Map<Uri, FileMetadata?> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Uri, FileMetadata?>()
        
        try {
            // Определяем необходимые колонки в зависимости от версии Android
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.Images.Media.IS_PENDING,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.RELATIVE_PATH
                )
            } else {
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.Images.Media.DATA // Для старых версий Android
                )
            }
            
            // Извлекаем ID из URI для создания WHERE условия
            val ids = uris.mapNotNull { uri ->
                try {
                    uri.lastPathSegment?.toLongOrNull()
                } catch (e: Exception) {
                    LogUtil.processDebug("Не удалось извлечь ID из URI: $uri")
                    null
                }
            }
            
            if (ids.isEmpty()) {
                // Если не можем извлечь ID, обрабатываем URI индивидуально
                return@withContext processFallbackMetadata(context, uris)
            }
            
            // Создаем WHERE условие с IN операцией
            val inClause = ids.joinToString(",") { "?" }
            val selection = "${MediaStore.MediaColumns._ID} IN ($inClause)"
            val selectionArgs = ids.map { it.toString() }.toTypedArray()
            
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val displayNameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeTypeColumn = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val modifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                
                val pendingColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                } else -1
                
                val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else -1
                
                val dataColumn = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                } else -1
                
                while (cursor.moveToNext()) {
                    val id = if (idColumn != -1 && !cursor.isNull(idColumn)) {
                        cursor.getLong(idColumn)
                    } else continue

                    // Находим соответствующий URI
                    val matchingUri = uris.find { uri ->
                        uri.lastPathSegment == id.toString()
                    }
                    
                    if (matchingUri != null) {
                        val size = if (sizeColumn != -1 && !cursor.isNull(sizeColumn)) {
                            cursor.getLong(sizeColumn)
                        } else -1L
                        
                        val displayName = if (displayNameColumn != -1 && !cursor.isNull(displayNameColumn)) {
                            cursor.getString(displayNameColumn)
                        } else null
                        
                        val mimeType = if (mimeTypeColumn != -1 && !cursor.isNull(mimeTypeColumn)) {
                            cursor.getString(mimeTypeColumn)
                        } else null
                        
                        val lastModified = if (modifiedColumn != -1 && !cursor.isNull(modifiedColumn)) {
                            cursor.getLong(modifiedColumn) * 1000 // Конвертируем в миллисекунды
                        } else 0L
                        
                        val isPending = if (pendingColumn != -1 && !cursor.isNull(pendingColumn)) {
                            cursor.getInt(pendingColumn) == 1
                        } else false
                        
                        val relativePath = if (relativePathColumn != -1 && !cursor.isNull(relativePathColumn)) {
                            cursor.getString(relativePathColumn)
                        } else if (dataColumn != -1 && !cursor.isNull(dataColumn)) {
                            // Для старых версий Android извлекаем путь из DATA колонки
                            val dataPath = cursor.getString(dataColumn)
                            extractRelativePathFromData(dataPath)
                        } else null
                        
                        result[matchingUri] = FileMetadata(
                            uri = matchingUri,
                            size = size,
                            isPending = isPending,
                            displayName = displayName,
                            mimeType = mimeType,
                            lastModified = lastModified,
                            relativePath = relativePath
                        )
                    }
                }
            }
            
            // Добавляем null для URI, которые не были найдены
            uris.forEach { uri ->
                if (!result.containsKey(uri)) {
                    result[uri] = null
                }
            }
            
        } catch (e: Exception) {
            LogUtil.error(null, "BATCH_MEDIASTORE", "Ошибка при пакетном запросе к MediaStore", e)
            // В случае ошибки возвращаем fallback результат
            return@withContext processFallbackMetadata(context, uris)
        }
        
        return@withContext result
    }

    /**
     * Fallback метод для получения метаданных, если пакетный запрос не работает
     */
    private suspend fun processFallbackMetadata(
        context: Context, 
        uris: List<Uri>
    ): Map<Uri, FileMetadata?> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Uri, FileMetadata?>()
        
        for (uri in uris) {
            try {
                val metadata = getIndividualFileMetadata(context, uri)
                result[uri] = metadata
            } catch (e: Exception) {
                LogUtil.error(uri, "INDIVIDUAL_METADATA", "Ошибка при получении индивидуальных метаданных", e)
                result[uri] = null
            }
        }
        
        return@withContext result
    }

    /**
     * Получение метаданных для отдельного файла (fallback)
     */
    private suspend fun getIndividualFileMetadata(
        context: Context, 
        uri: Uri
    ): FileMetadata? = withContext(Dispatchers.IO) {
        try {
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.Images.Media.IS_PENDING,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.RELATIVE_PATH
                )
            } else {
                arrayOf(
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.Images.Media.DATA
                )
            }
            
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val displayNameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val mimeTypeColumn = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    val modifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    
                    val size = if (sizeColumn != -1 && !cursor.isNull(sizeColumn)) {
                        cursor.getLong(sizeColumn)
                    } else -1L
                    
                    val displayName = if (displayNameColumn != -1 && !cursor.isNull(displayNameColumn)) {
                        cursor.getString(displayNameColumn)
                    } else null
                    
                    val mimeType = if (mimeTypeColumn != -1 && !cursor.isNull(mimeTypeColumn)) {
                        cursor.getString(mimeTypeColumn)
                    } else null
                    
                    val lastModified = if (modifiedColumn != -1 && !cursor.isNull(modifiedColumn)) {
                        cursor.getLong(modifiedColumn) * 1000
                    } else 0L
                    
                    val isPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val pendingColumn = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                        pendingColumn != -1 && !cursor.isNull(pendingColumn) && cursor.getInt(pendingColumn) == 1
                    } else false
                    
                    val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val relativePathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        if (relativePathColumn != -1 && !cursor.isNull(relativePathColumn)) {
                            cursor.getString(relativePathColumn)
                        } else null
                    } else {
                        val dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                        if (dataColumn != -1 && !cursor.isNull(dataColumn)) {
                            extractRelativePathFromData(cursor.getString(dataColumn))
                        } else null
                    }
                    
                    return@withContext FileMetadata(
                        uri = uri,
                        size = size,
                        isPending = isPending,
                        displayName = displayName,
                        mimeType = mimeType,
                        lastModified = lastModified,
                        relativePath = relativePath
                    )
                }
            }
            
            return@withContext null
        } catch (e: Exception) {
            LogUtil.error(uri, "INDIVIDUAL_METADATA", "Ошибка при получении метаданных", e)
            return@withContext null
        }
    }

    /**
     * Извлекает относительный путь из полного пути (для Android < Q)
     */
    private fun extractRelativePathFromData(dataPath: String?): String? {
        if (dataPath.isNullOrEmpty()) return null
        
        return try {
            // Пытаемся найти стандартные папки Android
            val patterns = listOf(
                "/storage/emulated/0/",
                "/storage/self/primary/",
                "/sdcard/"
            )
            
            for (pattern in patterns) {
                if (dataPath.startsWith(pattern)) {
                    val relativePath = dataPath.removePrefix(pattern)
                    val lastSlash = relativePath.lastIndexOf('/')
                    return if (lastSlash > 0) {
                        relativePath.substring(0, lastSlash + 1)
                    } else {
                        ""
                    }
                }
            }
            
            // Fallback: берем путь после последнего /Android/
            val androidIndex = dataPath.lastIndexOf("/Android/")
            if (androidIndex != -1) {
                val afterAndroid = dataPath.substring(androidIndex + "/Android/".length)
                val lastSlash = afterAndroid.lastIndexOf('/')
                return if (lastSlash > 0) {
                    afterAndroid.substring(0, lastSlash + 1)
                } else {
                    ""
                }
            }
            
            null
        } catch (e: Exception) {
            LogUtil.processDebug("Ошибка при извлечении относительного пути из: $dataPath")
            null
        }
    }

    /**
     * Кэширование метаданных файла
     */
    private fun cacheMetadata(uri: Uri, metadata: FileMetadata) {
        if (metadataCache.size < MAX_CACHE_SIZE) {
            metadataCache[uri.toString()] = CachedFileMetadata(metadata)
        }
    }

    /**
     * Очистка кэша при превышении максимального размера
     */
    private fun cleanupCacheIfNeeded() {
        if (metadataCache.size >= MAX_CACHE_SIZE) {
            val expiredKeys = mutableListOf<String>()
            
            // Сначала удаляем устаревшие записи
            metadataCache.forEach { (key, cached) ->
                if (cached.isExpired()) {
                    expiredKeys.add(key)
                }
            }
            
            expiredKeys.forEach { key ->
                metadataCache.remove(key)
            }
            
            // Если все еще превышен лимит, удаляем старейшие записи
            if (metadataCache.size >= MAX_CACHE_SIZE) {
                val sortedEntries = metadataCache.entries.sortedBy { it.value.timestamp }
                val toRemove = sortedEntries.take(MAX_CACHE_SIZE / 4) // Удаляем 25% старейших записей
                
                toRemove.forEach { entry ->
                    metadataCache.remove(entry.key)
                }
            }
            
            LogUtil.processDebug("Очистка кэша метаданных: удалено ${expiredKeys.size} устаревших записей, размер кэша: ${metadataCache.size}")
        }
    }

    /**
     * Очистка всего кэша метаданных
     */
    fun clearCache() {
        metadataCache.clear()
        LogUtil.processDebug("Кэш метаданных очищен")
    }

    /**
     * Получение статистики кэша
     */
    fun getCacheStats(): String {
        val total = metadataCache.size
        val expired = metadataCache.values.count { it.isExpired() }
        return "Кэш метаданных: $total записей, $expired устарели"
    }

    /**
     * Безопасное чтение Long из курсора с проверкой на null
     */
    private fun Cursor.getLongOrNull(index: Int): Long? {
        return if (index != -1 && !isNull(index)) {
            try {
                getLong(index)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    /**
     * Безопасное чтение String из курсора с проверкой на null
     */
    private fun Cursor.getStringOrNull(index: Int): String? {
        return if (index != -1 && !isNull(index)) {
            try {
                getString(index)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
}
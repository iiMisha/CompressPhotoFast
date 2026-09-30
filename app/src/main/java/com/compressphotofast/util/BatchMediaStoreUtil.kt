package com.compressphotofast.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
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
            if (e is kotlinx.coroutines.CancellationException) throw e
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
                METADATA_PROJECTION,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    if (cursor.isNull(idColumn)) continue
                    val id = cursor.getLong(idColumn).toString()

                    // Находим соответствующий URI
                    val matchingUri = uris.find { uri -> uri.lastPathSegment == id }
                    if (matchingUri != null) {
                        result[matchingUri] = cursor.toFileMetadata(matchingUri)
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
            if (e is kotlinx.coroutines.CancellationException) throw e
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
            context.contentResolver.query(uri, METADATA_PROJECTION, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return@withContext cursor.toFileMetadata(uri)
                }
            }
            
            return@withContext null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LogUtil.error(uri, "INDIVIDUAL_METADATA", "Ошибка при получении метаданных", e)
            return@withContext null
        }
    }

    private val METADATA_PROJECTION = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.SIZE,
        MediaStore.Images.Media.IS_PENDING,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.RELATIVE_PATH
    )

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index != -1 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index != -1 && !isNull(index)) getLong(index) else null
    }

    /**
     * Читает [FileMetadata] из текущей строки курсора, полученного с [METADATA_PROJECTION]
     */
    private fun Cursor.toFileMetadata(uri: Uri): FileMetadata = FileMetadata(
        uri = uri,
        size = longOrNull(MediaStore.MediaColumns.SIZE) ?: -1L,
        isPending = longOrNull(MediaStore.Images.Media.IS_PENDING) == 1L,
        displayName = stringOrNull(MediaStore.Images.Media.DISPLAY_NAME),
        mimeType = stringOrNull(MediaStore.Images.Media.MIME_TYPE),
        lastModified = longOrNull(MediaStore.MediaColumns.DATE_MODIFIED)?.secondsToMillis() ?: 0L,
        relativePath = stringOrNull(MediaStore.MediaColumns.RELATIVE_PATH)
    )

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
     * Получение статистики кэша
     */
    fun getCacheStats(): String {
        val total = metadataCache.size
        val expired = metadataCache.values.count { it.isExpired() }
        return "Кэш метаданных: $total записей, $expired устарели"
    }
}
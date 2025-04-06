package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.Collections
import java.util.HashMap
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.UriUtil

/**
 * Утилитарный класс для работы с информацией о файлах
 */
object FileInfoUtil {

    // Кэш информации о файлах, чтобы не запрашивать повторно
    private val fileInfoCache = Collections.synchronizedMap(HashMap<String, FileInfo>())
    private const val fileInfoCacheExpiration = 5 * 60 * 1000L // 5 минут
    
    // Хранит базовую информацию о файле
    data class FileInfo(
        val id: Long,
        val name: String,
        val size: Long,
        val date: Long,
        val mime: String,
        val path: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Получает информацию о файле и логирует её
     * @param context Контекст приложения
     * @param uri URI файла
     * @return FileInfo или null в случае ошибки
     */
    fun getFileInfo(context: Context, uri: Uri): FileInfo? {
        try {
            val uriString = uri.toString()
            
            // Проверяем кэш
            val cachedInfo = fileInfoCache[uriString]
            if (cachedInfo != null && (System.currentTimeMillis() - cachedInfo.timestamp < fileInfoCacheExpiration)) {
                // Используем кэшированную информацию
                LogUtil.debug("FileInfoUtil", "Информация о файле (из кэша): ID=${cachedInfo.id}, Имя=${cachedInfo.name}, Размер=${cachedInfo.size}, Дата=${cachedInfo.date}, MIME=${cachedInfo.mime}, URI=$uri")
                LogUtil.debug("FileInfoUtil", "Путь к файлу: ${cachedInfo.path}")
                return cachedInfo
            }
            
            // Если нет в кэше или кэш устарел, запрашиваем информацию
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE
            )
            
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val dateIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    
                    val id = if (idIndex != -1) cursor.getLong(idIndex) else -1
                    val name = if (nameIndex != -1) cursor.getString(nameIndex) else "unknown"
                    val size = if (sizeIndex != -1) cursor.getLong(sizeIndex) else -1
                    val date = if (dateIndex != -1) cursor.getLong(dateIndex) else -1
                    val mime = if (mimeIndex != -1) cursor.getString(mimeIndex) else "unknown"
                    
                    // Получаем путь к файлу
                    val path = UriUtil.getFilePathFromUri(context, uri) ?: "неизвестно"
                    
                    // Кэшируем полученную информацию
                    val fileInfo = FileInfo(id, name, size, date, mime, path)
                    fileInfoCache[uriString] = fileInfo
                    
                    LogUtil.debug("FileInfoUtil", "Информация о файле: ID=$id, Имя=$name, Размер=$size, Дата=$date, MIME=$mime, URI=$uri")
                    LogUtil.debug("FileInfoUtil", "Путь к файлу: $path")
                    
                    return fileInfo
                }
            }
            
            return null
        } catch (e: Exception) {
            LogUtil.error(uri, "Получение информации о файле", e)
            return null
        }
    }
    
    /**
     * Очищает старые записи в кэше
     */
    fun cleanupCache() {
        val currentTime = System.currentTimeMillis()
        val entriesToRemove = fileInfoCache.entries
            .filter { (currentTime - it.value.timestamp) > fileInfoCacheExpiration }
            .map { it.key }
            
        entriesToRemove.forEach { key -> fileInfoCache.remove(key) }
        LogUtil.processDebug("FileInfoUtil: Очищено ${entriesToRemove.size} устаревших записей в кэше")
    }
} 
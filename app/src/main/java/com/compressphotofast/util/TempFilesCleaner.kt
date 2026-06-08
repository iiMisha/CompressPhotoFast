package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import com.compressphotofast.util.LogUtil
import java.io.File
import java.io.FileOutputStream

/**
 * Утилитарный класс для очистки временных файлов
 */
object TempFilesCleaner {
    
    /**
     * Очистка старых временных файлов
     */
    fun cleanupTempFiles(context: Context, currentProcessingUri: String? = null) {
        try {
            val cacheDir = context.cacheDir
            val currentTime = System.currentTimeMillis()
            
            // Получаем список файлов, которые сейчас используются в текущем процессе
            val currentTempFile = currentProcessingUri?.let { uri ->
                Uri.parse(uri).lastPathSegment
            }
            
            // Синхронизируем доступ к файловой системе
            synchronized(this) {
                // Получаем все временные файлы в кэше
                val files = cacheDir.listFiles { file ->
                    // Проверяем все временные файлы, созданные приложением
                    val isTempFile = file.name.startsWith("temp_image_") || 
                                    file.name.startsWith("input_") ||
                                    file.name.endsWith(".jpg") ||
                                    file.name.endsWith(".jpeg")
                    
                    // Проверяем, что файл достаточно старый
                    val isOld = (currentTime - file.lastModified() > Constants.TEMP_FILE_MAX_AGE)
                    
                    // Не удаляем файл, если он используется в текущем процессе
                    val isCurrentlyInUse = currentTempFile != null && 
                                         file.name.contains(currentTempFile as CharSequence)
                    
                    isTempFile && (isOld || (!isCurrentlyInUse && file.length() == 0L && currentTime - file.lastModified() > 60_000L))
                }
                
                var deletedCount = 0
                var totalSize = 0L
                
                files?.forEach { file ->
                    // Дополнительная проверка перед удалением
                    if (file.exists() && !isFileInUse(file)) {
                        val fileSize = file.length()
                        
                        try {
                            // Удаляем файл (delete атомарен на Linux)
                            if (file.delete()) {
                                totalSize += fileSize
                                deletedCount++
                                LogUtil.processDebug("Удален временный файл: ${file.absolutePath}, размер: ${fileSize/1024}KB")
                            } else {
                                LogUtil.processWarning("Не удалось удалить временный файл: ${file.absolutePath}")
                                // Помечаем файл для удаления при выходе
                                file.deleteOnExit()
                            }
                        } catch (e: Exception) {
                            LogUtil.errorWithMessageAndException("FILE_CLEANUP", "Ошибка при удалении файла: ${file.absolutePath}", e)
                            // Помечаем файл для удаления при выходе
                            file.deleteOnExit()
                        }
                    }
                }
                
                if (deletedCount > 0) {
                    LogUtil.processDebug("Очистка временных файлов завершена, удалено файлов: $deletedCount, освобождено: ${totalSize/1024}KB")
                }
            }
        } catch (e: Exception) {
            LogUtil.errorWithException("FILE_CLEANUP", e)
        }
    }
    
    /**
     * Проверяет, используется ли файл в данный момент
     */
    private fun isFileInUse(file: File): Boolean {
        return try {
            // Проверяем через tryLock без модификации файла
            java.io.RandomAccessFile(file, "rw").use { raf ->
                val lock = raf.channel.tryLock()
                if (lock != null) {
                    lock.release()
                    false // Файл не используется
                } else {
                    true // Файл заблокирован другим процессом
                }
            }
        } catch (e: Exception) {
            LogUtil.processDebug("Файл используется другим процессом: ${file.absolutePath}")
            true // Файл используется
        }
    }
} 
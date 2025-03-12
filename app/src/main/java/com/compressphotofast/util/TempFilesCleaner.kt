package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import timber.log.Timber
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
                    
                    // Проверяем, что файл достаточно старый или имеет нулевой размер
                    val isOldOrEmpty = (currentTime - file.lastModified() > Constants.TEMP_FILE_MAX_AGE) || 
                                       file.length() == 0L
                    
                    // Не удаляем файл, если он используется в текущем процессе
                    val isCurrentlyInUse = currentTempFile != null && 
                                         file.name.contains(currentTempFile as CharSequence)
                    
                    isTempFile && (isOldOrEmpty || !isCurrentlyInUse)
                }
                
                var deletedCount = 0
                var totalSize = 0L
                
                files?.forEach { file ->
                    // Дополнительная проверка перед удалением
                    if (file.exists() && !isFileInUse(file)) {
                        val fileSize = file.length()
                        
                        try {
                            // Пробуем сначала очистить содержимое файла
                            if (fileSize > 0) {
                                FileOutputStream(file).use { it.channel.truncate(0) }
                            }
                            
                            // Теперь пытаемся удалить файл
                            if (file.delete()) {
                                totalSize += fileSize
                                deletedCount++
                                Timber.d("Удален временный файл: ${file.absolutePath}, размер: ${fileSize/1024}KB")
                            } else {
                                Timber.w("Не удалось удалить временный файл: ${file.absolutePath}")
                                // Помечаем файл для удаления при выходе
                                file.deleteOnExit()
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Ошибка при удалении файла: ${file.absolutePath}")
                            // Помечаем файл для удаления при выходе
                            file.deleteOnExit()
                        }
                    }
                }
                
                if (deletedCount > 0) {
                    Timber.d("Очистка временных файлов завершена, удалено файлов: $deletedCount, освобождено: ${totalSize/1024}KB")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при очистке временных файлов")
        }
    }
    
    /**
     * Проверяет, используется ли файл в данный момент
     */
    private fun isFileInUse(file: File): Boolean {
        return try {
            synchronized(this) {
                // Пробуем открыть файл для записи - если не получается, значит файл используется
                val channel = FileOutputStream(file, true).channel
                channel.close()
                false // Файл не используется
            }
        } catch (e: Exception) {
            Timber.d("Файл используется другим процессом: ${file.absolutePath}")
            true // Файл используется
        }
    }
} 
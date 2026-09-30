package com.compressphotofast.data

import android.content.Context
import android.net.Uri
import com.compressphotofast.util.LogUtil
import com.compressphotofast.util.Constants

/**
 * Утилитарный класс для очистки временных файлов
 */
object TempFilesCleaner {

    private val TEMP_FILE_PREFIXES = listOf(
        "temp_image_",
        "input_",
        "stream_cache",
        "exif_backup_",
        "exif_marker_backup_",
        "replace_backup_",
        "compressed_"
    )

    /**
     * Очистка старых временных файлов.
     *
     * ИНВАРИАНТ БЕЗОПАСНОСТИ: backup-файлы, зарегистрированные в [BackupRegistry],
     * не удаляются независимо от возраста — они нужны
     * [BackupRecoveryHelper.recoverPendingBackups] для восстановления оригинала.
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
                // Временные файлы живут в cacheDir, backup-файлы оригиналов —
                // в noBackupFilesDir (см. BackupRegistry.getBackupDir)
                val scanDirs = listOfNotNull(
                    cacheDir,
                    BackupRegistry.getBackupDir(context).takeIf { it != cacheDir }
                )
                val registeredBackups = BackupRegistry.getRegisteredBackupPaths(context)

                // Получаем все временные файлы
                val files = scanDirs.flatMap { dir ->
                    dir.listFiles { file ->
                        val name = file.name
                        val isTempFile = TEMP_FILE_PREFIXES.any { name.startsWith(it) }

                        // Проверяем, что файл достаточно старый
                        val isOld = (currentTime - file.lastModified() > Constants.TEMP_FILE_MAX_AGE)

                        // Не удаляем файл, если он используется в текущем процессе
                        val isCurrentlyInUse = currentTempFile != null &&
                                             file.name.contains(currentTempFile as CharSequence)

                        val isRegisteredBackup = file.absolutePath in registeredBackups

                        isTempFile && isOld && !isCurrentlyInUse && !isRegisteredBackup
                    }?.toList() ?: emptyList()
                }

                var deletedCount = 0
                var totalSize = 0L

                files.forEach { file ->
                    // Дополнительная проверка перед удалением
                    if (file.exists()) {
                        val fileSize = file.length()

                        try {
                            // Удаляем файл (delete атомарен на Linux)
                            if (file.delete()) {
                                totalSize += fileSize
                                deletedCount++
                                LogUtil.processDebug("Удален временный файл: ${file.absolutePath}, размер: ${fileSize/1024}KB")
                            } else {
                                LogUtil.processWarning("Не удалось удалить временный файл: ${file.absolutePath}")
                            }
                        } catch (e: Exception) {
                            LogUtil.errorWithMessageAndException("FILE_CLEANUP", "Ошибка при удалении файла: ${file.absolutePath}", e)
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
}

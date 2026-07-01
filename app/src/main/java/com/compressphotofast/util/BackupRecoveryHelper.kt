package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Восстановление файлов из orphan backup'ов, оставшихся после непредвиденного
 * закрытия приложения (kill, OOM, crash, перезагрузка).
 *
 * Вызывается при старте приложения из [com.compressphotofast.CompressPhotoApp.onCreate].
 * Логика:
 *  1. Читает [BackupRegistry.getPendingBackups].
 *  2. Для каждой записи проверяет существование и целостность URI.
 *     - Если URI повреждён/недоступен И backup существует → восстановление.
 *     - Если backup-файл отсутствует → запись удаляется из реестра.
 *  3. Очищает обработанные записи и orphan backup-файлы.
 */
object BackupRecoveryHelper {

    /**
     * Точка входа: проверяет и восстанавливает все ожидающие backup'ы.
     * Безопасна для вызова в фоновом потоке при старте приложения.
     */
    suspend fun recoverPendingBackups(context: Context) = withContext(Dispatchers.IO) {
        val pending = BackupRegistry.getPendingBackups(context)
        if (pending.isEmpty()) return@withContext

        LogUtil.processInfo("BackupRecovery: обнаружено ${pending.size} ожидающих backup-записей")

        var recovered = 0
        var cleaned = 0
        val processed = mutableSetOf<String>()

        for ((uriString, backupPath) in pending) {
            val uri = try { Uri.parse(uriString) } catch (e: Exception) { null }
            val backupFile = File(backupPath)

            // Если backup-файл не существует — запись неактуальна, удаляем из реестра
            if (!backupFile.exists() || backupFile.length() == 0L) {
                LogUtil.processDebug("BackupRecovery: backup-файл отсутствует для $uriString, удаляем запись")
                processed.add(uriString)
                cleaned++
                continue
            }

            if (uri == null) {
                processed.add(uriString)
                cleaned++
                continue
            }

            try {
                val exists = UriUtil.isUriExistsSuspend(context, uri)
                val isValid = exists && ImageCompressionUtil.verifyImageIntegrity(context, uri)

                if (!isValid) {
                    // URI повреждён или отсутствует → пытаемся восстановить из backup
                    LogUtil.warning(uri, "BackupRecovery", "URI повреждён/недоступен, восстанавливаем из backup: $backupPath")
                    val restored = MediaStoreUtil.restoreFromBackup(context, uri, backupFile)
                    if (restored) {
                        LogUtil.processInfo("BackupRecovery: ✅ файл восстановлен из backup: $uri")
                        recovered++
                    } else {
                        LogUtil.error(uri, "BackupRecovery", "Не удалось восстановить файл из backup: $backupPath")
                    }
                } else {
                    LogUtil.processDebug("BackupRecovery: URI валиден, backup не требуется: $uri")
                }
                processed.add(uriString)
            } catch (e: Exception) {
                LogUtil.error(uri, "BackupRecovery", "Ошибка при восстановлении из backup", e)
                processed.add(uriString)
            } finally {
                // Backup-файл больше не нужен в любом случае
                try {
                    if (backupFile.exists()) backupFile.delete()
                } catch (e: Exception) {
                    LogUtil.error(uri, "BackupRecovery", "Не удалось удалить backup-файл: $backupPath", e)
                }
            }
        }

        // Очищаем обработанные записи из реестра
        processed.forEach { uriString ->
            try {
                val uri = Uri.parse(uriString)
                BackupRegistry.clearBackup(context, uri)
            } catch (e: Exception) {
                // Игнорируем
            }
        }

        LogUtil.processInfo(
            "BackupRecovery: завершено — восстановлено $recovered, очищено $cleaned, обработано ${processed.size}"
        )
    }
}

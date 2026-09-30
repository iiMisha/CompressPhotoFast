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
 * Вызывается при старте приложения из [com.compressphotofast.CompressPhotoApp]
 * ДО очистки временных файлов.
 *
 * Запись в реестре существует только на время рискованной операции, поэтому само
 * её наличие означает, что операция не завершилась: файл восстанавливается из
 * backup безусловно (проверка заголовка не отличает обрезанный файл от целого).
 * Если для URI несколько backup'ов, используется самый ранний — состояние до
 * начала всей операции. Если restore невозможен, backup сохраняется в галерею
 * как отдельный файл. Backup удаляется только после успешного restore/сохранения;
 * иначе он остаётся в реестре до следующего старта.
 */
object BackupRecoveryHelper {

    private const val RECOVERED_DIRECTORY = "${Constants.APP_DIRECTORY}/Recovered"

    /** Запас на грубую точность mtime файловой системы. */
    private const val MTIME_MARGIN_MS = 2_000L

    /**
     * @param processStartMs время старта текущего процесса: backup'ы, созданные
     *        после него, принадлежат живым операциям этого процесса и не трогаются
     */
    suspend fun recoverPendingBackups(
        context: Context,
        processStartMs: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val cutoff = processStartMs - MTIME_MARGIN_MS
        val pending = BackupRegistry.getPendingBackups(context)
            .filterKeys { path -> File(path).let { !it.exists() || it.lastModified() < cutoff } }
        if (pending.isEmpty()) return@withContext

        LogUtil.processInfo("BackupRecovery: обнаружено ${pending.size} ожидающих backup-записей")

        var recovered = 0
        var savedAsCopy = 0
        var kept = 0

        val byUri = pending.entries.groupBy({ it.value }, { File(it.key) })
        for ((uriString, backups) in byUri) {
            val (valid, missing) = backups.partition { it.exists() && it.length() > 0L }
            missing.forEach { BackupRegistry.releaseBackup(context, it) }
            if (valid.isEmpty()) continue

            // Самый ранний backup — состояние до начала всей операции
            val primary = valid.minByOrNull { it.lastModified() }!!
            val uri = try { Uri.parse(uriString) } catch (e: Exception) { null }

            val handled = when {
                uri != null && UriUtil.isUriExistsSuspend(context, uri) &&
                    BackupRegistry.rollback(context, uri, primary) -> {
                    LogUtil.processInfo("BackupRecovery: ✅ файл восстановлен из backup: $uri")
                    UriUtil.invalidateUriExistsCache(uri)
                    recovered++
                    true
                }
                saveBackupAsNewFile(context, primary) -> {
                    LogUtil.warning(uri, "BackupRecovery", "Restore невозможен, backup сохранён как отдельный файл")
                    savedAsCopy++
                    true
                }
                else -> false
            }

            if (handled) {
                valid.forEach { BackupRegistry.releaseBackup(context, it) }
            } else {
                LogUtil.error(uri, "BackupRecovery", "Не удалось восстановить файл — backup сохранён до следующего запуска: ${primary.absolutePath}")
                kept++
            }
        }

        LogUtil.processInfo(
            "BackupRecovery: завершено — восстановлено $recovered, сохранено копией $savedAsCopy, отложено $kept"
        )
    }

    /**
     * Сохраняет backup как новый файл в галерее (Pictures/CompressPhotoFast/Recovered).
     */
    private suspend fun saveBackupAsNewFile(context: Context, backupFile: File): Boolean {
        val target = MediaStoreUtil.createMediaStoreEntry(
            context,
            "recovered_${System.currentTimeMillis()}.jpg",
            RECOVERED_DIRECTORY,
            "image/jpeg"
        ) ?: return false
        return try {
            val written = context.contentResolver.openFileDescriptor(target, "w")?.use { pfd ->
                java.io.FileOutputStream(pfd.fileDescriptor).use { output ->
                    val count = backupFile.inputStream().use { it.copyTo(output) }
                    output.flush()
                    pfd.fileDescriptor.sync()
                    count
                }
            } ?: -1L
            if (written != backupFile.length()) {
                context.contentResolver.delete(target, null, null)
                return false
            }
            MediaStoreUtil.clearIsPendingFlag(context, target)
            true
        } catch (e: Exception) {
            LogUtil.error(target, "BackupRecovery", "Не удалось сохранить backup в галерею", e)
            try { context.contentResolver.delete(target, null, null) } catch (_: Exception) {}
            false
        }
    }
}

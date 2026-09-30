package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Персистентный реестр backup-файлов для восстановления после непредвиденного
 * закрытия приложения (kill, OOM, crash, перезагрузка).
 *
 * Хранит mapping `backupFilePath -> uriString` в SharedPreferences: ключ — путь
 * backup'а, поэтому для одного URI могут одновременно существовать несколько
 * backup'ов (например, backup оригинала на время replace-записи и вложенные
 * backup'ы EXIF-фазы).
 *
 * Жизненный цикл: [createBackup] (копия + fsync + сверка длины + регистрация через
 * commit) → рискованная операция → [releaseBackup] только при успехе операции
 * или успешном [restoreFromBackup]. При неудачном restore backup остаётся в реестре,
 * и [BackupRecoveryHelper.recoverPendingBackups] обработает его при следующем старте.
 *
 * Все backup-файлы хранятся в [getBackupDir] (noBackupFilesDir — защищено от
 * очистки системой под дисковым давлением, в отличие от cacheDir).
 * [TempFilesCleaner] не удаляет файлы, зарегистрированные в реестре.
 */
object BackupRegistry {

    /**
     * Каталог для backup-файлов оригиналов.
     *
     * ИНВАРИАНТ БЕЗОПАСНОСТИ: backup оригинала не должен храниться в cacheDir —
     * система может очистить кэш под дисковым давлением именно в тот момент,
     * когда backup нужен для восстановления. Используется noBackupFilesDir:
     * не выгружается в облачный backup и не очищается автоматически.
     */
    fun getBackupDir(context: Context): File {
        val dir = context.noBackupFilesDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Создаёт durable backup содержимого [uri] и регистрирует его в реестре.
     *
     * Копия сбрасывается на носитель (fsync), её длина сверяется с фактическим
     * размером источника; регистрация выполняется синхронно (commit), чтобы
     * запись пережила SIGKILL сразу после возврата.
     *
     * @return backup-файл или null, если надёжную копию создать не удалось
     *         (в этом случае рискованную операцию выполнять нельзя)
     */
    fun createBackup(context: Context, uri: Uri, prefix: String): File? {
        val backupFile = File(getBackupDir(context), "${prefix}${uri.hashCode()}_${System.nanoTime()}.jpg")
        return try {
            val sourceSize = FileIoUtil.getDescriptorSize(context, uri)
            val input = FileIoUtil.openOriginalInputStream(context, uri)
                ?: throw IOException("Не удалось открыть поток источника")
            val copied = input.use { FileIoUtil.writeDurably(it, backupFile) }

            if (copied <= 0L || (sourceSize != null && copied != sourceSize)) {
                LogUtil.warning(uri, "Backup", "Неполная копия: скопировано $copied из $sourceSize байт")
                backupFile.delete()
                return null
            }
            if (!registerBackup(context, uri, backupFile)) {
                backupFile.delete()
                return null
            }
            backupFile
        } catch (e: Exception) {
            LogUtil.warning(uri, "Backup", "Не удалось создать backup: ${e.message}")
            backupFile.delete()
            null
        }
    }

    /**
     * Восстанавливает [uri] из backup через ParcelFileDescriptor "rwt" с fsync.
     *
     * @return true, только если скопирован весь backup и данные сброшены на носитель
     */
    fun restoreFromBackup(context: Context, uri: Uri, backupFile: File): Boolean {
        return try {
            if (!backupFile.exists() || backupFile.length() <= 0L) {
                LogUtil.error(uri, "Restore", "Backup отсутствует или пуст: ${backupFile.absolutePath}")
                return false
            }
            val expected = backupFile.length()
            val pfd = context.contentResolver.openFileDescriptor(uri, "rwt")
                ?: throw IOException("Не удалось открыть FileDescriptor для восстановления")
            val written = pfd.use {
                FileOutputStream(it.fileDescriptor).use { output ->
                    val count = backupFile.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                    it.fileDescriptor.sync()
                    count
                }
            }
            if (written != expected) {
                LogUtil.error(uri, "Restore", "Восстановлено $written из $expected байт")
                return false
            }
            true
        } catch (e: Exception) {
            LogUtil.error(uri, "Restore", "Критическая ошибка: не удалось восстановить файл из backup", e)
            false
        }
    }

    /**
     * Проверяет, совпадает ли текущее содержимое [uri] с backup побайтно.
     * Используется, чтобы не выполнять truncating-restore файла, который
     * неудачная операция фактически не изменила.
     */
    fun isContentIdentical(context: Context, uri: Uri, backupFile: File): Boolean {
        return try {
            val size = FileIoUtil.getDescriptorSize(context, uri) ?: return false
            if (size != backupFile.length()) return false
            val input = FileIoUtil.openOriginalInputStream(context, uri) ?: return false
            input.use { current -> backupFile.inputStream().use { backup -> streamsEqual(current, backup) } }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Откатывает [uri] к backup, если содержимое отличается.
     * @return true, если файл совпадает с backup (изначально или после restore)
     */
    fun rollback(context: Context, uri: Uri, backupFile: File): Boolean {
        if (isContentIdentical(context, uri, backupFile)) return true
        return restoreFromBackup(context, uri, backupFile) &&
            isContentIdentical(context, uri, backupFile)
    }

    /**
     * Удаляет backup-файл и его запись в реестре. Вызывать только после успешного
     * завершения операции или успешного отката — иначе страховка будет потеряна.
     */
    fun releaseBackup(context: Context, backupFile: File) {
        unregister(context, backupFile.absolutePath)
        if (backupFile.exists() && !backupFile.delete()) {
            LogUtil.warning(null, "Backup", "Не удалось удалить backup: ${backupFile.absolutePath}")
        }
    }

    /**
     * Пути backup'ов, зарегистрированных для [uri]. Снимок до операции передаётся
     * в [releaseBackupsCreatedSince], чтобы после неё освободить вложенные backup'ы.
     */
    fun getRegisteredPathsFor(context: Context, uri: Uri): Set<String> {
        val uriString = uri.toString()
        return getPendingBackups(context).filterValues { it == uriString }.keys
    }

    /**
     * Освобождает backup'ы [uri], зарегистрированные после снимка [existingBefore]
     * (вложенные backup'ы EXIF-фазы, чей собственный откат не удался).
     *
     * ИНВАРИАНТ БЕЗОПАСНОСТИ: вызывать только когда внешняя операция завершилась
     * успехом или успешным откатом (или файл удалён) — иначе recovery при следующем
     * старте безусловно запишет устаревшую промежуточную копию поверх файла.
     */
    fun releaseBackupsCreatedSince(context: Context, uri: Uri, existingBefore: Set<String>) {
        (getRegisteredPathsFor(context, uri) - existingBefore).forEach { path ->
            LogUtil.warning(uri, "Backup", "Освобождаем вложенный backup после завершения операции: $path")
            releaseBackup(context, File(path))
        }
    }

    /**
     * Регистрирует backup-файл для URI синхронно (commit).
     * @return true, если запись сохранена на диск
     */
    fun registerBackup(context: Context, uri: Uri, backupFile: File): Boolean {
        return try {
            synchronized(this) {
                val prefs = context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
                val current = readMap(prefs.getString(Constants.PREF_PENDING_BACKUPS, null))
                current[backupFile.absolutePath] = uri.toString()
                prefs.edit()
                    .putString(Constants.PREF_PENDING_BACKUPS, writeMap(current))
                    .commit()
            }
        } catch (e: Exception) {
            LogUtil.errorWithException("BackupRegistry.register", e)
            false
        }
    }

    /**
     * Удаляет запись о backup по пути файла.
     */
    fun unregister(context: Context, backupPath: String) {
        try {
            synchronized(this) {
                val prefs = context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
                val current = readMap(prefs.getString(Constants.PREF_PENDING_BACKUPS, null))
                if (current.remove(backupPath) != null) {
                    prefs.edit()
                        .putString(Constants.PREF_PENDING_BACKUPS, writeMap(current))
                        .commit()
                }
            }
        } catch (e: Exception) {
            LogUtil.errorWithException("BackupRegistry.unregister", e)
        }
    }

    /**
     * Возвращает все ожидающие backup-записи: `backupFilePath -> uriString`.
     */
    fun getPendingBackups(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
        return readMap(prefs.getString(Constants.PREF_PENDING_BACKUPS, null))
    }

    /**
     * Абсолютные пути всех зарегистрированных backup-файлов.
     */
    fun getRegisteredBackupPaths(context: Context): Set<String> = getPendingBackups(context).keys

    private fun streamsEqual(a: InputStream, b: InputStream): Boolean {
        val bufA = ByteArray(64 * 1024)
        val bufB = ByteArray(64 * 1024)
        while (true) {
            val readA = a.readNBytesCompat(bufA)
            val readB = b.readNBytesCompat(bufB)
            if (readA != readB) return false
            if (readA <= 0) return true
            for (i in 0 until readA) {
                if (bufA[i] != bufB[i]) return false
            }
        }
    }

    /** Читает до заполнения буфера или EOF (InputStream.readNBytes доступен только с API 33). */
    private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    /**
     * Читает реестр. Поддерживает legacy-формат `uriString -> backupPath`
     * (ключ — URI, а не абсолютный путь), конвертируя его в `backupPath -> uriString`.
     */
    private fun readMap(json: String?): MutableMap<String, String> {
        if (json.isNullOrEmpty()) return mutableMapOf()
        return try {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, String>()
            obj.keys().forEach { key ->
                val value = obj.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                if (!key.startsWith("/")) {
                    result[value] = key
                } else {
                    result[key] = value
                }
            }
            result
        } catch (e: Exception) {
            LogUtil.errorWithException("BackupRegistry.readMap", e)
            mutableMapOf()
        }
    }

    private fun writeMap(map: Map<String, String>): String {
        return try {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            obj.toString()
        } catch (e: Exception) {
            LogUtil.errorWithException("BackupRegistry.writeMap", e)
            "{}"
        }
    }
}

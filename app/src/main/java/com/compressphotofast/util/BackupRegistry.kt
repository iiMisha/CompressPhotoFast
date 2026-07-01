package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * Персистентный реестр backup-файлов для восстановления после непредвиденного
 * закрытия приложения (kill, OOM, crash, перезагрузка).
 *
 * Хранит mapping `uriString -> backupFileName` в SharedPreferences.
 * Перед рискованной файловой операцией (перезапись оригинала, saveAttributes)
 * вызывается [registerBackup]; при успешном завершении — [clearBackup].
 * При старте приложения [BackupRecoveryHelper.recoverPendingBackups] проверяет
 * целостность URI и восстанавливает файл из backup при повреждении.
 *
 * Все backup-файлы хранятся в [Context.cacheDir] и дополнительно вычищаются
 * [TempFilesCleaner] по истечении [Constants.TEMP_FILE_MAX_AGE].
 */
object BackupRegistry {

    /**
     * Регистрирует backup-файл для URI.
     * Должен вызываться ПОСЛЕ создания backup-файла и ДО рискованной операции.
     */
    fun registerBackup(context: Context, uri: Uri, backupFile: File) {
        try {
            val prefs = context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
            val current = readMap(prefs.getString(Constants.PREF_PENDING_BACKUPS, null))
            current[uri.toString()] = backupFile.absolutePath
            prefs.edit()
                .putString(Constants.PREF_PENDING_BACKUPS, writeMap(current))
                .apply()
        } catch (e: Exception) {
            LogUtil.errorWithException("BackupRegistry.register", e)
        }
    }

    /**
     * Удаляет запись о backup для URI.
     * Должен вызываться после успешного завершения операции (в finally).
     */
    fun clearBackup(context: Context, uri: Uri) {
        try {
            val prefs = context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
            val current = readMap(prefs.getString(Constants.PREF_PENDING_BACKUPS, null))
            if (current.remove(uri.toString()) != null) {
                prefs.edit()
                    .putString(Constants.PREF_PENDING_BACKUPS, writeMap(current))
                    .apply()
            }
        } catch (e: Exception) {
            LogUtil.errorWithException("BackupRegistry.clear", e)
        }
    }

    /**
     * Возвращает все ожидающие backup-записи: `uriString -> backupFilePath`.
     */
    fun getPendingBackups(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
        return readMap(prefs.getString(Constants.PREF_PENDING_BACKUPS, null))
    }

    /**
     * Полностью очищает реестр (после обработки всех записей при старте).
     */
    fun clearAll(context: Context) {
        try {
            val prefs = context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(Constants.PREF_PENDING_BACKUPS).apply()
        } catch (e: Exception) {
            LogUtil.errorWithException("BackupRegistry.clearAll", e)
        }
    }

    private fun readMap(json: String?): MutableMap<String, String> {
        if (json.isNullOrEmpty()) return mutableMapOf()
        return try {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, String>()
            obj.keys().forEach { key ->
                obj.optString(key).takeIf { it.isNotEmpty() }?.let { result[key] = it }
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

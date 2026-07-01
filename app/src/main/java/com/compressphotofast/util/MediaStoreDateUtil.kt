package com.compressphotofast.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Утилитарный класс для работы с датами в MediaStore.
 *
 * Решает проблему игнорирования обновлений DATE_MODIFIED на Samsung устройствах
 * путём установки DATE_ADDED при создании и многоуровневого fallback при восстановлении.
 */
/** Конвертирует значение DATE_MODIFIED (секунды) в миллисекунды */
fun Long.secondsToMillis(): Long = this * 1000

object MediaStoreDateUtil {

    /**
     * Установить DATE_ADDED и DATE_MODIFIED при создании записи в MediaStore
     *
     * @param values ContentValues для вставки
     * @param timestampMillis Время в миллисекундах
     */
    fun setCreationTimestamp(values: ContentValues, timestampMillis: Long) {
        val timestampInSeconds = timestampMillis / 1000
        values.put(MediaStore.Images.Media.DATE_ADDED, timestampInSeconds)
        values.put(MediaStore.Images.Media.DATE_MODIFIED, timestampInSeconds)
        // Логирование убрано для стабильной работы в unit-тестах
    }

    /**
     * Восстановить DATE_MODIFIED с многоуровневым fallback
     *
     * Последовательность попыток:
     * 1. DocumentFile.setLastModified() - восстановление через файловый путь
     * 2. Warning - DATE_ADDED уже установлен, этого достаточно для работы приложения
     *
     * Примечание: MediaStore.update() для DATE_MODIFIED не используется, т.к. на
     * Android 10+ это поле доступно только для чтения и update() всегда возвращает 0.
     *
     * @param context Контекст приложения
     * @param uri URI файла в MediaStore
     * @param timestampMillis Исходное время в миллисекундах
     * @return true если успешно восстановлен, false если все методы неудачны
     */
    suspend fun restoreModifiedDate(
        context: Context,
        uri: Uri,
        timestampMillis: Long
    ): Boolean = withContext(Dispatchers.IO) {
        // Метод 1: File.setLastModified() через файловый путь, полученный из URI
        try {
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            if (documentFile != null && documentFile.exists()) {
                // DocumentFile не имеет метода setLastModified(), используем альтернативный подход
                // Пытаемся получить путь к файлу через UriUtil
                val filePath = UriUtil.getFilePathFromUri(context, uri)
                if (filePath != null) {
                    val file = java.io.File(filePath)
                    if (file.exists()) {
                        val success = file.setLastModified(timestampMillis)
                        if (success) {
                            LogUtil.processInfo("DATE_MODIFIED восстановлен через File.setLastModified(): $timestampMillis")
                            return@withContext true
                        }
                        LogUtil.processWarning("File.setLastModified() вернул false для ${file.absolutePath}")
                    }
                } else {
                    LogUtil.processWarning("Не удалось получить путь к файлу для URI: $uri")
                }
            } else {
                LogUtil.processWarning("DocumentFile не существует для URI: $uri")
            }
        } catch (e: Exception) {
            LogUtil.error(uri, "DocumentFile / File.setLastModified()", e)
        }

        // Метод 2: Логируем warning и продолжаем
        // DATE_ADDED уже установлен при создании, это достаточно для работы приложения
        LogUtil.processWarning(
            "Не удалось восстановить DATE_MODIFIED для $uri. " +
            "DATE_ADDED установлен, приложение продолжит работу нормально."
        )
        return@withContext false
    }
}

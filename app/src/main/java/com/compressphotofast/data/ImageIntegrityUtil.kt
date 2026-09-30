package com.compressphotofast.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.compressphotofast.util.LogUtil

/**
 * Проверка целостности изображения по заголовку (inJustDecodeBounds).
 * Листовой модуль: не зависит от других util-объектов.
 */
object ImageIntegrityUtil {

    /**
     * @return true, если изображение открывается и имеет положительные размеры
     */
    suspend fun verifyImageIntegrity(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    LogUtil.error(uri, "Верификация", "Файл повреждён или не является изображением: ${options.outWidth}x${options.outHeight}")
                    return@withContext false
                }
            } ?: run {
                LogUtil.error(uri, "Верификация", "Не удалось открыть поток для проверки целостности")
                return@withContext false
            }
            return@withContext true
        } catch (e: Exception) {
            LogUtil.error(uri, "Верификация", "Ошибка при проверке целостности файла", e)
            return@withContext false
        }
    }
}

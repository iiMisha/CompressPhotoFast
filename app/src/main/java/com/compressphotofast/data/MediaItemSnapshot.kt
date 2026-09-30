package com.compressphotofast.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.compressphotofast.util.LogUtil

/**
 * Метаданные элемента MediaStore, полученные одним запросом.
 *
 * Заменяет серию отдельных запросов (существование, pending, имя, MIME, размер, путь)
 * в проверке и сжатии одного URI. Снимок отражает состояние на момент запроса:
 * TOCTOU-проверки перед перезаписью/удалением делают собственный свежий запрос.
 * Поля null, если провайдер не отдал колонку (Picker, сторонние провайдеры).
 */
data class MediaItemSnapshot(
    val uri: Uri,
    val displayName: String?,
    val relativePath: String?,
    val mimeType: String?,
    val size: Long?,
    val isPendingRaw: Boolean,
    val dateAddedSec: Long?,
    val dateModifiedSec: Long?
) {
    /** Эквивалент [UriUtil.isUriExistsSuspend]: строка найдена, не pending, размер не отрицательный. */
    val exists: Boolean get() = !isPendingRaw && (size ?: 0L) >= 0L

    /** Эквивалент [UriUtil.getFilePathFromUri] для MediaStore-строки. */
    val filePath: String?
        get() = if (!displayName.isNullOrEmpty() && !relativePath.isNullOrEmpty()) {
            "${Environment.getExternalStorageDirectory()}/$relativePath$displayName"
        } else null

    /** RELATIVE_PATH без завершающего слеша, как в [UriUtil.getDirectoryFromUri]. */
    val directory: String?
        get() = relativePath?.takeIf { it.isNotEmpty() }?.removeSuffix("/")

    val dateModifiedMs: Long? get() = dateModifiedSec?.takeIf { it > 0L }?.secondsToMillis()

    /** Индексы колонок курсора; -1 — колонки нет. */
    class Columns(cursor: Cursor) {
        val name = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val relativePath = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        val mime = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        val size = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val pending = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
        val dateAdded = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        val dateModified = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
    }

    companion object {
        /** Полная проекция: скан галереи запрашивает её, чтобы проверка не ходила в MediaStore повторно. */
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        private val OPENABLE_PROJECTION = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)

        /** Строит снимок из текущей строки курсора. */
        fun fromCursor(cursor: Cursor, uri: Uri, columns: Columns = Columns(cursor), mimeFallback: String? = null): MediaItemSnapshot {
            fun string(index: Int) = if (index != -1 && !cursor.isNull(index)) cursor.getString(index)?.takeIf { it.isNotEmpty() } else null
            fun long(index: Int) = if (index != -1 && !cursor.isNull(index)) cursor.getLong(index) else null
            return MediaItemSnapshot(
                uri = uri,
                displayName = string(columns.name),
                relativePath = string(columns.relativePath),
                mimeType = string(columns.mime) ?: mimeFallback,
                size = long(columns.size),
                isPendingRaw = long(columns.pending) == 1L,
                dateAddedSec = long(columns.dateAdded),
                dateModifiedSec = long(columns.dateModified)
            )
        }

        /**
         * Один запрос метаданных [uri]: полная проекция MediaStore, при сбое — запрос по ID
         * (Android 11), затем [OpenableColumns] для не-MediaStore провайдеров.
         * @return null, если элемент не найден или недоступен
         */
        suspend fun query(context: Context, uri: Uri): MediaItemSnapshot? = withContext(Dispatchers.IO) {
            val effectiveUri = UriUtil.convertMediaDocumentsUri(uri) ?: uri
            val resolver = context.contentResolver

            fun Cursor?.readFirst(): MediaItemSnapshot? = this?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val mimeFallback = if (cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE) == -1) {
                    UriUtil.getMimeType(context, uri)
                } else null
                fromCursor(cursor, uri, mimeFallback = mimeFallback)
            }

            try {
                resolver.query(effectiveUri, PROJECTION, null, null, null).readFirst()?.let { return@withContext it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.processDebug("MediaItemSnapshot: полный запрос не удался для $uri: ${e.message}")
            }
            try {
                UriUtil.queryMediaStoreWithIdFallbackApi30(context, effectiveUri, PROJECTION).readFirst()
                    ?.let { return@withContext it }
            } catch (e: Exception) {
                LogUtil.processDebug("MediaItemSnapshot: запрос по ID не удался для $uri: ${e.message}")
            }
            if (effectiveUri.authority == MediaStore.AUTHORITY && effectiveUri.pathSegments.firstOrNull() != "picker") {
                // Строка MediaStore не найдена (удалена или чужой pending) — OpenableColumns её не вернут
                return@withContext null
            }
            try {
                resolver.query(effectiveUri, OPENABLE_PROJECTION, null, null, null).readFirst()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.warning(uri, "MediaItemSnapshot", "Не удалось получить метаданные: ${e.message}")
                null
            }
        }
    }
}

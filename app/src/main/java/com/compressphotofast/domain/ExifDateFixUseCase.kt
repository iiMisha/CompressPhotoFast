package com.compressphotofast.domain

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.compressphotofast.data.CompressionMarker
import com.compressphotofast.data.ExifUtil
import com.compressphotofast.data.FileNameDateParser
import com.compressphotofast.data.MediaItemSnapshot
import com.compressphotofast.data.UriUtil
import com.compressphotofast.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Восстановление EXIF-дат файлам без единой даты (меню «три точки»).
 * Источники по приоритету: имя файла (настоящая дата съёмки → 3 тега,
 * включая `DateTimeOriginal`), затем timestamp маркера сжатия (дата сжатия,
 * не съёмки → 2 тега без `DateTimeOriginal`). mtime не используется.
 *
 * Чтение EXIF и запись дат сериализуются с сжатием через [CompressionExecutionGate]
 * на каждый файл (сжатие работает с EXIF под тем же gate) — отдельная проверка
 * «файл сейчас сжимается» не нужна.
 */
@Singleton
class ExifDateFixUseCase @Inject constructor(
    private val executionGate: CompressionExecutionGate
) {

    data class Outcome(
        val scanned: Int,
        val fixed: Int,
        val noSource: Int,
        val failed: Int
    )

    /**
     * Прогресс прохода для UI (диалог прогресса). [total] == 0 — идёт скан
     * галереи (indeterminate). null — проход не выполняется.
     */
    data class Progress(
        val scanned: Int,
        val total: Int,
        val fixed: Int,
        val noSource: Int,
        val failed: Int
    )

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /**
     * Снапшот итога на момент завершения: при отмене coroutine из-за сброса
     * [progress] в finally воркер уже не прочитает StateFlow — частичный итог
     * забирается отсюда.
     */
    @Volatile
    var lastOutcome: Progress? = null
        private set

    /**
     * Номер активного прохода. REPLACE-политика воркера может запустить новый
     * проход до фактической остановки старого (отмена срабатывает на ближайшей
     * suspend-точке); без генерационной проверки старый проход в `finally`
     * обнулял бы прогресс нового — диалог «мигал» нулём.
     */
    private val passGeneration = AtomicInteger(0)

    /** Для тестов: фактический номер активного/последнего прохода. */
    internal val currentGeneration: Int get() = passGeneration.get()

    suspend fun fixAll(context: Context): Outcome = withContext(Dispatchers.IO) {
        val generation = passGeneration.incrementAndGet()
        try {
            fixAllInner(context, generation)
        } finally {
            if (passGeneration.get() == generation) _progress.value = null
        }
    }

    private suspend fun fixAllInner(context: Context, generation: Int): Outcome {
        val uris = mutableListOf<Uri>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaItemSnapshot.PROJECTION,
                "${MediaStore.MediaColumns.IS_PENDING}=0",
                null,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val columns = MediaItemSnapshot.Columns(cursor)
                while (cursor.moveToNext()) {
                    uris += ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn)
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.errorWithException("Восстановление дат EXIF", e)
        }

        var scanned = 0
        var fixed = 0
        var noSource = 0
        var failed = 0

        _progress.value = Progress(0, uris.size, 0, 0, 0)

        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            scanned++
            _progress.value = Progress(scanned, uris.size, fixed, noSource, failed)
            try {
                executionGate.acquire()
                try {
                    // Свежий снимок под gate: TOCTOU между сканом и записью
                    val snapshot = MediaItemSnapshot.query(context, uri)
                    if (snapshot == null || !snapshot.exists) continue
                    if (UriUtil.isPendingEffective(context, snapshot)) continue

                    // HEIC: ExifInterface.saveAttributes не поддерживается — пропуск
                    if (UriUtil.isHeicMimeType(snapshot.mimeType)) {
                        LogUtil.processDebug("Восстановление дат: HEIC пропущен: $uri")
                        continue
                    }

                    val exif = ExifUtil.getExifInterface(context, uri)
                    if (exif == null) {
                        LogUtil.processDebug("Восстановление дат: EXIF не прочитан: $uri")
                        failed++
                        continue
                    }
                    if (ExifUtil.hasAnyDate(exif)) continue

                    val fromFileName = FileNameDateParser.parseDateMs(snapshot.displayName)
                    val marker = CompressionMarker.parse(exif.getAttribute(ExifInterface.TAG_USER_COMMENT))
                    val dateSourceMs: Long
                    val includeDateTimeOriginal: Boolean
                    if (fromFileName != null) {
                        dateSourceMs = fromFileName
                        includeDateTimeOriginal = true
                    } else if (marker != null && marker.timestamp > 0L) {
                        dateSourceMs = marker.timestamp
                        includeDateTimeOriginal = false
                    } else {
                        noSource++
                        continue
                    }

                    if (ExifUtil.writeMissingDates(context, uri, dateSourceMs, includeDateTimeOriginal)) {
                        fixed++
                    } else {
                        failed++
                    }
                } finally {
                    executionGate.release()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (passGeneration.get() == generation) {
                    lastOutcome = Progress(scanned, uris.size, fixed, noSource, failed)
                }
                throw e
            } catch (e: Exception) {
                LogUtil.error(uri, "Восстановление дат EXIF", e)
                failed++
            }
        }

        val outcome = Outcome(scanned, fixed, noSource, failed)
        lastOutcome = Progress(scanned, uris.size, fixed, noSource, failed)
        LogUtil.processInfo("Восстановление дат EXIF: сканов=${outcome.scanned}, записано=${outcome.fixed}, " +
            "без источников=${outcome.noSource}, ошибок=${outcome.failed}")
        return outcome
    }
}

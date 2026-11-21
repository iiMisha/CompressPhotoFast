package com.compressphotofast.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.compressphotofast.domain.CompressionEvents
import com.compressphotofast.domain.ExifDateFixUseCase
import com.compressphotofast.util.LogUtil
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/** Проход восстановления EXIF-дат по всей галерее (меню «три точки»). */
@HiltWorker
class ExifDateFixWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val exifDateFixUseCase: ExifDateFixUseCase,
    private val compressionEvents: CompressionEvents
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        try {
            val outcome = exifDateFixUseCase.fixAll(applicationContext)
            compressionEvents.emit(
                CompressionEvents.Event.DateFixCompleted(
                    outcome.fixed, outcome.noSource, outcome.failed
                )
            )
            return Result.success()
        } catch (e: CancellationException) {
            // Отмена (кнопка «Отмена» или REPLACE/system): частичный итог —
            // снапшот, захваченный use case до сброса progress в finally.
            val partial = exifDateFixUseCase.lastOutcome
            compressionEvents.emit(
                CompressionEvents.Event.DateFixCompleted(
                    fixed = partial?.fixed ?: 0,
                    noSource = partial?.noSource ?: 0,
                    failed = partial?.failed ?: 0,
                    cancelled = true
                )
            )
            throw e
        }
    }

    companion object {
        internal const val WORK_NAME = "exif_date_fix"

        /**
         * REPLACE: повторный запуск после завершения (KEEP заблокировал бы его);
         * операция идемпотентна — файлы с датами пропускаются.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<ExifDateFixWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            LogUtil.processDebug("ExifDateFixWorker поставлен в очередь")
        }

        /** Прерывает проход: текущий файл завершается, следующий не начинается. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            LogUtil.processDebug("ExifDateFixWorker: запрошена отмена")
        }
    }
}

package com.compressphotofast.domain

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import com.compressphotofast.util.Constants
import com.compressphotofast.data.DailyCompressionStats
import com.compressphotofast.data.ExifUtil
import com.compressphotofast.data.FileOperationsUtil
import com.compressphotofast.data.MediaItemSnapshot
import com.compressphotofast.util.LogUtil
import com.compressphotofast.data.MediaStoreUtil
import com.compressphotofast.data.SettingsManager
import com.compressphotofast.data.StatsTracker
import com.compressphotofast.data.UriUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.compressphotofast.data.UriProcessingTracker

/**
 * Сжатие одного изображения: EXIF → тестовое сжатие → сохранение → верификация →
 * удаление оригинала в режиме замены (или маркер для неэффективного сжатия).
 *
 * Не знает о WorkManager и UI: вызывающая сторона отвечает за блокировку URI,
 * [com.compressphotofast.domain.CompressionExecutionGate], foreground-уведомления,
 * retry-политику и показ результата. Исключения (IO, память, безопасность)
 * пробрасываются — их классифицирует вызывающая сторона.
 */
class CompressImageUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uriProcessingTracker: UriProcessingTracker,
    private val settingsManager: SettingsManager,
    private val compressionEvents: CompressionEvents
) {

    data class Params(val quality: Int, val maxResolution: Int)

    sealed interface Outcome {
        /**
         * Файл сжат и сохранён (оригинал удалён, перезаписан или ждёт подтверждения удаления).
         * [dailyStats] — обновлённая дневная статистика, null если её не удалось сохранить.
         */
        data class Compressed(
            val fileName: String,
            val originalSize: Long,
            val compressedSize: Long,
            val sizeReduction: Float,
            val dailyStats: DailyCompressionStats?
        ) : Outcome
        /** Сжатая копия сохранена, но оригинал удалить не удалось; в оригинал записан маркер. */
        data class CompressedOriginalKept(val dailyStats: DailyCompressionStats?) : Outcome
        /** Сжатие неэффективно: файл не изменён, записан маркер. */
        data class SkippedInefficient(val fileName: String, val originalSize: Long, val estimatedCompressedSize: Long, val estimatedReduction: Float) : Outcome
        /** Размер файла вне допустимого диапазона. */
        data object SkippedInvalidSize : Outcome
        data class Failed(val saveFailure: MediaStoreUtil.SaveFailure? = null) : Outcome
    }

    /**
     * @param snapshot метаданные MediaStore, полученные вызывающей стороной после входа
     *        в gate (null — запросить по отдельности)
     * @param preloadedExif теги исходника из [ExifUtil.readSourceExif] (null — прочитать здесь)
     */
    suspend operator fun invoke(
        imageUri: Uri,
        params: Params,
        snapshot: MediaItemSnapshot? = null,
        preloadedExif: Map<String, Any>? = null
    ): Outcome {
        // 1. Загружаем EXIF данные в память перед любыми операциями с файлом
        val exifDataMemory = preloadedExif ?: try {
            ExifUtil.readExifDataToMemory(context, imageUri)
        } catch (e: java.io.FileNotFoundException) {
            LogUtil.error(imageUri, "Чтение EXIF", "Файл не найден при чтении EXIF: ${e.message}")
            uriProcessingTracker.markUriUnavailable(imageUri)
            return Outcome.Failed()
        } catch (e: java.io.IOException) {
            LogUtil.error(imageUri, "Чтение EXIF", "Ошибка ввода/вывода при чтении EXIF: ${e.message}")
            uriProcessingTracker.markUriUnavailable(imageUri)
            return Outcome.Failed()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.error(imageUri, "Чтение EXIF", "Не удалось прочитать EXIF-данные, отмена задачи.", e)
            return Outcome.Failed()
        }

        val sourceSize = snapshot?.size?.takeIf { it > 0L } ?: try {
            UriUtil.getFileSize(context, imageUri)
        } catch (e: java.io.FileNotFoundException) {
            LogUtil.error(imageUri, "Проверка размера", "Файл не найден при получении размера: ${e.message}")
            return Outcome.Failed()
        }

        if (!FileOperationsUtil.isFileSizeValid(sourceSize)) {
            LogUtil.uriInfo(imageUri, "Размер файла невалидный: $sourceSize, пропускаем")
            return Outcome.SkippedInvalidSize
        }

        // Тестовое сжатие для оценки эффективности; его artifact переиспользуется при сохранении
        val testResult = ImageCompressionUtil.testCompression(
            context,
            imageUri,
            sourceSize,
            params.quality,
            keepStream = true,
            maxDimension = params.maxResolution,
            knownMimeType = snapshot?.mimeType
        )
        if (testResult == null) {
            LogUtil.error(imageUri, "Тестовое сжатие", "Ошибка при тестовом сжатии")
            return Outcome.Failed()
        }

        try {
            val stats = testResult.stats
            LogUtil.imageCompression(
                imageUri,
                "${stats.originalSize / 1024}KB → ${stats.compressedSize / 1024}KB (-${stats.sizeReduction}%)"
            )
            // Ветки вынесены в отдельные suspend-функции для уменьшения state machine
            // (ART-предупреждение `Method exceeds compiler instruction limit`).
            return if (testResult.isEfficient()) {
                saveCompressed(imageUri, params, exifDataMemory, testResult, sourceSize, snapshot)
            } else {
                markInefficient(imageUri, testResult, sourceSize, snapshot)
            }
        } finally {
            testResult.deleteArtifact()
        }
    }

    /**
     * Сохраняет сжатый поток в MediaStore, верифицирует результат и в режиме
     * замены удаляет оригинал.
     */
    private suspend fun saveCompressed(
        imageUri: Uri,
        params: Params,
        exifDataMemory: Map<String, Any>,
        testResult: ImageCompressionUtil.CompressionTestResult,
        sourceSize: Long,
        snapshot: MediaItemSnapshot?
    ): Outcome {
        val fileName = snapshot?.displayName ?: UriUtil.getFileNameFromUri(context, imageUri)
        if (fileName.isNullOrEmpty()) {
            LogUtil.error(imageUri, "Имя файла", "Не удалось получить имя файла")
            return Outcome.Failed()
        }

        val isReplaceMode = FileOperationsUtil.isSaveModeReplace(context)
        val finalFileName = FileOperationsUtil.createCompressedFileName(context, fileName)
        // В режиме замены сохраняем в той же директории, иначе — в директории приложения
        val directory = if (isReplaceMode) {
            snapshot?.directory ?: UriUtil.getDirectoryFromUri(context, imageUri)
        } else Constants.APP_DIRECTORY

        val compressedImageFile = testResult.compressedFile
        if (compressedImageFile == null || !compressedImageFile.exists()) {
            LogUtil.error(imageUri, "Сжатие", "Сжатый artifact утерян (null или удалён)")
            return Outcome.Failed()
        }

        val saveResult = MediaStoreUtil.saveCompressedImageFromFile(
            context = context,
            compressedFile = compressedImageFile,
            fileName = finalFileName,
            directory = directory,
            originalUri = imageUri,
            quality = params.quality,
            exifDataMemory = exifDataMemory,
            originalFileSize = sourceSize,
            pixelsTransformed = testResult.pixelsOriented
        )
        val savedUri = when (saveResult) {
            is MediaStoreUtil.SaveResult.Failed -> {
                LogUtil.error(imageUri, "Сохранение", "Не удалось сохранить сжатое изображение: ${saveResult.reason}")
                return Outcome.Failed(saveResult.reason)
            }
            is MediaStoreUtil.SaveResult.Saved -> saveResult.uri
        }

        // Перезапись на месте определяется по ID MediaStore: savedUri строится как
        // content://media/external/..., а imageUri может прийти через external_primary.
        val overwrittenInPlace = savedUri == imageUri || MediaStoreUtil.isSameMediaItem(savedUri, imageUri)

        uriProcessingTracker.setIgnorePeriod(savedUri)
        if (!overwrittenInPlace) {
            uriProcessingTracker.setIgnorePeriod(imageUri)
        }

        // Целостность сохранённого файла (в т.ч. после записи EXIF) уже проверена
        // в MediaStoreUtil: при провале новая копия удалена и вернулся Failed.

        // Защита от потери правок: если оригинал изменился за время сжатия,
        // сжатая копия устарела — удаляем её, оригинал не трогаем.
        if (isReplaceMode && !overwrittenInPlace &&
            !MediaStoreUtil.isFileUnchanged(context, imageUri, sourceSize)
        ) {
            LogUtil.warning(imageUri, "Replace", "Оригинал изменён во время сжатия — сжатая копия удалена, оригинал сохранён")
            try {
                context.contentResolver.delete(savedUri, null, null)
            } catch (e: Exception) {
                LogUtil.error(savedUri, "Replace", "Не удалось удалить устаревшую сжатую копию", e)
            }
            return Outcome.Failed()
        }

        // Учитываем только сохранённый и проверенный файл, независимо от удаления оригинала.
        val compressedSize = UriUtil.getFileSize(context, savedUri) ?: testResult.stats.compressedSize
        val dailyStats = StatsTracker.recordSuccessfulCompression(context, sourceSize, compressedSize)

        if (isReplaceMode && !overwrittenInPlace && !deleteOriginal(imageUri)) {
            // Сжатый файл уже сохранён: маркер в неудалённом оригинале исключает повторную обработку
            try {
                if (ExifUtil.writeSkipMarker(context, imageUri, 99, sourceSize)) {
                    LogUtil.processInfo("Маркер сжатия записан в неудалённый оригинал для предотвращения повторной обработки")
                } else {
                    LogUtil.warning(imageUri, "Маркер", "Маркер в неудалённый оригинал не записан")
                }
            } catch (e: Exception) {
                LogUtil.error(imageUri, "Маркер", "Не удалось записать маркер в оригинал", e)
            }
            return Outcome.CompressedOriginalKept(dailyStats)
        }

        val sizeReduction = if (sourceSize > 0 && compressedSize > 0) {
            FileOperationsUtil.computeSizeReductionPercent(sourceSize, compressedSize)
        } else testResult.stats.sizeReduction
        return Outcome.Compressed(finalFileName, sourceSize, compressedSize, sizeReduction, dailyStats)
    }

    /**
     * Удаляет оригинал после успешного сохранения сжатой копии. Если системе нужно
     * подтверждение пользователя, запрос откладывается до MainActivity.
     *
     * @return false, если оригинал остался на месте из-за ошибки
     */
    private suspend fun deleteOriginal(imageUri: Uri): Boolean {
        val errorMessage = try {
            if (!UriUtil.isUriExistsSuspend(context, imageUri)) {
                LogUtil.warning(imageUri, "Удаление", "Файл уже не существует к моменту удаления")
                return true
            }
            when (val deleteResult = FileOperationsUtil.deleteFile(context, imageUri, uriProcessingTracker, forceDelete = true)) {
                is IntentSender -> {
                    requestDeleteConfirmation(imageUri)
                    return true
                }
                true -> return true
                // Тихий отказ MediaStore: оригинал остался без маркера и был бы
                // пережат повторно, порождая дубликаты
                else -> "MediaStore не удалил файл ($deleteResult)"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.error(imageUri, "Удаление", "Ошибка при удалении оригинального файла", e)
            e.message
        }
        LogUtil.error(imageUri, "Удаление", "Не удалось удалить оригинальный файл после успешного сжатия. Причина: ${errorMessage ?: "неизвестно"}")
        return false
    }

    private fun requestDeleteConfirmation(uri: Uri) {
        // Сохраняем URI: MainActivity обработает его при следующем открытии,
        // а если она на экране — сразу по событию
        settingsManager.savePendingDeleteUri(uri.toString())
        compressionEvents.emit(CompressionEvents.Event.DeleteConfirmationRequired(uri))
    }

    /**
     * Сжатие неэффективно: файл не пережимается, в EXIF записывается маркер
     * (quality=99), чтобы исключить повторную обработку.
     */
    private suspend fun markInefficient(
        imageUri: Uri,
        testResult: ImageCompressionUtil.CompressionTestResult,
        sourceSize: Long,
        snapshot: MediaItemSnapshot?
    ): Outcome {
        if (!ExifUtil.writeSkipMarker(context, imageUri, 99, sourceSize)) {
            // Без маркера файл будет повторно протестирован при следующем скане
            LogUtil.warning(imageUri, "Маркер", "Маркер пропуска не записан")
        }
        return Outcome.SkippedInefficient(
            fileName = snapshot?.displayName ?: getFileNameSafely(imageUri),
            originalSize = sourceSize,
            estimatedCompressedSize = testResult.stats.compressedSize,
            estimatedReduction = testResult.stats.sizeReduction
        )
    }

    private fun getFileNameSafely(uri: Uri): String {
        val fileName = try {
            UriUtil.getFileNameFromUri(context, uri)
        } catch (e: Exception) {
            null
        }
        // Если имя файла не определено, генерируем временное имя на основе времени
        return if (fileName.isNullOrBlank() || fileName == "unknown") {
            "compressed_image_${System.currentTimeMillis()}.jpg"
        } else fileName
    }
}

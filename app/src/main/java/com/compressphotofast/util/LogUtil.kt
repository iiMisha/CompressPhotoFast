package com.compressphotofast.util

import android.net.Uri
import com.compressphotofast.BuildConfig
import timber.log.Timber

/**
 * Утилита для структурированного логирования в приложении.
 * Обеспечивает единый формат логов и категоризацию сообщений.
 */
object LogUtil {

    // Категории логов
    private const val CATEGORY_FILE = "ФАЙЛ"
    private const val CATEGORY_COMPRESSION = "СЖАТИЕ"
    private const val CATEGORY_PROCESS = "ПРОЦЕСС"
    private const val CATEGORY_EXIF = "EXIF"
    private const val CATEGORY_ERROR = "ОШИБКА"
    private const val CATEGORY_NOTIFICATION = "УВЕДОМЛЕНИЕ"
    private const val CATEGORY_URI = "URI"
    private const val CATEGORY_PERMISSIONS = "РАЗРЕШЕНИЯ"
    
    // Параметры дедупликации (thread-safe)
    @Volatile
    private var lastMessage: String? = null
    @Volatile
    private var lastLogTime: Long = 0L
    private const val DEDUPLICATION_INTERVAL_MS = 500L // 500ms для снижения шума
    
    // Флаг для управления уровнем детализации (настраивается через BuildConfig)
    private val SHOULD_LOG_DEBUG = BuildConfig.DEBUG_LOGGING

    /**
     * Проверяет, нужно ли выводить лог (дедупликация и уровень детализации)
     * Thread-safe для многопоточной обработки изображений
     */
    private fun shouldLog(fullMessage: String, isDebug: Boolean = false): Boolean {
        if (isDebug && !SHOULD_LOG_DEBUG) {
            return false
        }
        return synchronized(this) {
            val currentTime = System.currentTimeMillis()
            if (fullMessage == lastMessage && currentTime - lastLogTime < DEDUPLICATION_INTERVAL_MS) {
                return@synchronized false
            }
            lastMessage = fullMessage
            lastLogTime = currentTime
            true
        }
    }

    /**
     * Общее логирование (DEBUG)
     */
    fun log(tag: String, message: String) {
        if (shouldLog("[$tag] $message", isDebug = true)) {
            Timber.tag(tag).d(message)
        }
    }

    /**
     * Логирование операций с файлами (INFO)
     */
    fun fileOperation(uri: Uri, operation: String, details: String) {
        val fileId = getFileId(uri)
        val msg = "[$CATEGORY_FILE:$fileId] $operation: $details"
        if (shouldLog(msg)) {
            Timber.i(msg)
        }
    }

    /**
     * Логирование информации о файле (INFO)
     */
    fun fileInfo(uri: Uri, info: String) {
        val fileId = getFileId(uri)
        val msg = "[$CATEGORY_FILE:$fileId] $info"
        if (shouldLog(msg)) {
            Timber.i(msg)
        }
    }

    /**
     * Логирование информации о сжатии (INFO)
     */
    fun compression(uri: Uri, originalSize: Long, compressedSize: Long, reductionPercent: Int) {
        val fileId = getFileId(uri)
        val msg = "[$CATEGORY_COMPRESSION:$fileId] ${originalSize/1024}KB → ${compressedSize/1024}KB (-$reductionPercent%)"
        if (shouldLog(msg)) {
            Timber.i(msg)
        }
    }

    /**
     * Логирование информации о процессе (INFO)
     */
    fun processInfo(message: String) {
        val msg = "[$CATEGORY_PROCESS] $message"
        if (shouldLog(msg)) {
            Timber.i(msg)
        }
    }

    /**
     * Логирование пропуска изображения (INFO)
     */
    fun skipImage(uri: Uri, reason: String) {
        val fileId = getFileId(uri)
        val msg = "[$CATEGORY_PROCESS:$fileId] Пропуск: $reason"
        if (shouldLog(msg)) {
            Timber.i(msg)
        }
    }

    /**
     * Логирование EXIF операций (DEBUG)
     */
    fun exifOperation(uri: Uri, operation: String, success: Boolean, details: String = "") {
        val fileId = getFileId(uri)
        val status = if (success) "ОК" else "ОШИБКА"
        val msg = "[$CATEGORY_EXIF:$fileId] $operation: $status${if (details.isNotEmpty()) " ($details)" else ""}"
        if (shouldLog(msg, isDebug = true)) {
            Timber.d(msg)
        }
    }

    // ========== Логирование ошибок (без дедупликации для критичности) ==========
    
    fun errorSimple(operation: String, message: String) {
        Timber.e("[$CATEGORY_ERROR:$operation] $message")
    }

    fun errorWithException(operation: String, throwable: Throwable) {
        Timber.e(throwable, "[$CATEGORY_ERROR:$operation]")
    }

    fun errorWithMessageAndException(operation: String, message: String, throwable: Throwable) {
        Timber.e(throwable, "[$CATEGORY_ERROR:$operation] $message")
    }

    fun error(uri: Uri?, operation: String, message: String) {
        val fileId = uri?.let { getFileId(it) } ?: "null"
        Timber.e("[$CATEGORY_ERROR:$operation:$fileId] $message")
    }

    fun error(uri: Uri?, operation: String, throwable: Throwable) {
        val fileId = uri?.let { getFileId(it) } ?: "null"
        Timber.e(throwable, "[$CATEGORY_ERROR:$operation:$fileId]")
    }

    fun error(uri: Uri?, operation: String, message: String, throwable: Throwable) {
        val fileId = uri?.let { getFileId(it) } ?: "null"
        Timber.e(throwable, "[$CATEGORY_ERROR:$operation:$fileId] $message")
    }

    fun errorWithMessageAndException(uri: Uri?, operation: String, message: String, throwable: Throwable) {
        error(uri, operation, message, throwable)
    }

    // ========== Прочие логи ==========
    
    fun uriInfo(uri: Uri?, details: String) {
        val fileId = uri?.let { getFileId(it) } ?: "null"
        val msg = "[$CATEGORY_URI:$fileId] $details"
        if (shouldLog(msg)) {
            Timber.d(msg)
        }
    }

    fun notification(message: String) {
        val msg = "[$CATEGORY_NOTIFICATION] $message"
        if (shouldLog(msg)) {
            Timber.i(msg)
        }
    }

    fun debug(category: String, message: String) {
        val msg = "[$category] $message"
        if (shouldLog(msg, isDebug = true)) {
            Timber.d(msg)
        }
    }
    
    fun processDebug(message: String) {
        val msg = "[$CATEGORY_PROCESS] $message"
        if (shouldLog(msg, isDebug = true)) {
            Timber.d(msg)
        }
    }
    
    fun processWarning(message: String) {
        val msg = "[$CATEGORY_PROCESS] $message"
        if (shouldLog(msg)) {
            Timber.w(msg)
        }
    }
    
    fun imageCompression(uri: Uri, message: String) {
        val fileId = getFileId(uri)
        val msg = "[$CATEGORY_COMPRESSION:$fileId] $message"
        if (shouldLog(msg)) {
            Timber.i(msg)
        }
    }
    
    fun permissionsInfo(message: String) {
        val msg = "[$CATEGORY_PERMISSIONS] $message"
        if (shouldLog(msg)) {
            Timber.i(msg)
        }
    }
    
    fun permissionsWarning(message: String) {
        val msg = "[$CATEGORY_PERMISSIONS] $message"
        if (shouldLog(msg)) {
            Timber.w(msg)
        }
    }
    
    fun permissionsError(message: String, exception: Exception? = null) {
        val msg = "[$CATEGORY_PERMISSIONS] $message"
        if (exception != null) {
            Timber.e(exception, msg)
        } else {
            Timber.e(msg)
        }
    }

    /**
     * Подробное логирование только в DEBUG режиме (используйте для избыточной информации)
     */
    fun verbose(category: String, message: String) {
        if (BuildConfig.DEBUG) {
            Timber.v("[$category] $message")
        }
    }

    private fun getFileId(uri: Uri): String {
        return uri.lastPathSegment?.takeLast(4) ?: uri.toString().takeLast(4)
    }

    // ========== Batch логирование для массовых операций ==========

    private val batchCounters = mutableMapOf<String, Int>()
    private val batchLock = Any()

    /**
     * Начало batch операции
     */
    fun batchStart(operationId: String, totalCount: Int) {
        synchronized(batchLock) {
            batchCounters[operationId] = 0
        }
        val msg = "[BATCH:$operationId] Начало обработки $totalCount файлов"
        Timber.i(msg)
    }

    /**
     * Логирование прогресса batch операции (каждый 50-й файл для уменьшения шума)
     */
    fun batchProgress(operationId: String, fileName: String? = null) {
        val count = synchronized(batchLock) {
            val current = batchCounters.getOrDefault(operationId, 0) + 1
            batchCounters[operationId] = current
            current
        }
        // Логируем каждый 50-й файл и первый
        if (count == 1 || count % 50 == 0) {
            val fileInfo = fileName?.let { " - $it" } ?: ""
            val msg = "[BATCH:$operationId] Обработано $count файлов$fileInfo"
            Timber.i(msg)
        }
    }

    /**
     * Завершение batch операции с финальной статистикой
     */
    fun batchComplete(operationId: String, success: Int, failed: Int, skipped: Int = 0) {
        synchronized(batchLock) {
            batchCounters.remove(operationId)
        }
        val total = success + failed + skipped
        val msg = "[BATCH:$operationId] Завершено: $total файлов (✓$success ✗$failed →$skipped)"
        Timber.i(msg)
    }

    /**
     * Логирование batch ошибки (всегда логируется)
     */
    fun batchError(operationId: String, fileName: String, error: String) {
        val msg = "[BATCH:$operationId] Ошибка обработки '$fileName': $error"
        Timber.e(msg)
    }
} 
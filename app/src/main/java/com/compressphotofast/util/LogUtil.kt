package com.compressphotofast.util

import android.net.Uri
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
    private const val TAG = "LogUtil"
    
    /**
     * Общее логирование для различных категорий
     */
    fun log(tag: String, message: String) {
        Timber.tag(tag).d(message)
    }

    /**
     * Логирование операций с файлами (INFO)
     */
    fun fileOperation(uri: Uri, operation: String, details: String) {
        val fileId = getFileId(uri)
        Timber.i("[$CATEGORY_FILE:$fileId] $operation: $details")
    }

    /**
     * Логирование информации о файле (INFO)
     */
    fun fileInfo(uri: Uri, info: String) {
        val fileId = getFileId(uri)
        Timber.i("[$CATEGORY_FILE:$fileId] Инфо: $info")
    }

    /**
     * Логирование информации о сжатии (INFO)
     */
    fun compression(uri: Uri, originalSize: Long, compressedSize: Long, reductionPercent: Int) {
        val fileId = getFileId(uri)
        Timber.i("[$CATEGORY_COMPRESSION:$fileId] ${originalSize/1024}KB → ${compressedSize/1024}KB (-$reductionPercent%)")
    }

    /**
     * Логирование информации о процессе (INFO)
     */
    fun processInfo(message: String) {
        Timber.i("[$CATEGORY_PROCESS] $message")
    }

    /**
     * Логирование пропуска изображения (INFO)
     */
    fun skipImage(uri: Uri, reason: String) {
        val fileId = getFileId(uri)
        Timber.i("[$CATEGORY_PROCESS:$fileId] Пропуск: $reason")
    }

    /**
     * Логирование EXIF операций (DEBUG уровень)
     */
    fun exifOperation(uri: Uri, operation: String, success: Boolean, details: String = "") {
        val fileId = getFileId(uri)
        val status = if (success) "успешно" else "неудачно"
        Timber.d("[$CATEGORY_EXIF:$fileId] $operation: $status${if (details.isNotEmpty()) " ($details)" else ""}")
    }

    // ========== Логирование ошибок ==========
    
    /**
     * Базовая ошибка без URI (ERROR)
     */
    fun errorSimple(operation: String, message: String) {
        Timber.e("[$CATEGORY_ERROR:$operation] $message")
    }

    /**
     * Базовая ошибка с исключением, без URI (ERROR)
     */
    fun errorWithException(operation: String, throwable: Throwable) {
        Timber.e(throwable, "[$CATEGORY_ERROR:$operation]")
    }

    /**
     * Базовая ошибка с сообщением и исключением, без URI (ERROR)
     */
    fun errorWithMessageAndException(operation: String, message: String, throwable: Throwable) {
        Timber.e(throwable, "[$CATEGORY_ERROR:$operation] $message")
    }

    /**
     * Ошибка с URI (ERROR)
     */
    fun error(uri: Uri?, operation: String, message: String) {
        val fileId = if (uri != null) getFileId(uri) else "null"
        Timber.e("[$CATEGORY_ERROR:$operation:$fileId] $message")
    }

    /**
     * Ошибка с URI и исключением (ERROR)
     */
    fun error(uri: Uri?, operation: String, throwable: Throwable) {
        val fileId = if (uri != null) getFileId(uri) else "null"
        Timber.e(throwable, "[$CATEGORY_ERROR:$operation:$fileId]")
    }

    /**
     * Ошибка с URI, сообщением и исключением (ERROR)
     */
    fun error(uri: Uri?, operation: String, message: String, throwable: Throwable) {
        val fileId = if (uri != null) getFileId(uri) else "null"
        Timber.e(throwable, "[$CATEGORY_ERROR:$operation:$fileId] $message")
    }

    // ========== Логирование URI ==========
    
    /**
     * Логирование информации об URI (DEBUG)
     */
    fun uriInfo(uri: Uri?, details: String) {
        val fileId = if (uri != null) getFileId(uri) else "null"
        Timber.d("[$CATEGORY_URI:$fileId] $details")
    }

    /**
     * Логирование уведомлений (INFO)
     */
    fun notification(message: String) {
        Timber.i("[$CATEGORY_NOTIFICATION] $message")
    }

    /**
     * Логирование для отладки (только в режиме DEBUG)
     */
    fun debug(category: String, message: String) {
        Timber.d("[$category] $message")
    }
    
    /**
     * Логирование отладочной информации о процессе
     */
    fun processDebug(message: String) {
        Timber.d("[$CATEGORY_PROCESS] $message")
    }
    
    /**
     * Логирование предупреждений о процессе
     */
    fun processWarning(message: String) {
        Timber.w("[$CATEGORY_PROCESS] $message")
    }
    
    /**
     * Логирование информации о сжатии изображения
     */
    fun imageCompression(uri: Uri, message: String) {
        val fileId = getFileId(uri)
        Timber.i("[$CATEGORY_COMPRESSION:$fileId] $message")
    }
    
    /**
     * Создает короткий идентификатор файла на основе URI для идентификации в логах
     */
    private fun getFileId(uri: Uri): String {
        // Получаем последнюю часть пути или идентификатор
        return uri.lastPathSegment?.takeLast(4) ?: uri.toString().takeLast(4)
    }
} 
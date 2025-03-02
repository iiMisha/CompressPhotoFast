package com.compressphotofast.util

/**
 * Константы, используемые в приложении
 */
object Constants {
    // Общие константы
    const val APP_DIRECTORY = "CompressPhotoFast"
    const val COMPRESSED_SUFFIX = "_compressed"
    
    // Настройки сжатия
    const val DEFAULT_COMPRESSION_QUALITY = 80
    const val COMPRESSION_QUALITY_LOW = 50
    const val COMPRESSION_QUALITY_MEDIUM = 75
    const val COMPRESSION_QUALITY_HIGH = 90
    const val MAX_IMAGE_WIDTH = 1920
    const val MAX_IMAGE_HEIGHT = 1080
    
    // Ключи SharedPreferences
    const val PREF_FILE_NAME = "compress_photo_preferences"
    const val PREF_AUTO_COMPRESSION = "auto_compression_enabled"
    const val PREF_COMPRESSION_QUALITY = "compression_quality"
    
    // Ключи WorkManager
    const val WORK_TAG_COMPRESSION = "image_compression_work"
    const val WORK_INPUT_IMAGE_URI = "image_uri"
    
    // Уведомления
    const val NOTIFICATION_ID_BACKGROUND_SERVICE = 1001
    const val NOTIFICATION_ID_COMPRESSION = 1002
    
    // Интервалы
    const val BACKGROUND_SCAN_INTERVAL_MINUTES = 30L
} 
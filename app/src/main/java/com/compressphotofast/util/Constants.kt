package com.compressphotofast.util

/**
 * Константы, используемые в приложении
 */
object Constants {
    // Настройки приложения
    const val PREF_FILE_NAME = "app_settings"
    const val PREF_AUTO_COMPRESSION = "auto_compression"
    const val PREF_COMPRESSION_QUALITY = "compression_quality"
    const val PREF_SAVE_MODE = "save_mode"
    const val PREF_PENDING_DELETE_URIS = "pending_delete_uris"
    const val PREF_FIRST_LAUNCH = "first_launch"
    const val PREF_DELETE_PERMISSION_REQUESTED = "delete_permission_requested"
    
    // Значения по умолчанию
    const val DEFAULT_COMPRESSION_QUALITY = 80
    
    // Режимы сохранения
    const val SAVE_MODE_REPLACE = 1
    const val SAVE_MODE_SEPARATE = 2
    
    // Ограничения размера файлов
    const val MIN_FILE_SIZE = 50 * 1024L // 50 KB
    const val MAX_FILE_SIZE = 100 * 1024 * 1024L // 100 MB
    
    // Теги для WorkManager
    const val WORK_TAG_COMPRESSION = "compression_work"
    const val WORK_INPUT_IMAGE_URI = "image_uri"
    const val WORK_ERROR_MSG = "error_message"
    
    // Уведомления
    const val NOTIFICATION_CHANNEL_ID = "compression_channel"
    const val NOTIFICATION_ID_COMPRESSION = 1
    const val NOTIFICATION_ID_BACKGROUND_SERVICE = 2
    
    // Директории
    const val APP_DIRECTORY = "CompressPhotoFast"
    
    // Настройки сжатия
    const val COMPRESSION_QUALITY_LOW = 50
    const val COMPRESSION_QUALITY_MEDIUM = 75
    const val COMPRESSION_QUALITY_HIGH = 90
    const val MAX_IMAGE_WIDTH = 1920
    const val MAX_IMAGE_HEIGHT = 1080
    
    // Интервалы
    const val BACKGROUND_SCAN_INTERVAL_MINUTES = 30L
    
    // Настройки отслеживания файлов
    const val MAX_TRACKED_FILES = 1000
    const val PROCESSING_TIMEOUT_MS = 5 * 60 * 1000L // 5 минут
    const val PROCESSED_FILES_CLEANUP_INTERVAL = 24 * 60 * 60 * 1000L // 24 часа
    
    // Коды запросов
    const val REQUEST_CODE_DELETE_FILE = 12345
    const val REQUEST_CODE_DELETE_PERMISSION = 12346
    
    // Broadcast действия
    const val ACTION_REQUEST_DELETE_PERMISSION = "com.compressphotofast.action.REQUEST_DELETE_PERMISSION"
    const val EXTRA_URI = "uri"
    const val EXTRA_DELETE_INTENT_SENDER = "delete_intent_sender"
} 
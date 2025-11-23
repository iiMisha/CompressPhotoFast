package com.compressphotofast.util

/**
 * Константы, используемые в приложении
 */
object Constants {
    // Настройки приложения
    const val PREF_FILE_NAME = "compress_photo_prefs"
    const val PREF_AUTO_COMPRESSION = "auto_compression"
    const val PREF_COMPRESSION_QUALITY = "compression_quality"
    const val PREF_COMPRESSION_PRESET = "compression_preset"
    const val PREF_SAVE_MODE = "save_mode"
    const val PREF_PENDING_DELETE_URIS = "pending_delete_uris"
   const val PREF_PENDING_RENAME_URIS = "pending_rename_uris"
    const val PREF_FIRST_LAUNCH = "first_launch"
    const val PREF_DELETE_PERMISSION_REQUESTED = "delete_permission_requested"
    const val PREF_PERMISSION_SKIPPED = "permission_skipped"
    const val PREF_NOTIFICATION_PERMISSION_SKIPPED = "notification_permission_skipped"
    const val PREF_PERMISSION_REQUEST_COUNT = "permission_request_count"
    const val PREF_PROCESS_SCREENSHOTS = "process_screenshots"
    const val PREF_IGNORE_MESSENGER_PHOTOS = "ignore_messenger_photos"
    
    // Режимы сохранения
    const val SAVE_MODE_REPLACE = 1
    const val SAVE_MODE_SEPARATE = 2
    
    // Ограничения размера файлов
    const val MIN_FILE_SIZE = 50 * 1024L // 50 KB
    const val MAX_FILE_SIZE = 100 * 1024 * 1024L // 100 MB
    const val OPTIMUM_FILE_SIZE = 0.1 * 1024 * 1024L // 0.1 MB - файлы меньше этого размера считаются уже оптимизированными
    
    // Теги для WorkManager
    const val WORK_INPUT_IMAGE_URI = "image_uri"
    const val WORK_COMPRESSION_QUALITY = "compression_quality"
    const val WORK_BATCH_ID = "batch_id"
    
    // Уведомления
    const val NOTIFICATION_CHANNEL_ID = "compression_channel"
    const val NOTIFICATION_ID_COMPRESSION = 1
    const val NOTIFICATION_ID_BACKGROUND_SERVICE = 2
    const val NOTIFICATION_ID_COMPRESSION_RESULT = 4
    
    // Групповые уведомления
    const val NOTIFICATION_GROUP_COMPRESSION = "compression_group"
    const val NOTIFICATION_ID_COMPRESSION_SUMMARY = 10
    const val NOTIFICATION_ID_COMPRESSION_INDIVIDUAL_BASE = 100
    
    // Директории
    const val APP_DIRECTORY = "CompressPhotoFast"
    
    // Имена файлов
    const val COMPRESSED_FILE_SUFFIX = "_compressed"
    
    // Настройки сжатия
    const val COMPRESSION_QUALITY_LOW = 60
    const val COMPRESSION_QUALITY_MEDIUM = 70
    const val COMPRESSION_QUALITY_HIGH = 85
    const val MIN_COMPRESSION_SAVING_PERCENT = 40f // Минимальный процент экономии для продолжения сжатия
    
    // Интервалы
    const val BACKGROUND_SCAN_INTERVAL_MINUTES = 30L
    const val RECENT_SCAN_WINDOW_SECONDS = 5 * 60L // 5 минут в секундах
    const val CONTENT_OBSERVER_DELAY_SECONDS = 10L // 10 секунд задержки при обнаружении файла
    
    // Коды запросов
    const val REQUEST_CODE_DELETE_FILE = 12345
    const val REQUEST_CODE_DELETE_PERMISSION = 12346
    
    // BroadcastReceiver actions
    const val ACTION_PROCESS_IMAGE = "com.compressphotofast.PROCESS_IMAGE"
    const val ACTION_REQUEST_DELETE_PERMISSION = "com.compressphotofast.REQUEST_DELETE_PERMISSION"
   const val ACTION_REQUEST_RENAME_PERMISSION = "com.compressphotofast.REQUEST_RENAME_PERMISSION"
    const val ACTION_COMPRESSION_COMPLETED = "com.compressphotofast.ACTION_COMPRESSION_COMPLETED"
    const val ACTION_COMPRESSION_SKIPPED = "com.compressphotofast.ACTION_COMPRESSION_SKIPPED"
    const val ACTION_STOP_SERVICE = "com.compressphotofast.STOP_SERVICE"
    const val ACTION_ALREADY_OPTIMIZED = "com.compressphotofast.ACTION_ALREADY_OPTIMIZED"
    const val ACTION_COMPRESSION_PROGRESS = "com.compressphotofast.ACTION_COMPRESSION_PROGRESS"
    
    // Intent extras
    const val EXTRA_URI = "extra_uri"
    const val EXTRA_DELETE_INTENT_SENDER = "extra_delete_intent_sender"
   const val EXTRA_RENAME_INTENT_SENDER = "extra_rename_intent_sender"
    const val EXTRA_FILE_NAME = "file_name"
    const val EXTRA_ORIGINAL_SIZE = "original_size"
    const val EXTRA_COMPRESSED_SIZE = "compressed_size"
    const val EXTRA_REDUCTION_PERCENT = "extra_reduction_percent"
    const val EXTRA_SKIP_REASON = "skip_reason"
    const val EXTRA_PROGRESS = "progress"
    const val EXTRA_TOTAL = "total"
    const val EXTRA_BATCH_ID = "batch_id"
    
    // Временные файлы
    const val TEMP_FILE_MAX_AGE = 30 * 60 * 1000L // 30 минут
    
    /** Минимальный размер файла для обработки (100 КБ) */
    const val MIN_PROCESSABLE_FILE_SIZE = 100 * 1024L
    
    // Параметры для сжатия
    const val DEFAULT_COMPRESSION_QUALITY = 85
    const val MIN_COMPRESSION_QUALITY = 50
    const val MAX_COMPRESSION_QUALITY = 100
    
    // Обычно мы хотим сжать изображение как минимум на 20% от исходного размера
    const val MIN_COMPRESSION_RATIO = 0.8f
} 
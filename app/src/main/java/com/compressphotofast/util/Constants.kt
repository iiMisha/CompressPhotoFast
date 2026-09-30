package com.compressphotofast.util

/**
 * Константы, используемые в приложении
 */
object Constants {
    // Настройки приложения
    const val PREF_FILE_NAME = "compress_photo_prefs"
    const val PREF_AUTO_COMPRESSION = "auto_compression"
    const val PREF_COMPRESSION_QUALITY = "compression_quality"
    const val PREF_MAX_RESOLUTION = "max_resolution"
    const val PREF_SAVE_MODE = "save_mode"
    const val PREF_PENDING_DELETE_URIS = "pending_delete_uris"
    const val PREF_PERMISSION_SKIPPED = "permission_skipped"
    const val PREF_NOTIFICATION_PERMISSION_SKIPPED = "notification_permission_skipped"
    const val PREF_PROCESS_SCREENSHOTS = "process_screenshots"
    const val PREF_SHOW_COMPRESSION_TOAST = "show_compression_toast"
    const val PREF_LAST_SCAN_TIMESTAMP = "last_scan_timestamp"
    const val PREF_PENDING_BACKUPS = "pending_backups"
    const val PREF_BATTERY_EXEMPTION_REQUESTED = "battery_exemption_requested"

    // Локальная суточная статистика сжатия (не является пользовательской настройкой)
    const val DAILY_COMPRESSION_STATS_PREF_FILE = "daily_compression_stats"
    const val PREF_DAILY_STATS_EPOCH_DAY = "daily_stats_epoch_day"
    const val PREF_DAILY_STATS_SUCCESSFUL_COUNT = "daily_stats_successful_count"
    const val PREF_DAILY_STATS_ORIGINAL_BYTES = "daily_stats_original_bytes"
    const val PREF_DAILY_STATS_COMPRESSED_BYTES = "daily_stats_compressed_bytes"

    // Ограничения размера файлов
    const val MIN_FILE_SIZE = 50 * 1024L // 50 KB
    const val MAX_FILE_SIZE = 100 * 1024 * 1024L // 100 MB
    const val OPTIMUM_FILE_SIZE = 0.1 * 1024 * 1024L // 0.1 MB - файлы меньше этого размера считаются уже оптимизированными

    // Допуск сравнения фактического размера файла с размером в EXIF-маркере.
    // saveAttributes() пересобирает EXIF-сегменты и меняет размер файла в пределах
    // ~1 КБ — такие расхождения не считаются редактированием (4-кратный запас)
    const val MARKER_SIZE_TOLERANCE_BYTES = 4096L
    
    // Теги для WorkManager
    const val WORK_INPUT_IMAGE_URI = "image_uri"
    const val WORK_COMPRESSION_QUALITY = "compression_quality"
    const val WORK_BATCH_ID = "batch_id"
    const val WORK_ORIGIN = "work_origin"
    const val WORK_DISCOVERED_AT = "work_discovered_at"
    const val WORK_ENQUEUED_AT = "work_enqueued_at"
    const val WORK_UNIQUE_DIGEST = "work_unique_digest"
    const val WORK_MAX_RESOLUTION = "max_resolution"
    
    // Уведомления
    const val NOTIFICATION_ID_COMPRESSION = 1
    const val NOTIFICATION_ID_BACKGROUND_SERVICE = 2
    const val NOTIFICATION_ID_COMPRESSION_RESULT = 4

    // Директории
    const val APP_DIRECTORY = "CompressPhotoFast"
    
    // Имена файлов
    const val COMPRESSED_FILE_SUFFIX = "_compressed"
    
    // Настройки сжатия
    const val COMPRESSION_QUALITY_LOW = 60
    const val COMPRESSION_QUALITY_MEDIUM = 70
    const val COMPRESSION_QUALITY_HIGH = 80
    const val MIN_COMPRESSION_SAVING_PERCENT = 30f // Минимальный процент экономии для продолжения сжатия
    
    // Интервалы
    const val BACKGROUND_SCAN_INTERVAL_MINUTES = 15L
    // Редкий страховочный проход при живом ContentObserver: дешёвая защита от
    // пропущенных observer-событий (замороженный/приостановленный процесс в battery saver).
    const val BACKGROUND_SCAN_INTERVAL_FALLBACK_MINUTES = 60L
    // Периодический reconciliation WorkManager: основное обнаружение — ContentObserver
    // и content-trigger Job (будит и мёртвый процесс), это лишь страховка.
    const val RECONCILIATION_INTERVAL_MINUTES = 60L
    const val RECENT_SCAN_WINDOW_SECONDS = 15 * 60L // 15 минут в секундах (увеличено с 5 для обработки копируемых файлов)
    const val HISTORY_SCAN_WINDOW_DAYS = 2
    const val HISTORY_SCAN_WINDOW_SECONDS = HISTORY_SCAN_WINDOW_DAYS * 24 * 60 * 60L
    const val HISTORY_SCAN_WINDOW_MILLIS = HISTORY_SCAN_WINDOW_DAYS * 24 * 60 * 60 * 1000L
    const val CONTENT_OBSERVER_DELAY_SECONDS = 10L // 10 секунд задержки при обнаружении файла
    const val AUTO_COMPRESSION_INITIAL_DELAY_SECONDS = 30L
    
    
    // Intent actions
    const val ACTION_STOP_SERVICE = "com.compressphotofast.STOP_SERVICE"
    
    // Временные файлы
    const val TEMP_FILE_MAX_AGE = 30 * 60 * 1000L // 30 минут
    
    // Задержки для EXIF и MediaStore операций (мс)
    const val EXIF_COPY_DELAY_MS = 300L

    // Пресеты максимального разрешения (по большей стороне). 0 — сохранить исходное разрешение
    const val RESOLUTION_ORIGINAL = 0
    const val RESOLUTION_2560 = 2560
    const val RESOLUTION_1920 = 1920
    const val DEFAULT_MAX_RESOLUTION = RESOLUTION_ORIGINAL
}

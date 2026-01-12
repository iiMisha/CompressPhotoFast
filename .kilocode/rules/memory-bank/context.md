# Контекст

## Последние изменения

*   **Обновление Memory Bank (12.01.2026)**: Обновлена документация Android-версии с полным списком утилит в пакете `util`.
*   **`architecture.md`**: Обновлен раздел Domain Layer с детальным описанием всех утилит:
    *   Работа с MediaStore: `MediaStoreUtil.kt`, `BatchMediaStoreUtil.kt`, `MediaStoreObserver.kt`, `GalleryScanUtil.kt`
    *   Обработка изображений: `ImageCompressionUtil.kt`, `ImageProcessingUtil.kt`, `ImageProcessingChecker.kt`, `SequentialImageProcessor.kt`
    *   Работа с файлами и URI: `FileOperationsUtil.kt`, `FileInfoUtil.kt`, `UriUtil.kt`, `UriProcessingTracker.kt`
    *   EXIF-метаданные: `ExifUtil.kt`
    *   Отслеживание и статистика: `CompressionBatchTracker.kt`, `StatsTracker.kt`, `UriProcessingTracker.kt`
    *   Производительность и кэширование: `PerformanceMonitor.kt`, `OptimizedCacheUtil.kt`
    *   Уведомления: `NotificationUtil.kt`
    *   Разрешения: `PermissionsManager.kt`, `IPermissionsManager.kt`
    *   Очистка: `TempFilesCleaner.kt`
    *   Логирование и события: `LogUtil.kt`, `Event.kt`, `EventObserver.kt`

## Текущее состояние проекта

### Android-версия
*   Версия: 2.2.8 (31.08.2025), versionCode 2
*   Использует DataStore для хранения настроек (вместо SharedPreferences)
*   Поддерживает автоматическое сжатие в фоновом режиме
*   Включает функции игнорирования фото из мессенджеров и скриншотов

### CLI-версия
*   Версия: 1.0.0
*   Полностью поддерживает многопроцессорную обработку изображений с использованием ProcessPoolExecutor
*   Использует multiprocessing.Manager().Lock() для process-safe доступа к статистике
*   Поддерживает HEIC/HEIF форматы (опционально)
*   Имеет dry-run режим для предварительного анализа с многопроцессорной обработкой

## Дальнейшие шаги

*   Проверить актуальность `brief.md` и `product.md`.
*   Запросить у пользователя проверку обновленной документации.
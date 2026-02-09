# Архитектура CompressPhotoFast

## Android

### Слои
- **UI**: `MainActivity.kt`, `MainViewModel.kt`
- **Domain**: `ImageCompressionUtil.kt`, `ImageCompressionWorker.kt`, `SettingsManager.kt`
- **Data**: DataStore (SettingsDataStore), MediaStore
- **Utils**: 31 файл (MediaStore, EXIF, файлы, статистика, оптимизация)

### Фоновая обработка
- WorkManager (`ImageCompressionWorker.kt`) - основная обработка
- `BackgroundMonitoringService.kt` - отслеживание новых изображений
- `ImageDetectionJobService.kt` - периодическая проверка
- `BootCompletedReceiver.kt` - автозапуск

### DI
- Hilt 2.57.1 во все компоненты
- Модули: `AppModule.kt` (основной), тестовые модули
- Singleton: UriProcessingTracker, PerformanceMonitor, CompressionBatchTracker

### Оптимизации
- `inSampleSize`, `RGB_565` для декодирования
- CoroutineScope вместо GlobalScope/Handler
- Пакетные MediaStore операции
- Методы `destroy()` для cleanup

## CLI (Python)

### Компоненты
- `cli.py` (Click), `compression.py` (Pillow, pillow-heif)
- `multiprocessing_utils.py` (ProcessPoolExecutor)
- `exif_handler.py` (piexif)

### Интеграция с Android
- Общие константы: качество (60,70,85), мин размер (100KB), экономия (30%)
- EXIF-маркер: `CompressPhotoFast_Compressed:quality:timestamp`

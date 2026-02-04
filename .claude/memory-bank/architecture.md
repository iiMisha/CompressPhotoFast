# Архитектура CompressPhotoFast

## Android

### Слои
- **UI**: `MainActivity.kt`, `MainViewModel.kt`
- **Domain**: `ImageCompressionUtil.kt`, `ImageCompressionWorker.kt`, `SettingsManager.kt`
- **Data**: DataStore, MediaStore
- **Utils**: 36 файлов (MediaStore, EXIF, файлы, статистика, оптимизация)

### Фоновая обработка
- WorkManager (`ImageCompressionWorker.kt`)
- `BackgroundMonitoringService.kt`: отслеживание новых изображений
- `ImageDetectionJobService.kt`: периодическая проверка
- `BootCompletedReceiver.kt`: автозапуск

### DI
- Hilt 2.57.1 во все компоненты
- DI модули: `AppModule.kt` (основной), `HiltTestModule.kt` (тесты), `TestAppModule.kt`, `WorkManagerTestModule.kt`
- Singleton компоненты: UriProcessingTracker, PerformanceMonitor, CompressionBatchTracker

### Оптимизации
- `inSampleSize`, `RGB_565`
- CoroutineScope вместо GlobalScope
- Пакетные MediaStore операции
- Методы `destroy()`

## CLI (Python)

### Компоненты
- `cli.py` (Click)
- `compression.py` (Pillow, pillow-heif)
- `multiprocessing_utils.py` (ProcessPoolExecutor)
- `exif_handler.py` (piexif)

### Интеграция с Android
- Общие константы: качество (60,70,85), мин размер (100KB), экономия (30%)
- EXIF-маркер: `CompressPhotoFast_Compressed:quality:timestamp`

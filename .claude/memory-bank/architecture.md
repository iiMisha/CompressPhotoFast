# Архитектура CompressPhotoFast

## Обзор
MVVM архитектура с современными Android компонентами.

## Ключевые компоненты Android

### UI Layer (`ui/`)
- `MainActivity.kt`: Единственная Activity
- `MainViewModel.kt`: Управление состоянием UI

### Domain Layer
- Логика сжатия: `ImageCompressionUtil.kt`, `ImageCompressionWorker.kt`
- `SettingsManager.kt`: Настройки
- Утилиты `util/` (30+ файлов): MediaStore, обработка изображений, файлы, EXIF, статистика

### Data Layer
- `DataStore`: Хранение настроек
- `MediaStore`: Доступ к изображениям

## Фоновая обработка
- `WorkManager`: `ImageCompressionWorker.kt`
- `BackgroundMonitoringService.kt`: Отслеживание новых изображений
- `ImageDetectionJobService.kt`: Периодическая проверка
- `BootCompletedReceiver.kt`: Запуск после перезагрузки

## Внедрение зависимостей
- **Hilt**: Во все компоненты (Activity, ViewModel, Worker)

## Оптимизации
- `inSampleSize`, `RGB_565` для декодирования
- Background scanning: 5 минут, CoroutineScope
- Пакетная проверка конфликтов в MediaStore
- Методы `destroy()` для ресурсов

## Тестирование
- Unit: `app/src/test/` (JUnit, MockK)
- Instrumentation: `app/src/androidTest/` (Espresso)
- Базовые: `BaseUnitTest`, `BaseInstrumentedTest`
- 232 теста, coverage ~8-10%

## CLI-версия (Python 3.10+)

### Компоненты
- `cli.py` (Click), `compression.py` (Pillow + pillow-heif)
- `multiprocessing_utils.py`: ProcessPoolExecutor
- `exif_handler.py` (piexif)

### Интеграция с Android
- Идентичные константы: качество (60,70,85), мин размер (100KB), экономия (30%)
- Те же EXIF-маркеры: `CompressPhotoFast_Compressed:quality:timestamp`
- Поддержка HEIC/HEIF → JPEG

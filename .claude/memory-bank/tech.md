# Технологический стек

## Версии
- Android: 2.2.10
- CLI: 1.0.0

## Android
- **Язык**: Kotlin 2.2.10, Java 17
- **Сборка**: AGP 9.0.0, KSP 2.3.2
- **Архитектура**: MVVM, Hilt 2.57.1
- **Библиотеки**:
  - Compressor 3.0.1 (сжатие)
  - Coil 3.3.0 (загрузка изображений)
  - DataStore 1.1.7 (настройки)
  - ExifInterface 1.4.1 (метаданные)
  - Timber 5.0.1 (логирование)
- **Тестирование**: JUnit, MockK, Espresso, JaCoCo
- **Требования**: minSdk 29, targetSdk 36

## CLI (Python 3.10+)
- **Библиотеки**: Pillow, pillow-heif, piexif
- **CLI**: Click, Rich, tqdm
- **Многопроцессорность**: ProcessPoolExecutor

## Оптимизации
- `inSampleSize`, `RGB_565` для изображений
- CoroutineScope вместо GlobalScope
- Пакетные операции в MediaStore
- Методы `destroy()` для ресурсов

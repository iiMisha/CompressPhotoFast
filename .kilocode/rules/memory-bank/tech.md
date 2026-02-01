# Технологический стек CompressPhotoFast

## Версия приложения
- **Android**: 2.2.10 (текущая), versionCode 2
- **CLI**: 1.0.0

## Android-версия

### Язык и сборка
- **Kotlin 2.2.10**: Основной язык с корутинами
- **Android Gradle Plugin 9.0.0**: Плагин для сборки
- **KSP 2.3.2**: Генерация кода (Hilt)
- **Java 17**: Версия для компиляции

### Архитектура
- **MVVM**: Model-View-ViewModel паттерн
- **Android Architecture Components**: ViewModel, LiveData, WorkManager
- **Hilt 2.57.1**: Внедрение зависимостей
- **Hilt Work 1.2.0**: Интеграция Hilt с WorkManager

### Работа с изображениями
- **Compressor 3.0.1**: Сжатие изображений
- **Coil 3.3.0**: Асинхронная загрузка изображений
- **ExifInterface 1.4.1**: Чтение/запись EXIF

### Хранение данных
- **DataStore 1.1.7**: Хранение настроек
- **MediaStore**: Доступ к медиафайлам

### Core библиотеки
- **AndroidX Core KTX 1.17.0**: Расширения Kotlin для AndroidX
- **AndroidX Lifecycle 2.9.2**: Компоненты жизненного цикла
- **AndroidX Activity 1.10.1**: Компоненты Activity

### UI
- **Material Design 1.12.0**: Компоненты интерфейса
- **ViewBinding**: Безопасный доступ к View

### Логирование
- **Timber 5.0.1**: Гибкое логирование

### Тестирование
- **JUnit 4.13.2**: Unit тесты
- **Robolectric 4.11**: Android компоненты
- **MockK 1.13.10**: Mock-объекты
- **Espresso 3.6.1**: UI тесты
- **JaCoCo 0.8.11**: Покрытие кода
- **UIAutomator 2.3.0**: UI тесты на уровне системы
- **kotlinx-coroutines-test 1.10.2**: Тестирование корутин
- **androidx.arch.core:core-testing 2.2.0**: Тестирование архитектуры
- **androidx.work:work-testing 2.10.3**: Тестирование WorkManager
- **androidx.test:core-ktx 1.6.1**: AndroidX тестовые утилиты
- **androidx.test.ext:junit 1.2.1**: JUnit расширения для AndroidX
- **Truth 1.4.4**: Улучшенные assert'ы
- **Hilt Testing 2.57.1**: Тестирование с DI

### Требования
- **minSdk**: 29 (Android 10)
- **targetSdk**: 36 (Android 15)
- **compileSdk**: 36
- **Java**: 17

## CLI-версия (Python)

### Язык
- **Python 3.10+**: Основной язык

### Работа с изображениями
- **Pillow >= 10.0.0**: Сжатие (JPEG, PNG, HEIC/HEIF)
- **pillow-heif >= 0.16.0**: Поддержка HEIC/HEIF
- **piexif >= 1.1.3**: EXIF-метаданные

### CLI интерфейс
- **Click >= 8.1.0**: CLI интерфейс

### Вывод
- **Rich >= 13.0.0**: Форматированный вывод
- **tqdm >= 4.65.0**: Прогресс-бары

### Многопроцессорная обработка
- **ProcessPoolExecutor**: Параллельная обработка
- **multiprocessing.Manager().Lock()**: Process-safe статистика

### Управление пакетами
- **pip**: Менеджер пакетов
- **setup.py / pyproject.toml**: Конфигурация установки

### Запуск
- **Linux/macOS**: `compressphotofast.sh`
- **Windows**: `compressphotofast.bat`

### Автоматическая установка
- **Linux/macOS**: `install/install.sh`
- **Windows**: `install/install.ps1`

### Системные требования для HEIC
- **Linux**: `libheif-dev`, `libffi-dev` (apt-get)
- **Windows**: только `pillow-heif` (pip)
- **macOS**: `libheif` (Homebrew, не протестировано)

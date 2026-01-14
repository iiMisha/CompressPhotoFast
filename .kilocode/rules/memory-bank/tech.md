# Технологический стек CompressPhotoFast

## Версия приложения

*   **Android**: Версия 2.2.8 (31.08.2025), versionCode 2
*   **CLI**: Версия 1.0.0

## Android-версия

### Язык программирования

*   **Kotlin 2.2.10**: Основной язык разработки. Используются корутины для асинхронных операций.

### Сборка

*   **Android Gradle Plugin 8.13.2**: Плагин для сборки Android-приложений.
*   **KSP 2.2.10-2.0.2**: Kotlin Symbol Processing для генерации кода (Hilt).
*   **Java 17**: Версия Java для компиляции.

### Архитектура

*   **MVVM (Model-View-ViewModel)**: Архитектурный паттерн, используемый для разделения логики представления и бизнес-логики.
*   **Android Architecture Components**:
    *   **ViewModel**: для управления данными, связанными с UI, с учетом жизненного цикла.
    *   **LiveData**: для создания наблюдаемых объектов данных.
    *   **WorkManager**: для выполнения отложенных и надежных фоновых задач.

### Внедрение зависимостей

*   **Hilt 2.57.1**: для внедрения зависимостей в компоненты Android.

### Работа с изображениями

*   **Compressor** (id.zelory:compressor:3.0.1): библиотека для сжатия изображений.
*   **Coil 3.3.0**: для асинхронной загрузки и отображения изображений.
*   **ExifInterface** (androidx.exifinterface:exifinterface:1.4.1): для чтения и записи метаданных EXIF.

### Хранение данных

*   **DataStore** (androidx.datastore:datastore-preferences:1.1.7): для хранения простых настроек приложения.
*   **MediaStore**: для взаимодействия с медиафайлами на устройстве.

### Пользовательский интерфейс

*   **Material Design Components** (com.google.android.material:material:1.12.0): для создания современного и консистентного пользовательского интерфейса.
*   **ViewBinding**: для безопасного доступа к View.

### Логирование

*   **Timber** (com.jakewharton.timber:timber:5.0.1): для гибкого и расширяемого логирования.

### Зависимости Android

*   **AndroidX Core**: androidx.core:core-ktx:1.17.0
*   **AppCompat**: androidx.appcompat:appcompat:1.7.1
*   **ConstraintLayout**: androidx.constraintlayout:constraintlayout:2.1.4
*   **Lifecycle**: androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2, livedata-ktx:2.9.2, runtime-ktx:2.9.2
*   **Activity**: androidx.activity:activity-ktx:1.10.1
*   **WorkManager**: androidx.work:work-runtime-ktx:2.10.3
*   **Hilt Work**: androidx.hilt:hilt-work:1.2.0
*   **Coroutines**: org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2
*   **Hilt**: com.google.dagger:hilt-android:2.57.1

### Тестирование

*   **JUnit 4.13.2**: Фреймворк для unit тестирования.
*   **Robolectric 4.11**: Фреймворк для unit тестирования Android-компонентов.
*   **MockK 1.13.10**: Библиотека для создания mock-объектов в Kotlin.
*   **Espresso 3.6.1**: Фреймворк для UI тестирования.
*   **JaCoCo 0.8.11**: Инструмент для измерения покрытия кода тестами.
*   **AndroidX Test**: androidx.test:core-ktx:1.6.1, androidx.test.ext:junit:1.2.1, androidx.test:runner:1.6.1, androidx.test:rules:1.6.1
*   **UIAutomator 2.3.0**: Фреймворк для UI тестирования на уровне системы.

### Требования к системе

*   **minSdk**: 29 (Android 10)
*   **targetSdk**: 36 (Android 15)
*   **compileSdk**: 36
*   **Java**: 17

## CLI-версия (Python)

### Язык программирования

*   **Python 3.10+**: Основной язык разработки CLI-версии.

### Работа с изображениями

*   **Pillow >= 10.0.0**: Библиотека для сжатия изображений (JPEG, PNG, HEIC/HEIF). Поддерживает оптимизацию и сохранение с заданным качеством.
*   **pillow-heif >= 0.16.0**: Опциональная библиотека для поддержки HEIC/HEIF форматов. При наличии автоматически регистрирует HEIF opener.
*   **piexif >= 1.1.3**: Библиотека для чтения и записи EXIF-метаданных. Используется для добавления маркеров сжатия и копирования EXIF-тегов.

### CLI интерфейс

*   **Click >= 8.1.0**: Декоративная библиотека для создания CLI интерфейсов. Обеспечивает красивый и удобный интерфейс с подсказками и автодополнением.

### Вывод в терминал

*   **Rich >= 13.0.0**: Библиотека для красивого форматированного вывода в терминал. Используется для создания таблиц, прогресс-баров и цветного текста.
*   **tqdm >= 4.65.0**: Библиотека для отображения прогресс-баров.

### Многопроцессорная обработка

*   **concurrent.futures.ProcessPoolExecutor**: Для параллельной обработки изображений с использованием всех доступных ядер CPU. Использует контекст `spawn` для создания процессов.
*   **multiprocessing.Manager().Lock()**: Для process-safe доступа к статистике сжатия из нескольких процессов.

### Управление пакетами

*   **pip**: Менеджер пакетов Python для установки зависимостей.
*   **setup.py / pyproject.toml**: Файлы конфигурации для установки CLI-инструмента через pip.

### Запуск на платформах

*   **Linux/macOS**: Shell-скрипт `compressphotofast.sh` с автоматическим созданием виртуального окружения.
*   **Windows**: Batch-скрипт `compressphotofast.bat` для запуска CLI-инструмента.

### Автоматическая установка

*   **Linux/macOS**: Скрипт `install/install.sh` с детекцией Python и установкой системных зависимостей.
*   **Windows**: Скрипт `install/install.ps1` с детекцией Python и автоматической установкой через winget/chocolatey.

### Системные требования для HEIC

*   **Linux**: Требуется установка `libheif-dev` и `libffi-dev` через apt-get (устанавливается автоматически)
*   **Windows**: Требуется только установка `pillow-heif` через pip
*   **macOS**: Требуется установка `libheif` через Homebrew (теоретически поддерживается, не протестировано)
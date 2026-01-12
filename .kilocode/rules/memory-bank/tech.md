# Технологический стек CompressPhotoFast

## Версия приложения

*   **Android**: Версия 2.2.8 (31.08.2025), versionCode 2
*   **CLI**: Версия 1.0.0

## Android-версия

### Язык программирования

*   **Kotlin**: Основной язык разработки. Используются корутины для асинхронных операций.

### Архитектура

*   **MVVM (Model-View-ViewModel)**: Архитектурный паттерн, используемый для разделения логики представления и бизнес-логики.
*   **Android Architecture Components**:
    *   **ViewModel**: для управления данными, связанными с UI, с учетом жизненного цикла.
    *   **LiveData**: для создания наблюдаемых объектов данных.
    *   **WorkManager**: для выполнения отложенных и надежных фоновых задач.

### Внедрение зависимостей

*   **Hilt**: для внедрения зависимостей в компоненты Android.

### Работа с изображениями

*   **Compressor** (id.zelory:compressor:3.0.1): библиотека для сжатия изображений.
*   **Coil** (io.coil-kt.coil3:coil:3.3.0): для асинхронной загрузки и отображения изображений.
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

### Требования к системе

*   **minSdk**: 29 (Android 10)
*   **targetSdk**: 36 (Android 15)
*   **compileSdk**: 36
*   **Java**: 17

## CLI-версия (Python)

### Язык программирования

*   **Python 3.10+**: Основной язык разработки CLI-версии.

### Работа с изображениями

*   **Pillow (PIL)**: Библиотека для сжатия изображений (JPEG, PNG, HEIC/HEIF). Поддерживает оптимизацию и сохранение с заданным качеством.
*   **pillow-heif**: Опциональная библиотека для поддержки HEIC/HEIF форматов. При наличии автоматически регистрирует HEIF opener.
*   **piexif**: Библиотека для чтения и записи EXIF-метаданных. Используется для добавления маркеров сжатия и копирования EXIF-тегов.

### CLI интерфейс

*   **Click**: Декоративная библиотека для создания CLI интерфейсов. Обеспечивает красивый и удобный интерфейс с подсказками и автодополнением.

### Вывод в терминал

*   **Rich**: Библиотека для красивого форматированного вывода в терминал. Используется для создания таблиц, прогресс-баров и цветного текста.

### Многопроцессорная обработка

*   **concurrent.futures.ProcessPoolExecutor**: Для параллельной обработки изображений с использованием всех доступных ядер CPU. Использует контекст `spawn` для создания процессов.
*   **multiprocessing.Manager().Lock()**: Для process-safe доступа к статистике сжатия из нескольких процессов.

### Управление пакетами

*   **pip**: Менеджер пакетов Python для установки зависимостей.
*   **setup.py / pyproject.toml**: Файлы конфигурации для установки CLI-инструмента через pip.

### Запуск на платформах

*   **Linux/macOS**: Shell-скрипт `compressphotofast.sh` с автоматическим созданием виртуального окружения.
*   **Windows**: Batch-скрипт `compressphotofast.bat` для запуска CLI-инструмента.

### Системные требования для HEIC

*   **Linux**: Требуется установка `libheif-dev` и `libffi-dev` через apt-get
*   **Windows**: Требуется только установка `pillow-heif` через pip
*   **macOS**: Теоретически поддерживается (не протестировано)
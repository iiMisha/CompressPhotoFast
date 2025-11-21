# Архитектура приложения CompressPhotoFast

## Обзор

Приложение построено на основе современных архитектурных компонентов Android и следует паттерну MVVM (Model-View-ViewModel).

## Ключевые компоненты

*   **UI Layer (`app/src/main/java/com/compressphotofast/ui`)**:
    *   `MainActivity.kt`: Единственная Activity в приложении, отвечающая за отображение пользовательского интерфейса и обработку взаимодействий.
    *   `MainViewModel.kt`: ViewModel, которая управляет состоянием UI, обрабатывает бизнес-логику и взаимодействует с репозиториями и сервисами.

*   **Domain Layer**:
    *   Логика сжатия инкапсулирована в `ImageCompressionUtil.kt` и `ImageCompressionWorker.kt`.
    *   `SettingsManager.kt` управляет настройками приложения.
    *   Утилиты в пакете `app/src/main/java/com/compressphotofast/util` предоставляют вспомогательные функции для работы с файлами, URI, EXIF-данными и уведомлениями.

*   **Data Layer**:
    *   `DataStore` используется для хранения простых настроек.
    *   `MediaStore` используется для доступа к изображениям на устройстве.

## Фоновая обработка

*   **`WorkManager`**: Используется для выполнения задач сжатия изображений в фоновом режиме через `ImageCompressionWorker.kt`. Это обеспечивает надежное выполнение даже после закрытия приложения.
*   **`BackgroundMonitoringService.kt`**: Сервис, который отслеживает новые изображения в фоновом режиме, когда включена функция автоматического сжатия.
*   **`ImageDetectionJobService.kt`**: `JobService`, который периодически проверяет наличие новых изображений.
*   **`BootCompletedReceiver.kt`**: `BroadcastReceiver`, который запускает фоновые службы после перезагрузки устройства.

## Внедрение зависимостей

*   **Hilt**: Используется для внедрения зависимостей во все компоненты приложения, включая `Activity`, `ViewModel` и `Worker`. `AppModule.kt` определяет, как предоставлять зависимости.

## Диаграмма компонентов

```mermaid
graph TD
    subgraph "UI Layer"
        A[MainActivity] --> B[MainViewModel]
    end

    subgraph "Domain Layer"
        B --> C{ImageCompressionWorker}
        B --> D[SettingsManager]
        C --> E[ImageCompressionUtil]
        F[BackgroundMonitoringService] --> C
        G[ImageDetectionJobService] --> C
    end

    subgraph "Data Layer"
        D --> H[DataStore]
        C --> I[MediaStore]
        E --> I
    end

    A -- "Запускает" --> C
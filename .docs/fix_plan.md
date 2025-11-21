# План по устранению проблемы с дублированием файлов

## Проблема
При включении автосжатия после длительного периода его отключения, для накопившихся изображений создаются дубликаты в папке приложения, несмотря на включенную опцию "заменять оригинальные файлы". Причина — состояние гонки при чтении настроек из-за некорректного управления экземплярами `SettingsManager` в утилитах, используемых фоновыми задачами.

## Цель
Обеспечить, чтобы все компоненты приложения использовали единый, предоставленный через Hilt, экземпляр `SettingsManager` и других утилит. Это устранит состояние гонки и приведет архитектуру к единому стандарту внедрения зависимостей.

## Подробный план

### 1. Рефакторинг `SettingsManager`
- **Файл:** `app/src/main/java/com/compressphotofast/util/SettingsManager.kt`
- **Действия:**
  - Удалить `companion object` с методом `getInstance(context: Context)`. Это устранит основной источник проблемы.

### 2. Рефакторинг `FileOperationsUtil`
- **Файл:** `app/src/main/java/com/compressphotofast/util/FileOperationsUtil.kt`
- **Действия:**
  - Преобразовать `object FileOperationsUtil` в `class FileOperationsUtil`.
  - Добавить аннотацию `@Singleton`.
  - Внедрить `SettingsManager` через конструктор: `@Inject constructor(private val settingsManager: SettingsManager)`.
  - Заменить вызовы `isSaveModeReplace(context)` на внутренний метод, использующий внедренный `settingsManager`.

### 3. Рефакторинг `ImageCompressionUtil`
- **Файл:** `app/src/main/java/com/compressphotofast/util/ImageCompressionUtil.kt`
- **Действия:**
  - Преобразовать `object ImageCompressionUtil` в `class ImageCompressionUtil`.
  - Добавить аннотацию `@Singleton`.
  - Внедрить зависимости (`FileOperationsUtil`, `UriUtil`, `MediaStoreUtil` и др.) через конструктор.
  - Обновить внутренние вызовы статических методов на вызовы у внедренных экземпляров.

### 4. Рефакторинг `UriUtil`
- **Файл:** `app/src/main/java/com/compressphotofast/util/UriUtil.kt`
- **Действия:**
  - Преобразовать `object UriUtil` в `class UriUtil`.
  - Добавить аннотацию `@Singleton`.
  - Внедрить `FileOperationsUtil` через конструктор.
  - Обновить вызов `FileOperationsUtil.isScreenshot(context, uri)` на вызов метода у внедренного экземпляра.

### 5. Рефакторинг `ImageProcessingChecker`
- **Файл:** `app/src/main/java/com/compressphotofast/util/ImageProcessingChecker.kt`
- **Действия:**
  - Преобразовать `object ImageProcessingChecker` в `class ImageProcessingChecker`.
  - Добавить аннотацию `@Singleton`.
  - Внедрить `SettingsManager` и другие необходимые утилиты через конструктор.

### 6. Рефакторинг `ImageCompressionWorker`
- **Файл:** `app/src/main/java/com/compressphotofast/worker/ImageCompressionWorker.kt`
- **Действия:**
 - Внедрить через `@AssistedInject` все необходимые утилиты: `FileOperationsUtil`, `UriUtil`, `ImageCompressionUtil`, `ImageProcessingChecker`.
  - Заменить все статические вызовы (`FileOperationsUtil.isSaveModeReplace(...)`, `UriUtil.getFileNameFromUri(...)` и т.д.) на вызовы методов у внедренных экземпляров.

### 7. Глобальное обновление вызовов
- **Файлы:** Все файлы проекта.
- **Действия:**
  - Выполнить глобальный поиск по проекту на предмет использования статических методов из рефакторенных утилит (`FileOperationsUtil.*`, `UriUtil.*`, `ImageCompressionUtil.*` и т.д.).
  - В классах, поддерживающих DI (Activity, ViewModel, Service, Worker), внедрить утилиты через конструктор или `@Inject lateinit var`.
  - Заменить все найденные статические вызовы.

### 8. Обновление модуля Hilt
- **Файл:** `app/src/main/java/com/compressphotofast/di/AppModule.kt`
- **Действия:**
 - Убедиться, что Hilt автоматически предоставляет все новые классы, помеченные аннотацией `@Singleton`. Явных привязок (`@Provides`) для них создавать не нужно.

### 9. Сборка и тестирование
- **Действия:**
  - **После каждого шага** выполнять полную сборку проекта (`./gradlew assembleDebug`) для немедленного выявления исправления ошибок компиляции.
  - После завершения всего рефакторинга провести ручное тестирование, воспроизведя исходный сценарий: выключить автосжатие, накопить фото, включить автосжатие и убедиться в отсутствии дубликатов.
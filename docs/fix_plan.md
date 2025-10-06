# План по устранению проблемы с дублированием файлов

## Проблема
При включении автосжатия после длительного периода его отключения, для накопившихся изображений создаются дубликаты в папке приложения, несмотря на включенную опцию "заменять оригинальные файлы". Причина — состояние гонки при чтении настроек из-за некорректного управления экземплярами `SettingsManager` в утилитах, используемых фоновыми задачами.

## Цель
Обеспечить, чтобы все компоненты приложения, включая утилиты, использовали единый, предоставленный через Hilt, экземпляр `SettingsManager`, устранив состояние гонки.

## Подробный план

### 1. Рефакторинг `FileOperationsUtil`
- **Файл:** `app/src/main/java/com/compressphotofast/util/FileOperationsUtil.kt`
- **Действия:**
  - Преобразовать `object FileOperationsUtil` в `class FileOperationsUtil`.
  - Добавить аннотацию `@Singleton`.
  - Добавить конструктор с параметром `@Inject val settingsManager: SettingsManager`.
  - Удалить статический метод `isSaveModeReplace(context: Context)`.
  - Заменить его на внутренний метод `isSaveModeReplace(): Boolean`, использующий внедренный `settingsManager`.
  - Обновить вызовы `SettingsManager.getInstance(context)` на `settingsManager`.

### 2. Рефакторинг `UriUtil`
- **Файл:** `app/src/main/java/com/compressphotofast/util/UriUtil.kt`
- **Действия:**
 - Преобразовать `object UriUtil` в `class UriUtil`.
  - Добавить аннотацию `@Singleton`.
  - Добавить конструктор с параметром `@Inject val fileOperationsUtil: FileOperationsUtil`.
  - Обновить вызовы `FileOperationsUtil.isSaveModeReplace(context)` на `fileOperationsUtil.isSaveModeReplace()`.

### 3. Рефакторинг `ImageCompressionWorker`
- **Файл:** `app/src/main/java/com/compressphotofast/worker/ImageCompressionWorker.kt`
- **Действия:**
  - Добавить параметр `@Inject val fileOperationsUtil: FileOperationsUtil` и `@Inject val uriUtil: UriUtil` в конструктор.
  - Удалить или обновить все статические вызовы `FileOperationsUtil` и `UriUtil` на вызовы методов у внедренных экземпляров.
  - Примеры: `FileOperationsUtil.isSaveModeReplace(context)` -> `fileOperationsUtil.isSaveModeReplace()`, `UriUtil.getDirectoryFromUri(context, uri)` -> `uriUtil.getDirectoryFromUri(context, uri)`.

### 4. Обновление модуля Hilt
- **Файл:** `app/src/main/java/com/compressphotofast/di/AppModule.kt`
- **Действия:**
 - Проверить, что нет конфликтующих определений для `FileOperationsUtil` и `UriUtil`.
  - Hilt автоматически создаст синглтоны для этих классов, так как они помечены `@Singleton`.

### 5. Глобальное обновление вызовов утилит
- **Файлы:** Все файлы, использующие `FileOperationsUtil` и `UriUtil` (например, `SequentialImageProcessor.kt`, `MediaStoreUtil.kt`, `ImageProcessingChecker.kt`, `BackgroundMonitoringService.kt` и т.д.).
- **Действия:**
  - Найти все статические вызовы `FileOperationsUtil` и `UriUtil`.
  - Внедрить зависимости в классы, где возможно (через конструктор или `@Inject lateinit var`).
  - Заменить статические вызовы на вызовы методов у внедренных экземпляров.
  - В случае, если внедрение невозможно (например, в других `object`-ах), передавать экземпляры утилит в качестве параметров метода.

### 6. Сборка и тестирование
- **Действия:**
  - После каждого шага выполнять полную сборку проекта (`./gradlew assembleDebug`) для выявления ошибок компиляции.
  - После завершения рефакторинга провести ручное тестирование, воспроизведя исходный сценарий: выключить автосжатие, накопить фото, включить автосжатие, проверить отсутствие дубликатов.
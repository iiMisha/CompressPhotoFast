# Повторяющиеся задачи CompressPhotoFast

Этот файл содержит документацию по повторяющимся задачам и рабочим процессам, которые могут потребоваться в будущем.

## Добавление новых unit тестов

**Когда требуется**: При добавлении новой функциональности или рефакторинге существующего кода.

**Файлы для создания/изменения**:
- `app/src/test/java/com/compressphotofast/util/[ИмяУтилиты]Test.kt` - Создать новый тестовый класс
- `app/src/test/java/com/compressphotofast/BaseUnitTest.kt` - Базовый класс (обычно не меняется)

**Шаги**:
1. Создать новый тестовый класс, наследуемый от `BaseUnitTest`
2. Добавить необходимые зависимости через `@Inject` или создать вручную
3. Написать тестовые методы с аннотацией `@Test`
4. Использовать `runTest` для тестирования корутин
5. Использовать MockK для мокирования зависимостей
6. Запустить тесты: `./gradlew testDebugUnitTest`

**Важные замечания**:
- Все тесты должны наследоваться от `BaseUnitTest`
- Используйте `runTest` для тестирования корутин
- MockK автоматически инициализируется в `BaseUnitTest`
- Timber автоматически инициализируется для логирования

**Пример**:
```kotlin
class MyUtilTest : BaseUnitTest() {
    private lateinit var myUtil: MyUtil

    @Before
    override fun setUp() {
        super.setUp()
        myUtil = MyUtil()
    }

    @Test
    fun testSomething() = runTest {
        // Тестовый код
    }
}
```

## Добавление новых instrumentation тестов

**Когда требуется**: При тестировании UI компонентов или функциональности, требующей Android API.

**Файлы для создания/изменения**:
- `app/src/androidTest/java/com/compressphotofast/[ИмяТеста].kt` - Создать новый тестовый класс
- `app/src/androidTest/java/com/compressphotofast/di/HiltTestModule.kt` - Модуль для тестирования с Hilt

**Шаги**:
1. Создать новый тестовый класс с аннотацией `@HiltAndroidTest`
2. Наследоваться от `BaseInstrumentedTest`
3. Добавить `@get:Rule` для `HiltAndroidRule` и `ActivityScenarioRule`
4. Использовать `@Inject` для внедрения зависимостей
5. Написать тестовые методы с использованием Espresso
6. Запустить тесты: `./scripts/run_instrumentation_tests.sh`

**Важные замечания**:
- Требуется запущенный эмулятор или реальное устройство
- Используйте `check_device.sh` для проверки подключения
- Espresso тесты должны быть быстрыми и изолированными
- Используйте `@Before` для настройки и `@After` для очистки

**Пример**:
```kotlin
@HiltAndroidTest
class MyActivityTest : BaseInstrumentedTest() {
    
    @get:Rule
    override val hiltRule = HiltAndroidRule(this)
    
    @get:Rule
    override val activityRule = ActivityScenarioRule(MainActivity::class.java)
    
    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory
    
    @Before
    fun setup() {
        hiltRule.inject()
    }
    
    @Test
    fun testSomething() {
        // Espresso тесты
    }
}
```

## Обновление зависимостей Gradle

**Когда требуется**: При обновлении библиотек или добавлении новых зависимостей.

**Файлы для изменения**:
- `app/build.gradle.kts` - Зависимости приложения
- `build.gradle.kts` - Версии плагинов
- `gradle.properties` - Настройки Gradle

**Шаги**:
1. Проверить актуальные версии на официальных сайтах библиотек
2. Обновить версии в `app/build.gradle.kts` в блоке `dependencies`
3. Обновить версии плагинов в `build.gradle.kts`
4. Выполнить синхронизацию Gradle
5. Запустить тесты: `./gradlew testDebugUnitTest`
6. Исправить возможные ошибки совместимости

**Важные замечания**:
- Всегда проверяйте совместимость версий
- Обновляйте зависимости постепенно, а не все сразу
- После обновления обязательно запустите все тесты
- Проверьте changelog библиотек на breaking changes

## Добавление новой утилиты в пакет util

**Когда требуется**: При создании новой функциональности, которая может быть переиспользована.

**Файлы для создания/изменения**:
- `app/src/main/java/com/compressphotofast/util/[ИмяУтилиты].kt` - Новая утилита
- `app/src/test/java/com/compressphotofast/util/[ИмяУтилиты]Test.kt` - Тесты для утилиты

**Шаги**:
1. Создать новый файл в пакете `util`
2. Реализовать функциональность утилиты
3. Добавить необходимые константы в `Constants.kt` (если требуется)
4. Создать unit тесты для новой утилиты
5. Запустить тесты: `./gradlew testDebugUnitTest`
6. Добавить документацию в виде KDoc комментариев

**Важные замечания**:
- Утилиты должны быть stateless (без состояния)
- Используйте `object` для синглтонов или `class` для экземпляров
- Добавляйте KDoc документацию для публичных методов
- Всегда создавайте тесты для новой утилиты

## Обновление версии приложения

**Когда требуется**: При подготовке к релизу или после значительных изменений.

**Файлы для изменения**:
- `gradle.properties` - Базовая версия приложения
- `app/build.gradle.kts` - versionCode (при необходимости)

**Шаги**:
1. Обновить `VERSION_NAME_BASE` в `gradle.properties`
2. При необходимости увеличить `versionCode` в `app/build.gradle.kts`
3. Выполнить сборку: `./gradlew assembleDebug`
4. Протестировать приложение
5. Создать release APK: `./gradlew assembleRelease`

**Важные замечания**:
- versionCode должен быть уникальным для каждого релиза
- Версия форматируется как `MAJOR.MINOR.PATCH`
- При изменении API увеличивайте MAJOR
- При добавлении функциональности увеличивайте MINOR
- При исправлении ошибок увеличивайте PATCH

## Добавление поддержки нового формата изображений

**Когда требуется**: При необходимости поддержки новых форматов (например, WebP, AVIF).

**Файлы для изменения**:
- `app/src/main/java/com/compressphotofast/util/ImageProcessingChecker.kt` - Проверка форматов
- `app/src/main/java/com/compressphotofast/util/Constants.kt` - Константы форматов
- `app/src/test/java/com/compressphotofast/util/[ИмяУтилиты]Test.kt` - Тесты
- `compressphotofast-cli/src/constants.py` - Константы CLI

**Шаги**:
1. Добавить MIME тип нового формата в `Constants.kt`
2. Обновить `ImageProcessingChecker` для проверки нового формата
3. Обновить логику сжатия для поддержки нового формата
4. Добавить тесты для проверки поддержки нового формата
5. Обновить CLI константы в `constants.py`
6. Запустить все тесты

**Важные замечания**:
- Проверьте, поддерживает ли Compressor новый формат
- Для HEIC/HEIF требуется pillow-heif в CLI
- Добавьте тесты для проверки MIME типа
- Обновите документацию README

## Исправление проблем с двойными расширениями файлов

**Когда требуется**: При обработке HEIC/HEIF файлов создаются файлы с двойными расширениями.

**Файлы для изменения**:
- `app/src/main/java/com/compressphotofast/util/FileOperationsUtil.kt` - Операции с файлами
- `app/src/main/java/com/compressphotofast/util/ImageCompressionUtil.kt` - Сжатие изображений
- `app/src/test/java/com/compressphotofast/util/FileNameProcessingTest.kt` - Тесты

**Шаги**:
1. Изучить текущую логику в `FileOperationsUtil.createCompressedFileName()`
2. Добавить очистку двойных расширений
3. Определить и сохранить исходный MIME тип
4. Использовать правильный MIME тип при сохранении
5. Обновить тесты для проверки очистки расширений
6. Запустить тесты: `./gradlew testDebugUnitTest`

**Важные замечания**:
- HEIC/HEIF файлы должны конвертироваться в JPEG
- Имена файлов должны быть чистыми (одно расширение)
- MIME тип должен соответствовать формату файла
- EXIF метаданные должны сохраняться

## Отладка проблем с дубликатами сжатых файлов

**Когда требуется**: При массовой обработке создаются дубликаты сжатых файлов.

**Файлы для проверки**:
- `app/src/main/java/com/compressphotofast/util/MediaStoreUtil.kt` - Работа с MediaStore
- `app/src/main/java/com/compressphotofast/util/FileOperationsUtil.kt` - Операции с файлами
- `app/src/main/java/com/compressphotofast/worker/ImageCompressionWorker.kt` - Worker сжатия

**Шаги**:
1. Проверить логику копирования файлов в исходную директорию
2. Проверить работу с URI при замене файлов
3. Добавить логирование для отслеживания путей файлов
4. Проверить логику сохранения в отдельную папку
5. Протестировать с небольшим количеством файлов
6. Проанализировать логи для выявления проблемы

**Важные замечания**:
- Проблема может быть связана с доступом через URI
- Проверьте права доступа к файлам
- Убедитесь, что файлы копируются в правильную директорию
- Используйте Timber для логирования

## Запуск тестов в экономичном режиме

**Когда требуется**: Для снижения нагрузки на CPU при локальной разработке.

**Команды**:
```bash
# Unit тесты в экономичном режиме
GRADLE_MODE=eco ./gradlew testDebugUnitTest

# Все тесты в экономичном режиме
GRADLE_MODE=eco ./scripts/run_all_tests.sh
```

**Важные замечания**:
- Экономичный режим использует последовательное выполнение тестов
- Время выполнения увеличивается, но нагрузка на CPU снижается
- Для CI/CD используйте быстрый режим (по умолчанию)
- Настройки экономичного режима в `gradle.properties`

## Генерация тестовых изображений

**Когда требуется**: Для создания тестовых изображений для unit и instrumentation тестов.

**Команда**:
```bash
./scripts/generate_test_images.sh
```

**Требования**:
- ImageMagick: `sudo apt-get install imagemagick`
- Опционально: exiftool для добавления EXIF-данных

**Генерируемые изображения**:
- `test_image_small.jpg` - 100x100 пикселей, ~50 КБ
- `test_image_medium.jpg` - 800x600 пикселей, ~200 КБ
- `test_image_large.jpg` - 1920x1080 пикселей, ~500 КБ
- `test_image_huge.jpg` - 4000x3000 пикселей, ~2 МБ
- `test_image_with_exif.jpg` - 800x600 с EXIF-данными
- `test_image_screenshot.png` - скриншот
- `test_image_heic.heic` - HEIC формат
- `test_image_too_small.jpg` - изображение меньше 100 КБ
- `test_image_low_quality.jpg` - изображение с низким качеством
- `test_image_high_quality.jpg` - изображение с высоким качеством
- `test_image.png` - PNG изображение

**Важные замечания**:
- Изображения сохраняются в `app/src/test/resources/test_images/`
- Скрипт перезаписывает существующие изображения
- Для HEIC требуется поддержка pillow-heif

## Исправление переполнения памяти при обновлении Memory Bank

**Когда требуется**: При использовании скилла `memory-bank-updater` для обновления Memory Bank.

**Проблема**: При инициализации или обновлении Memory Bank происходит переполнение кучи JavaScript (JavaScript heap out of memory).

**Файлы для изменения**:
- `.claude/skills/memory-bank-updater/SKILL.md` - Настройки скилла
- `.claude/memory-bank/memory-bank-instructions.md` - Инструкции по работе с Memory Bank

**Шаги**:
1. Изменить thoroughness с `"very thorough"` на `"medium"` для операции initialize
2. Изменить thoroughness с `"medium"` на `"quick"` для операции update
3. Добавить инструкции о фокусировке на ключевых файлах (app/src/main, build.gradle)
4. Добавить явное предупреждение о запрете использования `very thorough`

**Важные замечания**:
- `"very thorough"` заставляет агента читать максимально много файлов → переполнение кучи JavaScript
- `"medium"` - сбалансированный анализ, достаточный для понимания структуры
- `"quick"` - быстрый поиск изменений, идеально для обновлений
- Всегда использовать `Task(Explore)` вместо прямого чтения файлов

**Пример правильного вызова**:
```
Task(Explore, "medium", "Проанализировать проект CompressPhotoFast: архитектуру, основные компоненты (app/src/main), build систему (Gradle), тесты, используемые технологии и библиотеки")
```

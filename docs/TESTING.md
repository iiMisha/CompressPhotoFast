# Система автоматического тестирования CompressPhotoFast

## Обзор

Создана локальная система автоматического тестирования для Android-приложения CompressPhotoFast. Система работает на эмуляторе или реальном устройстве через ADB и обеспечивает обязательное тестирование кода при изменениях.

## Быстрый старт

```bash
# Проверка устройства
./scripts/check_device.sh

# Генерация тестовых изображений
./scripts/generate_test_images.sh

# Запуск unit тестов
./scripts/run_unit_tests.sh

# Запуск всех тестов
./scripts/run_all_tests.sh
```

## Структура тестов

### Unit тесты

**Директория**: `app/src/test/java/com/compressphotofast/`

Структура папок:
- `util/` - тесты утилит (MediaStoreUtil, ImageCompressionUtil, ExifUtil и др.)
- `ui/` - тесты UI компонентов (ViewModel, LiveData)
- `worker/` - тесты Worker'ов (ImageCompressionWorker)
- `service/` - тесты сервисов (BackgroundMonitoringService, ImageDetectionJobService)
- `di/` - тесты модулей внедрения зависимостей

**Ресурсы**: `app/src/test/resources/`
- `test_images/` - тестовые изображения для тестов

### Instrumentation тесты

**Директория**: `app/src/androidTest/java/com/compressphotofast/`

Структура папок:
- `util/` - тесты утилит с доступом к Android API
- `ui/` - тесты UI с Espresso
- `worker/` - тесты Worker'ов на устройстве
- `service/` - тесты сервисов на устройстве

## Базовые классы для тестов

### BaseUnitTest

Базовый класс для всех unit тестов. Обеспечивает:
- Инициализацию MockK для мокирования зависимостей
- Настройку TestDispatcher для тестирования корутин
- Автоматическую очистку после тестов
- Инициализацию Timber для логирования

Пример использования:
```kotlin
class MyTest : BaseUnitTest() {
    private lateinit var myClass: MyClass

    @Before
    override fun setUp() {
        super.setUp()
        myClass = MyClass()
    }

    @Test
    fun testSomething() = runTest {
        // Тестовый код
    }
}
```

### BaseInstrumentedTest

Базовый класс для всех instrumentation тестов. Обеспечивает:
- Настройку Hilt для тестов
- ActivityScenarioRule для тестирования Activity
- Утилиты для работы с Espresso

Пример использования:
```kotlin
@HiltAndroidTest
class MainActivityTest : BaseInstrumentedTest() {
    
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
    fun testMainActivity() {
        // Espresso тесты
    }
}
```

### CoroutinesTestRule

Правило JUnit для тестирования корутин. Обеспечивает:
- TestDispatcher для синхронного выполнения корутин
- Автоматическую настройку MainDispatcher
- Метод `runTest` для выполнения тестовых блоков

## Скрипты

1. **check_device.sh** - проверка подключения устройства
2. **run_unit_tests.sh** - запуск только unit тестов
3. **run_all_tests.sh** - полный цикл тестирования
4. **generate_test_images.sh** - генерация тестовых изображений

### generate_test_images.sh

Скрипт для генерации тестовых изображений с использованием ImageMagick.

**Требования**:
- ImageMagick (установка: `sudo apt-get install imagemagick`)
- Опционально: exiftool для добавления EXIF-данных

**Генерируемые изображения**:
- `test_image_small.jpg` - 100x100 пикселей, ~50 КБ
- `test_image_medium.jpg` - 800x600 пикселей, ~200 КБ
- `test_image_large.jpg` - 1920x1080 пикселей, ~500 КБ
- `test_image_huge.jpg` - 4000x3000 пикселей, ~2 МБ
- `test_image_with_exif.jpg` - 800x600 с EXIF-данными
- `test_image_screenshot.png` - скриншот (для тестирования фильтрации)
- `test_image_heic.heic` - HEIC формат (если поддерживается)
- `test_image_too_small.jpg` - изображение меньше 100 КБ
- `test_image_low_quality.jpg` - изображение с низким качеством
- `test_image_high_quality.jpg` - изображение с высоким качеством
- `test_image.png` - PNG изображение

## Gradle команды

```bash
# Unit тесты
./gradlew testDebugUnitTest

# Instrumentation тесты
./gradlew connectedDebugAndroidTest

# Coverage отчет
./gradlew jacocoTestReport

# Все тесты + coverage
./gradlew checkAllTests

# Проверка минимального coverage (30%)
./gradlew jacocoTestCoverageVerification
```

## Coverage

- HTML отчет: `app/build/reports/jacoco/jacocoTestReport/html/index.html`
- Целевой coverage: 50-70%
- Минимальный coverage: 30%
- Текущий: ~8% (январь 2026)

### Статистика покрытия (январь 2026)

- **Инструкции**: 8% (2,746 из 30,282)
- **Ветки**: 0% (30 из 30)
- **Классы**: 0% (3,008 из 3,008)
- **Методы**: 0% (3,443 из 3,468)

### Покрытые модули

На данный момент протестированы:
- ✅ `PerformanceMonitor` - монитор производительности (полностью)
- ✅ `CompressionBatchTracker` - трекер батчей сжатия (полностью)
- ✅ `StatsTracker` - сбор статистики (полностью)
- ✅ `SettingsManager` - управление настройками (полностью)
- ✅ `Constants` - константы приложения (полностью)
- ✅ `Event` и `EventObserver` - событийная модель (полностью)
- ✅ `LogUtil` - утилита логирования (полностью)
- ✅ `FileOperationsUtil` - операции с файлами (частично)

## Конфигурация Gradle

### testOptions

Настройки для unit тестов:
```kotlin
testOptions {
    unitTests {
        isIncludeAndroidResources = true
        isReturnDefaultValues = true
        all {
            maxParallelForks = 4
        }
    }
}
```

### JaCoCo исключения

Исключенные классы из coverage:
- `**/databinding/**` - сгенерированные классы ViewBinding
- `**/di/Hilt_*` - сгенерированные классы Hilt
- `**/BR.class` - сгенерированный класс ViewBinding
- `**/BuildConfig.*` - конфигурация сборки
- `**/R.class` и `**/R$*.class` - ресурсы Android
- `**/*_HiltModules*` - модули Hilt
- `**/*_MembersInjector*` - инжекторы Hilt
- `**/*_Factory*` - фабрики Hilt
- `**/*_Provide*Factory*` - провайдеры Hilt

## Текущее состояние

### ✅ Реализовано (Этап 1 - завершён)
- Структура папок для тестов (unit и instrumentation)
- Базовый класс `BaseUnitTest` для всех unit тестов
- CoroutinesTestRule для тестирования корутин (интегрирован в BaseUnitTest)
- Обновленная конфигурация Gradle (testOptions, JaCoCo)
- Скрипт-генератор тестовых изображений
- Задача для проверки минимального coverage (30%)
- **217 тестов**, все проходят успешно

### ✅ Реализовано (Этап 2 - завершён)
- Unit тесты для утилит пакета `util`:
  - `PerformanceMonitorTest` - 30 тестов
  - `CompressionBatchTrackerTest` - 15 тестов
  - `StatsTrackerTest` - 20 тестов
  - `SettingsManagerTest` - 25 тестов
  - `ConstantsTest` - 10 тестов
  - `EventTest` и `EventObserverTest` - 15 тестов
  - `LogUtilTest` - 12 тестов
  - `FileOperationsUtilTest` - 18 тестов
- **Тестовый генератор изображений** (`TestImageGenerator`)
- **Mock-контекст** для тестов
- **TestUtilities** - вспомогательные функции для тестов
- **WorkManagerTestModule** - модуль для тестирования WorkManager
- **robolectric.properties** - конфигурация Robolectric

### ✅ Реализовано (Этап 3 - в процессе)
- **Instrumentation UI тесты для MainActivity:**
  - `MainActivityTest` - 15 UI тестов для проверки критических сценариев
    - Проверка запуска приложения
    - Проверка отображения всех UI элементов
    - Проверка переключателей (автосжатие, режим сохранения, мессенджеры)
    - Проверка выбора качества сжатия
    - Проверка кнопки выбора фото
- **Скрипт для instrumentation тестов:** `run_instrumentation_tests.sh`

### ⏳ В планах (Этап 4)
- Дополнительные instrumentation тесты для PermissionsManager
- Instrumentation тесты для фоновых сервисов
- Unit тесты для MainViewModel (требует рефакторинг синглтонов)
- Unit тесты для ImageCompressionWorker
- Увеличение coverage до 50-70%
- Добавление интеграционных тестов

## Зависимости для тестирования

### Unit тесты
- JUnit 4.13.2
- Robolectric 4.11
- MockK 1.13.10
- kotlinx-coroutines-test 1.10.2
- androidx.arch.core:core-testing 2.2.0
- Hilt Testing 2.57.1
- **androidx.work:work-testing:2.10.3** (добавлено для unit тестов)

### Instrumentation тесты
- JUnit (androidx.test.ext:junit)
- Espresso 3.6.1
- UIAutomator 2.3.0
- MockK-Android 1.13.10
- WorkManager Testing 2.10.3
- Hilt Testing 2.57.1

## Статистика тестирования (Январь 2026)

- **Всего unit тестов:** 231
- **Проходят успешно:** 218 (94.4%)
- **Instrumentation тесты:** 15 (MainActivityTest)
- **Общее покрытие:** ~8-10%
- **Целевое покрытие:** 60-70%

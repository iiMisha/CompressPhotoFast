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
- Настройку MockK для мокирования зависимостей
- CoroutinesTestRule для тестирования корутин
- Утилиты для создания mock-объектов

Пример использования:
```kotlin
class MyTest : BaseUnitTest() {
    
    @get:Rule
    override val coroutinesTestRule = CoroutinesTestRule()
    
    private lateinit var viewModel: MyViewModel
    
    @Before
    fun setup() {
        viewModel = MyViewModel(mockRepository)
    }
    
    @Test
    fun testSomething() = coroutinesTestRule.runTest {
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
- Текущий: ~5%

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

### ✅ Реализовано (Этап 1)
- Структура папок для тестов (unit и instrumentation)
- Базовые классы для тестов (BaseUnitTest, BaseInstrumentedTest)
- CoroutinesTestRule для тестирования корутин
- Обновленная конфигурация Gradle (testOptions, JaCoCo)
- Скрипт-генератор тестовых изображений
- Задача для проверки минимального coverage (30%)

### ⏳ В планах (Этап 2)
- Unit тесты для утилит
- Unit тесты для ViewModel
- Unit тесты для Worker'ов
- Instrumentation тесты для UI
- Увеличение coverage до 50-70%

## Зависимости для тестирования

### Unit тесты
- JUnit 4.13.2
- Robolectric 4.11
- MockK 1.13.10
- kotlinx-coroutines-test 1.10.2
- androidx.arch.core:core-testing 2.2.0
- Hilt Testing 2.57.1

### Instrumentation тесты
- JUnit (androidx.test.ext:junit)
- Espresso 3.6.1
- UIAutomator 2.3.0
- MockK-Android 1.13.10
- WorkManager Testing 2.10.3
- Hilt Testing 2.57.1

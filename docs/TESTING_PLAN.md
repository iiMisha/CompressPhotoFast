# План доработки системы автотестирования CompressPhotoFast

**Версия:** 1.0  
**Дата:** 14.01.2026  
**Текущий coverage:** ~5%  
**Целевой coverage:** 50-70%

---

## 1. Архитектура тестирования

### 1.1 Структура папок для тестов

```
app/src/
├── test/                                    # Unit тесты
│   └── java/com/compressphotofast/
│       ├── base/                            # Базовые классы
│       │   ├── BaseUnitTest.kt             # Базовый класс для unit тестов
│       │   ├── CoroutinesTestRule.kt        # Правило для тестирования корутин
│       │   └── InstantExecutorExtension.kt  # Extension для LiveData
│       ├── util/                            # Тесты утилит
│       │   ├── SettingsManagerTest.kt
│       │   ├── FileOperationsUtilTest.kt
│       │   ├── UriUtilTest.kt
│       │   ├── ImageCompressionUtilTest.kt
│       │   ├── ExifUtilTest.kt
│       │   ├── FileInfoUtilTest.kt
│       │   ├── ConstantsTest.kt
│       │   ├── LogUtilTest.kt
│       │   ├── EventObserverTest.kt
│       │   ├── CompressionBatchTrackerTest.kt
│       │   ├── StatsTrackerTest.kt
│       │   ├── UriProcessingTrackerTest.kt
│       │   ├── PerformanceMonitorTest.kt
│       │   ├── OptimizedCacheUtilTest.kt
│       │   ├── NotificationUtilTest.kt
│       │   ├── PermissionsManagerTest.kt
│       │   ├── TempFilesCleanerTest.kt
│       │   ├── ImageProcessingCheckerTest.kt
│       │   ├── ImageProcessingUtilTest.kt
│       │   ├── SequentialImageProcessorTest.kt
│       │   ├── MediaStoreUtilTest.kt
│       │   ├── BatchMediaStoreUtilTest.kt
│       │   ├── MediaStoreObserverTest.kt
│       │   └── GalleryScanUtilTest.kt
│       ├── ui/                              # Тесты UI компонентов
│       │   └── MainViewModelTest.kt
│       ├── worker/                          # Тесты Worker'ов
│       │   └── ImageCompressionWorkerTest.kt
│       └── di/                              # Тесты DI
│           └── AppModuleTest.kt
│
├── androidTest/                             # Instrumentation тесты
│   └── java/com/compressphotofast/
│       ├── base/                            # Базовые классы
│       │   ├── BaseInstrumentedTest.kt      # Базовый класс для instrumentation тестов
│       │   └── HiltTestRule.kt              # Правило для Hilt
│       ├── ui/                              # UI тесты (Espresso)
│       │   └── MainActivityTest.kt
│       ├── util/                            # Интеграционные тесты утилит
│       │   ├── MediaStoreUtilInstrumentedTest.kt
│       │   ├── FileOperationsUtilInstrumentedTest.kt
│       │   └── UriUtilInstrumentedTest.kt
│       ├── service/                         # Тесты сервисов
│       │   ├── BackgroundMonitoringServiceTest.kt
│       │   └── ImageDetectionJobServiceTest.kt
│       └── worker/                          # Тесты Worker'ов на устройстве
│           └── ImageCompressionWorkerInstrumentedTest.kt
│
└── main/
    └── res/
        └── raw/                             # Тестовые ресурсы
            ├── test_image_small.jpg          # Маленькое изображение (<100KB)
            ├── test_image_medium.jpg         # Среднее изображение (~500KB)
            ├── test_image_large.jpg          # Большое изображение (~2MB)
            ├── test_image_with_exif.jpg      # Изображение с EXIF
            ├── test_image_screenshot.jpg     # Скриншот
            ├── test_image_messenger.jpg      # Фото из мессенджера
            └── test_image_heic.heic          # HEIC изображение (опционально)
```

### 1.2 Базовые классы для тестов

#### BaseUnitTest.kt
```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 31, 32, 33, 34, 35])
abstract class BaseUnitTest {
    @get:Rule
    val coroutinesTestRule = CoroutinesTestRule()

    @get:Rule
    val instantExecutorRule = InstantExecutorExtension()

    protected val testDispatcher = coroutinesTestRule.testDispatcher
}
```

#### CoroutinesTestRule.kt
```kotlin
class CoroutinesTestRule : TestWatcher() {
    val testDispatcher = StandardTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
```

#### InstantExecutorExtension.kt
```kotlin
class InstantExecutorExtension : BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun executeOnMainThread(runnable: Runnable) = runnable.run()
        })
    }

    override fun afterEach(context: ExtensionContext) {
        ArchTaskExecutor.getInstance().setDelegate(null)
    }
}
```

#### BaseInstrumentedTest.kt
```kotlin
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
abstract class BaseInstrumentedTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Inject
    lateinit var context: Context

    @Before
    fun setup() {
        hiltRule.inject()
    }
}
```

### 1.3 Паттерны организации тестов

1. **AAA Pattern** (Arrange-Act-Assert) для всех тестов
2. **Given-When-Then** для сложных сценариев
3. **Parameterized tests** для тестирования с разными входными данными
4. **Nested test classes** для группировки связанных тестов

### 1.4 Стратегия мокинга зависимостей

**MockK** будет использоваться для мокинга:
- Context (Robolectric предоставляет реальный контекст)
- SharedPreferences
- ContentResolver
- WorkManager
- UriProcessingTracker
- SettingsManager
- MediaStore

**Robolectric** будет использоваться для:
- Android Framework компонентов
- Context
- Resources
- ContentResolver (частично)

---

## 2. Конфигурация Gradle

### 2.1 Изменения в app/build.gradle.kts

```kotlin
android {
    // ... существующая конфигурация ...

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "2048m"
                it.jvmArgs("-XX:MaxMetaspaceSize=512m")
            }
        }
        animationsDisabled = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{ALN,LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}"
            excludes += "/META-INF/{ASL2.0,LGPL2.1}"
        }
    }
}

// Улучшенная конфигурация JaCoCo
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    sourceDirectories.setFrom(files(
        "${project.layout.projectDirectory.dir("src/main/java")}",
        "${project.layout.projectDirectory.dir("src/main/kotlin")}"
    ))

    classDirectories.setFrom(files(
        fileTree("${project.layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
            exclude(
                // Исключаем сгенерированные файлы
                "**/databinding/**",
                "**/di/Hilt_*",
                "**/BR.*",
                "**/BuildConfig.*",
                "**/CompressPhotoApp.*",
                // Исключаем UI компоненты (будут покрыты instrumentation тестами)
                "**/ui/MainActivity.*",
                "**/ui/*Binding.*",
                // Исключаем сервисы (будут покрыты instrumentation тестами)
                "**/service/**",
                // Исключаем BroadcastReceiver
                "**/BootCompletedReceiver.*"
            )
        }
    ))

    executionData.setFrom(files(
        "${project.layout.buildDirectory.get()}/jacoco/testDebugUnitTest.exec"
    ))

    doLast {
        println("✅ JaCoCo отчет сгенерирован")
        println("📊 HTML: app/build/reports/jacoco/jacocoTestReport/html/index.html")
        println("📄 XML: app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml")
    }
}

// Задача для объединения coverage из unit и instrumentation тестов
tasks.register<JacocoReport>("jacocoCombinedReport") {
    dependsOn("testDebugUnitTest", "connectedDebugAndroidTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    sourceDirectories.setFrom(files(
        "${project.layout.projectDirectory.dir("src/main/java")}",
        "${project.layout.projectDirectory.dir("src/main/kotlin")}"
    ))

    classDirectories.setFrom(files(
        fileTree("${project.layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
            exclude(
                "**/databinding/**",
                "**/di/Hilt_*",
                "**/BR.*",
                "**/BuildConfig.*"
            )
        }
    ))

    executionData.setFrom(files(
        "${project.layout.buildDirectory.get()}/jacoco/testDebugUnitTest.exec",
        "${project.layout.buildDirectory.get()}/outputs/code_coverage/connectedDebugAndroidTest/connected/*.ec"
    ))
}

// Параллельный запуск тестов с улучшенной конфигурацией
tasks.withType<Test> {
    maxParallelForks = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    
    testLogging {
        events("passed", "skipped", "failed", "standard_out", "standard_error")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

// Задача для проверки минимального coverage
tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("jacocoTestReport")

    violationRules {
        rule {
            limit {
                minimum = "0.30".toBigDecimal() // Минимум 30% coverage
            }
        }
        
        rule {
            element = "CLASS"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.20".toBigDecimal() // Минимум 20% для каждого класса
            }
        }
    }
}
```

### 2.2 Дополнительные зависимости для тестирования

```kotlin
dependencies {
    // ... существующие зависимости ...

    // Дополнительные зависимости для тестирования
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("com.google.truth:truth:1.1.5") // Для более читаемых утверждений
    testImplementation("org.robolectric:robolectric:4.11")
    
    // Для instrumentation тестов
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.google.truth:truth:1.1.5")
    
    // Hilt testing
    testImplementation("com.google.dagger:hilt-android-testing:2.57.1")
    kspTest("com.google.dagger:hilt-android-compiler:2.57.1")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.57.1")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.57.1")
}
```

---

## 3. Тестовые ресурсы

### 3.1 Требования к тестовым изображениям

| Имя файла | Размер | Описание | EXIF | Назначение |
|-----------|--------|----------|------|------------|
| test_image_small.jpg | ~50KB | Маленькое изображение | Нет | Тестирование минимального размера |
| test_image_medium.jpg | ~500KB | Среднее изображение | Да | Основные тесты сжатия |
| test_image_large.jpg | ~2MB | Большое изображение | Да | Тестирование больших файлов |
| test_image_with_exif.jpg | ~300KB | С полным EXIF | GPS, даты, камера | Тестирование сохранения EXIF |
| test_image_screenshot.jpg | ~200KB | Скриншот | Нет | Тестирование фильтрации |
| test_image_messenger.jpg | ~400KB | Из мессенджера | Нет | Тестирование фильтрации |
| test_image_heic.heic | ~500KB | HEIC формат | Да | Тестирование конвертации |

### 3.2 Генератор тестовых изображений

Создать утилиту для генерации тестовых изображений:

```kotlin
// app/src/test/java/com/compressphotofast/util/TestImageGenerator.kt
object TestImageGenerator {
    fun createTestImage(
        width: Int = 1920,
        height: Int = 1080,
        quality: Int = 90,
        addExif: Boolean = false
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLUE)
        
        val file = File.createTempFile("test_image_", ".jpg")
        val outputStream = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        outputStream.close()
        bitmap.recycle()
        
        if (addExif) {
            addExifData(file)
        }
        
        return file
    }
    
    private fun addExifData(file: File) {
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "48.8566")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "2.3522")
        exif.setAttribute(ExifInterface.TAG_DATETIME, "2024:01:14 12:00:00")
        exif.setAttribute(ExifInterface.TAG_MAKE, "Test Camera")
        exif.setAttribute(ExifInterface.TAG_MODEL, "Test Model")
        exif.saveAttributes()
    }
}
```

---

## 4. План написания тестов (по приоритетам)

### Этап 1: Базовая инфраструктура (1-2 дня)

**Задачи:**
1. Создать структуру папок для тестов
2. Создать базовые классы (BaseUnitTest, BaseInstrumentedTest, CoroutinesTestRule)
3. Настроить конфигурацию Gradle для JaCoCo
4. Создать тестовые изображения
5. Создать генератор тестовых изображений

**Ожидаемый результат:**
- Готовая инфраструктура для написания тестов
- JaCoCo корректно генерирует отчеты
- Тестовые ресурсы доступны

**Coverage после этапа:** ~5% (без изменений)

---

### Этап 2: Unit тесты для утилит (5-7 дней)

**Приоритет 1: Критические утилиты (высокий приоритет)**

| Утилита | Количество тестов | Ожидаемый coverage | Сложность |
|---------|-------------------|-------------------|-----------|
| SettingsManager | 15-20 | 90%+ | Низкая |
| FileOperationsUtil | 20-25 | 80%+ | Средняя |
| UriUtil | 25-30 | 75%+ | Высокая |
| ImageCompressionUtil | 30-35 | 70%+ | Высокая |
| ExifUtil | 15-20 | 80%+ | Средняя |

**Пример теста для SettingsManager:**
```kotlin
class SettingsManagerTest : BaseUnitTest() {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setup() {
        sharedPreferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        settingsManager = SettingsManager(sharedPreferences)
    }

    @Test
    fun `isAutoCompressionEnabled returns false by default`() {
        val result = settingsManager.isAutoCompressionEnabled()
        assertFalse(result)
    }

    @Test
    fun `setAutoCompression updates preference`() {
        settingsManager.setAutoCompression(true)
        val result = settingsManager.isAutoCompressionEnabled()
        assertTrue(result)
    }

    @Test
    fun `getCompressionQuality returns medium by default`() {
        val result = settingsManager.getCompressionQuality()
        assertEquals(Constants.COMPRESSION_QUALITY_MEDIUM, result)
    }

    @Test
    fun `setCompressionQuality updates preference`() {
        settingsManager.setCompressionQuality(Constants.COMPRESSION_QUALITY_HIGH)
        val result = settingsManager.getCompressionQuality()
        assertEquals(Constants.COMPRESSION_QUALITY_HIGH, result)
    }

    @Test
    fun `savePendingDeleteUri adds uri to set`() {
        val testUri = "content://test/123"
        settingsManager.savePendingDeleteUri(testUri)
        val uris = settingsManager.getPendingDeleteUris()
        assertTrue(uris.contains(testUri))
    }

    @Test
    fun `getAndRemoveFirstPendingDeleteUri removes and returns uri`() {
        val testUri = "content://test/123"
        settingsManager.savePendingDeleteUri(testUri)
        val result = settingsManager.getAndRemoveFirstPendingDeleteUri()
        assertEquals(testUri, result)
        assertFalse(settingsManager.getPendingDeleteUris().contains(testUri))
    }

    @Test
    fun `isFirstLaunch returns true by default`() {
        val result = settingsManager.isFirstLaunch()
        assertTrue(result)
    }

    @Test
    fun `setFirstLaunch updates preference`() {
        settingsManager.setFirstLaunch(false)
        val result = settingsManager.isFirstLaunch()
        assertFalse(result)
    }

    @Test
    fun `shouldProcessScreenshots returns true by default`() {
        val result = settingsManager.shouldProcessScreenshots()
        assertTrue(result)
    }

    @Test
    fun `setProcessScreenshots updates preference`() {
        settingsManager.setProcessScreenshots(false)
        val result = settingsManager.shouldProcessScreenshots()
        assertFalse(result)
    }

    @Test
    fun `shouldIgnoreMessengerPhotos returns true by default`() {
        val result = settingsManager.shouldIgnoreMessengerPhotos()
        assertTrue(result)
    }

    @Test
    fun `setIgnoreMessengerPhotos updates preference`() {
        settingsManager.setIgnoreMessengerPhotos(false)
        val result = settingsManager.shouldIgnoreMessengerPhotos()
        assertFalse(result)
    }

    @Test
    fun `isSaveModeReplace returns false by default`() {
        val result = settingsManager.isSaveModeReplace()
        assertFalse(result)
    }

    @Test
    fun `setSaveMode updates preference`() {
        settingsManager.setSaveMode(true)
        val result = settingsManager.isSaveModeReplace()
        assertTrue(result)
    }

    @Test
    fun `getSaveMode returns SEPARATE by default`() {
        val result = settingsManager.getSaveMode()
        assertEquals(Constants.SAVE_MODE_SEPARATE, result)
    }

    @Test
    fun `setCompressionPreset sets correct quality`() {
        settingsManager.setCompressionPreset(CompressionPreset.LOW)
        assertEquals(Constants.COMPRESSION_QUALITY_LOW, settingsManager.getCompressionQuality())
        
        settingsManager.setCompressionPreset(CompressionPreset.MEDIUM)
        assertEquals(Constants.COMPRESSION_QUALITY_MEDIUM, settingsManager.getCompressionQuality())
        
        settingsManager.setCompressionPreset(CompressionPreset.HIGH)
        assertEquals(Constants.COMPRESSION_QUALITY_HIGH, settingsManager.getCompressionQuality())
    }
}
```

**Приоритет 2: Второстепенные утилиты (средний приоритет)**

| Утилита | Количество тестов | Ожидаемый coverage | Сложность |
|---------|-------------------|-------------------|-----------|
| FileInfoUtil | 10-15 | 80%+ | Низкая |
| Constants | 5-10 | 100% | Низкая |
| LogUtil | 5-10 | 60%+ | Низкая |
| EventObserver | 10-15 | 85%+ | Низкая |
| CompressionBatchTracker | 15-20 | 80%+ | Средняя |
| StatsTracker | 15-20 | 80%+ | Средняя |
| UriProcessingTracker | 20-25 | 75%+ | Средняя |
| PerformanceMonitor | 10-15 | 70%+ | Низкая |
| OptimizedCacheUtil | 15-20 | 75%+ | Средняя |
| NotificationUtil | 10-15 | 60%+ | Средняя |
| PermissionsManager | 15-20 | 75%+ | Средняя |
| TempFilesCleaner | 10-15 | 80%+ | Низкая |

**Приоритет 3: Сложные утилиты (низкий приоритет)**

| Утилита | Количество тестов | Ожидаемый coverage | Сложность |
|---------|-------------------|-------------------|-----------|
| ImageProcessingChecker | 20-25 | 70%+ | Высокая |
| ImageProcessingUtil | 15-20 | 70%+ | Высокая |
| SequentialImageProcessor | 25-30 | 65%+ | Высокая |
| MediaStoreUtil | 30-35 | 60%+ | Высокая |
| BatchMediaStoreUtil | 20-25 | 60%+ | Высокая |
| MediaStoreObserver | 15-20 | 60%+ | Высокая |
| GalleryScanUtil | 15-20 | 65%+ | Высокая |

**Ожидаемый результат после этапа 2:**
- Unit тесты для всех утилит
- Coverage: ~35-45%

---

### Этап 3: Unit тесты для бизнес-логики (3-4 дня)

**Компоненты для тестирования:**

| Компонент | Количество тестов | Ожидаемый coverage | Сложность |
|-----------|-------------------|-------------------|-----------|
| MainViewModel | 30-40 | 70%+ | Высокая |
| ImageCompressionWorker | 25-35 | 60%+ | Высокая |

**Пример теста для MainViewModel:**
```kotlin
@HiltViewModelTest
class MainViewModelTest : BaseUnitTest() {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var viewModel: MainViewModel

    @Inject
    lateinit var workManager: WorkManager

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun `setSelectedImageUri updates selectedImageUri LiveData`() = runTest {
        val testUri = Uri.parse("content://test/123")
        viewModel.setSelectedImageUri(testUri)
        
        val result = viewModel.selectedImageUri.getOrAwaitValue()
        assertEquals(testUri, result)
    }

    @Test
    fun `compressSelectedImage sets isLoading to true`() = runTest {
        val testUri = Uri.parse("content://test/123")
        viewModel.setSelectedImageUri(testUri)
        
        viewModel.compressSelectedImage()
        
        val isLoading = viewModel.isLoading.getOrAwaitValue()
        assertTrue(isLoading)
    }

    @Test
    fun `getCompressionQuality returns default medium quality`() {
        val result = viewModel.getCompressionQuality()
        assertEquals(Constants.COMPRESSION_QUALITY_MEDIUM, result)
    }

    @Test
    fun `setCompressionQuality updates compressionQuality LiveData`() {
        viewModel.setCompressionQuality(Constants.COMPRESSION_QUALITY_HIGH)
        
        val result = viewModel.compressionQuality.getOrAwaitValue()
        assertEquals(Constants.COMPRESSION_QUALITY_HIGH, result)
    }

    @Test
    fun `setCompressionPreset updates quality correctly`() {
        viewModel.setCompressionPreset(CompressionPreset.LOW)
        assertEquals(Constants.COMPRESSION_QUALITY_LOW, viewModel.compressionQuality.getOrAwaitValue())
        
        viewModel.setCompressionPreset(CompressionPreset.MEDIUM)
        assertEquals(Constants.COMPRESSION_QUALITY_MEDIUM, viewModel.compressionQuality.getOrAwaitValue())
        
        viewModel.setCompressionPreset(CompressionPreset.HIGH)
        assertEquals(Constants.COMPRESSION_QUALITY_HIGH, viewModel.compressionQuality.getOrAwaitValue())
    }

    @Test
    fun `isAutoCompressionEnabled returns false by default`() {
        val result = viewModel.isAutoCompressionEnabled()
        assertFalse(result)
    }

    @Test
    fun `setAutoCompression updates setting`() = runTest {
        viewModel.setAutoCompression(true)
        assertTrue(viewModel.isAutoCompressionEnabled())
        
        viewModel.setAutoCompression(false)
        assertFalse(viewModel.isAutoCompressionEnabled())
    }

    @Test
    fun `isSaveModeReplace returns false by default`() {
        val result = viewModel.isSaveModeReplace()
        assertFalse(result)
    }

    @Test
    fun `setSaveMode updates setting`() {
        viewModel.setSaveMode(true)
        assertTrue(viewModel.isSaveModeReplace())
        
        viewModel.setSaveMode(false)
        assertFalse(viewModel.isSaveModeReplace())
    }

    @Test
    fun `shouldIgnoreMessengerPhotos returns true by default`() {
        val result = viewModel.shouldIgnoreMessengerPhotos()
        assertTrue(result)
    }

    @Test
    fun `setIgnoreMessengerPhotos updates setting`() {
        viewModel.setIgnoreMessengerPhotos(false)
        assertFalse(viewModel.shouldIgnoreMessengerPhotos())
        
        viewModel.setIgnoreMessengerPhotos(true)
        assertTrue(viewModel.shouldIgnoreMessengerPhotos())
    }

    @Test
    fun `incrementSkippedCount increases counter`() {
        viewModel.incrementSkippedCount()
        viewModel.incrementSkippedCount()
        
        val result = viewModel.skippedCount.value
        assertEquals(2, result)
    }

    @Test
    fun `incrementAlreadyOptimizedCount increases counter`() {
        viewModel.incrementAlreadyOptimizedCount()
        viewModel.incrementAlreadyOptimizedCount()
        
        val result = viewModel.alreadyOptimizedCount.value
        assertEquals(2, result)
    }

    @Test
    fun `resetBatchCounters resets all counters`() {
        viewModel.incrementSkippedCount()
        viewModel.incrementAlreadyOptimizedCount()
        
        viewModel.resetBatchCounters()
        
        assertEquals(0, viewModel.skippedCount.value)
        assertEquals(0, viewModel.alreadyOptimizedCount.value)
    }

    @Test
    fun `toggleWarningExpanded toggles state`() {
        assertFalse(viewModel.isWarningExpanded.value)
        
        viewModel.toggleWarningExpanded()
        assertTrue(viewModel.isWarningExpanded.value)
        
        viewModel.toggleWarningExpanded()
        assertFalse(viewModel.isWarningExpanded.value)
    }
}
```

**Ожидаемый результат после этапа 3:**
- Unit тесты для бизнес-логики
- Coverage: ~45-55%

---

### Этап 4: Instrumentation тесты (4-5 дней)

**Компоненты для тестирования:**

| Компонент | Количество тестов | Ожидаемый coverage | Сложность |
|-----------|-------------------|-------------------|-----------|
| MainActivity (Espresso) | 20-30 | 60%+ | Средняя |
| MediaStoreUtil | 15-20 | 50%+ | Высокая |
| FileOperationsUtil | 15-20 | 50%+ | Высокая |
| UriUtil | 15-20 | 50%+ | Высокая |
| BackgroundMonitoringService | 10-15 | 40%+ | Высокая |
| ImageDetectionJobService | 10-15 | 40%+ | Высокая |
| ImageCompressionWorker | 15-20 | 50%+ | Высокая |

**Пример теста для MainActivity:**
```kotlin
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainActivityTest : BaseInstrumentedTest() {

    @Test
    fun `activity launches successfully`() {
        activityRule.scenario.onActivity { activity ->
            assertNotNull(activity)
        }
    }

    @Test
    fun `selectImage button is displayed`() {
        onView(withId(R.id.select_image_button))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `compress button is disabled initially`() {
        onView(withId(R.id.compress_button))
            .check(matches(not(isEnabled())))
    }

    @Test
    fun `compress button becomes enabled after selecting image`() {
        // TODO: Implement image selection
        onView(withId(R.id.compress_button))
            .check(matches(isEnabled()))
    }

    @Test
    fun `quality spinner displays correct options`() {
        onView(withId(R.id.quality_spinner))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.quality_spinner))
            .perform(click())
        
        onView(withText("Низкое"))
            .check(matches(isDisplayed()))
        onView(withText("Среднее"))
            .check(matches(isDisplayed()))
        onView(withText("Высокое"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `auto compression switch is displayed`() {
        onView(withId(R.id.auto_compression_switch))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `save mode radio buttons are displayed`() {
        onView(withId(R.id.radio_replace))
            .check(matches(isDisplayed()))
        onView(withId(R.id.radio_separate))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `ignore messenger photos checkbox is displayed`() {
        onView(withId(R.id.ignore_messenger_photos_checkbox))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `process screenshots checkbox is displayed`() {
        onView(withId(R.id.process_screenshots_checkbox))
            .check(matches(isDisplayed()))
    }
}
```

**Ожидаемый результат после этапа 4:**
- Instrumentation тесты для ключевых компонентов
- Coverage: ~55-65%

---

### Этап 5: Дополнительные тесты и оптимизация (2-3 дня)

**Задачи:**
1. Добавить parameterized тесты для граничных случаев
2. Оптимизировать медленные тесты
3. Добавить тесты для обработки ошибок
4. Улучшить coverage для сложных компонентов

**Ожидаемый результат после этапа 5:**
- Coverage: ~60-70%

---

## 5. Автоматизация на эмуляторе

### 5.1 Скрипт для запуска эмулятора

Создать скрипт `scripts/start_emulator.sh`:

```bash
#!/bin/bash

# Скрипт для запуска эмулятора Android для тестирования

set -e

echo "🚀 Запуск эмулятора Android для тестирования"
echo "=============================================="

# Проверка наличия AVD
AVD_NAME="compressphotofast_test"

if ! avdmanager list avd | grep -q "$AVD_NAME"; then
    echo "⚠️  AVD '$AVD_NAME' не найден"
    echo ""
    echo "Создание нового AVD..."
    
    # Создаем AVD с минимальными требованиями
    echo "no" | avdmanager create avd \
        -n "$AVD_NAME" \
        -k "system-images;android-30;google_apis;x86_64" \
        -d "pixel_4"
    
    echo "✅ AVD '$AVD_NAME' создан"
fi

# Запуск эмулятора
echo "📱 Запуск эмулятора..."
emulator -avd "$AVD_NAME" \
    -no-snapshot-load \
    -no-window \
    -no-audio \
    -gpu swiftshader_indirect \
    -no-boot-anim \
    -camera-back none \
    -camera-front none &

# Ожидание запуска эмулятора
echo "⏳ Ожидание запуска эмулятора..."
timeout 300 bash -c 'until adb shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do sleep 2; done'

if [ $? -eq 0 ]; then
    echo "✅ Эмулятор запущен успешно"
    
    # Показать информацию об устройстве
    DEVICE_ID=$(adb devices | grep "device$" | head -n 1 | awk '{print $1}')
    ANDROID_VERSION=$(adb -s "$DEVICE_ID" shell getprop ro.build.version.release)
    API_LEVEL=$(adb -s "$DEVICE_ID" shell getprop ro.build.version.sdk)
    
    echo "   📱 Device ID: $DEVICE_ID"
    echo "   🤖 Android: $ANDROID_VERSION (API $API_LEVEL)"
else
    echo "❌ Не удалось запустить эмулятор"
    exit 1
fi

exit 0
```

### 5.2 Интеграция с существующими скриптами

Обновить `scripts/run_all_tests.sh`:

```bash
#!/bin/bash

# Скрипт запуска всех тестов (Unit + Instrumentation) с coverage
# Выполняет полную проверку перед изменением кода

set -e

echo ""
echo "🧪 Запуск всех тестов CompressPhotoFast"
echo "======================================"
echo ""

# Перейти в директорию проекта
cd "$(dirname "$0")/.."

# 1. Проверка устройства
echo "1️⃣  Проверка устройства..."
./scripts/check_device.sh
if [ $? -ne 0 ]; then
    echo ""
    echo "❌ Устройство не найдено. Попытка запуска эмулятора..."
    ./scripts/start_emulator.sh
    
    if [ $? -ne 0 ]; then
        echo ""
        echo "❌ Не удалось запустить эмулятор. Instrumentation тесты пропущены."
        echo "   Запуск только Unit тестов..."
        SKIP_INSTRUMENTATION=true
    else
        SKIP_INSTRUMENTATION=false
    fi
else
    SKIP_INSTRUMENTATION=false
fi
echo ""

# 2. Unit тесты
echo "2️⃣  Запуск Unit тестов..."
echo "   ⏱️  Это может занять 30-60 секунд..."
./gradlew testDebugUnitTest --stacktrace

if [ $? -ne 0 ]; then
    echo ""
    echo "❌ Unit тесты не прошли!"
    echo "   Проверьте вывод выше для деталей"
    exit 1
fi
echo "   ✅ Unit тесты пройдены"
echo ""

# 3. Instrumentation тесты (если устройство подключено)
if [ "$SKIP_INSTRUMENTATION" = false ]; then
    echo "3️⃣  Запуск Instrumentation тестов на устройстве..."
    echo "   ⏱️  Это может занять 3-5 минут..."
    ./gradlew connectedDebugAndroidTest --stacktrace

    if [ $? -ne 0 ]; then
        echo ""
        echo "❌ Instrumentation тесты не прошли!"
        echo "   Проверьте вывод выше для деталей"
        exit 1
    fi
    echo "   ✅ Instrumentation тесты пройдены"
    echo ""
fi

# 4. Coverage отчет
echo "4️⃣  Генерация Coverage отчета..."
./gradlew jacocoTestReport --quiet

if [ $? -eq 0 ]; then
    echo "   ✅ Coverage отчет сгенерирован"
    echo ""
    echo "📊 Coverage отчет:"
    echo "   📁 app/build/reports/jacoco/jacocoTestReport/html/index.html"
    echo ""
    echo "   Для открытия:"
    echo "   xdg-open app/build/reports/jacoco/jacocoTestReport/html/index.html"
else
    echo "   ⚠️  Не удалось сгенерировать coverage отчет"
    echo "   Но тесты прошли успешно!"
fi

# Итог
echo ""
echo "======================================"
echo "✅ Все тесты завершены успешно!"
echo ""
echo "📈 Статистика:"
echo "   • Unit тесты: пройдены"
if [ "$SKIP_INSTRUMENTATION" = false ]; then
    echo "   • Instrumentation тесты: пройдены"
else
    echo "   • Instrumentation тесты: пропущены (нет устройства)"
fi
echo "   • Coverage: сгенерирован"
echo ""

exit 0
```

### 5.3 CI/CD интеграция (опционально)

Создать `.github/workflows/android.yml`:

```yaml
name: Android CI

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main, develop ]

jobs:
  test:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
        cache: gradle
    
    - name: Grant execute permission for gradlew
      run: chmod +x gradlew
    
    - name: Run unit tests
      run: ./gradlew testDebugUnitTest --stacktrace
    
    - name: Generate JaCoCo report
      run: ./gradlew jacocoTestReport
    
    - name: Upload coverage to Codecov
      uses: codecov/codecov-action@v3
      with:
        files: ./app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml
        flags: unittests
        name: codecov-umbrella
    
    - name: Upload test results
      if: always()
      uses: actions/upload-artifact@v3
      with:
        name: test-results
        path: app/build/test-results/
```

---

## 6. Метрики и отчетность

### 6.1 Отслеживание прогресса покрытия

**Еженедельные отчеты:**

| Неделя | Coverage | Цель | Статус |
|--------|----------|------|--------|
| 1 | 5% | 10% | ✅ |
| 2 | 15% | 20% | ✅ |
| 3 | 30% | 35% | ✅ |
| 4 | 45% | 50% | ✅ |
| 5 | 55% | 60% | ✅ |
| 6 | 65% | 70% | ✅ |

### 6.2 Генерация отчетов

**Команды для генерации отчетов:**

```bash
# Unit тесты + coverage
./gradlew testDebugUnitTest jacocoTestReport

# Все тесты + coverage
./gradlew checkAllTests

# Проверка минимального coverage
./gradlew jacocoTestCoverageVerification

# Открытие HTML отчета
xdg-open app/build/reports/jacoco/jacocoTestReport/html/index.html
```

### 6.3 Целевые показатели для каждого этапа

| Этап | Coverage | Количество тестов | Время выполнения |
|------|----------|-------------------|------------------|
| Этап 1 | 5% | 0 | 1-2 дня |
| Этап 2 | 35-45% | 300-400 | 5-7 дней |
| Этап 3 | 45-55% | 360-475 | 3-4 дня |
| Этап 4 | 55-65% | 435-570 | 4-5 дней |
| Этап 5 | 60-70% | 500-650 | 2-3 дня |

---

## 7. Риски и митигации

### 7.1 Риски

| Риск | Вероятность | Влияние | Митигация |
|------|------------|---------|-----------|
| Зависимости от Android Framework | Высокая | Высокое | Использовать Robolectric, мокать сложные зависимости |
| Асинхронность (корутины) | Средняя | Среднее | Использовать CoroutinesTestRule, runTest |
| Работа с файлами (требуются реальные изображения) | Высокая | Среднее | Создать генератор тестовых изображений |
| Hilt внедрение зависимостей | Средняя | Среднее | Использовать @HiltViewModelTest, HiltAndroidRule |
| Медленные тесты | Средняя | Низкое | Параллельный запуск, оптимизация тестов |
| Проблемы с эмулятором | Средняя | Среднее | Скрипт автоматического запуска, fallback на unit тесты |
| Недостаточное coverage сложных компонентов | Высокая | Высокое | Приоритет на критические компоненты, instrumentation тесты |

### 7.2 Решения проблем

**Проблема 1: Robolectric не поддерживает некоторые Android API**

**Решение:**
- Использовать моки для неподдерживаемых API
- Перенести тесты в instrumentation тесты
- Использовать shadow классы Robolectric

**Проблема 2: Тесты с корутинами падают**

**Решение:**
- Использовать `runTest` для suspend функций
- Использовать `CoroutinesTestRule` для переключения диспетчера
- Использовать `advanceUntilIdle()` для ожидания завершения корутин

**Проблема 3: Тесты с ContentResolver сложны**

**Решение:**
- Использовать Robolectric для базовых операций
- Мокать ContentResolver для сложных запросов
- Использовать instrumentation тесты для реальных операций

**Проблема 4: Медленные instrumentation тесты**

**Решение:**
- Параллельный запуск тестов
- Использовать `@SdkSuppress` для ограничения API уровней
- Оптимизировать setUp/tearDown методы
- Использовать флаги для запуска только нужных тестов

**Проблема 5: Недостаточное coverage для Worker'ов**

**Решение:**
- Использовать `work-testing` для unit тестов
- Создать instrumentation тесты для реального выполнения
- Мокировать зависимости Worker'ов

---

## 8. Логика предложенных изменений

### 8.1 Почему выбрана эта архитектура

1. **Разделение на unit и instrumentation тесты:**
   - Unit тесты быстрые и не требуют устройства
   - Instrumentation тесты тестируют интеграцию с Android Framework
   - Комбинация обеспечивает баланс между скоростью и покрытием

2. **Базовые классы для тестов:**
   - Уменьшают дублирование кода
   - Обеспечивают единый подход к тестированию
   - Упрощают написание новых тестов

3. **Приоритизация по критичности:**
   - Сначала тестируются критические компоненты
   - Постепенное увеличение coverage
   - Раннее выявление проблем в ключевых компонентах

4. **Использование Robolectric:**
   - Позволяет запускать unit тесты на JVM
   - Поддерживает большинство Android API
   - Быстрее instrumentation тестов

5. **Параллельный запуск тестов:**
   - Уменьшает время выполнения
   - Использует все доступные ядра CPU
   - Важно для больших наборов тестов

### 8.2 Почему выбраны эти инструменты

1. **JUnit 4:** Стандарт для unit тестирования в Android
2. **Robolectric:** Позволяет запускать Android тесты на JVM
3. **MockK:** Современная библиотека для мокинга в Kotlin
4. **Espresso:** Стандарт для UI тестирования в Android
5. **UIAutomator:** Для тестирования на уровне системы
6. **JaCoCo:** Стандарт для измерения coverage
7. **Truth:** Более читаемые утверждения по сравнению с JUnit assertions

### 8.3 Почему выбрана эта стратегия покрытия

1. **Целевой coverage 50-70%:**
   - Реалистичный для Android приложений
   - Баланс между усилиями и пользой
   - Достаточный для большинства компонентов

2. **Приоритет на утилиты:**
   - Содержат основную бизнес-логику
   - Менее зависят от Android Framework
   - Легче тестировать

3. **Instrumentation тесты для UI и сервисов:**
   - Требуют реального устройства/эмулятора
   - Тестируют интеграцию компонентов
   - Покрывают сложные сценарии

---

## 9. Заключение

Этот план обеспечивает:

1. **Постепенное увеличение coverage** с 5% до 60-70%
2. **Реалистичные сроки** - 15-21 день для полной реализации
3. **Четкие этапы** с измеримыми результатами
4. **Автоматизацию** для запуска тестов и генерации отчетов
5. **Митигацию рисков** для потенциальных проблем

План готов к реализации. После утверждения можно переходить к этапу 1.

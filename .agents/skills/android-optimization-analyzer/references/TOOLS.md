# Инструменты для мониторинга Android производительности

## Android Studio Profiler

### Memory Profiler
**Что показывает:**
- Аллокации и освобождения памяти
- Memory leaks
- Размер объектов
- GC activity

**Ключевые метрики:**
- **Heap size** - общий размер кучи
- **Allocated** - занятая память
- **Objects** - количество объектов

**Как использовать:**
1. View > Tool Windows > Profiler
2. Выбери процесс
3. Memory > Record allocation
4. Выполни операции (сжатие фото, навигация)
5. Capture heap dump для анализа утечек

### CPU Profiler
**Что показывает:**
- CPU usage по потокам
- Flame chart/Call chart
- Trace recording

**Как использовать:**
1. CPU > Record
2. Выполни подозрительные операции
3. Stop > Analyze
4. Ищи "flame" с высокой активностью на Main thread

### Network Profiler
**Что показывает:**
- Request/response timing
- Payload size
- Статус коды

## StrictMode

### Включение в Debug

```kotlin
// Application class
class CompressPhotoApp : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            enableStrictMode()
        }
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .penaltyDeathOnNetwork() // Crash on network on main thread
                .build()
        )

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClearedObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
    }
}
```

### Чтение Logcat
```bash
# Фильтр StrictMode violations
adb logcat | grep StrictMode
```

## LeakCanary

### Добавление зависимостей

```kotlin
// build.gradle.kts (app module)
dependencies {
    // Debug implementation only
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.12")
}
```

### Как работает
- Автоматически детектит Activity/Fragment leaks
- Создаёт уведомление при утечке
- Показывает reference path к утечке
- Генерирует heap dump trace

### Чтение отчётов
- Открой уведомление LeakCanary
- Изучи "Leak Trace" - путь от GC root до утечки
- Найди "Leaking: YES" объект
- Исправь удерживаемую ссылку

## Android Lint

### Запуск через Gradle
```bash
# Все проверки
./gradlew lint

# Конкретный module
./gradlew :app:lint

# Только performance проверки
./gradlew lint --issue 'Overdraw,DrawAllocation,ObsoleteSdkInt'
```

### Полезные Lint правила для производительности
| ID | Описание |
|----|----------|
| `Overdraw` | Избыточная отрисовка |
| `DrawAllocation` | Аллокации в draw() методах |
| `ObsoleteSdkInt` | Устаревший API |
| `UseSparseArrays` | Неэффективные Map с integer ключами |
| `UseValueOf` | Boxing/unboxing примитивов |
| `SuspiciousImport` | Неиспользуемые импорты |

## Custom Logging

### Performance Log

```kotlin
object PerfLog {
    private val map = mutableMapOf<String, Long>()

    fun start(tag: String) {
        map[tag] = System.nanoTime()
    }

    fun end(tag: String) {
        val start = map.remove(tag) ?: return
        val duration = (System.nanoTime() - start) / 1_000_000 // ms
        Log.d("PerfLog", "$tag: ${duration}ms")
    }

    inline fun <T> measure(tag: String, block: () -> T): T {
        start(tag)
        return block().also { end(tag) }
    }
}

// Usage:
PerfLog.measure("compress_photo") {
    compressImage(uri)
}
// Log: PerfLog: compress_photo: 234ms
```

## Profiling в Production

### Firebase Performance Monitoring

```kotlin
// Добавление зависимостей
dependencies {
    implementation("com.google.firebase:firebase-perf:20.4.0")
}

// Manual traces
val trace = Firebase.performance.newTrace("photo_compression")
trace.start()
compressImage(uri)
trace.stop()
```

### Custom Metrics

```kotlin
// Track compression time
val compressionMetric = Firebase.performance.newHttpMetric(
    "compress_image",
    FirebasePerformance.HttpMethod.POST
)

compressionMetric.start()
compressionMetric.setRequestPayloadSize(file.length())
compressionMetric.setHttpResponseCode(200)
compressionMetric.stop()
```

## Benchmarking (Jetpack Benchmark)

### Добавление модуля

```kotlin
// build.gradle.kts (benchmark module)
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }
}

dependencies {
    androidTestImplementation("androidx.benchmark:benchmark-junit4:1.2.0")
}
```

### Пример benchmark

```kotlin
@RunWith(AndroidJUnit4::class)
class CompressionBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Test
    fun benchmarkCompression() {
        benchmarkRule.measureRepeated(
            packageName = "com.compressphotofast",
            metrics = listOf(StartupTimingMetric()),
            iterations = 10,
            startupMode = StartupMode.COLD
        ) {
            val result = compressImage(testUri)
            assertThat(result).isNotNull()
        }
    }
}
```

## Memory Dump Analysis

### heapdump для анализа

```bash
# Получить heap dump
adb shell am dumpheap <pid> /data/local/tmp/heap.hprof
adb pull /data/local/tmp/heap.hprof

# Конвертировать если нужно
hprof-conv heap.hprof converted.hprof
```

### Android Studio Heap Dump Viewer
1. Profiler > Memory > Capture heap dump
2. Найти подозрительные объекты (большой count или size)
3. "Find GC Roots" для утечек
4. "Path to GC Roots" > "Exclude weak/soft/phantom refs"

## Команды ADB для анализа

### Проверка памяти процесса
```bash
adb shell dumpsys meminfo com.compressphotofast
```

### Heap info
```bash
adb shell dumpsys meminfo com.compressphotofast --package
```

### Graphics info (overdraw)
```bash
adb shell dumpsys gfxinfo com.compressphotofast
```

### CPU usage
```bash
adb shell top -n 1 | grep compressphotofast
```

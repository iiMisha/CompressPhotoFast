# Паттерны поиска мёртвого кода

## Типы неиспользуемого кода

### 1. Unused Imports

```kotlin
// ❌ Unused
import android.graphics.Color  // нигде не используется
import java.util.Date          // нигде не используется

// ✅ Used
import android.net.Uri         // используется в коде
```

**Поиск:** Автоматически через IDE lint или gradle check.

### 2. Unused Private Functions

```kotlin
class SomeClass {
    // ❌ Unused private function
    private fun helperMethod(): String {
        return "never called"
    }
    
    // ✅ Used
    fun publicMethod() {
        println("called from outside")
    }
}
```

**Поиск:** Grep для имени функции во всех файлах.

### 3. Unused Local Variables

```kotlin
fun process() {
    // ❌ Unused variable
    val temp = calculateSomething()  // результат не используется
    
    // ✅ Used
    val result = calculateSomething()
    return result
}
```

**Поиск:** Анализ использования переменной после объявления.

### 4. Unused Parameters

```kotlin
// ❌ Unused parameter
fun process(uri: Uri, unusedFlag: Boolean) {
    // unusedFlag нигде не используется
    doSomething(uri)
}
```

**Поиск:** Проверка использования параметра в теле функции.

## Исключения (НЕ считать unused)

### Hilt DI

```kotlin
// ✅ НЕ unused - используется через DI
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: SomeRepository
) : ViewModel()

// ✅ НЕ unused - предоставляет зависимость
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun provideRepository(): SomeRepository {
        return SomeRepositoryImpl()
    }
}
```

### Android Components

```kotlin
// ✅ НЕ unused - регистрируется в Manifest
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // может не иметь явных вызовов
    }
}

// ✅ НЕ unused - используется WorkManager
class ImageCompressionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    // запускается через enqueue
}
```

### Object Singletons

```kotlin
// ✅ НЕ unused - singleton утилита
object UriUtil {
    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        // методы могут вызываться из любого места
    }
}

// ✅ НЕ unused - используется через UriUtil.method()
```

### Override Methods

```kotlin
// ✅ НЕ unused - переопределение
override fun onCreate(savedInstanceState: Bundle?) {
    // вызывается системой
}

override fun onDestroy() {
    // вызывается системой
}
```

### Test Code

```kotlin
// ✅ НЕ unused - тестовый метод
@Test
fun testCompression() {
    // вызывается test runner'ом
}
```

## Поиск через Grep

```bash
# Поиск объявления функции
Grep("fun functionName")

# Поиск объявления переменной
Grep("val variableName|var variableName")

# Поиск импорта
Grep("import package.name")

# Поиск параметра в сигнатуре
Grep("fun.*parameterName")
```

## Уровни уверенности

| Уровень | Признаки | Действие |
|---------|----------|----------|
| **High** | Нет использования, нет аннотаций | Можно удалить |
| **Medium** | Возможное использование через reflection | Проверить перед удалением |
| **Low** | Hilt/Android компонент | Не удалять |

## Алгоритм анализа

1. **Сбор объявлений**
   - Все функции, переменные, импорты
   - Записать файл и строку

2. **Поиск использований**
   - Grep по имени во всех файлах
   - Учитывать import aliases

3. **Фильтрация исключений**
   - Проверить аннотации (@Inject, @Provides, @Test)
   - Проверить наследование (override)
   - Проверить Android components

4. **Генерация отчёта**
   - Группировать по уровню уверенности
   - Добавить рекомендации

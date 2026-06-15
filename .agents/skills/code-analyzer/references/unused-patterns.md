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

> **Все перечисленные ниже исключения автоматически учитываются скриптом
> `find_unused.py`** через `allowlist.txt` + детектор аннотаций/override.
> Раздел ниже — справочный: что и почему не должно попадать в отчёт.

### ALL-CAPS / однобуквенные импорты

```kotlin
// ✅ НЕ unused — используется синтаксически (R.string.*, R.drawable.*)
import com.compressphotofast.R

// ✅ НЕ unused — используется как тип в дженериках/объявлениях
import java.util.UUID          // mutableMapOf<UUID, ...>()
import android.net.Uri         // val uri: Uri
import android.graphics.Bitmap.Config.RGB_565
```

**Внимание:** эти символы часто ложно детектируются как unused в наивных
анализаторах (из-за фильтрации по `isupper()`). Скрипт их корректно учитывает.

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
| **High** | Нет использования, нет аннотаций, не override, не в allowlist | Можно удалить |
| **Medium** | Возможное использование через reflection / public API object | Проверить перед удалением |
| **Low** | Hilt/Android компонент, override, lifecycle, в allowlist | Не удалять |

## Allowlist

Файл `.agents/skills/code-analyzer/allowlist.txt` содержит явный список токенов,
которые скрипт НЕ сообщает как unused. Включает:
- ALL-CAPS импорты (`R`, `UUID`, `URI`, ...)
- Android lifecycle (`onCreate`, `onDestroy`, `doWork`, `onReceive`, ...)
- Hilt/DI (`@Inject`, `@Provides`, `@HiltViewModel`, ...)
- WorkManager/Service callbacks
- Testing (`setUp`, `tearDown`, `@Test`, ...)

**При стабильных ложных срабатываниях** добавляйте токен в `allowlist.txt`.

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

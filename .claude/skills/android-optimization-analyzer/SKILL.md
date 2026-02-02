---
name: android-optimization-analyzer
description: Анализирует Android код на предмет проблем с производительностью и памятью, предлагая конкретные рекомендации по оптимизации
user-invocable: true
arguments:
  - name: scope
    description: Область анализа (whole_project, specific_module, specific_files)
    required: false
    default: whole_project
  - name: focus_area
    description: Фокус анализа (memory, performance, ui, all)
    required: false
    default: all
  - name: thoroughness
    description: Уровень детализации анализа (quick, medium, very_thorough)
    required: false
    default: medium
---

# Android Optimization Analyzer

Этот скилл выполняет комплексный анализ Android кода на предмет проблем с производительностью и памятью.

## Что анализирует скилл

### 1. Проблемы с памятью (Memory Issues)
- **Memory Leaks**:
  - Утечки в View и Fragment (неправильное использование context)
  - Утечки в ViewModel (live data observers)
  - Утечки в корутинах (незакрытые CoroutineScope)
  - Утечки через static references
  - Утечки в listeners и callbacks

- **Избыточные аллокации**:
  - Создание объектов в циклах
  - Избыточные string concatenations
  - Неэффективное использование коллекций
  - Boxing/unboxing примитивов

- **Проблемы с изображениями** (актуально для CompressPhotoFast):
  - Загрузка больших изображений в память
  - Неэффективное декодирование Bitmap
  - Отсутствие сжатия/оптимизации
  - Утечки в image caches

### 2. Проблемы с производительностью (Performance Issues)
- **Блокировка главного потока**:
  - Долгие операции на Main Thread
  - Синхронные операции I/O на UI thread
  - Тяжёлые вычисления на главном потоке
  - Database operations на main thread

- **Неэффективные алгоритмы**:
  - O(n²) вместо O(n) там, где возможно
  - Избыточные итерации по коллекциям
  - Неоптимальные реализации поиска/фильтрации
  - N+1 проблемы при работе с БД

- **Проблемы с корутинами**:
  - Blocking calls внутри корутин
  - Неправильный выбор dispatcher
  - Отсутствие structured concurrency
  - Memory leaks через незакрытые Job

### 3. UI Проблемы
- **Overdraw**:
  - Избыточная отрисовка View
  - Ненужные background draws
  - Неэффективные layouts

- **Layout Performance**:
  - Избыточная вложенность layouts
  - Использование тяжелых View где можно легче
  - Отсутствие View Holder pattern в RecyclerView
  - Отсутствие diff util в RecyclerView

- **View Rendering**:
  - Частые invalidate() вызовы
  - Отсутствие кэширования Views
  - Избыточные measure/layout проходы

### 4. Database & Storage
- **SQLite Room**:
  - N+1 queries
  - Отсутствие индексов
  - Избыточные запросы
  - Отсутствие @Transaction где нужно

- **SharedPreferences**:
  - Блокирующие вызовы на main thread
  - Избыточные чтения/записи

- **File I/O**:
  - Синхронные операции на main thread
  - Отсутствие буферизации
  - Избыточные file operations

## Как работает анализ

### Шаг 1: Сбор информации
- Чтение Memory Bank файлов для понимания контекста
- Поиск всех исходных файлов Kotlin/Java в проекте
- Определение архитектуры проекта (MVVM, MVI, etc.)

### Шаг 2: Анализ кода
Используются специализированные агенты:
- **Explore** - для поиска файлов по паттернам
- **kotlin-specialist** - для глубокого анализа Kotlin кода
- **java-architect** - если есть legacy Java код

### Шаг 3: Категоризация проблем
Найденные проблемы группируются по:
- Критичности (Critical, High, Medium, Low)
- Типу (Memory, Performance, UI, Database)
- Местоположению (файл и строка)

### Шаг 4: Генерация рекомендаций
Для каждой проблемы предоставляется:
- Описание проблемы
- Почему это проблема (impact)
- Как исправить (concrete solution)
- Пример кода (before/after)
- Ссылки на документацию (через Context7)

## Формат отчёта

```markdown
# Android Optimization Analysis Report

## Summary
- Total Issues Found: X
- Critical: X | High: X | Medium: X | Low: X
- Focus Areas: Memory, Performance, UI, Database

## Critical Issues

### 1. [Memory Leak] Uncleared CoroutineScope in ViewModel
**Location:** `ui/main/MainViewModel.kt:45`

**Problem:**
ViewModel использует viewModelScope.launch но не отменяетjobs при очистке.

**Impact:**
Утечка памяти после закрытия экрана, потенциальное продолжение работы после уничтожения ViewModel.

**Solution:**
```kotlin
// Before
class MainViewModel : ViewModel() {
    fun loadData() {
        viewModelScope.launch {
            // work
        }
    }
}

// After
class MainViewModel : ViewModel() {
    fun loadData() {
        viewModelScope.launch {
            // work
        }
        // viewModelScope автоматически отменяется при cleared()
    }
}
```

**Documentation:**
- [Android ViewModelScope](https://developer.android.com/kotlin/coroutines/coroutinebestpractices)

---

## High Priority Issues
...

## Medium Priority Issues
...

## Low Priority Issues
...

## Recommendations
1. Общие рекомендации по проекту
2. Приоритеты исправлений
3. Инструменты для мониторинга (Android Profiler, LeakCanary, etc.)
```

## Использование скилла

### Базовое использование
```
Запусти android-optimization-analyzer
```
Это проанализирует весь проект на все типы проблем.

### Анализ конкретного модуля
```
Запусти android-optimization-analyzer с scope=specific_module и focus_area=memory
Проанализируй модуль app/src/main/java/com/compressphotofast/compression
```

### Быстрая проверка UI
```
Запусти android-optimization-analyzer с focus_area=ui и thoroughness=quick
```

### Глубокий анализ производительности
```
Запусти android-optimization-analyzer с focus_area=performance и thoroughness=very_thorough
```

## Интеграция с проектом CompressPhotoFast

Для CompressPhotoFast скилл особое внимание уделяет:
- **Оптимизация работы с изображениями** (так как это компрессор фото)
- **Memory usage при сжатии** (важно избегать OOM)
- **Performance алгоритмов сжатия**
- **Background processing** для долгих операций сжатия
- **Cancelation** операций сжатия при отмене пользователем

## Примеры работы

### Пример 1: Поиск memory leaks
```
Запусти android-optimization-analyzer с focus_area=memory
Найди все места где может быть memory leak в ViewModels и Fragments
```

### Пример 2: Оптимизация UI
```
Запусти android-optimization-analyzer с focus_area=ui
Проверь все RecyclerView на наличие ViewHolder pattern и DiffUtil
```

### Пример 3: Анализ корутин
```
Запусти android-optimization-analyzer
Проанализируй использование корутин в проекте
Особое внимание на:
- Правильный выбор dispatcher
- Отсутствие blocking calls
- Корректную отмену jobs
```

## Требования к окружению

- Android проект на Kotlin/Java
- Gradle build system
- Доступ к исходному коду проекта

## Related Tools

- **Android Profiler** - для runtime анализа
- **LeakCanary** - для детекта memory leaks
- **StrictMode** - для детекта main thread violations
- **Lint** - для статического анализа
- **R8/ProGuard** - для оптимизации кода при сборке

---
name: code-analyzer
description: |
  Анализ Kotlin/Java кода для поиска дублирующегося кода, мёртвого кода (unused) и предоставление рекомендаций по улучшению.
  
  **Когда использовать:**
  - (1) Нужно найти дубликаты функций или блоков кода
  - (2) Нужно найти неиспользуемый код (unused functions, variables, imports)
  - (3) Нужно проверить code quality и дать рекомендации
  - (4) Рефакторинг перед написанием тестов
  
  **Не изменяет код - только анализирует и выводит отчёт.**
---

# Code Analyzer

Анализирует Kotlin/Java код на предмет дублирования, мёртвого кода и качества.

## Quick Start

```bash
# Полный анализ проекта
/code-analyzer

# Анализ конкретной области
/code-analyzer scope=duplicates
/code-analyzer scope=unused
/code-analyzer scope=quality

# Анализ конкретных файлов
/code-analyzer files=ImageCompressionUtil.kt,MediaStoreUtil.kt
```

## CompressPhotoFast Специфика

**Учитывать при анализе:**

| Аспект | Описание |
|--------|----------|
| **Архитектура** | MVVM + Hilt DI, singleton objects для утилит |
| **Coroutines** | Использовать вместо GlobalScope/Handler |
| **Bitmap** | Обязательный recycle(), inSampleSize, RGB_565 |
| **MediaStore** | Пакетные операции, async обработка |
| **Singletons** | object классы используются для утилит, не считать неиспользуемыми |
| **DI** | Hilt-инжектируемые классы могут не иметь явных вызовов |

**Исключения при поиске unused кода:**
- Методы в `object` классах утилит
- Hilt-инжектируемые классы
- BroadcastReceiver, Service, Worker (регистрируются в манифесте)
- Методы с аннотациями `@Provides`, `@Binds`, `@HiltViewModel`

## Workflow

### 1. Сбор файлов

**КРИТИЧЕСКИЕ ПРАВИЛА:**
- ❌ НЕ используй `Task(Explore, ...)` - вызывает переполнение памяти
- ✅ Используй Glob/Grep/Read для поиска файлов

```bash
# Собираем файлы через Glob
Glob("**/src/main/java/**/*.kt")  # Основной код
Glob("**/src/test/**/*.kt")       # Тесты

# Фильтруем при необходимости
Grep("object.*Util")              # Singleton утилиты
Grep("class.*ViewModel")          # ViewModels
```

### 2. Анализ дубликатов

**Искать:**
- Повторяющиеся блоки кода (>5 строк)
- Похожие функции с разницей только в именах переменных
- Копипаст с небольшими изменениями

**Паттерны поиска:**
```kotlin
// Частые дубликаты в Android:
- Повторяющиеся try-catch блоки
- Похожие RecyclerView adapters
- Дублирование работы с ContentResolver
- Повторяющиеся преобразования URI
```

**См. [references/duplicates-patterns.md](references/duplicates-patterns.md)**

### 3. Анализ мёртвого кода

**Искать:**
- Unused imports
- Unused private functions
- Unused local variables
- Unused parameters

**Исключить из отчёта:**
- Hilt-инжектируемые классы
- Методы жизненного цикла (onCreate, onDestroy)
- Переопределённые методы
- Методы тестов

**См. [references/unused-patterns.md](references/unused-patterns.md)**

### 4. Анализ качества

**Проверить:**
- Соответствие code style проекта
- Отсутствие GlobalScope/Handler
- Правильное использование coroutines
- Корректная обработка Bitmap (recycle)
- Правильное использование Dispatchers

**См. [references/quality-patterns.md](references/quality-patterns.md)**

## Report Format

```markdown
# Code Analysis Report

## Summary
- Files Analyzed: X
- Duplicates Found: X
- Unused Code Items: X
- Quality Issues: X

## Duplicates

### 1. [Function Name]
**Locations:** 
- `path/to/File1.kt:line`
- `path/to/File2.kt:line`

**Similarity:** 85%

**Recommendation:** Extract to shared utility

```kotlin
// Duplicate block 1
...

// Duplicate block 2
...
```

---

## Unused Code

### 1. [Type] Name
**Location:** `path/to/File.kt:line`

**Type:** Function / Variable / Import

**Confidence:** High / Medium / Low

**Note:** Check if used via reflection/DI

---

## Quality Issues

### 1. [Category] Issue
**Location:** `path/to/File.kt:line`

**Problem:** Description

**Solution:** Recommendation

```

## Scope Options

| Scope | Что анализируется |
|-------|-------------------|
| `all` | Все категории (default) |
| `duplicates` | Только дубликаты |
| `unused` | Только мёртвый код |
| `quality` | Только качество кода |

## Thoroughness Levels

| Level | Описание | Когда использовать |
|-------|----------|-------------------|
| **quick** | Быстрый анализ критических проблем | Быстрая проверка |
| **medium** | Стандартный анализ | Рутинная проверка |
| **very thorough** | Глубокий анализ всех аспектов | Перед рефакторингом |

## References

- **[duplicates-patterns.md](references/duplicates-patterns.md)** - Паттерны поиска дубликатов
- **[unused-patterns.md](references/unused-patterns.md)** - Паттерны поиска мёртвого кода
- **[quality-patterns.md](references/quality-patterns.md)** - Паттерны качества кода

## After Analysis

После завершения анализа обновите AGENTS.md используя `/agents-updater` скилл.

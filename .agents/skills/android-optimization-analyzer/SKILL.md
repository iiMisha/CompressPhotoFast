---
name: android-optimization-analyzer
description: |
  Комплексный анализ Android кода на проблемы производительности и памяти с конкретными рекомендациями.

  **Когда использовать:**
  - Перед оптимизацией производительности или рефакторингом
  - При проблемах с памятью (OOM, crashes, memory leaks)
  - При появлении лагов/ANR в приложении
  - После добавления нового функционала для проверки
  - Регулярный профилактический анализ кода

  **Запускает агентов:** voltagent-lang:kotlin-specialist, android-silent-failure-hunter
---

# Android Optimization Analyzer

Анализирует Android код на предмет проблем с производительностью и памятью, используя специализированных агентов.

## Quick Start

```bash
# Анализ всего проекта
/android-optimization-analyzer

# Анализ конкретного модуля
/android-optimization-analyzer scope=specific_module focus_area=memory

# Быстрая проверка UI
/android-optimization-analyzer focus_area=ui thoroughness=quick
```

## CompressPhotoFast Специфика

**Особое внимание:**
- Операции с Bitmap (decode, compress, recycle)
- Memory usage при сжатии (избегать OOM)
- Background processing для долгих операций
- Корректная отмена jobs при cancelled operations
- MediaStore операции (async, без блокировки)

## Workflow

### 1. Сбор информации (в основном контексте)

**КРИТИЧЕСКИЕ ПРАВИЛА:**
- ❌ НЕ используй `Task(Explore, ...)` - вызывает переполнение памяти
- ✅ Используй Glob/Grep/Read для поиска файлов
- ✅ Читай AGENTS.md для контекста

```bash
# Читай AGENTS.md
Read: AGENTS.md

# Собирай файлы через Glob/Grep
Glob("**/*.kt")           # Все Kotlin файлы
Grep("class.*ViewModel")  # ViewModels
Grep("Bitmap|decode")     # Работа с изображениями
Grep("launch|async|Flow") # Корутины
```

### 2. Глубокий анализ (через агентов)

**Запускай агентов последовательно:**

```yaml
# 1. Анализ Kotlin кода
Task(tool: Task, subagent_type: "voltagent-lang:kotlin-specialist",
  prompt: "Выполни анализ производительности для CompressPhotoFast.
           Focus: {focus_area}
           Files: [список файлов из этапа 1]

           Проверь:
           - Memory leaks (Context, CoroutineScope, listeners)
           - Блокировку Main Thread
           - Неэффективную работу с изображениями
           - Проблемы с корутинами

           Верни: Critical/High/Medium issues с кодом (before/after)")

# 2. Проверка error handling
Task(tool: Task, subagent_type: "android-silent-failure-hunter",
  prompt: "Проверь обработку ошибок в найденных файлах.
           Фокус: file operations, image compression, MediaStore
           Верни: список silent failures с severity")
```

### 3. Генерация отчёта

Собери результаты от обоих агентов и сгенерируй структурированный отчёт (см. формат ниже).

## Categories

| Focus Area | Что проверяется |
|------------|-----------------|
| **memory** | Leaks, аллокации, Bitmap, collections |
| **performance** | Main thread blocking, алгоритмы, корутины |
| **ui** | Overdraw, layouts, RecyclerView |
| **database** | N+1 queries, индексы, transactions |
| **all** | Все категории |

## Thoroughness Levels

| Level | Описание | Когда использовать |
|-------|----------|-------------------|
| **quick** | Проверка критических проблем только | Быстрая диагностика |
| **medium** | Стандартный анализ с рекомендациями | Рутинная проверка |
| **very thorough** | Глубокий анализ всех аспектов | Перед рефакторингом |

## Report Format

```markdown
# Android Optimization Analysis Report

## Summary
- Total Issues: X
- Critical: X | High: X | Medium: X | Low: X
- Files Analyzed: X

## Critical Issues

### 1. [Type] Title
**Location:** `path/to/file.kt:line`

**Problem:** Краткое описание

**Impact:** Почему это критично

**Solution:** Конкретное исправление с кодом

---

## Recommendations
1. Приоритет действий
2. Инструменты мониторинга
```

## References

- **[PATTERNS.md](references/PATTERNS.md)** - Search patterns для поиска проблем
- **[TOOLS.md](references/TOOLS.md)** - Инструменты для мониторинга

**Примеры оптимизаций (по категориям):**
- **[memory-examples.md](references/memory-examples.md)** - Memory leaks (Context, CoroutineScope, Listener, Bitmap)
- **[performance-examples.md](references/performance-examples.md)** - Performance (File I/O, Image decoding, Collections)
- **[coroutine-examples.md](references/coroutine-examples.md)** - Coroutines (Dispatchers, Structured concurrency)
- **[database-examples.md](references/database-examples.md)** - Database (N+1, Transactions, Indexes)
- **[ui-examples.md](references/ui-examples.md)** - UI (RecyclerView, Layouts, Overdraw)
- **[collections-examples.md](references/collections-examples.md)** - Collections (Filtering, Grouping, Sequences)

## Update AGENTS.md

После завершения анализа обновите AGENTS.md используя `/agents-updater` скилл.
См. `agents-instructions.md` для деталей.

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

## Как вызывать

Скилл загружается через `skill(name="code-analyzer")` или упоминанием в запросе.
Параметры (scope, файлы, пороги) передаются в свободной форме в тексте запроса.

> Скилл **не регистрирует** slash-команду — `/code-analyzer` не работает.
> Все инструкции ниже описывают фактический workflow.

## Движок анализа

Анализ использует **два движка** в комбинации:

| Движок | Назначение | Скорость |
|--------|-----------|----------|
| **Python-скрипты** (`scripts/`) | Дубликаты + кастомные паттерны + приватные функции/свойства | Секунды |
| **`./gradlew lintDebug`** | Unused imports/ресурсы, качество (авторитетнее для Kotlin) | ~1-2 мин |

Ключевое правило: **`gradlew lint` первичен для unused/качества**, скрипты — для дубликатов и приватных деклараций, которые линтер может пропустить.

## Quick Start

```bash
# === 1. Python-скрипты (быстро, без сборки) ===

# Дубликаты (function-level + sliding-window)
python3 .agents/skills/code-analyzer/scripts/find_duplicates.py app/src/main --format json

# Unused private функции/свойства/импорты
python3 .agents/skills/code-analyzer/scripts/find_unused.py app/src/main --format json

# === 2. Gradle Lint (медленнее, но авторитетнее для unused/quality) ===
./gradlew lintDebug
# Читать: app/build/reports/lint-results-debug.txt
```

## CompressPhotoFast Специфика

**Учитывать при анализе:**

| Аспект | Описание |
|--------|----------|
| **Архитектура** | MVVM + Hilt DI, singleton objects для утилит |
| **Coroutines** | Использовать вместо GlobalScope/Handler |
| **Bitmap** | Обязательный recycle(), inSampleSize, RGB_565 |
| **MediaStore** | Пакетные операции, async обработка |
| **Singletons** | `object` классы для утилит — НЕ считать неиспользуемыми |
| **DI** | Hilt-инжектируемые классы могут не иметь явных вызовов |
| **Allowlist** | `allowlist.txt` в корне скилла — known-ok срабатывания (R, UUID, lifecycle, DI) |
| **История** | Недавно проведена масштабная очистка мёртвого кода (см. AGENTS.md) — не сообщать о deliberately удалённых элементах |

**Исключения при поиске unused кода (скрипты обрабатывают автоматически):**
- Методы в `object` классах утилит
- Hilt-инжектируемые декларации с аннотациями `@Inject`, `@Provides`, `@Binds`, `@HiltViewModel`
- BroadcastReceiver, Service, Worker (регистрируются в манифесте)
- Lifecycle/override методы (onCreate, onDestroy, doWork, onReceive и т.п. — см. allowlist)
- ALL-CAPS импорты (R, UUID, URI) — используются синтаксически

## Workflow

### 1. Сбор файлов

**КРИТИЧЕСКИЕ ПРАВИЛА:**
- ❌ НЕ используй `task(subagent_type=explore)` для больших обходов — может вызвать переполнение контекста
- ✅ Используй `Glob`/`Grep`/`Read` напрямую для поиска файлов

```bash
# Собираем файлы через Glob
Glob("app/src/main/java/**/*.kt")   # Основной код
Glob("app/src/test/**/*.kt")        # Тесты

# При необходимости — фильтруем
Grep("object.*Util")                # Singleton утилиты
Grep("class.*ViewModel")            # ViewModels
```

> Скрипты автоматически исключают `build/`, `.gradle/`, `generated/`, `.idea/`.

### 2. Запуск движков анализа

**Шаг A — Дубликаты (Python):**

```bash
python3 .agents/skills/code-analyzer/scripts/find_duplicates.py app/src/main \
    --format json --output /tmp/kilo/dups.json
```

Параметры:
- `--min-lines 6` — размер sliding-window (дефолт 6)
- `--threshold 0.85` — порог сходства функций
- `--no-intrafile` / `--no-crossfile` — отключить intra/cross-file
- `--allowlist <path>` — свой allowlist (по умолчанию `../allowlist.txt`)

**Шаг B — Unused код (Python):**

```bash
python3 .agents/skills/code-analyzer/scripts/find_unused.py app/src/main \
    --format json --output /tmp/kilo/unused.json
```

Каждый кандидат содержит `confidence`: `High` (можно удалять) / `Medium` (проверить) / `Low` (не трогать — отфильтровано).

**Шаг C — Gradle Lint (опционально, но авторитетно для unused/quality):**

```bash
./gradlew lintDebug
# Читать: app/build/reports/lint-results-debug.txt
# Или HTML: app/build/reports/lint-results-debug.html
```

Секции lint-отчёта, релевантные анализу: `UnusedResources`, `UnusedImports`, `IconLauncherShape`, `UnsafeExperimentalUsageError`.

### 3. Верификация кандидатов (LLM)

Скрипты дают **кандидатов**. Перед внесением в отчёт — обязательная верификация:

```bash
# Для каждого кандидата — проверить реальное использование
Grep("functionName")  # во всех файлах, не только в исходном
Read("file.kt", offset=<line>, limit=15)  # контекст декларации
```

**Проверять:**
- Использование через reflection (DataStore, аннотации)
- Косвенные вызовы (через `::funcRef`, `KClass`)
- Использование в тестах (`app/src/test/`)
- Lifecycle-вызовы системой

### 4. Анализ качества (LLM по паттернам)

Используй `references/quality-patterns.md` для проверки:
- Отсутствие `GlobalScope` / `Handler().post`
- Правильное использование coroutines
- Корректная обработка Bitmap (recycle)
- Правильные Dispatchers
- Resource management (use{})

## Allowlist

Файл `.agents/skills/code-analyzer/allowlist.txt` — список «ok»-срабатываний:
- ALL-CAPS импорты (`R`, `UUID`, `URI`)
- Android lifecycle методы (`onCreate`, `onDestroy` и т.д.)
- Hilt/DI аннотации и точки входа
- WorkManager/Service callbacks
- Testing callbacks (`setUp`, `tearDown`)

**Пополнение:** при стабильных ложных срабатываниях добавляй токен в `allowlist.txt`.
Скрипты загружают его автоматически (один токен на строку, `#` — комментарий).

## Report Format

```markdown
# Code Analysis Report

## Summary
- Files Analyzed: X
- Duplicates Found: X (High: X, Medium: X)
- Unused Code Items: X (по уровням confidence)
- Quality Issues: X

## Duplicates

### 1. [Function Name]
**Locations:** 
- `path/to/File1.kt:line`
- `path/to/File2.kt:line`

**Similarity:** 85% (High/Medium/Low)

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

| Scope | Что анализируется | Какие движки |
|-------|-------------------|-------------|
| `all` | Все категории (default) | скрипты + lint |
| `duplicates` | Только дубликаты | `find_duplicates.py` |
| `unused` | Только мёртвый код | `find_unused.py` + `./gradlew lintDebug` |
| `quality` | Только качество кода | `./gradlew lintDebug` + LLM-паттерны |

## Thoroughness Levels

| Level | Описание | Когда использовать |
|-------|----------|-------------------|
| **quick** | Только Python-скрипты, без lint | Быстрая проверка |
| **medium** | Скрипты + lint, без ручной верификации каждого | Рутинная проверка |
| **very thorough** | Скрипты + lint + полная верификация кандидатов | Перед рефакторингом |

## References

- **[duplicates-patterns.md](references/duplicates-patterns.md)** - Паттерны поиска дубликатов
- **[unused-patterns.md](references/unused-patterns.md)** - Паттерны поиска мёртвого кода
- **[quality-patterns.md](references/quality-patterns.md)** - Паттерны качества кода
- **[allowlist.txt](allowlist.txt)** - Список known-ok срабатываний

## After Analysis

После завершения анализа:
1. Если найдены **подтверждённые** проблемы, требующие изменений кода → обнови AGENTS.md через `/agents-updater` (описать найденные проблемы и план устранения).
2. Если ничего значимого не найдено → НЕ вызывать `/agents-updater`.

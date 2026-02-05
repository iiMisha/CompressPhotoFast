# Правила разработки CompressPhotoFast

**Язык:** Русский | **ОС:** Linux Mint

---

## КРИТИЧЕСКИ ВАЖНЫЕ ПРАВИЛА (Обязательны к выполнению)

```
1. ЧИТАТЬ Memory Bank в НАЧАЛЕ КАЖДОЙ задачи
2. ИСПОЛЬЗОВАТЬ Агенты/Скиллы для ВСЕХ изменений кода
3. ВЫПОЛНЯТЬ ./gradlew assembleDebug после КАЖДОГО изменения кода
4. ИСПОЛЬЗОВАТЬ /test-runner для ВСЕХ запусков тестов
```

**Нарушение этих правил приведёт к неправильной работе.**

---

## 1. Memory Bank - ОБЯЗАТЕЛЬНО

### ВЫПОЛНИ:
```
В НАЧАЛЕ КАЖДОЙ задачи:
1. ПРОЧИТАЙ .claude/memory-bank/brief.md
2. ПРОЧИТАЙ .claude/memory-bank/product.md
3. ПРОЧИТАЙ .claude/memory-bank/context.md     ← КРИТИЧНО!
4. ПРОЧИТАЙ .claude/memory-bank/architecture.md
5. ПРОЧИТАЙ .claude/memory-bank/tech.md
6. ПРОЧИТАЙ .claude/memory-bank/tasks.md
```

### ПОСЛЕ ЧТЕНИЯ:
ОБЯЗАТЕЛЬНО укажи в ответе: `[Memory Bank: Active]`

### ИСКЛЮЧЕНИЙ НЕТ:
Даже если ты "помнишь" проект - контекст мог измениться.

---

## 2. Рабочий процесс - СТРОГАЯ ПОСЛЕДОВАТЕЛЬНОСТЬ

```
Memory Bank → Агент/Скилл → Сборка → ... → Тесты (ТОЛЬКО В КОНЦЕ)
```

### 2.1 Сборка - ОБЯЗАТЕЛЬНО ПОСЛЕ КАЖДОГО ИЗМЕНЕНИЯ КОДА

```bash
./gradlew assembleDebug
```

- ОБЯЗАТЕЛЬНО выполнять после КАЖДОГО изменения кода
- ИСКЛЮЧЕНИЙ НЕТ
- Если сборка упала - исправь ошибки перед продолжением

### 2.2 Тестирование - ОБЯЗАТЕЛЬНО ЧЕРЕЗ SUBAGENT

**КРИТИЧЕСКОЕ ПРАВИЛО:**
```
НИКОГДА не запускай тесты в основном контексте!
ВСЕ тесты ДОЛЖНЫ запускаться в ОТДЕЛЬНОМ субагенте через Task tool.
```

**НАПИСАНИЕ тестов:**
- Используй агент `voltagent-lang:kotlin-specialist`

**ЗАПУСК тестов:**
- Используй Task tool с субагентом `general-purpose` для запуска тестов
- Команду теста передавай в промпте субагента
- НИКОГДА не запускай тесты напрямую через Bash в основном контексте

| Тип тестов | Команда | Субагент | Время |
|-----------|---------|----------|------|
| Unit | `./gradlew testDebugUnitTest` | `general-purpose` | ~10м |
| Инструменты | `./scripts/run_instrumentation_tests.sh` | `general-purpose` | ~15м |
| Все | `./scripts/run_all_tests.sh` | `general-purpose` | ~20м |

**Пример запуска через Task:**
```
Task(tool: Task, subagent_type: "general-purpose",
  prompt: "Запусти unit тесты: ./gradlew testDebugUnitTest.
   После завершения верни summary результатов.")
```

**Примечание:** Instrumentation тесты требуют эмулятор `Small_Phone`.

---

## 3. Агенты - ОБЯЗАТЕЛЬНО ДЛЯ ЗАДАЧ С КОДОМ

### ДЕРЕВО РЕШЕНИЙ:
```
Задача связана с кодом?
├─ ДА → Есть специализированный агент?
│   ├─ ДА → ИСПОЛЬЗУЙ АГЕНТА
│   └─ НЕТ → Используй агент general-purpose
└─ НЕТ → Можно сделать вручную?
    ├─ ДА → Сделай сам (Read, Glob, Grep)
    └─ НЕТ → Используй агент general-purpose
```

### ОБЯЗАТЕЛЬНОЕ ИСПОЛЬЗОВАНИЕ АГЕНТОВ:

| Тип задачи | Агент | Обязателен? |
|-----------|-------|-------------|
| Написание Kotlin/Android кода | `voltagent-lang:kotlin-specialist` | ✅ ДА |
| Рефакторинг кода | `voltagent-lang:kotlin-specialist` | ✅ ДА |
| Написание тестов | `voltagent-lang:kotlin-specialist` | ✅ ДА |
| Исследование кодовой базы | `Explore` | ✅ ДА |
| Планирование архитектуры | `Plan` | ✅ ДА |
| CI/CD, Gradle, GitHub Actions | `voltagent-infra:devops-engineer` | ✅ ДА |
| База данных (Room/SQLite) | `voltagent-infra:database-administrator` | ✅ ДА |

### ОБЯЗАТЕЛЬНО ИСПОЛЬЗУЙ АГЕНТА ДЛЯ:
- ✅ Изменений 2+ файлов
- ✅ Реализации нового функционала
- ✅ Рефакторинга существующего кода
- ✅ Написания или изменения тестов
- ✅ Анализа архитектуры
- ✅ Исследования кодовой базы
- ✅ Настройки CI/CD
- ✅ Операций с базой данных

### НЕ ИСПОЛЬЗУЙ АГЕНТА ДЛЯ:
- ❌ Чтения 1 файла → Используй `Read`
- ❌ Поиска файла по точному имени → Используй `Glob`
- ❌ Поиска в 1-2 файлах → Используй `Read`/`Grep`
- ❌ Однострочных исправлений (опечатки, импорты)

### ПРИ СОМНЕНИЯХ:
**ИСПОЛЬЗУЙ АГЕНТА.** Лучше делегировать, чем сделать плохо.

### ПАРАЛЛЕЛЬНОЕ ВЫПОЛНЕНИЕ:
✅ Запускай нескольких агентов параллельно, когда задачи независимы

---

## 4. Локальные агенты - Справочник

**Расположение:** `.claude/agents/`

**Языковые агенты:**
```
kotlin-specialist     → Kotlin/Android (Compose, Coroutines, KMP, Room)
java-architect        → Java + Android SDK архитектура
```

**Инфраструктурные агенты:**
```
deployment-engineer       → CI/CD, Gradle, GitHub Actions
devops-engineer           → Автоматизация
platform-engineer         → Инструменты разработки
database-administrator    → Room DB, SQLite
```

**Review агенты (локальные, адаптированные для Android):**
```
android-test-analyzer         → Анализ покрытия тестами (unit + instrumentation)
android-silent-failure-hunter → Поиск silent failures и ошибок обработки
android-code-reviewer         → Review кода на соответствие правилам проекта
```

**Вызов:** Используй глобальные префиксы `voltagent-lang:`, `voltagent-infra:`
**Локальные агенты:** Используй напрямую без префикса (например, `android-test-analyzer`)

### Когда использовать Review агентов:

| Агент | Когда использовать |
|-------|-------------------|
| `android-test-analyzer` | После создания PR, добавления тестов, проверки покрытия |
| `android-silent-failure-hunter` | После изменений с error handling, catch блоками |
| `android-code-reviewer` | Перед коммитом, перед созданием PR, после написания кода |

---

## 5. Скиллы - ОБЯЗАТЕЛЬНО ДЛЯ СПЕЦИФИЧЕСКИХ ЗАДАЧ

### ПРАВИЛО:
Скиллы - это ПЕРВИЧНЫЙ способ выполнения специфических задач. НЕ дублируй их вручную.

### ОБЯЗАТЕЛЬНОЕ ИСПОЛЬЗОВАНИЕ СКИЛЛОВ:

| Скилл | Когда использовать (ОБЯЗАТЕЛЬНО) |
|-------|--------------------------------|
| `/memory-bank-updater` | Начало НОВОЙ задачи, конец задачи, изменение архитектуры |
| `/android-optimization-analyzer` | Перед оптимизацией производительности или анализом проблем |
| `/lint-check` | Перед коммитом в main, review кода, поиск ошибок |
| `/test-runner` | **ЗАПУСК ТЕСТОВ** (всегда через этот скилл!) |

### ЗАПРЕЩЕНО:
```
❌ Запуск тестов через Bash напрямую → Используй Task tool с general-purpose
❌ Запуск тестов в основном контексте → Используй субагента через Task
❌ Написание ручных скриптов для тестов → Используй скиллы
❌ Пропуск lint проверки перед значительными изменениями
❌ Забывание обновить Memory Bank после завершения задачи
```

### ОБЯЗАТЕЛЬНЫЙ РАБОЧИЙ ПРОЦЕСС:
```
1. Начало задачи  → /memory-bank-updater (обновить context/tasks)
2. Написание кода → voltagent-lang:kotlin-specialist
3. После кода     → android-code-reviewer (review изменений)
4. Перед коммитом → /lint-check + android-test-analyzer (проверить тесты)
5. Запуск тестов  → Task tool + general-purpose (в отдельном контексте!)
6. Конец задачи    → /memory-bank-updater (документировать результаты)
```

**ВАЖНО: Тесты ВСЕГДА запускаются через Task tool:**
```yaml
Task(tool: Task, subagent_type: "general-purpose",
  prompt: "Запусти unit тесты: ./gradlew testDebugUnitTest")
```

### ДОПОЛНИТЕЛЬНЫЕ REVIEW АГЕНТЫ:
```
Перед созданием PR:
  - android-code-reviewer (финальный review)
  - android-test-analyzer (проверка тестов)
  - android-silent-failure-hunter (если были изменения error handling)
```

### АВТОМАТИЗАЦИЯ ЧЕРЕЗ АГЕНТОВ

**Все скиллы используют субагентов для выполнения задач:**

| Скилл | Вызываемые агенты | Назначение |
|-------|------------------|------------|
| `/test-runner` | `general-purpose` | Запуск тестов в изолированном контексте |
| `/android-test-suite` | `general-purpose`, `android-test-analyzer` | Запуск тестов + анализ покрытия |
| `/lint-check` | `general-purpose`, `kotlin-specialist`, `android-code-reviewer` | Lint + исправление + review |
| `/android-optimization-analyzer` | `kotlin-specialist`, `android-silent-failure-hunter` | Анализ производительности + error handling |
| `/memory-bank-updater` | (нет агентов) | Прямые инструменты Glob/Grep/Read |

**Вызов скилла автоматически вызывает необходимые агенты через Task tool.**

---

## 6. Руководство по использованию инструментов

### ПРЕДПОЧИТАЙ СПЕЦИАЛИЗИРОВАННЫЕ ИНСТРУМЕНТЫ:
- Файловые операции → `Read`, `Write`, `Edit` (НЕ bash echo/cat)
- Поиск файлов → `Glob` (НЕ bash find)
- Поиск содержимого → `Grep` (НЕ bash grep)
- Git операции → Инструмент `Bash`

### НИКОГДА НЕ ИСПОЛЬЗУЙ:
- ❌ `find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk` через Bash
- ❌ `echo` для коммуникации - выводи текст напрямую

---

## 7. Быстрая шпаргалка

```bash
# Memory Bank
ПРОЧИТАЙ: .claude/memory-bank/*.md
Статус: [Memory Bank: Active]

# Сборка
./gradlew assembleDebug

# Тесты (ВСЕГДА через Task tool!)
Task(general-purpose): "./gradlew testDebugUnitTest"
Task(general-purpose): "./scripts/run_instrumentation_tests.sh"
Task(general-purpose): "./scripts/run_all_tests.sh"

# Качество кода
Скилл: /lint-check

# Документация
Скилл: /memory-bank-updater
```

---

## КОНТРОЛЬ СОБЛЮДЕНИЯ ПРАВИЛ

Эти правила **СТРОГО ОБЯЗАТЕЛЬНЫ**.

Нарушение приведёт к:
- Неправильному коду
- Падающим сборкам
- Сломанным тестам
- Потере работы

**При сомнениях: ПЕРЕЧИТАЙ ПРАВИЛА.**

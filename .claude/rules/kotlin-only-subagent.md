---
paths:
- "src/**/*.kt"
- "app/src/**/*.kt"
- "feature/**/*.kt"
- "core/**/*.kt"
- "*/src/**/*.kt"
- "**/*.kt"
---

# Kotlin Development Rules

## КРИТИЧЕСКИ ВАЖНОЕ ПРАВИЛО

**ВСЕ операции с Kotlin файлами ДОЛЖНЫ выполняться ТОЛЬКО через субагента voltagent-lang:kotlin-specialist.**

**ЭТО ПРАВИЛО ПРИМЕНЯЕТСЯ КО ВСЕМ .kt ФАЙЛАМ В ПРОЕКТЕ.**

---

## Что запрещено

### ❌ СТРОГО ЗАПРЕЩЕНО в основном разговоре:

1. **Edit tool для .kt файлов**
   - НЕ используй Edit для редактирования Kotlin кода
   - Никаких исключений

2. **Write tool для .kt файлов**
   - НЕ создавай новые .kt файлы через Write
   - Никаких исключений

3. **Прямое изменение Kotlin кода**
   - Никаких изменений Kotlin кода в основном разговоре
   - Никаких "быстрых правок"
   - Никаких "небольших изменений"

4. **Glob/Grep/Read для исследования Kotlin кода**
   - НЕ используй для исследования Kotlin кода
   - Для исследований → `Task(Explore)`

---

## Что разрешено

### ✅ В основном разговоре разрешено:

1. **Read tool** - чтение конкретных .kt файлов для ознакомления
2. **Управление todo** - создание и обновление todo списков
3. **Запуск субагентов** - использование Task tool
4. **Bash tool** - запуск команд (./gradlew, git, etc)
5. **Ответы на вопросы** - объяснение, обсуждение

---

## Обязательный Workflow для Kotlin

### Шаг 1: Исследование (ОБЯЗАТЕЛЬНЫЙ)

Перед любой работой с Kotlin кодом:

```
Task(Explore, "medium", "Найти существующие Kotlin реализации [компонент]

Нужно изучить:
1. Паттерны Kotlin кода в проекте
2. Используемые библиотеки (androidx, Coil, Coroutines, etc.)
3. Стиль кода и конвенции
4. Архитектурные принципы
5. Best practices

Цель: Реализовать/изменить [задача] следуя этим паттернам.")
```

### Шаг 2: Чтение Memory Bank

ПЕРЕД任何 Kotlin работой прочитай:

- `.claude/memory-bank/brief.md`
- `.claude/memory-bank/architecture.md`
- `.claude/memory-bank/tech.md`
- `.claude/memory-bank/context.md`
- `.kilocode/rules/rules.md`

### Шаг 3: Реализация через субагента

```
Task(voltagent-lang:kotlin-specialist, "
[Задача]

КОНТЕКСТ ИЗ ИССЛЕДОВАНИЯ:
[Результаты из Explore - паттерны, стиль, библиотеки]

АРХИТЕКТУРА ИЗ MEMORY BANK:
[Архитектурные принципы из architecture.md]

ТЕХНОЛОГИИ:
[Tech stack из tech.md - Kotlin, Coroutines, Flow, Compose, etc.]

ТРЕБОВАНИЯ:
[Детальные требования]
")
```

### Шаг 4: Сборка (ОБЯЗАТЕЛЬНАЯ)

```
./gradlew assembleDebug
```

или для экономии ресурсов:

```
GRADLE_MODE=eco ./gradlew assembleDebug
```

---

## Kotlin-Specific Best Practices

При передаче задачи в voltagent-lang:kotlin-specialist, убедись что контекст включает:

### 1. Kotlin язык и библиотеки
- Kotlin 1.9+ / 2.0+
- Coroutines (kotlinx.coroutines)
- Flow и StateFlow для реактивности
- Sealed classes для state/events

### 2. Android компоненты
- Jetpack Compose для UI
- ViewModel + StateFlow для state management
- Room для базы данных
- WorkManager для фоновой работы
- Coil для загрузки изображений

### 3. Архитектурные паттерны
- Clean Architecture (Domain/Data/Presentation layers)
- Repository pattern
- Use cases (Interactors)
- Dependency Injection (Koin/Hilt)

### 4. Тестирование
- JUnit для unit тестов
- MockK для mocking
- Coroutines Test для корутин
- Turbine для Flow тестирования

### 5. Стиль кода
- Kotlin coding conventions
- Null safety (везде nullable/?)
- Extension functions
- Data classes
- Companion objects для констант

---

## Примеры правильного workflow

### Пример 1: Создание новой ViewModel

```
// ❌ НЕПРАВИЛЬНО
User: "Создай CompressionViewModel"
Assistant: [Edit tool] → compressionViewModel.kt

// ✅ ПРАВИЛЬНО
User: "Создай CompressionViewModel"
Assistant:
1. Task(Explore, "medium", "Найти существующие ViewModels в проекте")
2. Read Memory Bank файлы
3. Task(voltagent-lang:kotlin-specialist, "
   Создать CompressionViewModel следуя паттернам:
   - StateFlow для state
   - Sealed class для событий
   - Repository через DI
   - Coroutines для асинхронности
   ")
4. ./gradlew assembleDebug
```

### Пример 2: Исправление бага в Kotlin коде

```
// ❌ НЕПРАВИЛЬНО
User: "Исправь ошибку в функции compress"
Assistant: [Read] → [Edit] compressionManager.kt

// ✅ ПРАВИЛЬНО
User: "Исправь ошибку в функции compress"
Assistant:
1. Task(Explore, "medium", "Найти место с ошибкой и изучить контекст")
2. Read context.md для understanding текущего состояния
3. Task(voltagent-lang:kotlin-specialist, "
   Исправить ошибку в функции compress
   Контекст ошибки: [...]
   Связанный код: [...]
   ")
4. ./gradlew assembleDebug
```

### Пример 3: Рефакторинг Kotlin кода

```
// ✅ ПРАВИЛЬНЫЙ WORKFLOW
1. Task(Explore, "very thorough", "Проанализировать весь модуль компрессии")
2. Task(Plan, "medium", "План рефакторинга compression модуля")
3. Read architecture.md для понимания архитектурных ограничений
4. Task(voltagent-lang:kotlin-specialist, "
   Выполнить рефакторинг следуя плану:
   [План из Plan агента]

   Сохранить:
   - Архитектуру (Clean Architecture)
   - Паттерны (StateFlow, sealed classes)
   - Стиль кода (Kotlin conventions)
   ")
5. ./gradlew assembleDebug
6. ./gradlew test (для проверки тестов)
```

---

## Контрольные вопросы

Перед выполнением любой операции с .kt файлами, спроси себя:

1. **Это .kt файл?**
   - ДА → Иди к шагу 2
   - НЕТ → Можно выполнять в основном разговоре

2. **Это Read или Bash?**
   - ДА → Можно в основном разговоре
   - НЕТ → Иди к шагу 3

3. **Это Edit/Write для .kt?**
   - ДА → ЗАПРЕЩЕНО! Используй `Task(voltagent-lang:kotlin-specialist)`
   - НЕТ → Проверь еще раз

---

## Специальные случаи

### Чтение .kt файлов (Read tool)

✅ **РАЗРЕШЕНО** в основном разговоре для:
- Ознакомления с кодом
- Понимания структуры
- Объяснения пользователю
- Анализа перед запуском субагента

❌ **НЕЛЬЗЯ** использовать Read вместо исследования:
- Не читай множество файлов последовательно
- Для исследований → `Task(Explore)`

### Небольшие изменения

Даже для "небольших изменений" в .kt файлах:
```
// ❌ НЕПРАВИЛЬНО
"Это небольшое изменение, я сделаю напрямую"
[Edit tool]

// ✅ ПРАВИЛЬНО
"Даже небольшое изменение требует субагента"
Task(voltagent-lang:kotlin-specialist, "Внести небольшое изменение: [...]")
```

### Горячие фиксы (Hotfix)

Даже для срочных исправлений:
```
// ❌ НЕПРАВИЛЬНО
"Это срочно, сделаю быстро"
[Edit tool]

// ✅ ПРАВИЛЬНО
"Срочно, но правильно через субагента"
Task(voltagent-lang:kotlin-specialist, "Срочно исправить: [...]", model="haiku")
```

Используй `model="haiku"` для быстродействия, но всё равно через субагента.

---

## Интеграция с другими правилами

### Связь с mandatory-subagent-usage.md
- Это правило является specialization общего правила
- Применяется только к .kt файлам

### Связь с workflow-implementation.md
- Следует общему workflow implementation
- Добавляет Kotlin-specific требования

### Связь с workflow-research.md
- Для исследований Kotlin кода → `Task(Explore)`
- Не Glob/Grep/Read напрямую

---

## Последствия нарушения

Если это правило нарушено (Edit/Write .kt в основном разговоре):

1. **Код будет inconsistent** с существующим
2. **Паттерны не соблюдены** - ухудшение качества
3. **Архитектура нарушена** - технический долг
4. **Сборка может fail** - непроверенный код
5. **Тесты могут сломаться** - отсутствие тестирования

**ПОЭТОМУ ЭТО ПРАВИЛО ОБЯЗАТЕЛЬНО К СОБЛЮДЕНИЮ.**

---

## Summary

### Для .kt файлов:

**ДОЛЖЕН:**
- ✅ Использовать `Task(voltagent-lang:kotlin-specialist)` для Edit/Write
- ✅ Использовать `Task(Explore)` для исследований
- ✅ Читать Memory Bank перед работой
- ✅ Собирать проект после изменений

**НЕ ДОЛЖЕН:**
- ❌ Edit/Write .kt файлы напрямую
- ❌ Пропускать этап исследования
- ❌ Игнорировать Memory Bank
- ❌ Забывать про сборку

---

**ЭТО ПРАВИЛО ПРИМЕНЯЕТСЯ КО ВСЕМ ФАЙЛАМ С ПУТЁМ **/*.kt**

**БЕЗ ИСКЛЮЧЕНИЙ. БЕЗ ПОСЛАБЛЕНИЙ. БЕЗ "ОДНОРАЗОВЫХ" ИСКЛЮЧЕНИЙ.**

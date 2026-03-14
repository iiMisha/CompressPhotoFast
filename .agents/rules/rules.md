# Правила разработки CompressPhotoFast

**Язык:** Русский | **ОС:** Linux Mint

---

## 📁 Unified AI Configuration

**Source of Truth:** `.agents/` (универсальная директория для всех AI-инструментов)

**Все AI-IDE используют симлинки на файлы:**
- `.claude/rules/` → `.agents/rules/` (симлинк на директорию)
- `.claude/agents/` → директория с симлинками на файлы из `.agents/agents/`
- `.claude/skills/` → директория с симлинками на директории из `.agents/skills/`
- `.kilocode/rules/` → `.agents/rules/` (симлинк на директорию)
- `.kilocode/agents/` → директория с симлинками на файлы из `.agents/agents/`
- `.kilocode/skills/` → директория с симлинками на директории из `.agents/skills/`
- `.gemini/rules/` → `.agents/rules/` (симлинк на директорию)
- `.gemini/agents/` → директория с симлинками на файлы из `.agents/agents/`
- `.gemini/skills/` → директория с симлинками на директории из `.agents/skills/`
- `.opencode/rules/` → `.agents/rules/` (симлинк на директорию)
- `.opencode/agents/` → директория с симлинками на файлы из `.agents/agents/`
- `.opencode/skills/` → директория с симлинками на директории из `.agents/skills/`
- `.cursor/rules/` → `.agents/rules/` (симлинк на директорию)
- `.cursor/agents/` → директория с симлинками на файлы из `.agents/agents/`
- `.cursor/skills/` → директория с симлинками на директории из `.agents/skills/`

**Преимущества:**
- Не привязано к конкретному агенту/IDE
- Мгновенная синхронизация между всеми IDE
- Единственный источник правды
- Легко добавить новую IDE

**Структура `.agents/`:**
- `.agents/rules/` - основные правила проекта (rules.md)
- `.agents/agents/` - локальные агенты (14 файлов)
- `.agents/skills/` - скиллы (6 директорий)
- `.agents/[другое]/` - (в будущем) прочие конфигурации

**Примечание:** Файлы `agents-instructions.md` находятся в папках соответствующих скиллов (`.agents/skills/*/`).

---

## 📁 Создание новых скиллов и агентов

### ПРАВИЛО:
```
ВСЕ новые скиллы и агенты ДОЛЖНЫ создаваться в .agents/:
- Новые агенты → .agents/agents/
- Новые скиллы → .agents/skills/
```

### ПОСЛЕ СОЗДАНИЯ:
1. Симлинки на файлы автоматически обеспечат доступ из всех AI-IDE
2. НЕ создавайте агенты/скиллы напрямую в .claude/, .cursor/, .kilocode/, .gemini/, .opencode/
3. При необходимости обновления симлинков для новой AI-IDE, создайте директории и симлинки на файлы

### ПРИМЕР:
```bash
# Создание нового агента
touch .agents/agents/my-new-agent.md

# Создание нового скилла
mkdir -p .agents/skills/my-new-skill
touch .agents/skills/my-new-skill/SKILL.md
touch .agents/skills/my-new-skill/agents-instructions.md
```

---

## 📚 Документация проекта

**Основная информация о проекте** (архитектура, tech stack, особенности):
→ Смотри **AGENTS.md** в корне проекта

**Этот файл** (rules.md) содержит инструкции для AI-агентов по работе с проектом.

---

## 🚨 КРИТИЧЕСКИ ВАЖНЫЕ ПРАВИЛА (Обязательны к выполнению)

```
1. ЧИТАТЬ AGENTS.md в НАЧАЛЕ КАЖДОЙ задачи
2. ИСПОЛЬЗОВАТЬ Агенты/Скиллы для ВСЕХ изменений кода
3. ВЫПОЛНЯТЬ ./gradlew assembleDebug после КАЖДОГО изменения кода
4. ИСПОЛЬЗОВАТЬ Task tool для ВСЕХ запусков тестов
```

**Нарушение этих правил приведёт к неправильной работе.**

---

## 1. AGENTS.md - ОБЯЗАТЕЛЬНО К ЧТЕНИЮ

### ПРАВИЛО:
```
В НАЧАЛЕ КАЖДОЙ задачи:
1. ПРОЧИТАЙ AGENTS.md (файл в корне проекта)
2. ПОНИМИЙ контекст задачи (архитектура, tech stack, известные проблемы)
```

### ПОСЛЕ ЧТЕНИЯ:
ОБЯЗАТЕЛЬНО укажи в ответе: `[AGENTS.md: Active]`

### ИСКЛЮЧЕНИЙ НЕТ:
Даже если ты "помнишь" проект - контекст мог измениться.

---

## 2. Рабочий процесс - СТРОГАЯ ПОСЛЕДОВАТЕЛЬНОСТЬ

```
AGENTS.md → Агент/Скилл → Сборка → ... → Тесты (ТОЛЬКО В КОНЦЕ)
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

| Тип тестов | Команда | Субагент | Время | Когда использовать |
|-----------|---------|----------|------|-------------------|
| Unit | `./gradlew testDebugUnitTest` | `general-purpose` | ~10м | **ВСЕГДА** после изменений кода |
| Инструменты | `./scripts/run_instrumentation_tests.sh` | `general-purpose` | ~20м | Только при необходимости |
| Все | `./scripts/run_all_tests.sh` | `general-purpose` | ~30м | Только перед релизом |

**ПРАВИЛО:** По умолчанию запускай **ТОЛЬКО unit тесты**. Instrumentation тесты запускай только:
- Перед созданием PR в main ветку
- При изменении кода, который работает с Android API (Activity, Fragment, BroadcastReceiver)
- При подозрении на проблемы с UI или фоновыми сервисами

**Пример запуска через Task:**
```
Task(tool: Task, subagent_type: "general-purpose",
  prompt: "Запусти unit тесты: ./gradlew testDebugUnitTest.
   После завершения верни summary результатов.")
```

**Примечание:** Instrumentation тесты требуют эмулятор `Small_Phone`.

### 2.3 Запуск эмулятора - Быстрая инструкция

**Проверка доступных эмуляторов:**
```bash
export ANDROID_HOME=/home/misha/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools
$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager list avd
```

**Запуск эмулятора Small_Phone:**
```bash
export ANDROID_HOME=/home/misha/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools
# Запуск (блокирует терминал до закрытия эмулятора)
$ANDROID_HOME/emulator/emulator -avd Small_Phone -no-boot-anim -no-snapshot -gpu swiftshader_indirect
```

**Ожидание готовности эмулятора:**
```bash
export ANDROID_HOME=/home/misha/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
# Ждём пока статус изменится с "offline" на "device"
for i in {1..30}; do
  sleep 5
  status=$(adb devices | grep emulator | awk '{print $2}')
  echo "Attempt $i: $status"
  if [ "$status" = "device" ]; then
    echo "✓ Emulator ready!"
    break
  fi
done
```

**Установка и запуск приложения:**
```bash
# Сборка и установка
./gradlew installDebug

# Запуск MainActivity
adb shell am start -n com.compressphotofast/.ui.MainActivity
```

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
| Написание Python CLI кода | `python-pro` | ✅ ДА |
| Рефакторинг кода | `voltagent-lang:kotlin-specialist` | ✅ ДА |
| Написание тестов | `voltagent-lang:kotlin-specialist` | ✅ ДА |
| Исследование кодовой базы | `Explore` | ✅ ДА |
| Планирование архитектуры | `Plan` | ✅ ДА |
| CI/CD, Gradle, GitHub Actions | `voltagent-infra:devops-engineer` | ✅ ДА |
| База данных (Room/SQLite/MediaStore) | `voltagent-infra:database-administrator` или `sql-pro` | ✅ ДА |
| SQL запросы, MediaStore оптимизация | `sql-pro` | ✅ ДА |
| Безопасность (URI, права доступа) | `security-engineer` | ✅ ДА |
| SLOs, производительность, мониторинг | `sre-engineer` | ✅ РЕКОМЕНДУЕТСЯ |
| Инциденты, кризис-менеджмент | `incident-responder` | ✅ ПРИ ИНЦИДЕНТАХ |

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

**Расположение:** `.agents/agents/`

**Языковые агенты:**
```
kotlin-specialist     → Kotlin/Android (Compose, Coroutines, KMP, Room)
java-architect        → Java + Android SDK архитектура
python-pro            → Python 3.10+ (CLI часть: Pillow, Click, asyncio)
```

**Инфраструктурные агенты:**
```
deployment-engineer       → CI/CD, Gradle, GitHub Actions
devops-engineer           → Автоматизация
platform-engineer         → Инструменты разработки
database-administrator    → Room DB, SQLite
sre-engineer              → SLOs, мониторинг, надежность, chaos engineering
```

**Безопасность и инциденты:**
```
security-engineer         → DevSecOps, URI безопасность, EXIF/GPS данные
incident-responder        → Crisis-менеджмент, root cause analysis, postmortems
```

**Базы данных и SQL:**
```
sql-pro                   → MediaStore запросы, SQLite оптимизация, индексы
```

**Review агенты (локальные, адаптированные для Android):**
```
android-test-analyzer         → Анализ покрытия тестами (unit + instrumentation)
android-silent-failure-hunter → Поиск silent failures и ошибок обработки
android-code-reviewer         → Review кода на соответствие правилам проекта
```

**Вызов:** Используй глобальные префиксы `voltagent-lang:`, `voltagent-infra:`
**Локальные агенты:** Используй напрямую без префикса (например, `android-test-analyzer`, `python-pro`, `sql-pro`, `security-engineer`)

### Когда использовать Review агентов:

| Агент | Когда использовать |
|-------|-------------------|
| `android-test-analyzer` | После создания PR, добавления тестов, проверки покрытия |
| `android-silent-failure-hunter` | После изменений с error handling, catch блоками |
| `android-code-reviewer` | Перед коммитом, перед созданием PR, после написания кода |
| `python-pro` | Изменения в CLI части (cli.py, compression.py, exif_handler.py) |
| `sql-pro` | Оптимизация MediaStore запросов, работа с SQLite/Room |
| `security-engineer` | Анализ безопасности (URI, права доступа, EXIF/GPS), review кода на уязвимости |
| `sre-engineer` | Анализ производительности, мониторинг, SLOs, postmortems |
| `incident-responder` | При инцидентах (краши, data loss, ANR), root cause analysis |

---

## 5. Скиллы - ОБЯЗАТЕЛЬНО ДЛЯ СПЕЦИФИЧЕСКИХ ЗАДАЧ

### ПРАВИЛО:
Скиллы - это ПЕРВИЧНЫЙ способ выполнения специфических задач. НЕ дублируй их вручную.

### ОБЯЗАТЕЛЬНОЕ ИСПОЛЬЗОВАНИЕ СКИЛЛОВ:

| Скилл | Когда использовать (ОБЯЗАТЕЛЬНО) |
|-------|--------------------------------|
| `/agents-updater` | Начало НОВОЙ задачи, конец задачи, изменение архитектуры |
| `/android-optimization-analyzer` | Перед оптимизацией производительности или анализом проблем |
| `/lint-check` | Перед коммитом в main, review кода, поиск ошибок |
| `/test-runner` | **ЗАПУСК ТЕСТОВ** (всегда через этот скилл!) |

### ЗАПРЕЩЕНО:
```
❌ Запуск тестов через Bash напрямую → Используй Task tool с general-purpose
❌ Запуск тестов в основном контексте → Используй субагента через Task
❌ Написание ручных скриптов для тестов → Используй скиллы
❌ Пропуск lint проверки перед значительными изменениями
❌ Забывание обновить AGENTS.md после завершения задачи
```

### ОБЯЗАТЕЛЬНЫЙ РАБОЧИЙ ПРОЦЕСС:
```
1. Начало задачи  → /agents-updater (обновить AGENTS.md)
2. Написание кода → voltagent-lang:kotlin-specialist
3. После кода     → android-code-reviewer (review изменений)
4. Перед коммитом → /lint-check + android-test-analyzer (проверить тесты)
5. Запуск тестов  → Task tool + general-purpose (в отдельном контексте!)
6. Конец задачи    → /agents-updater (документировать результаты)
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
| `/android-test-suite` | `general-purpose` ⛔ НЕ вызывает android-test-analyzer (рекурсия!) | Запуск тестов в изолированном контексте |
| `/lint-check` | `general-purpose`, `kotlin-specialist`, `android-code-reviewer` | Lint + исправление + review |
| `/android-optimization-analyzer` | `kotlin-specialist`, `android-silent-failure-hunter` | Анализ производительности + error handling |
| `/agents-updater` | (нет агентов) | Прямые инструменты Glob/Grep/Read |

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
# AGENTS.md
ПРОЧИТАЙ: AGENTS.md
Статус: [AGENTS.md: Active]

# Сборка
./gradlew assembleDebug

# Тесты (ВСЕГДА через Task tool!)
# Unit тесты - запускай ВСЕГДА после изменений кода
Task(general-purpose): "./gradlew testDebugUnitTest"

# Instrumentation тесты - только при необходимости (перед PR, изменения Android API)
# Task(general-purpose): "./scripts/run_instrumentation_tests.sh"

# Все тесты - только перед релизом
# Task(general-purpose): "./scripts/run_all_tests.sh"

# Качество кода
Скилл: /lint-check

# Документация
Скилл: /agents-updater
```

---

## ⚠️ КОНТРОЛЬ СОБЛЮДЕНИЯ ПРАВИЛ

Эти правила **СТРОГО ОБЯЗАТЕЛЬНЫ**.

Нарушение приведёт к:
- Неправильному коду
- Падающим сборкам
- Сломанным тестам
- Потере работы

**При сомнениях: ПЕРЕЧИТАЙ ПРАВИЛА.**

# OpenCode Integration

Этот проект настроен для работы с [OpenCode CLI](https://opencode.ai/) через символические ссылки на конфигурацию Claude Code.

## Структура интеграции

```
CompressPhotoFast/
├── .opencode/                # OpenCode конфигурация (симлинки)
│   ├── skills/          → .claude/skills/    (shared skills)
│   ├── agents/          → .claude/agents/    (shared agents)
│   ├── rules/           → .claude/rules/     (shared rules)
│   └── memory-bank/     → .claude/memory-bank/ (shared context)
└── AGENTS.md            → CLAUDE.md          (shared instructions)
```

## Использование

### Запуск OpenCode

```bash
# Из корня проекта
opencode
```

OpenCode автоматически обнаружит:
- ✅ Все скиллы из `.claude/skills/`
- ✅ Всех агентов из `.claude/agents/`
- ✅ Правила проекта из `CLAUDE.md`
- ✅ Memory Bank для контекста

## Доступные скиллы

| Скилл | Описание | Вызов |
|-------|----------|-------|
| `test-runner` | Умный запуск тестов с определением изменённых модулей | `/test-runner` |
| `android-test-suite` | Комплексное тестирование Android приложения | `/android-test-suite` |
| `lint-check` | Android Lint + Detekt статический анализ | `/lint-check` |
| `android-optimization-analyzer` | Анализ производительности и памяти | `/android-optimization-analyzer` |
| `memory-bank-updater` | Обновление Memory Bank проекта | `/memory-bank-updater` |

## Доступные агенты

| Агент | Специализация |
|-------|---------------|
| `kotlin-specialist` | Kotlin/Android (Compose, Coroutines, KMP, Room) |
| `java-architect` | Java + Android SDK архитектура |
| `deployment-engineer` | CI/CD, Gradle, GitHub Actions |
| `devops-engineer` | Автоматизация |
| `platform-engineer` | Инструменты разработки |
| `database-administrator` | Room DB, SQLite |
| `android-test-analyzer` | Анализ покрытия тестами (unit + instrumentation) |
| `android-silent-failure-hunter` | Поиск silent failures и ошибок обработки |
| `android-code-reviewer` | Review кода на соответствие правилам проекта |

## Правила проекта

Правила разработки определены в `CLAUDE.md` (доступен через `AGENTS.md`):

- 📖 **Memory Bank** - обязательное чтение перед каждой задачей
- 🤖 **Агенты** - использование специализированных агентов для изменений кода
- 🔨 **Сборка** - обязательная после каждого изменения (`./gradlew assembleDebug`)
- ✅ **Тесты** - запуск только через субагентов

Полные правила: [`.claude/rules/rules.md`](.claude/rules/rules.md)

## Memory Bank

Контекст проекта доступен в `.claude/memory-bank/`:

- [`brief.md`](../.claude/memory-bank/brief.md) - краткое описание проекта
- [`product.md`](../.claude/memory-bank/product.md) - описание продукта и функций
- [`context.md`](../.claude/memory-bank/context.md) - текущий контекст и последние изменения
- [`architecture.md`](../.claude/memory-bank/architecture.md) - архитектура приложения
- [`tech.md`](../.claude/memory-bank/tech.md) - технологический стек
- [`tasks.md`](../.claude/memory-bank/tasks.md) - актуальные задачи

## Совместимость

### Agent Skills Standard

Этот проект использует **Agent Skills** - открытый стандарт от Anthropic для переиспользуемых AI инструкций.

Формат совместим с:
- ✅ Claude Code (`.claude/skills/`)
- ✅ OpenCode CLI (`.opencode/skills/`)
- ✅ GitHub Copilot CLI (`.copilot/skills/`)
- ✅ Другие инструменты поддерживающие Agent Skills

### Требования

- **Linux/macOS**: Симлинки работают из коробки
- **Windows**: Требуется Developer Mode или использование junctions

#### Windows (без Developer Mode)

Если симлинки не работают, скопируйте содержимое `.claude/` в `.opencode/`:

```cmd
# Windows CMD
mklink /J .opencode\skills .claude\skills
mklink /J .opencode\agents .claude\agents
mklink /J .opencode\rules .claude\rules
mklink /J .opencode\memory-bank .claude\memory-bank
```

Или скопируйте файлы:

```cmd
xcopy .claude\skills .opencode\skills /E /I
xcopy .claude\agents .opencode\agents /E /I
xcopy .claude\rules .opencode\rules /E /I
xcopy .claude\memory-bank .opencode\memory-bank /E /I
copy CLAUDE.md AGENTS.md
```

## Примеры использования

### Запуск тестов через OpenCode

```
> /test-runner
Smart mode - автоматически определит изменённые модули
```

### Анализ кода через агента

```
> Используй агента kotlin-specialist для рефакторинга ImageCompressionUtil
```

### Обновление Memory Bank

```
> /memory-bank-updater
Обновить контекст и задачи после завершения работы
```

## Troubleshooting

### Проблема: OpenCode не обнаруживает скиллы

**Решение:** Проверьте формат SKILL.md

```bash
# Должен содержать YAML frontmatter
head -10 .claude/skills/test-runner/SKILL.md
```

Ожидается:
```yaml
---
name: test-runner
description: Умный запуск тестов
user-invocable: true
arguments:
  - name: mode
    description: Режим запуска
    required: false
    default: smart
---
```

### Проблема: Симлинки не работают

**Решение:** Проверьте права доступа

```bash
# Проверка симлинков
ls -la .opencode/

# Должно показывать:
# skills -> ../.claude/skills
# agents -> ../.claude/agents
# rules -> ../.claude/rules
# memory-bank -> ../.claude/memory-bank
```

### Проблема: Конфликты между Claude Code и OpenCode

**Решение:** Используйте разные сессии

- Claude Code: `claude-code`
- OpenCode: `opencode`

Каждый инструмент будет использовать свои настройки, но общую конфигурацию проекта.

## Дополнительные ресурсы

- [OpenCode Documentation](https://opencode.ai/docs/)
- [Agent Skills Specification](https://agentskills.io/specification)
- [AGENTS.md Format](https://agents.md/)
- [Claude Code Documentation](https://docs.anthropic.com/claude-code)

---

**Проект:** CompressPhotoFast
**Версия интеграции:** 1.0
**Обновлено:** 2026-02-05

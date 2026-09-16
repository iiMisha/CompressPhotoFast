# Agent Symlinks System

Единая система символических ссылок для синхронизации агентов и скиллов между AI-платформами.

## Структура

```
.agents/                    # Оригинальные файлы (источник истинности)
├── agents/                 # 4 агента
├── rules/                  # Единые правила
└── skills/                 # 7 скиллов

.claude/                    # Папка платформы
├── agents → .agents/agents    (symlink)
├── rules  → .agents/rules    (symlink)
└── skills → .agents/skills   (symlink)

Аналогично: .gemini/, .opencode/, .qwen/
```

## Преимущества

- **Единый источник** — изменения в `.agents/` автоматически видны всем платформам
- **Без дублирования** — файлы существуют только в `.agents/`
- **Простота обновления** — один симлинк на папку
- **Масштабируемость** — новый агент/скилл автоматически доступен везде

## Создание (Linux/macOS)

```bash
mkdir -p .claude .gemini .opencode .qwen

ln -s ../.agents/agents .claude/agents
ln -s ../.agents/rules .claude/rules
ln -s ../.agents/skills .claude/skills
```

---
name: skill-creator
description: Создаёт новые скиллы Claude Code с правильным YAML frontmatter, поддержкой аргументов, subagent'ов и расширенных возможностей. Используйте, когда пользователь просит создать новый скилл, сделать скилл или сгенерировать документацию скилла.
metadata:
  version: "2.0"
  author: compressphotofast
argument-hint: [skill-name]
license: MIT
compatibility: Claude Code 1.0+
disable-model-invocation: false
user-invocable: true
allowed-tools: Write, Edit, Read
model: auto
context: inline
agent: general-purpose
hooks: null
---

# Создатель скиллов Claude Code

Вы создаёте новый скилл Claude Code. Следуйте этим шагам:

## 1. Сбор требований

Спросите у пользователя:
- **Название скилла**: Что должен делать скилл (например, "pdf-processing", "code-review")
- **Тип контента**: Reference (знания/конвенции) или Task (конкретное действие)
- **Кто вызывает**: Пользователь, Claude или оба
- **Аргументы**: Нужны ли аргументы и какие
- **Инструкции**: Пошаговые инструкции для AI агента

## 2. Выбор местоположения

| Расположение | Путь | Для кого |
|-------------|-----|----------|
| Enterprise | Managed settings | Все пользователи организации |
| Personal | `~/.claude/skills/<skill-name>/SKILL.md` | Все проекты пользователя |
| Project | `.claude/skills/<skill-name>/SKILL.md` | Только этот проект |
| Plugin | `<plugin>/skills/<skill-name>/SKILL.md` | Где плагин включён |

Приоритет: Enterprise > Personal > Project. Для CompressPhotoFast используйте `.claude/skills/`.

## 3. Генерация SKILL.md

### Минимальный пример

```yaml
---
name: explain-code
description: Объясняет код с визуальными диаграммами и аналогиями
---

При объяснении кода всегда включайте:
1. Аналогию из повседневной жизни
2. ASCII-диаграмму потока
3. Пошаговое объяснение
4. Распространённые ошибки
```

### Полный пример с Claude Code полями

```yaml
---
name: deploy
description: Развёртывает приложение в продакшн
argument-hint: [environment]
disable-model-invocation: true
allowed-tools: Bash(git:*) Bash(docker:*)
context: fork
agent: general-purpose
---

Развёртывание $ARGUMENTS:

1. Запустите тесты
2. Соберите приложение
3. Push в target
4. Проверьте развёртывание
```

## 4. Поля Frontmatter

### Базовые поля (Agent Skills + Claude Code)

| Поле | Обязательное | Описание |
|------|-------------|----------|
| `name` | Нет* | Имя скилла (используется директория, если опущено). Max 64 символа. |
| `description` | Рекомендуется | Что делает скилл и когда использовать. Claude использует это для автозагрузки. |
| `argument-hint` | Нет | Подсказка для автодополнения: `[issue-number]` или `[filename] [format]` |
| `disable-model-invocation` | Нет | `true` - только пользователь может вызвать. По умолчанию `false` |
| `user-invocable` | Нет | `false` - скрыть из меню `/`. По умолчанию `true` |
| `allowed-tools` | Нет | Инструменты без подтверждения при активном скилле |
| `model` | Нет | Модель для использования при активном скилле |
| `context` | Нет | `fork` - запуск в изолированном subagent'е |
| `agent` | Нет | Какой subagent использовать при `context: fork` |
| `hooks` | Нет | Hooks для lifecycle скилла |

### Поля Agent Skills (стандарт)

| Поле | Обязательное | Макс. длина | Правила |
|------|-------------|------------|---------|
| `license` | Нет | - | Название лицензии или ссылка на LICENSE |
| `compatibility` | Нет | 500 символов | Требования к окружению |
| `metadata` | Нет | - | Произвольные пары ключ-значение (строки) |

### Правила валидации name

- 1-64 символа
- Только lowercase буквы, цифры и дефисы
- Не может начинаться/заканчиваться на `-`
- Не может содержать `--` (двойные дефисы)
- Должно совпадать с именем директории

## 5. String Substitutions

Используйте подстановки в содержимом скилла:

| Переменная | Описание |
|-----------|----------|
| `$ARGUMENTS` | Все аргументы, переданные при вызове скилла |
| `$ARGUMENTS[N]` или `$N` | Доступ к конкретному аргументу (0-based) |
| `${CLAUDE_SESSION_ID}` | ID текущей сессии |

### Примеры использования

```yaml
---
name: fix-issue
argument-hint: [issue-number]
---

Исправьте issue $ARGUMENTS следуя стандартам:
1. Прочитайте описание issue #$ARGUMENTS
2. Реализуйте исправление
3. Напишите тесты
```

```yaml
---
name: migrate-component
argument-hint: [component] [from] [to]
---

Мигрируйте компонент $0 с $1 на $2.
Сохраните всё существующее поведение и тесты.
```

```yaml
---
name: session-logger
description: Логирует активность сессии
---

Запишите следующее в logs/${CLAUDE_SESSION_ID}.log:
$ARGUMENTS
```

## 6. Типы контента скилла

### Reference Content (Inline)

Добавляет знания, которые Claude применяет к текущей работе:

```yaml
---
name: api-conventions
description: паттерны проектирования API для этой кодовой базы
---

При написании API endpoints:
- Используйте RESTful naming conventions
- Возвращайте консистентные error formats
- Включайте request validation
```

**Особенности:**
- Запускается inline в текущей сессии
- Claude использует контекст alongside conversation
- Описание всегда в контексте
- Полный скилл загружается при invocation

### Task Content (Explicit Action)

Даёт инструкции для конкретного действия:

```yaml
---
name: deploy
description: Развёртывает приложение в production
disable-model-invocation: true
context: fork
---

Развёртывание приложения:
1. Запустите test suite
2. Соберите приложение
3. Push в deployment target
```

**Особенности:**
- `disable-model-invocation: true` - предотвращает авто-запуск
- `context: fork` - изолированное выполнение
- Описание НЕ в контексте (если disable-model-invocation)
- Полный скилл загружается при invocation

## 7. Контроль вызова скилла

| Настройка | Вы можете вызвать | Claude может вызвать | Когда загружен |
|-----------|------------------|---------------------|----------------|
| (default) | Да | Да | Описание всегда, полный при invocation |
| `disable-model-invocation: true` | Да | Нет | Описание нет, полный при вашем вызове |
| `user-invocable: false` | Нет | Да | Описание всегда, полный при invocation |

### Пример: Только пользователь

```yaml
---
name: deploy
description: Развёртывает приложение в production
disable-model-invocation: true
---

Развёртывание $ARGUMENTS в production:
1. Запустите тесты
2. Соберите приложение
3. Push в target
```

### Пример: Только Claude

```yaml
---
name: legacy-system-context
description: Контекст старой системы для понимания кода
user-invocable: false
---

Эта система использует:
- Legacy database schema
- Old authentication flow
- Custom error handling
```

## 8. Advanced Patterns

### Динамический контент с `!command`

Выполняет shell команду перед отправкой в Claude:

```yaml
---
name: pr-summary
description: Суммаризирует изменения в pull request
context: fork
agent: Explore
allowed-tools: Bash(gh *)
---

## Контекст PR
- Diff: !`gh pr diff`
- Комментарии: !`gh pr view --comments`
- Изменённые файлы: !`gh pr diff --name-only`

## Задача
Суммаризуйте этот pull request...
```

**Важно:** Это preprocessing - Claude видит только результат.

### Запуск в Subagent

```yaml
---
name: deep-research
description: Глубоко исследует тему
context: fork
agent: Explore
---

Исследуйте $ARGUMENTS:

1. Найдите релевантные файлы через Glob и Grep
2. Прочитайте и проанализируйте код
3. Суммаризируйте находки с конкретными ссылками на файлы
```

**Когда использовать `context: fork`:**
- Изолированное выполнение без доступа к истории
- Использование специальных agent types (Explore, Plan, etc.)
- Тяжёлые операции в background

**Когда НЕ использовать:**
- Скиллы с guidelines без explicit task
- Reference content знание

### Ограничение инструментов

```yaml
---
name: safe-reader
description: Читает файлы без изменений
allowed-tools: Read, Grep, Glob
---

Этот скилл создаёт read-only режим для изучения кодовой базы.
```

## 9. Структура директорий

```
skill-name/
├── SKILL.md           # Обязательный: основные инструкции
├── template.md        # Шаблон для заполнения Claude
├── examples/
│   └── sample.md      # Пример вывода
├── references/
│   ├── REFERENCE.md   # Детальная документация
│   └── FORMS.md       # Формы и форматы
├── scripts/
│   └── helper.py      # Исполняемый скрипт
└── assets/
    └── template.txt   # Статические ресурсы
```

**Правила:**
- `SKILL.md` обязательный
- Другие файлы опциональные
- Ссылайтесь на них из SKILL.md
- Держите SKILL.md под 500 строк
- Переносите детали в `references/`

### Пример ссылок

```markdown
## Дополнительные ресурсы

- Для полной API документации см. [reference.md](reference.md)
- Для примеров использования см. [examples.md](examples.md)
- Запустите скрипт: `scripts/helper.py`
```

## 10. Лучшие практики

1. **Постепенное раскрытие**: Держите SKILL.md кратким, детали в references/
2. **Ссылки на файлы**: Относительные пути, максимум один уровень вложенности
3. **Качество описания**: Включайте конкретные ключевые слова
4. **Валидация имени**: Совпадение с директорией
5. **Эффективность токенов**: Основные инструкции под 5000 токенов
6. **Контроль вызова**: Используйте `disable-model-invocation` для task скиллов
7. **Аргументы**: Используйте `$ARGUMENTS[N]` или `$N` для доступа
8. **Subagent'ы**: Используйте `context: fork` для изолированных задач

## 11. Примеры готовых скиллов

### Reference Content

```yaml
---
name: api-conventions
description: API design patterns для этой кодовой базы
---

При написании API endpoints:
- Используйте RESTful naming conventions
- Возвращайте консистентные error formats
- Включайте request validation
```

### Task Content с аргументами

```yaml
---
name: fix-issue
description: Исправляет GitHub issue по номеру
argument-hint: [issue-number]
disable-model-invocation: true
---

Исправьте GitHub issue $ARGUMENTS следуя стандартам кода:

1. Прочитайте описание issue
2. Понимайте требования
3. Реализуйте исправление
4. Напишите тесты
5. Создайте commit
```

### Скилл с subagent'ом

```yaml
---
name: deep-research
description: Глубоко исследует тему используя Explore agent
context: fork
agent: Explore
---

Исследуйте $ARGUMENTS:

1. Найдите релевантные файлы используя Glob и Grep
2. Прочитайте и проанализируйте код
3. Суммаризируйте находки с конкретными ссылками на файлы
```

### Скилл с динамическим контентом

```yaml
---
name: pr-summary
description: Суммаризирует изменения в pull request
context: fork
agent: Explore
allowed-tools: Bash(gh *)
---

## Pull request контекст
- PR diff: !`gh pr diff`
- PR комментарии: !`gh pr view --comments`
- Изменённые файлы: !`gh pr diff --name-only`

## Ваша задача
Суммаризуйте этот pull request:
1. Что изменяется
2. Ключевые файлы
3. Потенциальные риски
```

## 12. Валидация

### Проверка через skills-ref

```bash
skills-ref validate ./skill-name
```

### Чеклист

- [ ] Имя совпадает с именем директории
- [ ] Имя следует правилам (lowercase, no `--`, не начинается/заканчивается на `-`)
- [ ] Описание чёткое и конкретное с ключевыми словами
- [ ] SKILL.md содержит менее 500 строк
- [ ] Опциональные поля корректны
- [ ] Ссылки на файлы рабочие
- [ ] Для task скиллов стоит `disable-model-invocation: true`
- [ ] Для reference скиллов описание включает ключевые слова

## 13. Совместимость

Скиллы следуют **Agent Skills open standard** с расширениями Claude Code:

- **Agent Skills**: Базовые поля (name, description, license, metadata, etc.)
- **Claude Code extensions**: argument-hint, disable-model-invocation, context, agent, hooks и др.

Это обеспечивает переносимость между AI инструментами + дополнительные возможности в Claude Code.

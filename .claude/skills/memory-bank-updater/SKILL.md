---
name: memory-bank-updater
description: Управляет Memory Bank проекта - инициализация, обновление и документирование задач
user-invocable: true
arguments:
  - name: operation
    description: Тип операции (initialize, update, add_task)
    required: true
  - name: focus_source
    description: Конкретный файл или папка для фокуса при обновлении (опционально)
    required: false
  - name: task_context
    description: Контекст задачи для документирования (для add_task)
    required: false
---

# Memory Bank Updater

Этот скилл управляет Memory Bank проекта CompressPhotoFast - системой документации для сохранения контекста между сессиями работы.

## 📚 Полная документация

**Все инструкции по работе с Memory Bank находятся в:** [`.claude/memory-bank/memory-bank-instructions.md`](.claude/memory-bank/memory-bank-instructions.md)

Там описаны:
- Структура Memory Bank и файлов
- Workflows (Initialize, Update, Add Task)
- Правила использования
- Форматирование задач

---

## Операции скилла

### 1. Initialize (`initialize memory bank`)
Полная инициализация Memory Bank через комплексный анализ проекта.

### 2. Update (`update memory bank`)
Обновление Memory Bank после изменений. Можно указать `focus_source` для фокуса на конкретной части.

### 3. Add Task (`add task` / `store this as a task`)
Документирование повторяющейся задачи в `tasks.md`.

---

## Расположение

Memory Bank находится в `.claude/memory-bank/`.

## Связанные файлы

- `.claude/memory-bank/memory-bank-instructions.md` - Полная документация
- `.claude/rules/mandatory-subagent-usage.md` - Правила использования субагентов
- `.claude/rules/workflow-research.md` - Workflow исследования
- `.claude/rules/workflow-implementation.md` - Workflow реализации

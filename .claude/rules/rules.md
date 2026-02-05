# Правила разработки CompressPhotoFast

Язык: Русский | ОС: Linux Mint

---

## 1. Memory Bank — обязательно в начале каждой задачи

Прочитать все файлы из `.claude/memory-bank/`:
- `brief.md` — описание проекта
- `product.md` — функции продукта
- `context.md` — текущий контекст
- `architecture.md` — архитектура
- `tech.md` — технологический стек
- `tasks.md` — актуальные задачи

Статус в ответе: `[Memory Bank: Active]` или `[Memory Bank: Missing]`

---

## 2. Рабочий процесс

```
Изменение кода → Сборка → ... → Тесты (в КОНЦЕ)
```

### Сборка
После КАЖДОГО изменения кода:
```bash
./gradlew assembleDebug
```

### Тестирование
- **Писать тесты** — в процессе разработки
- **Запускать тесты** — ТОЛЬКО в конце завершённой задачи

**Важно:** запуск тестов через Bash — всегда с `timeout=1200000`

| Тип | Команда | Время |
|-----|---------|-------|
| Unit | `./gradlew testDebugUnitTest` | ~10м |
| Все | `./scripts/run_all_tests.sh` | ~20м |
| Coverage | `./gradlew jacocoTestReport` | — |

**Instrumentation тесты** требуют эмулятор `Small_Phone`.

---

## 3. Специализированные агенты

| Задача | Агент |
|--------|-------|
| Kotlin/Android код | `voltagent-lang:kotlin-specialist` |
| Исследование кода | `Explore` |
| Тесты | `voltagent-lang:kotlin-specialist` |
| Планирование | `Plan` |
| Lint проверка | `/lint-check` (skill) |
| Запуск тестов | `/test-runner` (skill) |

### Использовать агенты когда:
✅ Многофайловые изменения
✅ Специфичные задачи (Kotlin, тесты, CI/CD)
✅ Исследование кодовой базы

### НЕ использовать агенты когда:
❌ Чтение файла → `Read`
❌ Поиск файла по имени → `Glob`
❌ Поиск в 2-3 файлах → `Read`/`Grep`
❌ Простые операции → самому

✅ Запускайте агентов параллельно

---

## 4. Локальные агенты проекта

Папка `.claude/agents/` — локальные копии для быстрого доступа:

**Языковые:**
- `kotlin-specialist` — Kotlin/Android (Compose, корутины, KMP, Room)
- `java-architect` — Java + Android SDK архитектура

**Инфраструктура:**
- `deployment-engineer` — CI/CD, Gradle, GitHub Actions
- `devops-engineer` — Автоматизация
- `platform-engineer` — Инструменты разработки
- `database-administrator` — Room DB, SQLite

Вызов через глобальные префиксы: `voltagent-lang:`, `voltagent-infra:`

---

## 5. Skills (Скиллы)

Вызов через `/skill-name`:

| Скилл | Назначение |
|-------|------------|
| `/memory-bank-updater` | Обновление Memory Bank — инициализация, документирование задач |
| `/test-runner` | Умный запуск тестов с определением изменённых модулей |
| `/android-test-suite` | Комплексное тестирование (unit, instrumentation, E2E, performance) |
| `/android-optimization-analyzer` | Анализ производительности и памяти с рекомендациями |
| `/lint-check` | Android Lint и статический анализ кода |

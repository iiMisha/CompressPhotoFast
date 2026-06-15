# План: Усиление скилла `code-analyzer` (надёжность и точность)

> Цель: сделать скилл `code-analyzer` предсказуемым, убрать ложные срабатывания,
> встроить реальный движок анализа (Python-скрипты + `./gradlew lint`) и
> привести документацию в соответствие с фактическим поведением.
>
> Решённые развилки (согласовано с пользователем):
> 1. **Детекция** = Python-скрипты (дубликаты/кастомные паттерны) **+** `./gradlew lint` (unused/качество).
> 2. **Точка входа** = только `SKILL.md` (НЕ регистрировать slash-команду, убрать вводящий в заблуждение синтаксис `/code-analyzer`).
> 3. **Allowlist** = ввести файл исключений для известных «ок»-срабатываний.

---

## Контекст: подтверждённые проблемы (на реальном коде проекта)

### Скрипты
| # | Файл | Проблема | Симптом |
|---|------|----------|---------|
| B1 | `find_unused.py:124-128` | `extract_used_symbols` пропускает слова с `word.isupper() is True` | Ложные unused-импорты для `R`, `UUID`, `URI`, `RGB_565` (подтверждено: `R.` используется 14 раз в `PermissionsManager.kt`) |
| B2 | `find_unused.py:22` | Регекс импорта `import\s+([\w.]+)` игнорирует `import x as y` | Алиасные импорты → ложные unused |
| B3 | `find_unused.py:169-181` | `extract_local_variables` определена, но не вызывается | Мёртвый код в самом анализаторе |
| B4 | `find_duplicates.py:83-107` | `find_duplicates_in_file` определена, но не вызывается из `main` | Внутрифайловые дубликаты не ищутся |
| B5 | `find_duplicates.py:39-75` | `extract_blocks` парсит только `fun ... {` (без expression-body, init-блоков, произвольных блоков) | На реальном проекте — `No duplicates found` |
| B6 | оба скрипта | `rglob("*.kt")` без исключения `build/`, `generated/` | Риск сканирования сгенерированных исходников |
| B7 | оба скрипта | Нет JSON-вывода, нет уровней уверенности (High/Medium/Low из references) | LLM не может надёжно парсить |
| B8 | `find_unused.py:292-310` | Целые файлы с Hilt/Android-компонентом полностью исключаются из анализа приватных функций/свойств | Слишком грубый фильтр — пропускает реальные unused внутри Worker/Service |

### SKILL.md
| # | Проблема |
|---|----------|
| D1 | Описан синтаксис `/code-analyzer scope=... files=...`, но команда нигде не зарегистрирована — вводит в заблуждение |
| D2 | Workflow не инструктирует запускать Python-скрипты — вся работа делается Grep/Read вручную (медленно и нестабильно) |
| D3 | Нет шага с `./gradlew lint` как первичного источника unused/качества |
| D4 | Шаг «After Analysis» безусловно зовёт `/agents-updater` даже без изменений |
| D5 | Упоминается устаревший инструмент `Task(Explore, ...)` вместо `task` |

---

## План выполнения

### Шаг 1. Создать allowlist-файл
**Файл:** `.agents/skills/code-analyzer/allowlist.txt`

Содержимое — построчный список «ok»-идентификаторов и паттернов (комментарии через `#`):
```
# Однобуквенные/ALL-CAPS импорты, всегда используемые синтаксически
R
URI
URL
UUID
RGB_565
HEX
# Android lifecycle / override — никогда не unused
onCreate
onDestroy
onStart
onStop
onResume
onPause
onReceive
onCreateWindow
# Hilt/DI
@Provides
@Binds
@HiltViewModel
@Inject
# WorkManager / Receiver (регистрируются в манифесте)
doWork
onReceive
```
Формат: скрипты читают файл по строкам, игнорируют `#` и пустые; LLM читает тот же файл при ручном анализе.

### Шаг 2. Починить `find_unused.py`
- **[B1]** Убрать фильтр `not word.isupper()`. Вместо этого: собирать ВСЕ идентификаторы, а в ключевые слова добавить типы `R`-стиля только если нужно. Корректно: оставить `R`, `UUID` в used_symbols.
- **[B2]** Регекс импорта: `^import\s+([\w.]+)(?:\s+as\s+(\w+))?` — учитывать алиас; символ использования = алиас, если есть, иначе последний сегмент.
- **[B8]** Заменить «файл целиком Hilt → пропустить всё» на **построчный фильтр**: private-функция/свойство пропускается, только если:
  - есть аннотация `@Inject @Provides @Binds` на самой декларации, ИЛИ
  - имя функции входит в allowlist (lifecycle/override/doWork), ИЛИ
  - функция имеет модификатор `override`.
- **[B3]** Удалить неиспользуемую `extract_local_variables` (либо подключить, если реально нужно — но локалки плохо детектируются регексом; удалить).
- **[B6]** В `find_kotlin_files` исключать сегменты пути `build/`, `.gradle/`, `generated/`.
- **[B7]** Добавить `--format json|text` (по умолчанию `text`). В JSON включать `confidence` (High/Medium/Low) по правилам из `references/unused-patterns.md`.
- **[Allowlist]** Загружать `allowlist.txt` (поиск вверх от скрипта: `../allowlist.txt`); для каждого unused-импорта/функции/свойства проверять вхождение имени в allowlist → исключать.
- Добавить в конце сводку: `Summary: N unused imports, M unused functions, K unused properties`.

### Шаг 3. Починить `find_duplicates.py`
- **[B5]** Расширить `extract_blocks`:
  - добавить извлечение произвольных блоков скользящим окном (sliding window) фиксированного размера `min_lines` поверх нормализованных строк (классический token-based детектор дубликатов);
  - оставить и function-level сравнение;
  - исправить off-by-one в подсчёте скобок (стартовать `brace_count` аккуратно с первой строки функции).
- **[B4]** В `main` вызвать `find_duplicates_in_file` для каждого файла (внутрифайловые дубликаты) — объединить с cross-file результатами.
- **[B6]** Исключать `build/`, `.gradle/`, `generated/`.
- **[B7]** Добавить `--format json|text` с уровнями сходства из references (`High >90%`, `Medium 70-90%`, `Low 50-70%`).
- Оптимизация: для sliding-window использовать хэш-таблицу нормализованных окон вместо O(n²), чтобы масштабировалось.
- Сводка в конце.

### Шаг 4. Переработать `SKILL.md`
- **[D1]** Убрать секцию «Quick Start» с `/code-analyzer ...`. Заменить на честное описание: скилл вызывается через загрузку скилла; параметры передаются в свободной форме в запросе.
- **[D2]** В Workflow добавить **явный шаг «Запуск скриптов»**:
  ```
  python3 .agents/skills/code-analyzer/scripts/find_unused.py app/src/main --format json
  python3 .agents/skills/code-analyzer/scripts/find_duplicates.py app/src/main --format json
  ```
  с инструкцией читать `allowlist.txt` и интерпретировать JSON.
- **[D3]** Добавить шаг **«Gradle Lint (первичный детектор unused/качества)»**:
  ```
  ./gradlew lintDebug          # собирает lint-отчёт
  # читать app/build/reports/lint-results-debug.txt
  ```
  с пояснением: lint авторитетнее скриптов для unused imports/ресурсов; скрипты дополняют для приватных функций и дубликатов.
- **[D5]** Заменить `Task(Explore, ...)` → `task(... subagent_type=explore)` с пометкой «использовать с осторожностью, предпочитать Glob/Grep».
- **[D4]** Шаг «After Analysis»: вызывать `/agents-updater` **только если найдены подтверждённые изменения для фиксации**, иначе пропускать.
- Добавить ссылку на `allowlist.txt` и инструкцию по его пополнению.
- В «CompressPhotoFast Специфика» добавить пункт: «недавно проведена масштабная очистка мёртвого кода (см. AGENTS.md) — не сообщать о deliberately удалённых элементах».

### Шаг 5. Обновить `references/unused-patterns.md`
- Дополнить секцию «Исключения» ссылкой на `allowlist.txt`.
- Добавить примеры из актуального кода (R, UUID) как НЕ unused с пояснением причины.

### Шаг 6. Валидация
1. Запустить починенные скрипты на `app/src/main`:
   - `find_unused.py` НЕ должен сообщать `R`, `UUID` и lifecycle-методы как unused.
   - `find_duplicates.py` должен находить реальные дубликаты (если есть) либо корректно сообщать об их отсутствии.
2. Проверить `--format json` парсится.
3. Запустить `./gradlew lintDebug` и убедиться, что SKILL.md-инструкция читаема.
4. Прогнать один полный цикл анализа по обновлённому workflow вручную.

---

## Логика процесса (flow)

```mermaid
flowchart TD
    A[Запрос на анализ кода] --> B[Шаг 1: Сбор файлов<br/>Glob app/src/main]
    B --> C{Тип анализа?}
    C -->|unused/quality| D[./gradlew lintDebug<br/>читать lint-results-debug.txt]
    C -->|duplicates| E[python find_duplicates.py --format json]
    C -->|unused (доп.)| F[python find_unused.py --format json]
    D --> G[Применить allowlist.txt<br/>отфильтровать known-ok]
    E --> G
    F --> G
    G --> H[LLM: верификация кандидатов<br/>Grep/Read конкретных строк]
    H --> I[Отчёт по формату Report Format]
    I --> J{Есть подтверждённые<br/>изменения?}
    J -->|Да| K[/agents-updater]
    J -->|Нет| L[Готово, без обновления AGENTS.md]
```

---

## Файлы, затрагиваемые изменениями
- `+ .agents/skills/code-analyzer/allowlist.txt` (новый)
- `~ .agents/skills/code-analyzer/scripts/find_unused.py`
- `~ .agents/skills/code-analyzer/scripts/find_duplicates.py`
- `~ .agents/skills/code-analyzer/SKILL.md`
- `~ .agents/skills/code-analyzer/references/unused-patterns.md`
- (симлинки `.claude/`, `.gemini/`, `.opencode/`, `.qwen/` → `.agents/` обновятся автоматически)

## Риски и митигация
- **Слишком широкое скользящее окно** в дубликатах → шум. Митигация: дефолт `min_lines=6`, `threshold=0.85`, allowlist паттернов (`cursor?.use`, `try/catch Log`).
- **JSON-формат ломает существующий парсинг** → оставить `text` по умолчанию.
- **Gradle lint медленный (~минуты)** → отмечено в SKILL как опциональный, но авторитетный шаг; для быстрых проверок — только Python.

# План: привести AGENTS.md, скиллы (agents-updater, apk) и конфиги CompressPhotoFast в соответствие с RogaineHelper

Эталон: `/home/misha/Документы/1 Проекты/RogaineHelper` (пути ниже — `/home/misha/Документы/1 Проекты/RogaineHelper/...`).
Целевой проект: `/home/misha/Документы/1 Проекты/CompressPhotoFast`.
Все изменения — в CompressPhotoFast; RogaineHelper не трогать. Git-коммиты — только по явной просьбе пользователя.

## Решения (подтверждены пользователем)
1. Скилл `apk` — порт по образцу RogaineHelper (сборка debug + публикация одноразовой HTTP-ссылкой через Python, порт 8080+).
2. Агенты в `.agents/agents` — почистить по образцу (оставить только реально используемые).
3. Конфиг-каталоги — консолидировать (android-test-suite, LOG.txt, пустой docs/).

## Задачи

### 1. Переписать AGENTS.md в стиле RogaineHelper
Эталон: `RogaineHelper/AGENTS.md` (28.8 КБ, только действующие правила; история — отдельно).
Текущий: `CompressPhotoFast/AGENTS.md` (13.6 КБ, лимит не превышен, но стиль нарративный).

- Переструктурировать: «Базовые правила» → «Сборка и тесты» → «Проект» (стек/архитектура) → «Бизнес-логика сжатия» (критические инварианты: 100 КБ минимум, ≥30% + 10 КБ, EXIF-маркер, HEIC-fallback) → «Тестирование» → «Workflows».
- Убрать из «Текущий фокус» нарративы: телеграфный стиль, ≤2-3 строки на запись.
- Удалить дубль: пункт «Очистка мёртвого кода v3 (Tier 1)» повторяется дважды (строки 84-85).
- Историю (v2-очистка -654 строки, мессенджер-фича, скорректированные пункты Tier 2) перенести в архив `docs/AGENTS_ARCHIVE_2026-06.md` (по образцу `RogaineHelper/docs/AGENTS_ARCHIVE_2026-09.md`), в AGENTS.md оставить одну ссылку на архив.
- Симлинки `CLAUDE.md`/`GEMINI.md`/`QWEN.md` → `AGENTS.md` уже настроены — не трогать.

### 2. Переписать скилл agents-updater (компактный вариант RogaineHelper)
Эталон: `RogaineHelper/.agents/skills/agents-updater/SKILL.md` (35 строк) + `references/AGENTS_TEMPLATE.md`.
Текущий: `CompressPhotoFast/.agents/skills/agents-updater/SKILL.md` (200 строк).

- Сократить SKILL.md до ~35 строк: frontmatter (name, description с триггерами), таблица операций (initialize/update/add_task/update_architecture), секции «Границы (критично)», «Лаконичность по умолчанию», «Лимит размера: ≤15000 токенов» (проверка `wc -c`: русский ≈ символов/2.5 ≤ 15000).
- Создать `references/AGENTS_TEMPLATE.md` по образцу: структура AGENTS.md, паттерны анализа, формат задачи, правила сжатия.
- Удалить устаревший `CompressPhotoFast/.agents/skills/agents-updater/agents-instructions.md` (ссылается на несуществующий `.claude/rules/agents-instructions.md`).
- Убрать frontmatter-поля `user-invocable`/`arguments`, которых нет в эталоне.

### 3. Порт скилла apk
Эталон: `RogaineHelper/.agents/skills/apk/` (SKILL.md + `scripts/share_nosudo.py`).

- Скопировать `scripts/share_nosudo.py` в `CompressPhotoFast/.agents/skills/apk/scripts/`, адаптировать:
  - `PROJECT_DIR` → CompressPhotoFast;
  - имя APK: `app/build/outputs/apk/debug/*.apk` (у CompressPhotoFast один модуль `:app`, единственный applicationId, без debug-overlay);
  - убрать release-специфику RogaineHelper (`-PabiList`, `-PcompressNativeLibs`, shared-keystore, `is_arm64_only_compressed`) — у CompressPhotoFast обычный `assembleDebug`/`assembleRelease`; оставить флаг `--release` как выбор варианта сборки;
  - Content-Disposition: `CompressPhotoFast-debug-YYYYMMDD-HHMMSS.apk`.
- Написать SKILL.md по образцу (короче): триггеры («apk», «/apk», «дай ссылку на билд»), режим без sudo (Python http.server, порт 8080+, TTL по умолчанию 1ч, веб-корень `~/apk-share`), параметры (`--release`, `--no-build`, `--ttl`, `--host`, `--port`, `--list`, `--stop*`).
- В AGENTS.md (см. задачу 1) добавить правило по образцу: после изменения runtime-кода `app/src/**` и успешной сборки — публиковать APK автоматически (не для docs/тестов/CLI).

### 4. Почистить агентов в `.agents/agents`
Оставить (реально упоминаются в AGENTS.md/скиллах): `kotlin-specialist`, `android-silent-failure-hunter`, `android-code-reviewer`, `android-test-analyzer`.
Удалить (не упоминаются нигде вне взаимных ссылок друг на друга): `database-administrator`, `deployment-engineer`, `devops-engineer`, `incident-responder`, `java-architect`, `platform-engineer`, `python-pro`, `security-engineer`, `sql-pro`, `sre-engineer`.
- Перед удалением проверить, что оставшиеся 4 не ссылаются на удаляемых (`grep` по `.agents/`); если ссылаются — почистить ссылки.

### 5. Консолидация конфиг-каталогов
- **android-test-suite дублируется**: `.agents/skills/android-test-suite/` и `.kilocode/skills/android-test-suite/`. Сравнить (`diff -r`); оставить один экземпляр в `.agents/skills/` (источник истины, виден всем платформам через симлинки), из `.kilocode/skills/` удалить (или заменить симлинком на `.agents/skills/android-test-suite`, если Kilo не подхватывает из `.agents`).
- `LOG.txt` (846 КБ) в корне → перенести в `docs/` или удалить (исторический лог, не правила); в `.gitignore` добавить `LOG.txt`/`log.txt`.
- Пустой `docs/` — туда же положить `AGENTS_ARCHIVE_2026-06.md` (задача 1), каталог перестаёт быть пустым.
- `.kilo/node_modules` + `package.json` — проверить содержимое; если это мусор от разового npm — удалить (`.kilo/plans/` оставить).
- `.docs/archive` и `.cursor/` не трогать (вне скоупа).

### 6. Обновить симлинк-систему
- `CompressPhotoFast/.agents/rules/agent_symlinks_system.md` — обновить цифры (4 скилла → фактическое количество: agents-updater, android-optimization-analyzer, android-test-suite, apk, code-analyzer, skill-creator = 6) и описание агентов (6 → фактическое = 4).
- Проверить, что симлинки `.claude/`, `.gemini/`, `.opencode/`, `.qwen/` → `.agents/` живы (сейчас есть; после чистки агентов ничего пересоздавать не нужно).

## Порядок выполнения
1 → 4 → 6 → 5 → 2 → 3 (AGENTS.md сначала, чтобы у apk-скилла было куда добавить правило; apk — последним, т.к. самый объёмный).

## Валидация
- `wc -c AGENTS.md` ≤ 37 КБ (лимит 15000 токенов для русского).
- `ls -la` симлинков: `CLAUDE.md`, `GEMINI.md`, `QWEN.md`, `.claude/{agents,rules,skills}` — не битые (`readlink`).
- `bash -n` на `share_nosudo.py` (синтаксис) + ручной прогон: `./gradlew assembleDebug` затем `.agents/skills/apk/scripts/share_nosudo.py --list` (полный прогон публикации — по желанию пользователя, поднимает сервер на 8080).
- Grep: в `.agents/` не осталось ссылок на удалённых агентов.
- AGENTS.md не содержит дублей и архивного нарратива; история найдётся в `docs/AGENTS_ARCHIVE_2026-06.md`.

## Риски / заметки
- Kilo загружает скиллы из `.kilocode/skills` (см. текущую сессию) — перед удалением дубля android-test-suite убедиться, что `.agents/skills`-версия подхватывается; иначе заменить симлинком.
- Порт 8080 на локальной машине должен быть открыт для телефона в той же сети (тот же принцип, что в RogaineHelper).
- `.kilo/plans/` не трогать (планы Kilo).

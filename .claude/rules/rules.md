# Правила разработки CompressPhotoFast

## Настройки
- **Язык общения**: Русский
- **ОС**: Linux Mint

## Memory Bank (ОБЯЗАТЕЛЬНО)

**В начале КАЖДОЙ задачи ОБЯЗАТЕЛЬНО прочитайте ВСЕ файлы из папки `.claude/memory-bank/`:**

- `brief.md` - краткое описание проекта
- `context.md` - текущий контекст и последние изменения
- `architecture.md` - архитектура приложения
- `tech.md` - технологический стек
- `tasks.md` - актуальные задачи (если есть)

После чтения укажите статус в начале ответа:
- `[Memory Bank: Active]` - файлы прочитаны
- `[Memory Bank: Missing]` - папка не существует

**См. подробнее:** `workflow-memory-bank.md`

## Сборка
ОБЯЗАТЕЛЬНО: После КАЖДОГО изменения кода выполняйте сборку измененного модуля (или полного проекта при изменениях в нескольких) для проверки. При составлении планов разработки также НЕОБХОДИМО указывать о необходимости производить сборку и исправлять ошибки после каждого шага.

## Тестирование
ОБЯЗАТЕЛЬНО: При изменении кода необходимо создавать необходимые unit и инструментальные тесты и проводить тестирование.

### Запуск тестов

**Только unit тесты:**
```bash
./gradlew testDebugUnitTest
```

**Все тесты (unit + instrumentation):**
```bash
./scripts/run_all_tests.sh
```

**Для запуска instrumentation тестов нужен эмулятор Small_Phone:**
- Откройте Android Studio → Device Manager
- Запустите AVD "Small_Phone"
- Или через командную строку (требует настроенный ANDROID_HOME):
```bash
export ANDROID_HOME=~/Android/Sdk
$ANDROID_HOME/emulator/emulator -avd Small_Phone
```

**Проверка покрытия кода:**
```bash
./gradlew jacocoTestReport
```
HTML отчет покрытия: `app/build/reports/jacoco/jacocoTestReport/html/index.html`

### Полная сборка
```bash
./gradlew assembleDebug
```

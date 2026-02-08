# Повторяющиеся задачи

## Использование новых агентов (февраль 2026)

### python-pro - для CLI части
**Когда**: Изменения в `cli/` (cli.py, compression.py, exif_handler.py, multiprocessing_utils.py)
**Шаги**:
1. Использовать агент `python-pro` для изменений CLI кода
2. Соблюдать совместимость с Android версией (качество, мин. размер, EXIF маркер)
3. Добавлять type hints и docstrings
4. Тестировать с pytest

### sql-pro - для MediaStore/SQLite
**Когда**: Оптимизация запросов, работа с MediaStore, проблемы с производительностью базы
**Шаги**:
1. Использовать агент `sql-pro` для анализа запросов
2. Проверить projections (не SELECT *)
3. Использовать parameterized queries (selectionArgs)
4. Профилировать через Android Profiler > Database Inspector

### security-engineer - для безопасности
**Когда**: Работа с URI, правами доступа, EXIF/GPS данными, Share Intent
**Шаги**:
1. Использовать агент `security-engineer` для review кода безопасности
2. Валидировать все URIs перед обработкой
3. Проверять ContentResolver типы
4. По умолчанию удалять GPS данные (opt-in для сохранения)

### sre-engineer - для надежности
**Когда**: Анализ производительности, SLOs, мониторинг, postmortems
**Шаги**:
1. Использовать агент `sre-engineer` для анализа проблем надежности
2. Мониторить SLOs (ANR rate, crash rate, memory leaks)
3. Писать runbooks для известных инцидентов
4. Проводить chaos engineering тесты

### incident-responder - при инцидентах
**Когда**: Краши, ANR, data loss, performance degradation
**Шаги**:
1. Использовать агент `incident-responder` для crisis-менеджмента
2. Собрать evidence (logs, build, device state)
3. Провести root cause analysis
4. Написать postmortem с lessons learned

---

## Проверка кода через lint-check
**Когда**: Перед коммитом, рефакторинг
**Шаги**:
1. Использовать скилл: `/lint-check`
2. Опции: `auto_fix=true` для автоисправления
3. Проверить отчет: `app/build/reports/lint-results-debug.html`
4. Исправить критичные проблемы

## Запуск тестов через Task tool (ОБЯЗАТЕЛЬНО!)
**Когда**: Разработка, рефакторинг, перед коммитом
**Шаги**:
1. Использовать Task tool с субагентом `general-purpose`
2. Передать команду тестирования в prompt
3. Субагент выполнит тесты в отдельном контексте
4. Получить summary результатов

**Команды для запуска:**
```yaml
# Unit тесты
Task(subagent_type: "general-purpose",
  prompt: "Запусти unit тесты: ./gradlew testDebugUnitTest")

# Все тесты
Task(subagent_type: "general-purpose",
  prompt: "Запусти все тесты: ./scripts/run_all_tests.sh")

# Instrumentation тесты
Task(subagent_type: "general-purpose",
  prompt: "Запусти instrumentation тесты: ./scripts/run_instrumentation_tests.sh")
```

**ВАЖНО:** Никогда не запускай тесты в основном контексте! Всегда используй Task tool.

## Добавление unit тестов
**Когда**: Новая функциональность
**Файлы**: `app/src/test/**/*Test.kt`
**Шаги**:
1. Наследовать от `BaseUnitTest`
2. Использовать `runTest` для корутин
3. MockK для моков
4. `./gradlew testDebugUnitTest`

## Добавление instrumentation тестов
**Когда**: UI тестирование, Android API
**Файлы**: `app/src/androidTest/**/*Test.kt`
**Шаги**:
1. Аннотация `@HiltAndroidTest`
2. Наследовать от `BaseInstrumentedTest`
3. `@get:Rule` для HiltAndroidRule и ActivityScenarioRule
4. Espresso для UI
5. `./scripts/run_instrumentation_tests.sh`

## Исправление двойных расширений
**Когда**: HEIC/HEIF → `.HEIC.jpg`
**Файлы**: `FileOperationsUtil.kt`, `ImageCompressionUtil.kt`
**Шаги**:
1. Изучить `createCompressedFileName()`
2. Добавить очистку двойных расширений
3. Определить исходный MIME тип
4. `./gradlew testDebugUnitTest`

## Отладка дубликатов файлов
**Когда**: 50+ файлов создают дубликаты
**Файлы**: `MediaStoreUtil.kt`, `FileOperationsUtil.kt`, `ImageCompressionWorker.kt`
**Шаги**:
1. Проверить логику копирования файлов
2. Проверить работу с URI
3. Добавить логирование путей
4. Протестировать с небольшим количеством файлов

## Обновление версии приложения
**Когда**: Релиз
**Файлы**: `gradle.properties`, `app/build.gradle.kts`
**Шаги**:
1. Обновить `VERSION_NAME_BASE` (MAJOR.MINOR.PATCH)
2. Увеличить `versionCode`
3. `./gradlew assembleDebug`
4. `./gradlew assembleRelease`

## Генерация тестовых изображений
**Когда**: Нужны тестовые изображения
**Команда**: `./scripts/generate_test_images.sh`
**Требования**: ImageMagick

## Исправление FileNotFoundException в тестах
**Когда**: ExifInterface.saveAttributes() удаляет временные файлы
**Файлы**: `CompressionLoadTest.kt`
**Шаги**:
1. Сохранить байты в ByteArrayOutputStream до создания файла
2. Проверить существование файла перед ExifInterface
3. Проверить существование после saveAttributes()
4. Использовать сохраненные байты как fallback

## Интеграция субагентов в скиллы
**Когда**: Использование скиллов для автоматизации задач
**Файлы**: `.claude/skills/*/SKILL.md`, `.claude/rules/rules.md`

**Шаги**:
1. При вызове скилла он САМ вызывает необходимые агенты через Task tool
2. test-runner → general-purpose (запуск тестов)
3. android-test-suite → general-purpose + android-test-analyzer
4. lint-check → general-purpose + kotlin-specialist + android-code-reviewer
5. android-optimization-analyzer → kotlin-specialist + android-silent-failure-hunter
6. memory-bank-updater → прямые инструменты (Glob/Grep/Read), БЕЗ агентов

**Важно**: Тесты ВСЕГДА запускаются через general-purpose в отдельном контексте

## Исправление проблем с производительностью

### Когда: Медленная обработка, ANR, OOM
**Шаги**:
1. Использовать скилл: `/android-optimization-analyzer`
2. Агент проанализирует код на проблемы с памятью/производительностью
3. Использовать `sre-engineer` для анализа SLOs и мониторинга
4. Проверить `PerformanceMonitor` метрики
5. Протестировать исправления через нагрузочные тесты

## Анализ безопасности кода

### Когда: Работа с URI, правами доступа, EXIF данными
**Шаги**:
1. Использовать `security-engineer` для review
2. Проверить валидацию URIs
3. Убедиться в безопасной обработке EXIF/GPS
4. Проверить минимальные разрешения
5. Использовать `android-silent-failure-hunter` для поиска утечек ошибок

## Работа с CLI (Python)

### Когда: Изменения в CLI части проекта
**Файлы**: `cli/*.py`
**Шаги**:
1. Использовать `python-pro` для изменений
2. Поддерживать совместимость с Android (те же константы сжатия)
3. Добавлять type hints и docstrings
4. Тестировать с pytest: `cd cli && pytest tests/`
5. Проверить идентичность логики с Android версией

## Оптимизация MediaStore запросов

### Когда: Медленная работа с файлами, много запросов
**Файлы**: `MediaStoreUtil.kt`, `FileOperationsUtil.kt`
**Шаги**:
1. Использовать `sql-pro` для анализа запросов
2. Проверить projections (минимум колонок)
3. Использовать batch queries
4. Добавить кеширование результатов
5. Профилировать через Android Profiler

## Инцидент-менеджмент

### Когда: Краши, ANR, data loss в production
**Шаги**:
1. Использовать `incident-responder` для управления инцидентом
2. Собрать evidence (logs, crash reports, device state)
3. Провести root cause analysis
4. Создать hotfix branch
5. После разрешения - написать postmortem

## Post-Incident Activities

### После разрешения инцидента
**Шаги**:
1. Написать postmortem (шаблон в `incident-responder.md`)
2. Обновить runbooks (через `sre-engineer`)
3. Добавить тесты для предотвращения повторения
4. Обновить monitoring/alerts
5. Документировать lessons learned в Memory Bank

# Повторяющиеся задачи

## Агенты (kotlin-specialist, python-pro, sql-pro, security-engineer, sre-engineer, incident-responder)
**Когда**: Код/Kotlin/Android / CLI / MediaStore / Безопасность / SLOs / Инциденты
1. Использовать соответствующий агента
2. Следовать best practices
3. Анализировать и документировать

## Качество кода (lint-check, android-test-analyzer, android-silent-failure-hunter, android-code-reviewer)
**Когда**: Перед коммитом / После PR / После изменений error handling
1. `/lint-check` или соответствующий агент
2. Проверить отчеты и coverage
3. Исправить проблемы

## Тестирование (ОБЯЗАТЕЛЬНО через Task!)
**Когда**: Разработка, рефакторинг, перед коммитом
1. Task tool → `general-purpose`
2. Передать команду тестирования
3. Получить summary

**Команды:**
```yaml
Unit: ./gradlew testDebugUnitTest
Instr: ./scripts/run_instrumentation_tests.sh
Все: ./scripts/run_all_tests.sh
```

## Специфические задачи

### Отладка дубликатов файлов
**Файлы**: MediaStoreUtil.kt, FileOperationsUtil.kt, ImageCompressionWorker.kt
1. Проверить логику копирования и работу с URI
2. Добавить логирование путей
3. Протестировать

### Оптимизация производительности
1. `/android-optimization-analyzer`
2. `sre-engineer` для SLOs
3. Проверить `PerformanceMonitor`
4. Нагрузочные тесты

### Обновление версии
**Файлы**: gradle.properties, app/build.gradle.kts
1. Обновить `VERSION_NAME_BASE`
2. Увеличить `versionCode`
3. `assembleDebug`, `assembleRelease`

## Инцидент-менеджмент

### При инцидентах
1. `incident-responder`
2. Собрать evidence
3. Root cause analysis
4. Postmortem

### Post-Incident
1. Написать postmortem
2. Обновить runbooks
3. Добавить тесты

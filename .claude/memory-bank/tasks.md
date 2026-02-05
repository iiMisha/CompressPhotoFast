# Повторяющиеся задачи

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

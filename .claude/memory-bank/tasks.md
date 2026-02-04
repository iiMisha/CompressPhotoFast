# Повторяющиеся задачи CompressPhotoFast

## Запуск тестов

**Когда**: Разработка, рефакторинг, релиз
**Файлы**: `app/src/test/**/*.kt`, `app/src/androidTest/**/*.kt`

**Шаги**:
1. `./gradlew testDebugUnitTest` (~7m)
2. `./scripts/run_all_tests.sh` (~15m, требует эмулятор Small_Phone)
3. Исправить failing тесты
4. `./gradlew jacocoTestReport`

**Важно**: 232 теста, все должны pass. Coverage в `app/build/reports/jacoco/jacocoTestReport/html/index.html`

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
**Файлы**: `app/src/androidTest/**/*Test.kt`, `HiltTestModule.kt`

**Шаги**:
1. Аннотация `@HiltAndroidTest`
2. Наследовать от `BaseInstrumentedTest`
3. `@get:Rule` для HiltAndroidRule и ActivityScenarioRule
4. Espresso для UI тестов
5. `./scripts/run_instrumentation_tests.sh`

**Важно**: Требуется эмулятор/устройство

## Обновление зависимостей Gradle

**Когда**: Обновление библиотек
**Файлы**: `app/build.gradle.kts`, `build.gradle.kts`, `gradle.properties`

**Шаги**:
1. Проверить актуальные версии
2. Обновить в `dependencies`
3. Gradle sync
4. `./gradlew testDebugUnitTest`
5. Проверить changelog на breaking changes

## Исправление двойных расширений

**Когда**: HEIC/HEIF файлы создают `.HEIC.jpg`
**Файлы**: `FileOperationsUtil.kt`, `ImageCompressionUtil.kt`, `FileNameProcessingTest.kt`

**Шаги**:
1. Изучить `createCompressedFileName()`
2. Добавить очистку двойных расширений
3. Определить исходный MIME тип
4. Использовать правильный MIME
5. `./gradlew testDebugUnitTest`

## Отладка дубликатов файлов

**Когда**: 50+ файлов создают дубликаты
**Файлы**: `MediaStoreUtil.kt`, `FileOperationsUtil.kt`, `ImageCompressionWorker.kt`

**Шаги**:
1. Проверить логику копирования файлов
2. Проверить работу с URI
3. Добавить логирование путей
4. Протестировать с небольшим количеством файлов
5. Проанализировать логи

## Обновление Memory Bank

**Когда**: Значительные изменения
**Файлы**: `.claude/memory-bank/*.md`

**Шаги**:
1. `Task(Explore, "quick", "Найти недавние изменения")`
2. Обновить файлы Memory Bank
3. **ПРОВЕРИТЬ размер**: brief.md ≤5, context.md ≤50, tasks.md ≤100, architecture.md ≤80, tech.md ≤30
4. Если превышен - сократить (удалить старое, убрать детали)
5. Сообщить о сокращениях

**Важно**: Соблюдать лимиты строк. Только факты, без примеров кода.

## Обновление версии приложения

**Когда**: Релиз
**Файлы**: `gradle.properties`, `app/build.gradle.kts`

**Шаги**:
1. Обновить `VERSION_NAME_BASE` (MAJOR.MINOR.PATCH)
2. Увеличить `versionCode`
3. `./gradlew assembleDebug`
4. Протестировать
5. `./gradlew assembleRelease`

**Важно**: versionCode уникален для каждого релиза

## Генерация тестовых изображений

**Когда**: Нужны тестовые изображения
**Команда**: `./scripts/generate_test_images.sh`

**Требования**: ImageMagick (`sudo apt-get install imagemagick`)

**Генерирует**: test_image_*.{jpg,png,heic} в `app/src/test/resources/test_images/`

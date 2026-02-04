# Повторяющиеся задачи

## Запуск тестов
**Когда**: Разработка, рефакторинг
**Шаги**:
1. `./gradlew testDebugUnitTest` (~7m)
2. `./scripts/run_all_tests.sh` (~15m, нужен эмулятор Small_Phone)
3. `./gradlew jacocoTestReport`
4. Coverage: `app/build/reports/jacoco/jacocoTestReport/html/index.html`

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

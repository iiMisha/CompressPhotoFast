# План: Исправление false positive в проверке директории приложения

## Проблема

В `ImageProcessingChecker.kt` метод `isInAppDirectoryNormalized()` содержит слишком широкий паттерн `"compressphotofast"`, который совпадает не только с путём директории приложения, но и с **именами файлов**, содержащими `com.compressphotofast` (например, скриншоты, сделанные приложением).

**Последствие:** Скриншоты вида `screenshot_20260611_183918_com.compressphotofast.jpg` ошибочно распознаются как файлы в директории приложения и пропускаются.

## Изменения

### 1. Исправить `isInAppDirectoryNormalized()` в `ImageProcessingChecker.kt`

**Файл:** `app/src/main/java/com/compressphotofast/util/ImageProcessingChecker.kt`

Убрать третий паттерн `"compressphotofast"` из списка `appDirPatterns`. Достаточно первых двух паттернов, которые точно совпадают с путём директории:

```kotlin
// ДО (баг):
val appDirPatterns = listOf(
    "/pictures/compressphotofast/",
    "/storage/emulated/0/pictures/compressphotofast/",
    "compressphotofast"  // ← false positive на имена файлов
)

// ПОСЛЕ (исправление):
val appDirPatterns = listOf(
    "/pictures/compressphotofast/",
    "/storage/emulated/0/pictures/compressphotofast/"
)
```

### 2. Проверить и при необходимости исправить аналогичные проверки

Проверить `OptimizedCacheUtil.checkIsInAppDirectory()` — там паттерн `path.contains("/$appDirectory/")` **безопасен**, т.к. `"/CompressPhotoFast/"` требует слешей вокруг. Вторая часть условия ограничена `content://media/...`. Ошибок нет.

**Вывод:** Изменения требуются только в одном месте — `ImageProcessingChecker.kt`.

## Верификация

1. Собрать проект: `./gradlew assembleDebug`
2. Запустить unit-тесты: `./scripts/run_unit_tests.sh`
3. Ручная проверка: сделать скриншот из приложения и убедиться, что он корректно поступает в обработку (не пропускается с `BASIC_CHECK_FAILED`)

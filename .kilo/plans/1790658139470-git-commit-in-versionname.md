# План: интеграция номера git-коммита в версию приложения

## Цель
Хеш git-коммита попадает в `versionName` и имя APK, так что собранный APK однозначно привязан к коммиту, с которого собран.

## Решения (согласованы с пользователем)
- Хеш показывается в `versionName` → имя APK и системная «О приложении».
- Формат: `2.2.10(dd.MM.yyyy-HHmm)-<short-hash>`, при несохранённых изменениях — суффикс `-dirty` (например `-1661c77-dirty`).
- Даты/время сборки в версии сохраняются как есть.

## Изменения

### 1. `app/build.gradle.kts`
- Добавить функцию рядом с `getBuildVersion()`:

```kotlin
fun getGitHash(): String {
    return try {
        val hash = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootDir)
            .start().inputStream.bufferedReader().readText().trim()
        val dirty = ProcessBuilder("git", "status", "--porcelain")
            .directory(rootDir)
            .start().inputStream.bufferedReader().readText().isNotBlank()
        if (hash.isEmpty()) "unknown" else if (dirty) "$hash-dirty" else hash
    } catch (e: Exception) {
        "unknown"
    }
}
```

- В `defaultConfig` после `versionName = getBuildVersion(baseVersion)`:

```kotlin
val gitHash = getGitHash()
versionName = "${getBuildVersion(baseVersion)}-$gitHash"
```

- Ничего больше не менять: `outputFileName` уже строится из `variant.versionName` (строка 105), поэтому APK автоматически получит имя вида `CompressPhotoFast_v2.2.10(29.09.2026-0905)-1661c77_debug.apk`.

### 2. `AGENTS.md`
- Через навык `agents-updater` добавить одну строку в «Проверка и релиз»: versionName и имя APK содержат короткий хеш коммита + `-dirty` при несохранённых изменениях; сборка должна выполняться с чистого дерева для точного соответствия.

## Риски / ограничения
- Хеш вычисляется в configuration-фазе Gradle. Так как `assembleDebug` запускается вручную после коммита, хеш актуален. Если Gradle решит, что сборка up-to-date и не пересоберёт APK — это не проблема соответствия: хеш фиксируется в имени APK/versionName при фактической сборке. При сомнениях — `./gradlew clean assembleDebug`.
- Грязное дерево помечается `-dirty` — сборка с несохранёнными правками не притворяется чистым коммитом.
- Вне git-репозитория (CI-экспорт, архив) — fallback `unknown`, сборка не ломается.
- Instrumentation-тесты не зависят от versionName; проверка минимальна.

## Валидация
1. `./gradlew clean assembleDebug` — сборка успешна.
2. Проверить имя APK в `app/build/outputs/apk/debug/`: суффикс совпадает с `git rev-parse --short HEAD`.
3. Проверить `aapt dump badging <apk> | grep versionName` (или `apkanalyzer`) — versionName содержит тот же хеш.
4. Внести временное несохранённое изменение, пересобрать — суффикс `-dirty`; откатить.
5. `./gradlew testDebugUnitTest` — зелёный.

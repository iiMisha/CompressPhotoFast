# Troubleshooting

Решение распространенных проблем при тестировании.

## Нет устройства для instrumentation тестов

```bash
./scripts/start_emulator.sh
# или
./scripts/check_device.sh --start-emulator
```

## OutOfMemoryError

Увеличьте heap size в `app/build.gradle.kts`:

```kotlin
testOptions {
    unitTests {
        isIncludeAndroidResources = true
        maxHeapSize = "4g"
    }
}
```

## Медленные тесты

1. Используйте `@Ignore` для медленных тестов
2. Параллельный запуск: `./gradlew test --parallel`
3. Используйте Robolectric вместо instrumented тестов

## Flaky тесты (нестабильные)

1. Добавьте явные ожидания: `advanceUntilIdle()`
2. Используйте IdlingResources для Espresso
3. Добавьте retry логику
4. Изолируйте тесты друг от друга

## Coverage не генерируется

```bash
./gradlew clean
./gradlew jacocoTestReport --rerun-tasks
```

## Тесты падают на CI но проходят локально

1. Проверьте версии зависимостей
2. Убедитесь что используете тот же JDK
3. Проверьте локали (locale-sensitive тесты)
4. Добавьте таймауты

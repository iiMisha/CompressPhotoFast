# Экономичный режим Gradle

## Обзор

Проект CompressPhotoFast настроен на использование **экономичного режима** по умолчанию. Это обеспечивает низкую нагрузку на CPU и позволяет комфортно работать на компьютере во время сборки и тестирования.

## Настройки экономичного режима

### gradle.properties

```properties
# Экономичный режим (по умолчанию)
org.gradle.configureondemand=false
org.gradle.parallel=false
org.gradle.configuration-cache=false
org.gradle.workers.max=2
org.gradle.caching=false
```

**Описание настроек:**
- `org.gradle.configureondemand=false` - Отключает конфигурирование по требованию
- `org.gradle.parallel=false` - Отключает параллельное выполнение задач
- `org.gradle.configuration-cache=false` - Отключает кэш конфигурации
- `org.gradle.workers.max=2` - Ограничивает количество рабочих процессов до 2
- `org.gradle.caching=false` - Отключает кэширование сборки

### app/build.gradle.kts

```kotlin
// Тесты выполняются последовательно (maxParallelForks=1)
val gradleMode = System.getenv("GRADLE_MODE") ?: "eco"

maxParallelForks = when (gradleMode) {
    "fast" -> (Runtime.getRuntime().availableProcessors() / 2).coerceAtMost(4)
    else -> 1  // Последовательное выполнение (по умолчанию)
}
```

## Использование

### Запуск тестов

```bash
# Экономичный режим (по умолчанию)
./scripts/quick_test.sh unit
./scripts/quick_test.sh instrumentation
./scripts/quick_test.sh all

# Быстрый режим (высокая нагрузка на CPU)
GRADLE_MODE=fast ./scripts/quick_test.sh unit
GRADLE_MODE=fast ./scripts/quick_test.sh instrumentation
GRADLE_MODE=fast ./scripts/quick_test.sh all
```

### Сборка проекта

```bash
# Экономичный режим (по умолчанию)
./gradlew assembleDebug

# Быстрый режим (высокая нагрузка на CPU)
GRADLE_MODE=fast ./gradlew assembleDebug
```

### Другие команды Gradle

```bash
# Экономичный режим (по умолчанию)
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew jacocoTestReport

# Быстрый режим (высокая нагрузка на CPU)
GRADLE_MODE=fast ./gradlew testDebugUnitTest
GRADLE_MODE=fast ./gradlew connectedDebugAndroidTest
GRADLE_MODE=fast ./gradlew jacocoTestReport
```

## Переключение на быстрый режим

Если вам нужна максимальная производительность и вы готовы к высокой нагрузке на CPU, вы можете временно переключиться на быстрый режим:

### Вариант 1: Переменная окружения (рекомендуется)

```bash
# Для одной команды
GRADLE_MODE=fast ./gradlew testDebugUnitTest

# Для сессии
export GRADLE_MODE=fast
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

### Вариант 2: Изменение gradle.properties

Раскомментируйте настройки быстрого режима в [`gradle.properties`](../gradle.properties:23) и закомментируйте настройки экономичного режима:

```properties
# Быстрый режим
org.gradle.configureondemand=true
org.gradle.parallel=true
org.gradle.configuration-cache=true
org.gradle.workers.max=4
org.gradle.caching=true
```

## Сравнение режимов

| Характеристика | Экономичный режим | Быстрый режим |
|----------------|------------------|---------------|
| Нагрузка на CPU | Низкая | Высокая |
| Время выполнения | Дольше | Быстрее |
| Параллелизм | Отключен | Включен |
| Количество процессов | 2 | 4+ |
| Кэширование | Отключено | Включено |
| Удобство работы | Комфортно | Мешает работе |

## Рекомендации

- **Используйте экономичный режим по умолчанию** для повседневной разработки
- **Переключайтесь на быстрый режим** только когда нужно быстро собрать проект или запустить тесты
- **Используйте переменную окружения** `GRADLE_MODE=fast` для временного переключения
- **Не меняйте gradle.properties** без необходимости - это повлияет на все команды

## Дополнительные скрипты

### quick_test.sh

Основной скрипт для запуска тестов в экономичном режиме:

```bash
./scripts/quick_test.sh [unit|instrumentation|all]
```

### run_all_tests.sh

Полный цикл тестирования (Unit + Instrumentation + Coverage):

```bash
./scripts/run_all_tests.sh --start-emulator
```

### run_unit_tests.sh

Запуск только unit тестов:

```bash
./scripts/run_unit_tests.sh
```

### run_instrumentation_tests.sh

Запуск только instrumentation тестов:

```bash
./scripts/run_instrumentation_tests.sh
```

## Устранение проблем

### Тесты выполняются слишком медленно

Если тесты выполняются слишком медленно и вам не нужно работать на компьютере во время тестирования:

```bash
GRADLE_MODE=fast ./scripts/quick_test.sh all
```

### Компьютер тормозит во время сборки

Убедитесь, что вы используете экономичный режим (по умолчанию). Если проблема сохраняется, попробуйте:

```bash
# Остановить Gradle daemon
./gradlew --stop

# Запустить снова в экономичном режиме
./gradlew assembleDebug
```

### Хочу использовать быстрый режим постоянно

Отредактируйте [`gradle.properties`](../gradle.properties:1) и переключите настройки на быстрый режим (см. раздел "Переключение на быстрый режим").

## Дополнительная информация

- [Документация по тестированию](TESTING.md)
- [План развития системы тестирования](TESTING_PLAN.md)
- [Базовые классы для тестов](TEST_BASE_CLASSES.md)

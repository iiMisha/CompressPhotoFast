# Usage Examples

Примеры использования android-test-suite скилла.

## Базовое использование

```
Запусти android-test-suite
```
Запускает все тесты (unit + instrumentation) с coverage.

## Только unit тесты

```
Запусти android-test-suite с test_type=unit
```

## E2E тесты с эмулятором

```
Запусти android-test-suite с test_type=e2e и start_emulator=true
```

## Тестирование конкретного модуля

```
Запусти android-test-suite с test_type=unit и focus_module=compression
Протестируй класс ImageCompressionUtil
```

## Performance тесты

```
Запусти android-test-suite с test_type=performance и coverage=false
```

## Непрерывный режим

```
Запусти android-test-suite с test_type=unit и continuous=true
```

## Быстрая проверка перед коммитом

```
Запусти android-test-suite с test_type=unit и coverage=false
```

## Полный тестовый цикл для релиза

```
Запусти android-test-suite с test_type=all, coverage=true и verbose=true
Сгенерируй полный отчет покрытия кода
```

## Performance тестирование сравнение

```
Запусти android-test-suite с test_type=performance
Измерь производительность сжатия JPEG изображений
Сравни с baseline показателями
```

## Тестирование после изменений

```
Запусти android-test-suite
Сфокусируйся на модулях compression и settings
Проверь что все тесты проходят
```

# Workflow: Исследование кодовой базы

## Назначение

Это правило определяет, как проводить исследование кодовой базы проекта CompressPhotoFast.

---

## ⚠️ КРИТИЧЕСКИ ВАЖНО

**Агент `Explore` ВЫЗЫВАЕТ ПЕРЕПОЛНЕНИЕ ПАМЯТИ (JavaScript heap out of memory) на Android проекте CompressPhotoFast.**

**ОБЯЗАТЕЛЬНО ИСПОЛЬЗУЙТЕ ПРЯМЫЕ ИНСТРУМЕНТЫ:**
- ✅ **Glob** - для поиска файлов по паттерну
- ✅ **Grep** - для поиска кода по ключевым словам
- ✅ **Read** - для чтения конкретных файлов

---

## Когда использовать исследования

### 1. Поиск файлов по паттернам
Используй **Glob** для:
- Найти все Kotlin файлы в модуле
- Найти тестовые файлы
- Найти файлы ресурсов
- Найти файлы конфигурации

**ПРИМЕРЫ:**
```bash
Glob("**/*ViewModel.kt")
Glob("app/src/**/*Test.kt")
Glob("**/build.gradle.kts")
```

### 2. Поиск кода по ключевым словам
Используй **Grep** для:
- Найти использование конкретного API
- Найти реализации интерфейса
- Найти обработчики ошибок
- Найти конфигурации

**ПРИМЕРЫ:**
```bash
Grep("ImageDecoder", "**/*.kt")
Grep("interface Compressor", "**/*.kt")
Grep("class.*Exception", "**/*.kt")
```

### 3. Понимание архитектуры
Используй **комбинацию Glob + Grep + Read**:
- `Glob` для нахождения файлов компонента
- `Grep` для поиска связей
- `Read` для чтения ключевых файлов

**ПРИМЕР:**
```bash
# Найти файлы системы сжатия
Glob("**/*Compression*.kt")
Glob("**/*Compressor*.kt")

# Прочитать ключевые файлы
Read("app/src/main/java/com/compressphotofast/util/ImageCompressionUtil.kt")
```

### 4. Анализ существующих паттернов
Используй **Grep + Read**:
- `Grep("class.*ViewModel")` - найти все ViewModels
- `Read` нескольких файлов для анализа паттернов

**ПРИМЕР:**
```bash
# Найти все ViewModels
Grep("class.*ViewModel", "**/*.kt")

# Прочитать пару для понимания паттерна
Read("app/src/main/java/com/compressphotofast/ui/MainViewModel.kt")
```

### 5. Подготовка к реализации
Перед написанием нового кода ВСЕГДА исследуй существующие реализации:

**ШАГ 1: Найти похожие файлы**
```bash
Glob("**/*Compressor*.kt")
```

**ШАГ 2: Прочитать несколько для паттернов**
```bash
Read("app/src/main/java/com/compressphotofast/compression/JpegCompressor.kt")
Read("app/src/main/java/com/compressphotofast/compression/PngCompressor.kt")
```

**ШАГ 3: Использовать результаты в специализированном агенте**
```
Task(voltagent-lang:kotlin-specialist, "Реализовать WebPCompressor следуя паттернам из JpegCompressor и PngCompressor")
```

---

## Стратегии исследования

### Стратегия 1: Быстрый поиск (1-2 инструмента)
Для простых вопросов:
```bash
# Где находится конкретный файл?
Glob("**/MainActivity.kt")

# Какой файл содержит интерфейс?
Grep("interface Compressor", "**/*.kt")
```

### Стратегия 2: Средний анализ (3-5 инструментов)
Для понимания компонента:
```bash
# Найти все ViewModels
Glob("**/*ViewModel.kt")

# Найти как они используются
Grep("class.*ViewModel", "**/*.kt")

# Прочитать пару примеров
Read("app/src/main/java/com/compressphotofast/ui/MainViewModel.kt")
Read("app/src/main/java/com/compressphotofast/ui/CompressionViewModel.kt")
```

### Стратегия 3: Глубокий анализ (для рефакторинга)
Для комплексного анализа подсистемы:
```bash
# Все файлы подсистемы
Glob("**/*Compression*.kt")

# Все usage
Grep("compressImage|Compressor", "**/*.kt")

# Прочитать ключевые файлы
Read("...")
Read("...")
Read("...")
```

---

## После исследования

1. **Проанализируй результаты** - изучи найденные паттерны
2. **Обсуди с пользователем** - предложи подход на основе найденного
3. **Используй результаты** - передай их в specialized агент для реализации

**Пример полного workflow:**

```
// Шаг 1: Исследование через прямые инструменты
Glob("**/*Compressor*.kt")
Grep("interface Compressor", "**/*.kt")
Read("app/src/main/java/com/compressphotofast/compression/JpegCompressor.kt")

// Шаг 2: Реализация (с использованием результатов)
Task(voltagent-lang:kotlin-specialist, "
Реализовать новый WebPCompressor следуя паттернам из JpegCompressor:
- Использовать ту же структуру
- Применить те же зависимости
- Следовать тем же соглашениям об именовании
")
```

---

## Что НЕЛЬЗЯ делать

❌ **НЕ используй Task(Explore)**
- Вызывает переполнение памяти (JavaScript heap out of memory)
- Процесс падает с ошибкой

❌ **НЕ читай слишком много файлов одновременно**
- Читай 2-5 файлов для паттернов
- Не пытайся прочитать весь проект

❌ **НЕ делай многошаговый анализ без цели**
- Определи цель исследования
- Используй только нужные инструменты

---

## Примеры правильного использования

### ✅ Правильно: Прямые инструменты
```
User: "Где находятся все ViewModel?"

Assistant: Найду все ViewModel через Grep:
Grep("class.*ViewModel", "**/*.kt")
```

### ❌ Неправильно: Использование Explore
```
User: "Где находятся все ViewModel?"

Assistant: Использую Explore... [CRASH с ошибкой памяти]
Task(Explore, "medium", "Найти все ViewModel")  // ВЫЗЫВАЕТ CRASH!
```

---

## Интеграция с другими workflow

### Перед реализацией
1. `Glob` + `Grep` + `Read` для поиска паттернов
2. Прочитать Memory Bank файлы
3. `Task(voltagent-lang:kotlin-specialist, "Реализовать используя паттерны")`

### Перед рефакторингом
1. `Glob` + `Grep` + `Read` для анализа системы
2. `Task(Plan, "medium", "План рефакторинга")`
3. `Task(voltagent-lang:kotlin-specialist, "Выполнить рефакторинг")`

### Для написания тестов
1. `Glob("**/*Test.kt")` для поиска существующих тестов
2. `Read` нескольких тестов для паттернов
3. `Task(voltagent-lang:kotlin-specialist, "Написать тесты следуя паттернам")`

---

## Чек-лист для исследования

Перед тем как выполнить исследование, убедись:

- [ ] Используются Glob/Grep/Read а НЕ Task(Explore)
- [ ] Цель исследования четко определена
- [ ] Количество файлов для чтения ограничено (2-5 файлов)
- [ ] Результаты будут использованы для дальнейшей работы

---

**ЭТОТ WORKFLOW ОБЯЗАТЕЛЕН ДЛЯ ВСЕХ ИССЛЕДОВАНИЙ КОДОВОЙ БАЗЫ.**

---
name: code-analyzer
description: Анализ Kotlin/Java кода для поиска дублирующегося кода, мёртвого кода (unused), и предоставление рекомендаций по улучшению. Используй когда: (1) нужно найти дубликаты функций или блоков кода, (2) нужно найти неиспользуемый код (unused functions, variables, imports), (3) нужно проверить code quality и дать рекомендации, (4) рефакторинг перед написанием тестов. Не изменяет код - только анализирует и выводит отчёт.
---

# Code Analyzer

## Overview

Анализирует Kotlin/Java файлы проекта для выявления проблем качества кода. Проводит статический анализ без выполнения кода.

## Quick Start

```bash
python3 .opencode/skills/code-analyzer/scripts/analyze_code.py --path app/src/main/kotlin
```

## Analysis Types

### 1. Duplicate Code
- Похожие функции (>70% similarity)
- Дублирующиеся блоки кода

### 2. Dead Code
- Неиспользуемые private функции
- Неиспользуемые переменные
- Неиспользуемые imports
- Неиспользуемые private классы

### 3. Code Quality
- Функции >50 строк
- Глубокая вложенность (>4 уровня)
- Длинные параметры (>5)
- Magic numbers
- Empty catch blocks
- TODO/FIXME комментарии

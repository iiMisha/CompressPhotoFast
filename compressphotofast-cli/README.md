# CompressPhotoFast CLI

Быстрый инструмент для сжатия изображений в командной строке. Работает на Linux, Windows и macOS.

## Быстрый старт

### Установка

```bash
# Linux/macOS
./compressphotofast.sh

# Windows
compressphotofast.bat
```

### Базовое использование

```bash
# Сжать папку с изображениями
compressphotofast compress ~/Photos

# Сжать один файл
compressphotofast compress photo.jpg

# Посмотреть статистику
compressphotofast stats ~/Photos
```

## Команды

### `compress` — Сжатие изображений

```bash
compressphotofast compress [ПУТЬ] [ОПЦИИ]
```

**Опции:**

| Опция | Короткая | Описание |
|-------|----------|----------|
| `--quality` | `-q` | Качество: `low`, `medium`, `high`, `auto` (по умолчанию: `medium`) |
| `--replace` | `-r` | Заменить оригинальные файлы |
| `--output-dir` | `-o` | Папка для сохранения сжатых файлов |
| `--skip-screenshots` | `-s` | Пропустить скриншоты |
| `--skip-messenger` | `-m` | Пропустить фото из мессенджеров |
| `--force` | `-f` | Пересжать уже сжатые файлы |
| `--dry-run` | `-n` | Показать что будет сжато без фактического сжатия |

**Примеры:**

```bash
# Высокое качество, замена оригиналов
compressphotofast compress ~/Photos -q high -r

# Сохранить в отдельную папку
compressphotofast compress ~/Photos -o ~/Compressed

# Тестовый режим
compressphotofast compress ~/Photos -n

# Пропустить скриншоты и мессенджеры
compressphotofast compress ~/Photos -s -m
```

### `stats` — Статистика

```bash
compressphotofast stats [ПУТЬ] [ОПЦИИ]
```

Показывает количество изображений, общий размер и процент сжатых файлов.

**Примеры:**

```bash
compressphotofast stats ~/Photos
compressphotofast stats ~/Photos -s -m
```

### `version` — Версия

```bash
compressphotofast version
```

## Как это работает

- **Минимальный размер файла**: 100 КБ
- **Минимальная экономия**: 30% + 10 КБ
- **EXIF-маркеры**: Добавляет метку сжатия для отслеживания
- **Уровни качества**: Low (60), Medium (70), High (85)
- **Автоматический режим**: Подбирает оптимальное качество для каждого изображения

## Требования

- Python 3.10+
- Зависимости устанавливаются автоматически при первом запуске

## Лицензия

MIT

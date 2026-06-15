"""
Общий модуль для скриптов code-analyzer.

Содержит shared scaffolding: исключения путей, поиск файлов, загрузка allowlist,
а также помощники нормализации исходного кода.

Импортируется как find_unused, так и find_duplicates, чтобы поведение
(форматы allowlist, scope сканирования, обработка строк/комментариев)
не разошлось между инструментами.
"""

import re
from pathlib import Path


# Сегменты путей, которые всегда исключаются из сканирования
EXCLUDED_PATH_PARTS = ("build", ".gradle", "generated", ".idea", "node_modules")


def is_excluded(path: Path) -> bool:
    """Проверяет, нужно ли исключить файл по сегментам пути."""
    return any(part in EXCLUDED_PATH_PARTS for part in set(path.parts))


def find_kotlin_files(directory: Path) -> list[Path]:
    """Все Kotlin-файлы в директории, кроме build/generated/.idea."""
    return sorted(p for p in directory.rglob("*.kt") if not is_excluded(p))


def find_allowlist(allowlist_arg: str | None) -> set[str]:
    """Загружает allowlist: либо из аргумента, либо ../allowlist.txt от скрипта.

    Формат: один токен на строку; '#' начинает комментарий; пустые строки игнорируются.
    """
    if allowlist_arg:
        path = Path(allowlist_arg)
    else:
        # allowlist.txt лежит в корне скилла, на уровень выше scripts/
        path = Path(__file__).resolve().parent.parent / "allowlist.txt"
    if not path.exists():
        return set()
    result = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        token = raw.split("#", 1)[0].strip()
        if token:
            result.add(token)
    return result


# === Помощники нормализации для обоих скриптов ===
#
# ВАЖНО: мы НЕ пытаемся писать полноценный лексер Kotlin. Полный парсинг строк
# с поддержкой string templates (`"${foo("inner")}"`) и raw strings через
# несколько строк слишком сложен и хрупок. Вместо этого используем КОНСЕРВАТИВНЫЕ
# эвристики, которые безопасны (лучше недобрать, чем обрезать файл).
#
# Стратегия:
#  - Комментарии: убираем полностью (`//...` и `/* ... */`).
#  - Строковые литералы: убираем только ОДНОСТРОЧНЫЕ `"..."` без перевода строки.
#    Многострочные raw strings `"""..."""` оставляем как есть — они редко
#    содержат значимые идентификаторы/скобки в нашем проекте.
#  - Это гарантирует, что регекс никогда не «съест» кусок кода между
#    разнесёнными кавычками (как было с жадным `"(?:\\.|[^"\\])*"`).

_RE_LINE_COMMENT = re.compile(r"//.*$", re.MULTILINE)
_RE_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
_RE_SINGLE_LINE_STRING = re.compile(r'"[^"\n]*"')
_RE_SINGLE_LINE_CHAR = re.compile(r"'[^'\n]*'")


def _keep_newlines(match: re.Match) -> str:
    """Заменяет совпадение на строку той же длины из пробелов/переводов строк,
    чтобы сохранить номера строк."""
    text = match.group(0)
    return "".join("\n" if c == "\n" else " " for c in text)


def strip_comments_and_strings(content: str) -> str:
    """Возвращает контент без комментариев и однострочных строковых литералов.

    - Блочные `/* ... */` и однострочные `//...` комментарии удаляются с
      сохранением переводов строк (номера строк не меняются).
    - Однострочные `"..."` и `'...'` маскируются на `""`/`''`.
    - Многострочные raw strings `\"\"\"...\"\"\"` НЕ трогаются намеренно
      (см. комментарий выше).

    Это нужно, чтобы:
      - идентификаторы в комментариях не маскировали unused (L5)
      - brace-depth не портился от скобок в строках (L6)
    """
    s = _RE_BLOCK_COMMENT.sub(_keep_newlines, content)
    s = _RE_LINE_COMMENT.sub("", s)
    s = _RE_SINGLE_LINE_STRING.sub('""', s)
    s = _RE_SINGLE_LINE_CHAR.sub("''", s)
    return s


def clean_line_for_braces(line: str) -> str:
    """Для одной строки: убрать // комментарий и замаскировать однострочные
    строковые/символьные литералы, чтобы '{'/'}' внутри строк не влияли на
    подсчёт скобок. Многострочные raw strings здесь не учитываются (caller
    обрабатывает построчно)."""
    line = re.sub(r"//.*$", "", line)
    line = _RE_SINGLE_LINE_STRING.sub('""', line)
    line = _RE_SINGLE_LINE_CHAR.sub("''", line)
    return line

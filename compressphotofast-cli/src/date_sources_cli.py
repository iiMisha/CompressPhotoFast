"""
CLI-расширение источников дат для fix-dates — поверх Android-контракта.

Используется ТОЛЬКО командой fix-dates, когда базовый parse_date_ms имя не
распознал; общий date_parser.py и Android FileNameDateParser не меняются
(инварианты сжатия/паритета в AGENTS.md, раздел «Восстановление EXIF-дат»).

Дополнительные источники:

1. Siemens/Meizu: ``YYMMDD[-_]HHMMSS`` на basename без расширения
   (``070414_152426`` = 2007-04-14 15:24:26); Meizu — с ``P``-префиксом,
   дефисом и годом без ведущего нуля (``P70429-132038`` = 2007-04-29
   13:20:38). Век — из окна валидности 1990..now (двузначные годы в
   базовом парсере намеренно запрещены — риск счётчиков; здесь fullmatch +
   strict-календарь + время отсекают ложные совпадения).

2. Lenovo: непаддированные ``YYYY M D [H]MMSS`` — all-digits basename длины
   11..14 (``2010101080149`` = 2010-10-10 08:01:49; ``2014426210722`` =
   2014-04-26 21:07:22). Перебор разбиений M/D(1-2 цифры) и хвоста
   [H]MMSS (5-6 цифр) с strict-календарём; при нескольких валидных
   прочтениях берётся максимальная дата (подтверждено владельцем архива
   на примере 2010101080149 = 2010-10-10, а не 2010-01-01).

3. Дата из имени папки: ближайшая к файлу компонента пути вида
   ``YYYY``, ``YYYY_MM``, ``YYYY_MM_DD…`` (2004, 2009_02_forhome…,
   2010_04_25_test); отсутствующие месяц/день = 1. Диапазоны
   (``1988-2004``) и некалендарные значения не матчатся.
"""
import os
import re
from datetime import datetime
from typing import Optional

from .date_parser import _valid

# Siemens: паддированные YYMMDD_HHMMSS (070414_152426);
# Meizu: P<YYMMDD>-<HHMMSS>, год без ведущего нуля (P70429-132038 = 2007-04-29)
_SIEMENS = re.compile(r"^(\d{6})[-_](\d{6})$")
_MEIZU = re.compile(r"^P(\d{5,6})[-_](\d{6})$")

# Папка: YYYY с опциональными _MM и _DD в начале компоненты пути
_FOLDER_DATE = re.compile(
    r"^(?P<y>(19|20)\d{2})(?:[_\-. ](?P<m>[01]\d)(?:[_\-. ](?P<d>[0-3]\d))?)?"
)

_DIGITS = re.compile(r"\d+")


def _calendar_ms(
    year: int, month: int, day: int, hour: int = 0, minute: int = 0, second: int = 0
) -> Optional[int]:
    """Strict-календарь (31 февраля, месяц 13, час 24+ — None), epoch ms."""
    try:
        dt = datetime(year, month, day, hour, minute, second)
    except ValueError:
        return None
    return int(dt.timestamp() * 1000)


def _two_digit_year_ms(
    yy: int, month: int, day: int, hour: int, minute: int, second: int
) -> Optional[int]:
    """Век из окна валидности: сначала 20YY, иначе 19YY."""
    ms = _calendar_ms(2000 + yy, month, day, hour, minute, second)
    if ms is not None and _valid(ms) is not None:
        return ms
    ms = _calendar_ms(1900 + yy, month, day, hour, minute, second)
    if ms is not None and _valid(ms) is not None:
        return ms
    return None


def parse_siemens_meizu_ms(basename: str) -> Optional[int]:
    """``YYMMDD_HHMMSS`` / ``P<YYMMDD>-<HHMMSS>`` (basename без расширения)."""
    yy = month = day = None
    time_digits = None
    match = _SIEMENS.fullmatch(basename)
    if match is not None:
        date_digits, time_digits = match.group(1), match.group(2)
        yy, month, day = int(date_digits[0:2]), int(date_digits[2:4]), int(date_digits[4:6])
    else:
        match = _MEIZU.fullmatch(basename)
        if match is None:
            return None
        date_digits, time_digits = match.group(1), match.group(2)
        if len(date_digits) == 6:
            yy, month, day = int(date_digits[0:2]), int(date_digits[2:4]), int(date_digits[4:6])
        else:  # год без ведущего нуля: Y MM DD
            yy, month, day = int(date_digits[0:1]), int(date_digits[1:3]), int(date_digits[3:5])
    hour, minute, second = (
        int(time_digits[0:2]),
        int(time_digits[2:4]),
        int(time_digits[4:6]),
    )
    if hour > 23 or minute > 59 or second > 59:
        return None
    return _two_digit_year_ms(yy, month, day, hour, minute, second)


def parse_lenovo_unpadded_ms(basename: str) -> Optional[int]:
    """
    All-digits basename длины 11..14 как ``YYYY + M(1-2) + D(1-2) + [H]MMSS``.
    Несколько валидных прочтений → максимальная дата; ни одного → None.
    """
    if not basename.isdigit() or not (11 <= len(basename) <= 14):
        return None
    year = int(basename[0:4])
    candidates = []
    rest = basename[4:]
    for month_len in (1, 2):
        for day_len in (1, 2):
            date_end = month_len + day_len
            if date_end >= len(rest):
                continue
            month = int(rest[0:month_len])
            day = int(rest[month_len:date_end])
            tail = rest[date_end:]
            if len(tail) not in (5, 6):
                continue
            if len(tail) == 6:
                hour, minute, second = int(tail[0:2]), int(tail[2:4]), int(tail[4:6])
            else:
                hour, minute, second = int(tail[0:1]), int(tail[1:3]), int(tail[3:5])
            if hour > 23 or minute > 59 or second > 59:
                continue
            ms = _calendar_ms(year, month, day, hour, minute, second)
            if ms is not None and _valid(ms) is not None:
                candidates.append(ms)
    if not candidates:
        return None
    return max(candidates)


def folder_component_date_ms(component: str) -> Optional[int]:
    """Дата из одной компоненты пути (см. модуль). Диапазоны → None."""
    match = _FOLDER_DATE.match(component)
    if match is None:
        return None
    year = int(match.group("y"))
    month_text = match.group("m")
    day_text = match.group("d")
    if month_text is None:
        ms = _calendar_ms(year, 1, 1)
    elif day_text is None:
        ms = _calendar_ms(year, int(month_text), 1)
    else:
        ms = _calendar_ms(year, int(month_text), int(day_text))
    if ms is None:
        return None
    return _valid(ms)


def folder_date_ms(path: str) -> Optional[int]:
    """Дата из ближайшей к файлу подходящей компоненты пути (снизу вверх)."""
    head, _ = os.path.split(path)
    while head:
        head, component = os.path.split(head)
        if not component or component in (":",):
            break
        ms = folder_component_date_ms(component)
        if ms is not None:
            return ms
        if len(head) <= 3:  # «D:\» — выше подниматься некуда
            break
    return None


def parse_date_cli_extra_ms(name: str) -> Optional[int]:
    """Имя файла (как есть, с расширением) → epoch ms. Только CLI-fix-dates."""
    if not name:
        return None
    basename = name.rsplit(".", 1)[0] if "." in name else name
    if _DIGITS.fullmatch(basename):
        ms = parse_lenovo_unpadded_ms(basename)
        if ms is not None:
            return ms
    return parse_siemens_meizu_ms(basename)

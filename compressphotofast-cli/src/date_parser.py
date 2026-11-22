"""
Извлекает дату съёмки из имени файла (epoch ms; локальная таймзона, если в имени
нет явного TZ-суффикса). Чистые функции без IO. Порт Android FileNameDateParser
(семантическое соответствие).

Порядок шаблонов — первое совпадение выигрывает. Все цифровые шаблоны защищены
границами `(?<!\\d)…(?!\\d)`, чтобы не сматчиться внутри более длинной цифровой
последовательности (счётчики, числовые ID соцсетей); год-первый шаблоны
дополнительно не начинают матч после `цифра + разделитель` — иначе хвост
day-first имени (`31-12-2025_10-20`) распознавался бы как год-первая дата.

Намеренно не парсится: 2-значные годы (`251201`, `25-12-01` — риск
счётчиков/ID), русские месяцы, слитный и пробельный day-first (`01122025`,
`01 02 2025`), числовые прогоны длины 9, 11–12, 15, 17–18 и 19+ без известного
префикса, счётчики без даты (`DSC01234`, `GOPR1234`), `dd/MM` без года.
"""
import re
import time
from datetime import datetime, timedelta, timezone
from typing import Optional

# Нижняя граница окна: 1990-01-01T00:00:00 локального времени.
MIN_VALID_MS = 631_152_000_000
# Верхняя граница окна: сейчас + 1 сутки (константа, легко изменить).
MAX_FUTURE_WINDOW_MS = 24 * 60 * 60 * 1000

# 1. Epoch с известным префиксом: WhatsApp/WeChat/Snapchat-экспорт (мс/µs/нс)
EPOCH_PREFIX = re.compile(
    r"(?:mmexport|wx_camera_|snapchat-)(\d{13}|\d{16}|\d{19})(?!\d)",
    re.IGNORECASE,
)

# 2. Basename целиком из цифр проверяется без regex (см. parse_date_ms)

# 3. Месяц словами: Jan 1, 2025 / 01 Jan 2025 / 2025-Jan-01 / 01Jan2025
#    (+ опциональное время, `;` — замена двоеточий, запрещённых на FAT)
_MONTH_WORDS = (
    r"Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
    r"Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|"
    r"Dec(?:ember)?"
)
_MONTH_WORD = r"(?<![A-Za-z])(?:%s)(?![A-Za-z])" % _MONTH_WORDS
MONTH_NAME = re.compile(
    r"(?<!\d)(?:(?P<yA>(19|20)\d{2})[-_ ](?P<mA>%s)[-_ ](?P<dA>\d{1,2})"
    r"|(?P<mB>%s)[ _](?P<dB>\d{1,2}),?[ _](?P<yB>(19|20)\d{2})"
    r"|(?P<dC>\d{1,2})[ _](?P<mC>%s)[ _](?P<yC>(19|20)\d{2})"
    r"|(?P<dD>\d{1,2})(?P<mD>%s)(?P<yD>(19|20)\d{2}))"
    r"(?:[ T_]+(?:at )?(?P<hh>[0-2]\d)[;:. _-](?P<mi>[0-5]\d)"
    r"(?:[;:. _-](?P<ss>[0-5]\d))?)?(?!\d)"
    % (_MONTH_WORD, _MONTH_WORD, _MONTH_WORD, _MONTH_WORD),
    re.IGNORECASE,
)

# 4. Компактные дата-время: 20250101123456, 20250101_123456, 20250101T123456
COMPACT_DATETIME = re.compile(
    r"(?<!\d)(19|20)\d{2}[01]\d[0-3]\d[_\-. T]?[0-2]\d[0-5]\d[0-5]\d(?!\d)"
)

# 5a. Разделённые дата-время с зоной: 2025-01-01T12:30:45Z, 2025-01-01 12:30:45+03:00,
#     2025-01-01 123045+0300. Разделитель дата-время — только T/пробел, чтобы
#     дефисные мс-хвосты (`signal-…-123`) не съедались как ±HHMM.
SEPARATED_TZ = re.compile(
    r"(?<!\d)(?<!\d[-_.])(19|20)\d{2}[-_.][01]\d[-_.][0-3]\d[ T]"
    r"(?:[0-2]\d:[0-5]\d(?::[0-5]\d)?|[0-2]\d[0-5]\d[0-5]\d)(?:Z|[+-][01]\d:?[0-5]\d)?(?!\d)"
)
TZ_SUFFIX = re.compile(r"(?:Z|[+-][01]\d:?[0-5]\d)$")

# 5b. Разделённые дата-время: 2025-01-02-03-04-05, 2025.01.01_12.30.45, 2025-01-01_12:30
SEPARATED_DATETIME = re.compile(
    r"(?<!\d)(?<!\d[-_.])(19|20)\d{2}[-_.][01]\d[-_.][0-3]\d"
    r"(?:[-_ ][0-2]\d[-_.:][0-5]\d(?:[-_.:][0-5]\d)?)?(?!\d)"
)

# 6. Day-first: 01-02-2025, 31.12.2025_10-20-30; DD-MM, невалидно → MM-DD
DAY_FIRST = re.compile(
    r"(?<!\d)(\d{1,2})[-._/](\d{1,2})[-._/]((?:19|20)\d{2})"
    r"(?:[ T_]?([0-2]\d)[:._-]([0-5]\d)(?:[:._-]([0-5]\d))?)?(?!\d)"
)

# 7. Только дата: 20250101, 2025-01-01, 2025.01.01
DATE_ONLY = re.compile(r"(?<!\d)(?<!\d[-_.])(19|20)\d{2}[-_.]?[01]\d[-_.]?[0-3]\d(?!\d)")

_ALL_DIGITS = re.compile(r"\d+")
_SEPARATORS = re.compile(r"[_\-. T:]")

_MONTH_NUMBERS = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}


def _valid(ms: int) -> Optional[int]:
    max_ms = int(time.time() * 1000) + MAX_FUTURE_WINDOW_MS
    if ms > MIN_VALID_MS and ms <= max_ms:
        return ms
    return None


def _strict_calendar_to_ms(
    year: int,
    month: int,
    day: int,
    hour: int = 0,
    minute: int = 0,
    second: int = 0,
    tzinfo: Optional[timezone] = None,
) -> Optional[int]:
    """Strict-календарь: 31 февраля, месяц 13, час 24+ — reject."""
    try:
        dt = datetime(year, month, day, hour, minute, second, tzinfo=tzinfo)
    except ValueError:
        return None
    return int(dt.timestamp() * 1000)


def _month_number(word: str) -> int:
    return _MONTH_NUMBERS.get(word[:3].lower(), 0)


def _digits_to_ms(digits: str, tzinfo: Optional[timezone] = None) -> Optional[int]:
    year = int(digits[0:4])
    month = int(digits[4:6])
    day = int(digits[6:8])
    hour = int(digits[8:10]) if len(digits) >= 12 else 0
    minute = int(digits[10:12]) if len(digits) >= 12 else 0
    second = int(digits[12:14]) if len(digits) == 14 else 0
    return _strict_calendar_to_ms(year, month, day, hour, minute, second, tzinfo)


def _from_digit_runs(fragment: str) -> Optional[int]:
    """
    Разбирает совпавший фрагмент, очищенный от разделителей, позиционно:
    yyyyMMdd[HHmm[ss]]. Мс-хвосты (`signal-…-123`) отсекаются границами шаблонов.
    """
    digits = _SEPARATORS.sub("", fragment)
    if len(digits) not in (8, 12, 14):
        return None
    return _digits_to_ms(digits)


def _parse_with_zone(fragment: str) -> Optional[int]:
    """Разбор фрагмента 5a с TZ-суффиксом: `Z` → UTC, `±HH[:MM]` → фиксированная зона."""
    tz_match = TZ_SUFFIX.search(fragment)
    if tz_match is None:
        return None
    tz_text = tz_match.group(0)
    core = fragment[: tz_match.start()]
    digits = _SEPARATORS.sub("", core)
    if len(digits) not in (12, 14):
        return None
    if tz_text == "Z":
        tzinfo = timezone.utc
    else:
        numbers = tz_text[1:].replace(":", "")
        hours = int(numbers[0:2])
        minutes = int(numbers[2:4])
        if hours > 14 or minutes > 59:
            return None
        offset = timedelta(hours=hours, minutes=minutes)
        if tz_text[0] == "-":
            offset = -offset
        tzinfo = timezone(offset)
    return _digits_to_ms(digits, tzinfo)


def _parse_day_first(match: "re.Match") -> Optional[int]:
    a = int(match.group(1))
    b = int(match.group(2))
    year = int(match.group(3))
    hour = int(match.group(4) or 0)
    minute = int(match.group(5) or 0)
    second = int(match.group(6) or 0)
    return _strict_calendar_to_ms(year, b, a, hour, minute, second) or \
        _strict_calendar_to_ms(year, a, b, hour, minute, second)


def parse_date_ms(name: Optional[str]) -> Optional[int]:
    """Epoch ms или None, если в имени нет надёжной даты."""
    if not name:
        return None

    match = EPOCH_PREFIX.search(name)
    if match:
        digits = match.group(1)
        value = int(digits)
        if len(digits) == 13:
            ms = value                      # epoch-ms
        elif len(digits) == 16:
            ms = value // 1000              # epoch-µs
        else:
            ms = value // 1_000_000         # epoch-ns
        return _valid(ms)

    base = name.rsplit(".", 1)[0]
    if _ALL_DIGITS.fullmatch(base):
        if len(base) == 13:
            return _valid(int(base))              # epoch-ms
        if len(base) == 16:
            return _valid(int(base) // 1000)      # epoch-µs
        if len(base) == 10:
            return _valid(int(base) * 1000)       # epoch-s
        # прочие длины (счётчики, ID) — не дата, но имя может
        # начинаться с компактной даты; проверка продолжается по шаблонам ниже

    match = MONTH_NAME.search(name)
    if match:
        year_text = (match.group("yA") or match.group("yB")
                     or match.group("yC") or match.group("yD"))
        month_word = (match.group("mA") or match.group("mB")
                      or match.group("mC") or match.group("mD"))
        day_text = (match.group("dA") or match.group("dB")
                    or match.group("dC") or match.group("dD"))
        if year_text and month_word and day_text:
            hour = int(match.group("hh") or 0)
            minute = int(match.group("mi") or 0)
            second = int(match.group("ss") or 0)
            ms = _strict_calendar_to_ms(
                int(year_text), _month_number(month_word), int(day_text),
                hour, minute, second,
            )
            if ms is not None:
                return _valid(ms)

    match = COMPACT_DATETIME.search(name)
    if match:
        ms = _from_digit_runs(match.group(0))
        return _valid(ms) if ms is not None else None

    match = SEPARATED_TZ.search(name)
    if match:
        ms = _parse_with_zone(match.group(0))
        if ms is not None:
            return _valid(ms)

    match = SEPARATED_DATETIME.search(name)
    if match:
        ms = _from_digit_runs(match.group(0))
        return _valid(ms) if ms is not None else None

    match = DAY_FIRST.search(name)
    if match:
        ms = _parse_day_first(match)
        if ms is not None:
            return _valid(ms)

    match = DATE_ONLY.search(name)
    if match:
        ms = _from_digit_runs(match.group(0))
        return _valid(ms) if ms is not None else None

    return None

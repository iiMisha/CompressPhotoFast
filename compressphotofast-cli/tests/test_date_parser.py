#!/usr/bin/env python3
"""
Порт FileNameDateParserTest.kt: epoch-префиксы, цифровые basename, месяцы
словами, компактные/разделённые дата-время, day-first, только дата, негативы.
Запуск: python3 tests/test_date_parser.py или через pytest.
"""
import os
import sys
import time
from datetime import datetime, timezone, timedelta

# Добавляем корень CLI-пакета в путь: src является пакетом (относительные импорты)
cli_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, cli_root)

from src.date_parser import parse_date_ms  # noqa: E402


def ms_of(year, month, day, hour=0, minute=0, second=0):
    return int(datetime(year, month, day, hour, minute, second).timestamp() * 1000)


def ms_in_zone(tz, year, month, day, hour, minute, second):
    sign = 1 if tz.startswith("+") else -1
    hours = int(tz[1:3])
    minutes = int(tz[4:6])
    tzinfo = timezone(sign * timedelta(hours=hours, minutes=minutes))
    dt = datetime(year, month, day, hour, minute, second, tzinfo=tzinfo)
    return int(dt.timestamp() * 1000)


def test_epoch_с_известным_префиксом():
    assert parse_date_ms("mmexport1698741123456.jpg") == 1698741123456
    assert parse_date_ms("wx_camera_1698741123456.jpg") == 1698741123456
    assert parse_date_ms("Snapchat-1698741123456.jpg") == 1698741123456
    assert parse_date_ms("mmexport1698741123456000.jpg") == 1698741123456
    assert parse_date_ms("Snapchat-1698741123456000000.jpg") == 1698741123456


def test_basename_целиком_из_цифр():
    assert parse_date_ms("1698741123456.jpg") == 1698741123456
    assert parse_date_ms("1698741123456000.jpg") == 1698741123456
    assert parse_date_ms("1698741123.jpg") == 1698741123000


def test_месяцы_словами_и_ios_время():
    assert parse_date_ms("Photo Jan 1, 2025 at 12;30;45 JPG.jpg") == ms_of(2025, 1, 1, 12, 30, 45)
    assert parse_date_ms("Jan 01 2025.jpg") == ms_of(2025, 1, 1)
    assert parse_date_ms("01 Jan 2025.jpg") == ms_of(2025, 1, 1)
    assert parse_date_ms("2025-Jan-01.jpg") == ms_of(2025, 1, 1)
    assert parse_date_ms("01Jan2025.jpg") == ms_of(2025, 1, 1)
    assert parse_date_ms("photo January 01, 2025.jpg") == ms_of(2025, 1, 1)
    assert parse_date_ms("Sep 02 2025 08.15.jpg") == ms_of(2025, 9, 2, 8, 15)


def test_компактные_дата_время():
    expected = ms_of(2025, 1, 1, 12, 34, 56)
    assert parse_date_ms("IMG_20250101_123456.jpg") == expected
    assert parse_date_ms("20250101_123456.jpg") == expected
    assert parse_date_ms("Screenshot_20250101-123456_CompressPhotoFast.jpg") == expected
    assert parse_date_ms("PANO_20250101_123456.jpg") == expected
    assert parse_date_ms("MVIMG_20250101_123456.jpg") == expected
    assert parse_date_ms("IMG_20250101_123456_BURST001.jpg") == expected
    assert parse_date_ms("IMG_20250101_123456_compressed.jpg") == expected
    assert parse_date_ms("20250101123456.jpg") == expected
    assert parse_date_ms("20250101T123456.jpg") == expected


def test_разделённые_дата_время_мс_хвост_игнорируется():
    assert parse_date_ms("signal-2025-01-02-03-04-05-123.jpg") == ms_of(2025, 1, 2, 3, 4, 5)
    assert parse_date_ms("photo_2025-01-01_12-30-45.jpg") == ms_of(2025, 1, 1, 12, 30, 45)
    assert parse_date_ms("photo_2025-01-01_12-30-45 (1).jpg") == ms_of(2025, 1, 1, 12, 30, 45)
    assert parse_date_ms("Screenshot_2022-01-01-12-30-45-123.png") == ms_of(2022, 1, 1, 12, 30, 45)
    assert parse_date_ms("photo_2025-01-01_12-30.jpg") == ms_of(2025, 1, 1, 12, 30)


def test_разделённые_дата_время_с_двоеточиями():
    assert parse_date_ms("2025-01-01 12:30:45.jpg") == ms_of(2025, 1, 1, 12, 30, 45)
    assert parse_date_ms("2025-01-01_12:30.jpg") == ms_of(2025, 1, 1, 12, 30)
    assert parse_date_ms("2025-01-01_12:30:45 (1).jpg") == ms_of(2025, 1, 1, 12, 30, 45)


def test_таймзонные_суффиксы():
    assert parse_date_ms("2025-01-01T12:30:45Z.jpg") == ms_in_zone("+00:00", 2025, 1, 1, 12, 30, 45)
    assert parse_date_ms("2025-01-01 12:30:45+03:00.jpg") == ms_in_zone("+03:00", 2025, 1, 1, 12, 30, 45)
    assert parse_date_ms("2025-01-01 12:30:45-05:00.jpg") == ms_in_zone("-05:00", 2025, 1, 1, 12, 30, 45)
    assert parse_date_ms("2025-01-01 123045+0300.jpg") == ms_in_zone("+03:00", 2025, 1, 1, 12, 30, 45)


def test_day_first_гибрид_dd_mm_and_mm_dd():
    assert parse_date_ms("01-02-2025.jpg") == ms_of(2025, 2, 1)
    assert parse_date_ms("12-25-2025.jpg") == ms_of(2025, 12, 25)
    assert parse_date_ms("01.02.2025") == ms_of(2025, 2, 1)
    assert parse_date_ms("01/02/2025") == ms_of(2025, 2, 1)
    assert parse_date_ms("31-12-2025_10-20-30.jpg") == ms_of(2025, 12, 31, 10, 20, 30)
    assert parse_date_ms("25-12-2025 12:30:45") == ms_of(2025, 12, 25, 12, 30, 45)


def test_только_дата_полночь():
    assert parse_date_ms("IMG-20250101-WA0001.jpg") == ms_of(2025, 1, 1)
    assert parse_date_ms("VID-20250101-WA0001.mp4") == ms_of(2025, 1, 1)
    assert parse_date_ms("20250101.jpg") == ms_of(2025, 1, 1)
    assert parse_date_ms("2025-01-01.png") == ms_of(2025, 1, 1)
    assert parse_date_ms("2025.01.01.jpg") == ms_of(2025, 1, 1)


def test_негативные_случаи():
    assert parse_date_ms(None) is None
    assert parse_date_ms("") is None
    assert parse_date_ms("DSC01234.jpg") is None
    assert parse_date_ms("GOPR1234.jpg") is None
    assert parse_date_ms("2025-13-01.jpg") is None
    assert parse_date_ms("20250230.jpg") is None
    assert parse_date_ms("19900101.jpg") is None
    assert parse_date_ms("12345678.jpg") is None
    assert parse_date_ms("received_10161672603575608.jpg") is None
    assert parse_date_ms("202501011234567.jpg") is None
    assert parse_date_ms("202501011234.jpg") is None


def test_негативные_случаи_новых_форматов():
    assert parse_date_ms("251201.jpg") is None
    assert parse_date_ms("25-12-01.jpg") is None
    assert parse_date_ms("13-25-2025.jpg") is None
    assert parse_date_ms("01122025.jpg") is None
    assert parse_date_ms("photo 01 02 2025.jpg") is None
    assert parse_date_ms("1234567890123456789.jpg") is None
    assert parse_date_ms("IMG-1234-5678-2025.jpg") is None


def test_окно_валидности():
    # Будущее +25 ч — за окном
    future = int((time.time() + 25 * 3600) * 1000)
    assert parse_date_ms(f"{future}.jpg") is None
    # Будущее +1 ч (13 цифр) — в окне
    near_future = int(time.time() * 1000) + 3600 * 1000
    assert parse_date_ms(f"{near_future}.jpg") is not None
    # Прошлое до 1990 — за окном
    assert parse_date_ms("2025-13-01.jpg") is None


def main():
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    print("=" * 60)
    print("Testing FileNameDateParser port (date_parser.py)")
    print("=" * 60)
    failed = 0
    for test in tests:
        try:
            test()
            print(f"✓ PASS: {test.__name__}")
        except AssertionError as e:
            failed += 1
            print(f"✗ FAIL: {test.__name__}: {e}")
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"✗ ERROR: {test.__name__}: {type(e).__name__}: {e}")
    print("=" * 60)
    print(f"{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

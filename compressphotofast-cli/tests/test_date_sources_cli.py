#!/usr/bin/env python3
"""
Тесты CLI-расширения источников дат (src/date_sources_cli.py):
Siemens ``YYMMDD_HHMMSS`` / Meizu ``P<YYMMDD>-<HHMMSS>`` / Lenovo непаддированные
11-14 цифр / дата из имени папки. Запуск: python3 tests/test_date_sources_cli.py
или pytest. Форматы подтверждены владельцем архива D:\\Docs\\FOTO:
070414_152426 = 14.04.2007 15:24; P70429-132038 = 29.04.2007 13:20;
2010101080149 = 10.10.2010 08:01 (при неоднозначности — максимальная дата).
"""
import os
import sys
from datetime import datetime

cli_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, cli_root)

from src.date_sources_cli import (  # noqa: E402
    folder_component_date_ms,
    folder_date_ms,
    parse_date_cli_extra_ms,
    parse_lenovo_unpadded_ms,
    parse_siemens_meizu_ms,
)


def _ms(y, mo, d, h=0, mi=0, s=0):
    return int(datetime(y, mo, d, h, mi, s).timestamp() * 1000)


# --- Siemens / Meizu ------------------------------------------------------

def test_siemens_известные_файлы():
    assert parse_siemens_meizu_ms("070414_152426") == _ms(2007, 4, 14, 15, 24, 26)
    assert parse_siemens_meizu_ms("060605_213445") == _ms(2006, 6, 5, 21, 34, 45)
    assert parse_siemens_meizu_ms("000108_194902") == _ms(2000, 1, 8, 19, 49, 2)
    assert parse_siemens_meizu_ms("091225_103352") == _ms(2009, 12, 25, 10, 33, 52)


def test_meizu_с_префиксом_P_и_дефисом():
    assert parse_siemens_meizu_ms("P70429-132038") == _ms(2007, 4, 29, 13, 20, 38)
    assert parse_siemens_meizu_ms("P80115-125958") == _ms(2008, 1, 15, 12, 59, 58)


def test_siemens_отвергает_аномалии():
    # месяц 16 (P201611), часы 65, минуты 99, счётчики, дата вне окна
    assert parse_siemens_meizu_ms("P201611-124438") is None
    assert parse_siemens_meizu_ms("123456_654321") is None
    assert parse_siemens_meizu_ms("120414_999945") is None
    assert parse_siemens_meizu_ms("P1160126") is None
    # YY=85: 2085 вне окна, 1985 тоже (окно с 1990) → отказ
    assert parse_siemens_meizu_ms("850505_210000") is None


def test_двузначный_год_выбирает_век_по_окну():
    # YY=05 → 2005 (20-й век не нужен); YY=99 → 2099 вне окна → 1999
    assert parse_siemens_meizu_ms("050505_210000") == _ms(2005, 5, 5, 21, 0, 0)
    assert parse_siemens_meizu_ms("991231_235959") == _ms(1999, 12, 31, 23, 59, 59)


# --- Lenovo непаддированные ------------------------------------------------

def test_lenovo_непаддированные_подтверждённые():
    assert parse_lenovo_unpadded_ms("2010101080149") == _ms(2010, 10, 10, 8, 1, 49)
    assert parse_lenovo_unpadded_ms("2014426210722") == _ms(2014, 4, 26, 21, 7, 22)
    assert parse_lenovo_unpadded_ms("2014427084505") == _ms(2014, 4, 27, 8, 45, 5)


def test_lenovo_14_значный_паддированный():
    assert parse_lenovo_unpadded_ms("20141001151941") == _ms(2014, 10, 1, 15, 19, 41)


def test_lenovo_неоднозначность_берётся_максимальная_дата():
    # 2010101080149: валидны 2010-10-10 08:01:49 и 2010-01-01 08:01:49 —
    # владелец подтвердил первое (максимальная дата)
    assert parse_lenovo_unpadded_ms("2010101080149") > _ms(2010, 1, 1)


def test_lenovo_отвергает_мусор():
    assert parse_lenovo_unpadded_ms("9999999999999") is None  # год вне окна
    assert parse_lenovo_unpadded_ms("12345678901234567") is None  # длина
    # время 99:99:99 — все разбиения невалидны
    assert parse_lenovo_unpadded_ms("2014426999999") is None
    assert parse_lenovo_unpadded_ms("2099133207221") is None


# --- Дата из папки ----------------------------------------------------------

def test_папка_только_год():
    assert folder_component_date_ms("2004") == _ms(2004, 1, 1)
    assert folder_component_date_ms("2023") == _ms(2023, 1, 1)


def test_папка_год_месяц_и_полная_дата_с_текстом():
    assert folder_component_date_ms("2009_02_forhome_алчевск_маи") == _ms(2009, 2, 1)
    assert folder_component_date_ms("2009_08 Анапа") == _ms(2009, 8, 1)
    assert folder_component_date_ms("2009_06_28") == _ms(2009, 6, 28)
    assert folder_component_date_ms("2010_04_25_test") == _ms(2010, 4, 25)


def test_папка_диапазон_и_мусор_не_матчатся():
    assert folder_component_date_ms("1988-2004") is None  # месяц 20 невалиден
    assert folder_component_date_ms("Camera_Meizu") is None
    assert folder_component_date_ms("001-001") is None
    assert folder_component_date_ms("2020_13") is None  # месяц 13


def test_путь_ближайшая_компонента_снизу_вверх():
    path = os.path.join("D:", "Docs", "FOTO", "2023", "Download", "img.jpg")
    assert folder_date_ms(path) == _ms(2023, 1, 1)
    path2 = os.path.join("D:", "Docs", "FOTO", "2009_06_28", "sub", "img.jpg")
    assert folder_date_ms(path2) == _ms(2009, 6, 28)  # ближайшая = 2009_06_28
    path3 = os.path.join("D:", "Docs", "FOTO", "Pictures", "img.jpg")
    assert folder_date_ms(path3) is None


# --- Точка входа parse_date_cli_extra_ms -----------------------------------

def test_extra_точка_входа_с_расширением():
    assert parse_date_cli_extra_ms("070414_152426.jpg") == _ms(2007, 4, 14, 15, 24, 26)
    assert parse_date_cli_extra_ms("P70429-132038.jpg") == _ms(2007, 4, 29, 13, 20, 38)
    assert parse_date_cli_extra_ms("2010101080149.JPG") == _ms(2010, 10, 10, 8, 1, 49)
    assert parse_date_cli_extra_ms("IMG002.jpg") is None
    assert parse_date_cli_extra_ms("") is None


def main():
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    print("=" * 60)
    print("Testing date_sources_cli (Siemens/Meizu/Lenovo/folder)")
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

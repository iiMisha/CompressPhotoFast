#!/usr/bin/env python3
"""
Подстановка DateTime/DateTimeDigitized из mtime при записи маркера сжатия
(семантическое соответствие Android ExifUtil.writeSkipMarker).
Запуск: python3 tests/test_exif_handler_dates.py (без pytest) или через pytest.
"""
import os
import sys
import tempfile
from datetime import datetime

# Добавляем корень CLI-пакета в путь: src является пакетом (относительные импорты)
cli_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, cli_root)

from src.exif_handler import ExifHandler  # noqa: E402
import piexif  # noqa: E402
from PIL import Image  # noqa: E402


def _make_jpeg_without_dates():
    """JPEG без каких-либо дат в EXIF."""
    fd, path = tempfile.mkstemp(suffix=".jpg")
    os.close(fd)
    Image.new("RGB", (64, 64), color=(120, 120, 120)).save(path, quality=90)
    return path


def _set_dates(path, datetime_original=None, date_time=None, digitized=None):
    exif_dict = piexif.load(path)
    if datetime_original is not None:
        exif_dict.setdefault("Exif", {})[piexif.ExifIFD.DateTimeOriginal] = datetime_original
    if date_time is not None:
        exif_dict.setdefault("0th", {})[piexif.ImageIFD.DateTime] = date_time
    if digitized is not None:
        exif_dict.setdefault("Exif", {})[piexif.ExifIFD.DateTimeDigitized] = digitized
    piexif.insert(piexif.dump(exif_dict), path)


def _set_mtime(path, epoch_seconds):
    os.utime(path, (epoch_seconds, epoch_seconds))
    return epoch_seconds


def _read_marker(path):
    exif_dict = piexif.load(path)
    user_comment = exif_dict["Exif"].get(piexif.ExifIFD.UserComment)
    assert user_comment is not None, "маркер UserComment отсутствует"
    return user_comment.decode("utf-8", errors="replace")


def _expected_date_str(epoch_seconds):
    return datetime.fromtimestamp(epoch_seconds).strftime("%Y:%m:%d %H:%M:%S")


def test_маркер_без_дат_добавляет_даты_из_mtime():
    path = _make_jpeg_without_dates()
    try:
        mtime = _set_mtime(path, 1700000000)

        assert ExifHandler.add_compression_marker(path, 99) is True

        expected = _expected_date_str(mtime)
        exif_dict = piexif.load(path)
        assert exif_dict["0th"][piexif.ImageIFD.DateTime].decode() == expected
        assert exif_dict["Exif"][piexif.ExifIFD.DateTimeDigitized].decode() == expected
        assert piexif.ExifIFD.DateTimeOriginal not in exif_dict["Exif"]
        assert _read_marker(path).startswith("CompressPhotoFast_Compressed:99:")
    finally:
        os.remove(path)


def test_маркер_без_source_exif_через_чтение_существующего_exif():
    """Ветка без source_exif: piexif.load из файла, затем маркер."""
    path = _make_jpeg_without_dates()
    try:
        mtime = _set_mtime(path, 1600000000)

        assert ExifHandler.add_compression_marker(path, 99, None) is True

        expected = _expected_date_str(mtime)
        exif_dict = piexif.load(path)
        assert exif_dict["0th"][piexif.ImageIFD.DateTime].decode() == expected
        assert exif_dict["Exif"][piexif.ExifIFD.DateTimeDigitized].decode() == expected
    finally:
        os.remove(path)


def test_маркер_с_source_exif_без_дат_добавляет_даты():
    path = _make_jpeg_without_dates()
    try:
        mtime = _set_mtime(path, 1500000000)
        source_exif = piexif.load(path)

        assert ExifHandler.add_compression_marker(path, 99, source_exif) is True

        expected = _expected_date_str(mtime)
        exif_dict = piexif.load(path)
        assert exif_dict["0th"][piexif.ImageIFD.DateTime].decode() == expected
        assert exif_dict["Exif"][piexif.ExifIFD.DateTimeDigitized].decode() == expected
    finally:
        os.remove(path)


def test_при_наличии_datetime_original_даты_не_подставляются():
    path = _make_jpeg_without_dates()
    try:
        original_date = b"2020:01:02 03:04:05"
        _set_dates(path, datetime_original=original_date)
        _set_mtime(path, 1700000000)

        assert ExifHandler.add_compression_marker(path, 99) is True

        exif_dict = piexif.load(path)
        assert exif_dict["Exif"][piexif.ExifIFD.DateTimeOriginal] == original_date
        assert piexif.ImageIFD.DateTime not in exif_dict["0th"]
        assert piexif.ExifIFD.DateTimeDigitized not in exif_dict["Exif"]
    finally:
        os.remove(path)


def test_при_наличии_datetime_в_0th_даты_не_подставляются():
    path = _make_jpeg_without_dates()
    try:
        existing = b"2019:05:06 07:08:09"
        _set_dates(path, date_time=existing)
        _set_mtime(path, 1700000000)

        assert ExifHandler.add_compression_marker(path, 99) is True

        exif_dict = piexif.load(path)
        assert exif_dict["0th"][piexif.ImageIFD.DateTime] == existing
        assert piexif.ExifIFD.DateTimeDigitized not in exif_dict["Exif"]
    finally:
        os.remove(path)


def test_has_any_date_покрывает_все_четыре_тега():
    assert ExifHandler._has_any_date(None) is False
    assert ExifHandler._has_any_date({}) is False

    path = _make_jpeg_without_dates()
    try:
        _set_dates(path, datetime_original=b"2020:01:02 03:04:05")
        assert ExifHandler._has_any_date(piexif.load(path)) is True

        exif_dict = piexif.load(path)
        exif_dict["Exif"].pop(piexif.ExifIFD.DateTimeOriginal)
        exif_dict.setdefault("GPS", {})[piexif.GPSIFD.GPSDateStamp] = b"2020:01:02"
        assert ExifHandler._has_any_date(exif_dict) is True

        exif_dict["GPS"].pop(piexif.GPSIFD.GPSDateStamp)
        assert ExifHandler._has_any_date(exif_dict) is False
    finally:
        os.remove(path)


def main():
    tests = [
        test_маркер_без_дат_добавляет_даты_из_mtime,
        test_маркер_без_source_exif_через_чтение_существующего_exif,
        test_маркер_с_source_exif_без_дат_добавляет_даты,
        test_при_наличии_datetime_original_даты_не_подставляются,
        test_при_наличии_datetime_в_0th_даты_не_подставляются,
        test_has_any_date_покрывает_все_четыре_тега,
    ]
    print("=" * 60)
    print("Testing ExifHandler date backfill in add_compression_marker")
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

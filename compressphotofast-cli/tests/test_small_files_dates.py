#!/usr/bin/env python3
"""
Бэкфилл дат для мелких файлов (<100 КБ) на пропуске «Already small»
и идемпотентность ExifHandler.backfill_missing_dates.
Запуск: python3 tests/test_small_files_dates.py (без pytest) или через pytest.
"""
import os
import sys
import tempfile

# Добавляем корень CLI-пакета в путь: src является пакетом (относительные импорты)
cli_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, cli_root)

from src.exif_handler import ExifHandler  # noqa: E402
from src.multiprocessing_utils import (  # noqa: E402
    process_single_file,
    process_single_file_dry_run,
)
from src.file_utils import FileInfo, is_already_small  # noqa: E402
import piexif  # noqa: E402
from PIL import Image  # noqa: E402


def _set_mtime(path, epoch_seconds):
    os.utime(path, (epoch_seconds, epoch_seconds))
    return epoch_seconds


def _mkstemp_closed(suffix):
    """mkstemp с закрытым fd: открытый дескриптор блокирует файл на Windows."""
    fd, path = tempfile.mkstemp(suffix=suffix)
    os.close(fd)
    return path


def _expected_date_str(epoch_seconds):
    from datetime import datetime
    return datetime.fromtimestamp(epoch_seconds).strftime("%Y:%m:%d %H:%M:%S")


def _dates_of(path):
    # PNG: piexif.load по пути не читает PNG — eXIf-чанк через Pillow
    if os.path.splitext(path)[1].lower() == ".png":
        with Image.open(path) as img:
            raw = img.info.get("exif")
        if not raw:
            return None, None
        exif_dict = piexif.load(raw)
    else:
        exif_dict = piexif.load(path)
    dt = exif_dict.get("0th", {}).get(piexif.ImageIFD.DateTime)
    dd = exif_dict.get("Exif", {}).get(piexif.ExifIFD.DateTimeDigitized)
    if isinstance(dt, bytes):
        dt = dt.decode()
    if isinstance(dd, bytes):
        dd = dd.decode()
    return dt, dd


def _make_small_jpeg(path):
    """JPEG меньше 100 КБ (порог is_already_small = 100 КБ)."""
    Image.new("RGB", (24, 24), color=(1, 2, 3)).save(path, quality=85)
    assert is_already_small(os.path.getsize(path))
    return path


def _make_small_png(path):
    Image.new("RGB", (24, 24), color=(1, 2, 3)).save(path, format="PNG")
    assert is_already_small(os.path.getsize(path))
    return path


def test_backfill_jpeg_без_дат():
    path = _make_small_jpeg(_mkstemp_closed(".jpg"))
    try:
        mtime = _set_mtime(path, 1700000777)
        assert ExifHandler.backfill_missing_dates(path) is True
        expected = _expected_date_str(mtime)
        dt, dd = _dates_of(path)
        assert dt == expected
        assert dd == expected
        # mtime записи не меняет
        assert os.path.getmtime(path) == mtime
    finally:
        os.remove(path)


def test_backfill_png_без_дат():
    path = _make_small_png(_mkstemp_closed(".png"))
    try:
        mtime = _set_mtime(path, 1600000333)
        assert ExifHandler.backfill_missing_dates(path) is True
        expected = _expected_date_str(mtime)
        dt, dd = _dates_of(path)
        assert dt == expected
        assert dd == expected
    finally:
        os.remove(path)


def test_backfill_с_датами_noop_байты_не_меняются():
    path = _make_small_jpeg(_mkstemp_closed(".jpg"))
    try:
        exif_dict = {"0th": {}, "Exif": {}}
        exif_dict["0th"][piexif.ImageIFD.DateTime] = b"2019:02:03 04:05:06"
        img = Image.open(path)
        img.save(path, quality=85, exif=piexif.dump(exif_dict))
        img.close()
        before = open(path, "rb").read()
        mtime = _set_mtime(path, 1700000777)

        assert ExifHandler.backfill_missing_dates(path) is True

        assert open(path, "rb").read() == before
        assert os.path.getmtime(path) == mtime
        dt, _ = _dates_of(path)
        assert dt == "2019:02:03 04:05:06"
    finally:
        os.remove(path)


def test_backfill_heic_не_поддерживается():
    # JPEG-байты с расширением .heic: отказ по расширению (pillow-heif может
    # отсутствовать в окружении, контент для проверки не важен)
    path = _make_small_jpeg(_mkstemp_closed(".jpg"))
    heic_path = path + ".heic"
    os.replace(path, heic_path)
    try:
        assert ExifHandler.backfill_missing_dates(heic_path) is False
    finally:
        os.remove(heic_path)


def test_process_single_file_already_small_jpeg_пишет_даты():
    path = _make_small_jpeg(_mkstemp_closed(".jpg"))
    try:
        mtime = _set_mtime(path, 1550000444)
        info = FileInfo(path, os.path.basename(path), os.path.getsize(path), mtime)

        file_info, result, skipped, reason, error = process_single_file(
            info, 70, replace=False, output_dir=None, force=False
        )

        assert error == ""
        assert skipped is True
        assert reason == "Already small"
        assert result is None
        expected = _expected_date_str(mtime)
        dt, dd = _dates_of(path)
        assert dt == expected
        assert dd == expected
    finally:
        os.remove(path)


def test_process_single_file_already_small_png_пишет_даты():
    path = _make_small_png(_mkstemp_closed(".png"))
    try:
        mtime = _set_mtime(path, 1500000111)
        info = FileInfo(path, os.path.basename(path), os.path.getsize(path), mtime)

        _, _, skipped, reason, error = process_single_file(
            info, 70, replace=False, output_dir=None, force=False
        )

        assert error == ""
        assert skipped is True
        assert reason == "Already small"
        expected = _expected_date_str(mtime)
        dt, dd = _dates_of(path)
        assert dt == expected
        assert dd == expected
    finally:
        os.remove(path)


def test_dry_run_ничего_не_пишет():
    path = _make_small_jpeg(_mkstemp_closed(".jpg"))
    try:
        mtime = _set_mtime(path, 1450000555)
        before = open(path, "rb").read()
        info = FileInfo(path, os.path.basename(path), os.path.getsize(path), mtime)

        _, should_process, reason, _ = process_single_file_dry_run(info, 70, False)

        assert should_process is False
        assert reason == "Already small"
        assert open(path, "rb").read() == before
        assert os.path.getmtime(path) == mtime
        # Дат нет — dry-run не писал
        dt, dd = _dates_of(path)
        assert dt is None and dd is None
    finally:
        os.remove(path)


def main():
    tests = [
        test_backfill_jpeg_без_дат,
        test_backfill_png_без_дат,
        test_backfill_с_датами_noop_байты_не_меняются,
        test_backfill_heic_не_поддерживается,
        test_process_single_file_already_small_jpeg_пишет_даты,
        test_process_single_file_already_small_png_пишет_даты,
        test_dry_run_ничего_не_пишет,
    ]
    print("=" * 60)
    print("Testing small-file date backfill")
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

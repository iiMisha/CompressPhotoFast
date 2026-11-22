#!/usr/bin/env python3
"""
Гарантия дат в сжатых копиях: JPEG/PNG success-пути и HEIC-конвертация
берут DateTime/DateTimeDigitized из mtime исходника (до перезаписи).
Запуск: python3 tests/test_compression_dates.py (без pytest) или через pytest.
"""
import os
import sys
import tempfile

# Добавляем корень CLI-пакета в путь: src является пакетом (относительные импорты)
cli_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, cli_root)

from src.compression import ImageCompressor  # noqa: E402
from src.exif_handler import ExifHandler  # noqa: E402
import piexif  # noqa: E402
from PIL import Image  # noqa: E402

try:
    import pillow_heif  # noqa: F401
    HEIC_OK = True
except ImportError:
    HEIC_OK = False


def _noise_image(size=900):
    """Шумовая картинка: плохо сжимается, превышает порог 100 КБ."""
    import random
    rng = random.Random(42)
    raw = bytes(rng.randrange(256) for _ in range(size * size * 3))
    return Image.frombytes("RGB", (size, size), raw)


def _set_mtime(path, epoch_seconds):
    os.utime(path, (epoch_seconds, epoch_seconds))
    return epoch_seconds


def _expected_date_str(epoch_seconds):
    from datetime import datetime
    return datetime.fromtimestamp(epoch_seconds).strftime("%Y:%m:%d %H:%M:%S")


def _dates_of(path):
    # PNG: piexif.load по пути не читает PNG — eXIf-чанк через Pillow
    if os.path.splitext(path)[1].lower() == ".png":
        with Image.open(path) as img:
            raw = img.info.get("exif")
        if not raw:
            return None, None, None
        exif_dict = piexif.load(raw)
    else:
        exif_dict = piexif.load(path)
    dt = exif_dict.get("0th", {}).get(piexif.ImageIFD.DateTime)
    dd = exif_dict.get("Exif", {}).get(piexif.ExifIFD.DateTimeDigitized)
    do = exif_dict.get("Exif", {}).get(piexif.ExifIFD.DateTimeOriginal)
    if isinstance(dt, bytes):
        dt = dt.decode()
    if isinstance(dd, bytes):
        dd = dd.decode()
    if isinstance(do, bytes):
        do = do.decode()
    return dt, dd, do


def _make_jpeg(path, with_make=False, with_dates=False):
    img = _noise_image()
    if with_make or with_dates:
        exif_dict = {"0th": {}, "Exif": {}}
        if with_make:
            exif_dict["0th"][piexif.ImageIFD.Make] = b"TestCam"
        if with_dates:
            exif_dict["0th"][piexif.ImageIFD.DateTime] = b"2020:01:02 03:04:05"
        img.save(path, quality=92, exif=piexif.dump(exif_dict))
    else:
        img.save(path, quality=92)
    return path


def test_jpeg_без_exif_копия_получает_даты_из_mtime_исходника():
    tmp = tempfile.mkdtemp()
    src = os.path.join(tmp, "src.jpg")
    out = os.path.join(tmp, "out_compressed.jpg")
    try:
        _make_jpeg(src)
        mtime = _set_mtime(src, 1700000123)

        result = ImageCompressor.compress_image(src, 40, out, preserve_exif=True)

        assert result.success, result.message
        assert os.path.exists(out)
        dt, dd, do = _dates_of(out)
        expected = _expected_date_str(mtime)
        assert dt == expected
        assert dd == expected
        assert do is None  # DateTimeOriginal не выдумывается
        # Маркера на success-пути нет
        assert not os.path.splitext(out)[1].lower() == ".png"
        is_marker, _, _ = ExifHandler.get_compression_info(out)
        assert not is_marker
        # mtime исходника не меняется
        assert os.path.getmtime(src) == mtime
    finally:
        for p in (src, out):
            if os.path.exists(p):
                os.remove(p)
        os.rmdir(tmp)


def test_jpeg_с_exif_сохраняет_make_и_добавляет_даты():
    tmp = tempfile.mkdtemp()
    src = os.path.join(tmp, "src.jpg")
    out = os.path.join(tmp, "out_compressed.jpg")
    try:
        _make_jpeg(src, with_make=True)
        mtime = _set_mtime(src, 1600000456)

        result = ImageCompressor.compress_image(src, 40, out, preserve_exif=True)

        assert result.success, result.message
        exif_dict = piexif.load(out)
        assert exif_dict["0th"][piexif.ImageIFD.Make].decode() == "TestCam"
        dt, dd, _ = _dates_of(out)
        expected = _expected_date_str(mtime)
        assert dt == expected
        assert dd == expected
        assert os.path.getmtime(src) == mtime
    finally:
        for p in (src, out):
            if os.path.exists(p):
                os.remove(p)
        os.rmdir(tmp)


def test_jpeg_с_существующими_датами_даты_не_заменяются():
    tmp = tempfile.mkdtemp()
    src = os.path.join(tmp, "src.jpg")
    out = os.path.join(tmp, "out_compressed.jpg")
    try:
        _make_jpeg(src, with_dates=True)
        _set_mtime(src, 1700000123)

        result = ImageCompressor.compress_image(src, 40, out, preserve_exif=True)

        assert result.success, result.message
        dt, dd, _ = _dates_of(out)
        assert dt == "2020:01:02 03:04:05"
        assert dd is None
    finally:
        for p in (src, out):
            if os.path.exists(p):
                os.remove(p)
        os.rmdir(tmp)


def test_png_с_датами_даты_сохраняются():
    tmp = tempfile.mkdtemp()
    src = os.path.join(tmp, "src.png")
    out = os.path.join(tmp, "out_compressed.png")
    try:
        img = _noise_image()
        exif_dict = {"0th": {}, "Exif": {}}
        exif_dict["0th"][piexif.ImageIFD.DateTime] = b"2021:06:07 08:09:10"
        img.save(src, format="PNG", exif=piexif.dump(exif_dict))
        assert os.path.getsize(src) >= 100 * 1024
        _set_mtime(src, 1700000123)

        # PNG пережимается теми же пикселями — порог эффективности
        # не при деле, фокус теста на сохранении EXIF в eXIf-чанке
        original = ImageCompressor.is_compression_efficient
        ImageCompressor.is_compression_efficient = staticmethod(lambda a, b: True)
        try:
            result = ImageCompressor.compress_image(src, 40, out, preserve_exif=True)
        finally:
            ImageCompressor.is_compression_efficient = staticmethod(original)

        assert result.success, result.message
        assert result.metadata_preserved
        dt, _, _ = _dates_of(out)
        assert dt == "2021:06:07 08:09:10"
    finally:
        for p in (src, out):
            if os.path.exists(p):
                os.remove(p)
        os.rmdir(tmp)


def test_png_без_дат_получает_даты_из_mtime_исходника():
    tmp = tempfile.mkdtemp()
    src = os.path.join(tmp, "src.png")
    out = os.path.join(tmp, "out_compressed.png")
    try:
        _noise_image().save(src, format="PNG")
        assert os.path.getsize(src) >= 100 * 1024
        mtime = _set_mtime(src, 1650000999)

        original = ImageCompressor.is_compression_efficient
        ImageCompressor.is_compression_efficient = staticmethod(lambda a, b: True)
        try:
            result = ImageCompressor.compress_image(src, 40, out, preserve_exif=True)
        finally:
            ImageCompressor.is_compression_efficient = staticmethod(original)

        assert result.success, result.message
        assert result.metadata_preserved
        expected = _expected_date_str(mtime)
        dt, dd, _ = _dates_of(out)
        assert dt == expected
        assert dd == expected
    finally:
        for p in (src, out):
            if os.path.exists(p):
                os.remove(p)
        os.rmdir(tmp)


def test_heic_конвертация_даты_из_mtime_исходника_и_origSize():
    if not HEIC_OK:
        print("SKIP: pillow-heif недоступен")
        return
    from src.constants import EXIF_COMPRESSION_MARKER
    tmp = tempfile.mkdtemp()
    src = os.path.join(tmp, "src.heic")
    out = os.path.join(tmp, "out_compressed.jpg")
    try:
        # JPEG-контент в .heic-имени: компрессор открывает через Pillow,
        # HEIC определяется по расширению (как в replace-режиме)
        _noise_image().save(src, quality=92)
        mtime = _set_mtime(src, 1550000222)
        original_size = os.path.getsize(src)

        result = ImageCompressor.compress_image(src, 40, out, preserve_exif=True)

        assert result.success, result.message
        expected = _expected_date_str(mtime)
        # Ожидаемая дата — не «сейчас», а mtime исходника
        dt, dd, _ = _dates_of(out)
        assert dt == expected
        assert dd == expected
        # origSize маркера = размер исходника
        parts = ExifHandler._read_marker_parts(out)
        assert parts is not None and parts[0] == EXIF_COMPRESSION_MARKER
        orig_size = int(parts[4])
        assert orig_size == original_size
    finally:
        for p in (src, out):
            if os.path.exists(p):
                os.remove(p)
        os.rmdir(tmp)


def main():
    tests = [
        test_jpeg_без_exif_копия_получает_даты_из_mtime_исходника,
        test_jpeg_с_exif_сохраняет_make_и_добавляет_даты,
        test_jpeg_с_существующими_датами_даты_не_заменяются,
        test_png_с_датами_даты_сохраняются,
        test_png_без_дат_получает_даты_из_mtime_исходника,
        test_heic_конвертация_даты_из_mtime_исходника_и_origSize,
    ]
    print("=" * 60)
    print("Testing compression date guarantee in copies")
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

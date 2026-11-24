#!/usr/bin/env python3
"""
Тесты команды fix-dates: источники дат (имя файла → маркер → mtime),
no-op при наличии дат, HEIC-пропуск, PNG eXIf-чанк, идемпотентность,
CLI-счётчики через CliRunner. Запуск: python3 tests/test_fix_dates.py или pytest.
"""
import os
import sys
import tempfile
import shutil
import time
from datetime import datetime

# Добавляем корень CLI-пакета в путь: src является пакетом (относительные импорты)
cli_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, cli_root)

from src.exif_handler import ExifHandler  # noqa: E402
from src.file_utils import FileInfo  # noqa: E402
from src.multiprocessing_utils import process_single_file_date_fix  # noqa: E402
from src.date_parser import parse_date_ms  # noqa: E402
import piexif  # noqa: E402
from PIL import Image  # noqa: E402


def _make_jpeg_without_dates(name="photo.jpg"):
    """JPEG без каких-либо дат в EXIF, во временной папке."""
    tmpdir = tempfile.mkdtemp()
    path = os.path.join(tmpdir, name)
    Image.new("RGB", (64, 64), color=(120, 120, 120)).save(path, quality=90)
    return tmpdir, path


def _make_png_without_dates(name="photo.png"):
    tmpdir = tempfile.mkdtemp()
    path = os.path.join(tmpdir, name)
    Image.new("RGB", (64, 64), color=(50, 50, 50)).save(path, "PNG")
    return tmpdir, path


def _file_info(path):
    stat = os.stat(path)
    return FileInfo(path, os.path.basename(path), stat.st_size, stat.st_mtime)


def _set_mtime(path, epoch_seconds):
    os.utime(path, (epoch_seconds, epoch_seconds))
    return epoch_seconds


def _add_marker(path, quality, timestamp_ms):
    marker = f"CompressPhotoFast_Compressed:{quality}:{timestamp_ms}:000000000000000:000000000000000"
    exif_dict = piexif.load(path)
    if exif_dict is None:
        exif_dict = {"0th": {}, "Exif": {}}
    exif_dict.setdefault("Exif", {})[piexif.ExifIFD.UserComment] = marker.encode("utf-8")
    if path.lower().endswith(".png"):
        with Image.open(path) as img:
            buf_img = img
            icc = img.info.get("icc_profile")
            import io
            buf = io.BytesIO()
            kwargs = {"format": "PNG"}
            if icc:
                kwargs["icc_profile"] = icc
            buf_img.load()
            buf_img.save(buf, exif=piexif.dump(exif_dict), **kwargs)
            data = buf.getvalue()
        with open(path, "wb") as f:
            f.write(data)
    else:
        piexif.insert(piexif.dump(exif_dict), path)


def _expected_date_str(epoch_ms):
    return datetime.fromtimestamp(epoch_ms / 1000).strftime("%Y:%m:%d %H:%M:%S")


def _cleanup(tmpdir):
    shutil.rmtree(tmpdir, ignore_errors=True)


def test_имя_файла_даёт_три_тега_включая_original():
    tmpdir, path = _make_jpeg_without_dates("mmexport1698741123456.jpg")
    try:
        mtime = _set_mtime(path, 1700000000)
        fi = _file_info(path)

        fi, status, source, date_str, error = process_single_file_date_fix(fi, False)

        assert status == "fixed", (status, error)
        assert source == "filename"
        expected = _expected_date_str(parse_date_ms("mmexport1698741123456.jpg"))
        exif_dict = piexif.load(path)
        assert exif_dict["0th"][piexif.ImageIFD.DateTime].decode() == expected
        assert exif_dict["Exif"][piexif.ExifIFD.DateTimeDigitized].decode() == expected
        assert exif_dict["Exif"][piexif.ExifIFD.DateTimeOriginal].decode() == expected
        # mtime файла восстановлен
        assert os.path.getmtime(path) == mtime
    finally:
        _cleanup(tmpdir)


def test_маркер_приоритетнее_mtime():
    tmpdir, path = _make_jpeg_without_dates("photo.jpg")
    try:
        marker_ts = 1698741123456
        _set_mtime(path, 1700000000)
        _add_marker(path, 80, marker_ts)
        _set_mtime(path, 1700000000)  # «неподходящий» mtime после маркера
        fi = _file_info(path)

        fi, status, source, date_str, error = process_single_file_date_fix(fi, False)

        assert status == "fixed", (status, error)
        assert source == "marker"
        assert date_str == _expected_date_str(marker_ts)
        exif_dict = piexif.load(path)
        assert exif_dict["0th"][piexif.ImageIFD.DateTime].decode() == _expected_date_str(marker_ts)
        assert exif_dict["Exif"][piexif.ExifIFD.DateTimeDigitized].decode() == _expected_date_str(marker_ts)
        assert piexif.ExifIFD.DateTimeOriginal not in exif_dict["Exif"]
    finally:
        _cleanup(tmpdir)


def test_mtime_fallback_даёт_два_тега():
    tmpdir, path = _make_jpeg_without_dates("photo.jpg")
    try:
        mtime = _set_mtime(path, 1700000000)
        fi = _file_info(path)

        fi, status, source, date_str, error = process_single_file_date_fix(fi, False)

        assert status == "fixed", (status, error)
        assert source == "mtime"
        expected = datetime.fromtimestamp(mtime).strftime("%Y:%m:%d %H:%M:%S")
        exif_dict = piexif.load(path)
        assert exif_dict["0th"][piexif.ImageIFD.DateTime].decode() == expected
        assert exif_dict["Exif"][piexif.ExifIFD.DateTimeDigitized].decode() == expected
        assert piexif.ExifIFD.DateTimeOriginal not in exif_dict["Exif"]
        assert os.path.getmtime(path) == mtime
    finally:
        _cleanup(tmpdir)


def test_уже_есть_дата_noop():
    tmpdir, path = _make_jpeg_without_dates("photo.jpg")
    try:
        exif_dict = piexif.load(path)
        exif_dict.setdefault("GPS", {})[piexif.GPSIFD.GPSDateStamp] = b"2020:01:02"
        piexif.insert(piexif.dump(exif_dict), path)
        mtime = _set_mtime(path, 1700000000)
        fi = _file_info(path)

        fi, status, source, date_str, error = process_single_file_date_fix(fi, False)

        assert status == "already_ok"
        assert source == ""
        assert os.path.getmtime(path) == mtime
    finally:
        _cleanup(tmpdir)


def test_heic_пропускается():
    # JPEG-байты с расширением .heic: пропуск по расширению (контент не важен,
    # pillow-heif в окружении может отсутствовать)
    tmpdir, path = _make_jpeg_without_dates("photo.jpg")
    heic_path = os.path.join(tmpdir, "photo.heic")
    os.replace(path, heic_path)
    try:
        fi = _file_info(heic_path)
        fi, status, source, date_str, error = process_single_file_date_fix(fi, False)
        assert status == "heic"
    finally:
        _cleanup(tmpdir)


def test_png_запись_через_exif_chunk():
    tmpdir, path = _make_png_without_dates("20250101_123456.png")
    try:
        fi = _file_info(path)
        fi, status, source, date_str, error = process_single_file_date_fix(fi, False)

        assert status == "fixed", (status, error)
        assert source == "filename"
        # PNG без eXIf-чанка читается как None, но после записи даты есть
        exif_bytes = None
        with Image.open(path) as img:
            exif_bytes = img.info.get("exif")
        assert exif_bytes
        exif_dict = piexif.load(exif_bytes)
        assert piexif.ImageIFD.DateTime in exif_dict["0th"]
        assert piexif.ExifIFD.DateTimeOriginal in exif_dict["Exif"]
    finally:
        _cleanup(tmpdir)


def test_идемпотентность_второй_проход_already_ok():
    tmpdir, path = _make_jpeg_without_dates("photo_2025-01-01_12-30-45.jpg")
    try:
        fi = _file_info(path)
        fi, status, _, _, error = process_single_file_date_fix(fi, False)
        assert status == "fixed", (status, error)

        fi = _file_info(path)
        fi, status, source, _, _ = process_single_file_date_fix(fi, False)
        assert status == "already_ok"
        assert source == ""
    finally:
        _cleanup(tmpdir)


def test_dry_run_ничего_не_пишет():
    tmpdir, path = _make_jpeg_without_dates("photo.jpg")
    try:
        _set_mtime(path, 1700000000)
        fi = _file_info(path)

        fi, status, source, date_str, error = process_single_file_date_fix(fi, True)

        assert status == "fixed"
        assert source == "mtime"
        assert date_str != ""
        # Файл не изменился: EXIF-дат нет (piexif.load JPEG без EXIF — пустые IFD)
        exif_dict = ExifHandler.read_exif_data(path)
        assert not ExifHandler._has_any_date(exif_dict)
    finally:
        _cleanup(tmpdir)


def test_write_missing_dates_напрямую():
    tmpdir, path = _make_jpeg_without_dates("photo.jpg")
    try:
        mtime = _set_mtime(path, 1700000000)
        date_ms = int(mtime * 1000)
        assert ExifHandler.write_missing_dates(path, date_ms, True) is True
        # Повтор — no-op
        assert ExifHandler.write_missing_dates(path, date_ms, True) is True
        # HEIC — False
        assert ExifHandler.write_missing_dates(path + ".heic", date_ms, False) is False
    finally:
        _cleanup(tmpdir)


def test_расширение_siemens_имя_даёт_три_тега():
    tmpdir, path = _make_jpeg_without_dates("070414_152426.jpg")
    try:
        _set_mtime(path, 1700000000)  # «неподходящий» mtime — имя приоритетнее
        fi = _file_info(path)

        fi, status, source, date_str, error = process_single_file_date_fix(fi, False)

        assert status == "fixed", (status, error)
        assert source == "filename"
        expected = datetime(2007, 4, 14, 15, 24, 26).strftime("%Y:%m:%d %H:%M:%S")
        assert date_str == expected
        exif_dict = piexif.load(path)
        assert exif_dict["0th"][piexif.ImageIFD.DateTime].decode() == expected
        assert exif_dict["Exif"][piexif.ExifIFD.DateTimeOriginal].decode() == expected
    finally:
        _cleanup(tmpdir)


def test_расширение_папка_приоритетнее_mtime_два_тега():
    tmpdir = tempfile.mkdtemp()
    dated = os.path.join(tmpdir, "2009_06_28")
    os.makedirs(dated)
    path = os.path.join(dated, "photo.jpg")
    Image.new("RGB", (64, 64), color=(120, 120, 120)).save(path, quality=90)
    try:
        mtime = _set_mtime(path, 1700000000)
        fi = _file_info(path)

        fi, status, source, date_str, error = process_single_file_date_fix(fi, False)

        assert status == "fixed", (status, error)
        assert source == "folder"
        assert date_str == "2009:06:28 00:00:00"
        exif_dict = piexif.load(path)
        assert exif_dict["0th"][piexif.ImageIFD.DateTime].decode() == "2009:06:28 00:00:00"
        assert exif_dict["Exif"][piexif.ExifIFD.DateTimeDigitized].decode() == "2009:06:28 00:00:00"
        # папка — точность «дата», DateTimeOriginal не выдумывается
        assert piexif.ExifIFD.DateTimeOriginal not in exif_dict["Exif"]
        assert os.path.getmtime(path) == mtime
    finally:
        _cleanup(tmpdir)


def test_расширение_имя_приоритетнее_папки():
    tmpdir = tempfile.mkdtemp()
    dated = os.path.join(tmpdir, "2010")
    os.makedirs(dated)
    path = os.path.join(dated, "P70429-132038.jpg")
    Image.new("RGB", (64, 64), color=(120, 120, 120)).save(path, quality=90)
    try:
        fi = _file_info(path)

        fi, status, source, date_str, error = process_single_file_date_fix(fi, False)

        assert status == "fixed", (status, error)
        assert source == "filename"
        assert date_str == datetime(2007, 4, 29, 13, 20, 38).strftime("%Y:%m:%d %H:%M:%S")
    finally:
        _cleanup(tmpdir)


def test_cli_fix_dates():
    from click.testing import CliRunner
    from src.cli import cli as cli_group

    tmpdir, path = _make_jpeg_without_dates("photo.jpg")
    try:
        _set_mtime(path, 1700000000)
        runner = CliRunner()
        result = runner.invoke(cli_group, ["fix-dates", tmpdir, "--yes"])
        assert result.exit_code == 0, result.output
        assert "Fixed" in result.output

        # Второй проход: все already have dates
        result2 = runner.invoke(cli_group, ["fix-dates", tmpdir, "--yes"])
        assert result2.exit_code == 0, result2.output
        assert "Already have dates" in result2.output

        # Dry-run показывает план
        tmpdir2, path2 = _make_jpeg_without_dates("20250101_123456.jpg")
        try:
            result3 = runner.invoke(cli_group, ["fix-dates", tmpdir2, "--yes", "--dry-run"])
            assert result3.exit_code == 0, result3.output
            assert "filename" in result3.output
            assert not ExifHandler._has_any_date(ExifHandler.read_exif_data(path2))
        finally:
            _cleanup(tmpdir2)
    finally:
        _cleanup(tmpdir)


def main():
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    print("=" * 60)
    print("Testing fix-dates (worker, write_missing_dates, CLI)")
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

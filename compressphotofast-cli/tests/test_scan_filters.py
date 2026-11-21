"""
Тесты фильтров скана: MIN/MAX_FILE_SIZE и исключение _compressed.heic
(паритет GalleryScanUtil и CompressionMarker в Android).
"""
import os

import pytest
from PIL import Image

from src.file_utils import find_image_files
from src.constants import MIN_FILE_SIZE, MAX_FILE_SIZE


def write_file(path, size_bytes: int, image: bool = True) -> None:
    """Пишет файл с точным размером: картинка + добивка до size_bytes."""
    if image:
        img = Image.new("RGB", (16, 16), (10, 20, 30))
        img.save(path, format="JPEG", quality=95)
        with open(path, "ab") as f:
            current = os.fstat(f.fileno()).st_size
            if current < size_bytes:
                f.write(b"\x00" * (size_bytes - current))
            elif current > size_bytes:
                raise ValueError("размер картинки больше целевого")
    else:
        with open(path, "wb") as f:
            f.write(b"\x00" * size_bytes)


class TestScanSizeFilters:
    """Фильтры MIN_FILE_SIZE (50 КБ) / MAX_FILE_SIZE (100 МБ) на этапе скана"""

    def test_min_file_size(self, tmp_path):
        small = tmp_path / "small.jpg"
        big = tmp_path / "big.jpg"
        write_file(str(small), MIN_FILE_SIZE - 1)
        write_file(str(big), MIN_FILE_SIZE)

        files = find_image_files(str(tmp_path))
        names = [f.name for f in files]
        assert "big.jpg" in names
        assert "small.jpg" not in names

    def test_max_file_size(self, tmp_path):
        # Полные 100 МБ писать не будем: проверяем логику через monkeypatch константы
        from src import file_utils
        original_max = file_utils.MAX_FILE_SIZE
        original_min = file_utils.MIN_FILE_SIZE
        file_utils.MAX_FILE_SIZE = 100
        file_utils.MIN_FILE_SIZE = 0
        try:
            # Скан фильтрует только по расширению — сырые байты с .jpg годятся
            small = tmp_path / "ok.jpg"
            big = tmp_path / "huge.jpg"
            write_file(str(small), 90, image=False)
            write_file(str(big), 200, image=False)
            files = file_utils.find_image_files(str(tmp_path))
            names = [f.name for f in files]
            assert "ok.jpg" in names
            assert "huge.jpg" not in names
        finally:
            file_utils.MAX_FILE_SIZE = original_max
            file_utils.MIN_FILE_SIZE = original_min

    def test_constants_match_android(self):
        assert MIN_FILE_SIZE == 50 * 1024
        assert MAX_FILE_SIZE == 100 * 1024 * 1024


class TestScanExcludesHeicRenamed:
    """Файлы _compressed.heic/.heif не попадают в скан как кандидаты на сжатие"""

    def test_heic_renamed_excluded(self, tmp_path):
        marker = tmp_path / "IMG_0001_compressed.heic"
        plain = tmp_path / "IMG_0002.heic"
        write_file(str(marker), MIN_FILE_SIZE + 1)
        write_file(str(plain), MIN_FILE_SIZE + 1)

        files = find_image_files(str(tmp_path))
        names = [f.name for f in files]
        assert "IMG_0002.heic" in names
        assert "IMG_0001_compressed.heic" not in names

    def test_heif_renamed_excluded_case_insensitive(self, tmp_path):
        marker = tmp_path / "IMG_0001_COMPRESSED.HEIF"
        write_file(str(marker), MIN_FILE_SIZE + 1)

        files = find_image_files(str(tmp_path))
        assert all(not f.name.lower().endswith("_compressed.heif") for f in files)
        assert [f.name for f in files] == []

    def test_jpg_compressed_in_app_dir_excluded(self, tmp_path):
        # Обычные _compressed.jpg вне app-папки остаются в скане
        # (replace-режим, jpeg-контент), а app-папка пропускается целиком
        nested = tmp_path / "CompressPhotoFast"
        nested.mkdir()
        in_app = nested / "IMG_0001_compressed.jpg"
        outside = tmp_path / "IMG_0001_compressed.jpg"
        write_file(str(in_app), MIN_FILE_SIZE + 1)
        write_file(str(outside), MIN_FILE_SIZE + 1)

        files = find_image_files(str(tmp_path))
        names = [f.name for f in files]
        assert "IMG_0001_compressed.jpg" in names
        assert len(names) == 1


if __name__ == "__main__":
    import sys
    sys.exit(pytest.main([__file__, "-v"]))

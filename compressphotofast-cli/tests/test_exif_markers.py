"""
Тесты HEIC rename-маркера и PNG eXIf-маркера (паритет с Android-контрактами).

Контракт Android:
- CompressionMarker.hasHeicCompressedSuffix: regex _compressed\.(heic|heif)$, IGNORECASE
- ExifUtil.markHeicFileAsCompressed: идемпотентное переименование name.heic -> name_compressed.heic
- PNG: piexif.insert не работает с PNG-файлами — маркер через eXIf-чанк (Pillow)
"""
import os

import piexif
import pytest
from PIL import Image

from src.compression import ImageCompressor, HEIC_SUPPORT_AVAILABLE
from src.constants import (
    EXIF_COMPRESSION_MARKER,
    DEFAULT_COMPRESSION_QUALITY,
    COMPRESSION_QUALITY_HIGH,
)
from src.exif_handler import (
    ExifHandler,
    has_heic_compressed_suffix,
)


def make_image(path: str, size: tuple = (800, 600), color=(120, 90, 60)) -> None:
    """Создаёт картинку такого объёма, чтобы сжатие было заведомо неэффективным."""
    img = Image.new("RGB", size, color)
    img.save(path)


def make_noisy_heic(path: str, size: tuple = (1200, 900), seed: int = 42) -> None:
    """HEIC ≥ 100 КБ: шумовое изображение не сжимается в крошечный контейнер."""
    import random

    random.seed(seed)
    noise = Image.frombytes(
        "RGB", size, bytes(random.getrandbits(8) for _ in range(size[0] * size[1] * 3))
    )
    noise.save(path, quality=30)


class TestHasHeicCompressedSuffix:
    """Кейсы CompressionMarker.hasHeicCompressedSuffix"""

    def test_heic_suffix(self):
        assert has_heic_compressed_suffix("IMG_0001_compressed.heic") is True

    def test_heif_suffix(self):
        assert has_heic_compressed_suffix("IMG_0001_compressed.heif") is True

    def test_uppercase(self):
        assert has_heic_compressed_suffix("IMG_0001_COMPRESSED.HEIC") is True

    def test_mixed_case(self):
        assert has_heic_compressed_suffix("IMG_0001_Compressed.Heic") is True

    def test_no_suffix(self):
        assert has_heic_compressed_suffix("IMG_0001.heic") is False

    def test_suffix_in_middle_not_at_ext(self):
        # _compressed есть в имени, но не перед .heic/.heif
        assert has_heic_compressed_suffix("IMG_compressed.jpg") is False
        assert has_heic_compressed_suffix("IMG_compressed.png") is False

    def test_jpg_extension(self):
        assert has_heic_compressed_suffix("IMG_0001_compressed.jpg") is False

    def test_none(self):
        assert has_heic_compressed_suffix(None) is False

    def test_no_extension(self):
        assert has_heic_compressed_suffix("IMG_compressed") is False


@pytest.mark.skipif(not HEIC_SUPPORT_AVAILABLE, reason="pillow-heif недоступен")
class TestHeicRenameMarker:
    """HEIC skip-маркер через rename (аналог ExifUtil.markHeicFileAsCompressed)"""

    def test_rename_adds_suffix(self, tmp_path):
        src = tmp_path / "IMG_0001.heic"
        make_image(str(src))
        assert ExifHandler.mark_heic_file_as_compressed(str(src)) is True
        assert not src.exists()
        assert (tmp_path / "IMG_0001_compressed.heic").exists()

    def test_rename_heif_extension(self, tmp_path):
        src = tmp_path / "IMG_0001.heif"
        make_image(str(src))
        assert ExifHandler.mark_heic_file_as_compressed(str(src)) is True
        assert (tmp_path / "IMG_0001_compressed.heif").exists()

    def test_rename_idempotent(self, tmp_path):
        # Повторный rename не даёт name_compressed_compressed.heic
        src = tmp_path / "IMG_0001_compressed.heic"
        make_image(str(src))
        assert ExifHandler.mark_heic_file_as_compressed(str(src)) is True
        assert src.exists()
        assert list(p.name for p in tmp_path.iterdir()) == ["IMG_0001_compressed.heic"]

    def test_rename_collision_keeps_contract(self, tmp_path):
        # Целевое имя занято: счётчик перед суффиксом, контракт _compressed.heic сохраняется
        src = tmp_path / "IMG_0001.heic"
        make_image(str(src))
        make_image(str(tmp_path / "IMG_0001_compressed.heic"))
        assert ExifHandler.mark_heic_file_as_compressed(str(src)) is True
        assert (tmp_path / "IMG_0001_1_compressed.heic").exists()

    def test_is_heic_file(self):
        assert ExifHandler.is_heic_file("/x/IMG.heic") is True
        assert ExifHandler.is_heic_file("/x/IMG.HEIF") is True
        assert ExifHandler.is_heic_file("/x/IMG.jpg") is False

    def test_is_image_compressed_by_suffix(self, tmp_path):
        src = tmp_path / "IMG_0001_compressed.heic"
        make_image(str(src))
        assert ExifHandler.is_image_compressed(str(src)) is True

    def test_should_recompress_false_by_suffix(self, tmp_path):
        # Маркер без размера (rename) — доверяем и пропускаем
        src = tmp_path / "IMG_0001_compressed.heic"
        make_image(str(src))
        assert ExifHandler.should_recompress(str(src)) is False

    def test_unsuffixed_heic_not_compressed(self, tmp_path):
        src = tmp_path / "IMG_0001.heic"
        make_image(str(src))
        assert ExifHandler.is_image_compressed(str(src)) is False
        assert ExifHandler.should_recompress(str(src)) is True


@pytest.mark.skipif(not HEIC_SUPPORT_AVAILABLE, reason="pillow-heif недоступен")
class TestHeicAlwaysConvert:
    """HEIC ≥ 100 КБ всегда конвертируется в JPEG, даже при неэффективном сжатии
    (контракт CompressImageUseCase в Android: правило 30%/10 КБ к HEIC не применяется)"""

    def test_compress_image_inefficient_heic_keeps_jpeg(self, tmp_path, monkeypatch):
        # Copy-режим: JPEG-копия сохраняется, rename не выполняется
        monkeypatch.setattr(
            ImageCompressor, "is_compression_efficient", staticmethod(lambda a, b: False)
        )
        src = tmp_path / "IMG_0001.heic"
        make_noisy_heic(str(src))
        out_dir = tmp_path / "out"
        out_dir.mkdir()
        out = out_dir / "IMG_0001.jpg"
        result = ImageCompressor.compress_image(str(src), DEFAULT_COMPRESSION_QUALITY, out)

        assert result.success is True
        assert str(result.saved_path) == str(out)
        assert out.exists()
        assert src.exists()
        # Исходник не переименован
        assert (tmp_path / "IMG_0001.heic").exists()
        with Image.open(str(out)) as img:
            assert img.format == "JPEG"

    def test_compress_image_heic_larger_jpeg_is_kept(self, tmp_path, monkeypatch):
        # JPEG больше оригинала (отрицательная экономия) — копия всё равно сохраняется
        monkeypatch.setattr(
            ImageCompressor, "is_compression_efficient", staticmethod(lambda a, b: b > a)
        )
        src = tmp_path / "IMG_0001.heic"
        make_noisy_heic(str(src))
        out_dir = tmp_path / "out"
        out_dir.mkdir()
        out = out_dir / "IMG_0001.jpg"
        result = ImageCompressor.compress_image(str(src), DEFAULT_COMPRESSION_QUALITY, out)

        assert result.success is True
        assert str(result.saved_path) == str(out)
        assert out.exists()
        assert out.stat().st_size > src.stat().st_size

    def test_compress_image_replace_heic_converts_in_place(self, tmp_path, monkeypatch):
        # Replace-вызов (output == file): HEIC перезаписан JPEG-контентом и остаётся
        monkeypatch.setattr(
            ImageCompressor, "is_compression_efficient", staticmethod(lambda a, b: False)
        )
        src = tmp_path / "IMG_0001.heic"
        make_noisy_heic(str(src))
        result = ImageCompressor.compress_image(str(src), DEFAULT_COMPRESSION_QUALITY)

        assert result.success is True
        assert str(result.saved_path) == str(src)
        assert src.exists()
        with Image.open(str(src)) as img:
            assert img.format == "JPEG"

    def test_replace_mode_heic_converts_without_restore(self, tmp_path, monkeypatch):
        # Replace-режим: неэффективное сжатие не откатывает конвертацию HEIC
        monkeypatch.setattr(
            ImageCompressor, "is_compression_efficient", staticmethod(lambda a, b: False)
        )
        src = tmp_path / "IMG_0001.heic"
        make_noisy_heic(str(src))

        result = ImageCompressor.compress_image_safe(
            str(src), DEFAULT_COMPRESSION_QUALITY, replace_mode=True
        )

        assert result.success is True
        assert str(result.saved_path) == str(src)
        # backup удалён, rename не выполнялся
        assert not (tmp_path / "IMG_0001.heic.bak").exists()
        assert list(p.name for p in tmp_path.iterdir()) == ["IMG_0001.heic"]
        with Image.open(str(src)) as img:
            assert img.format == "JPEG"


class TestPngMarker:
    """PNG skip-маркер через eXIf-чанк (piexif.insert на PNG не работает)"""

    def test_png_marker_round_trip(self, tmp_path):
        src = tmp_path / "IMG_0001.png"
        make_image(str(src))
        assert ExifHandler.add_compression_marker(str(src), 99) is True

        assert ExifHandler.is_image_compressed(str(src)) is True
        quality, timestamp = ExifHandler.get_compression_info(str(src))[1:]
        assert quality == 99
        assert timestamp > 0

        marker_size = ExifHandler.get_marker_file_size(str(src))
        # Перекодировка в памяти: маркер получает точный размер записанного файла
        assert marker_size == os.path.getsize(str(src))

        assert ExifHandler.should_recompress(str(src)) is False

    def test_png_marker_contract_format(self, tmp_path):
        # Строка маркера парсится по контракту CompressionMarker.kt
        # (prefix + 5 полей, поля размера фиксированной ширины 15)
        src = tmp_path / "IMG_0001.png"
        make_image(str(src))
        assert ExifHandler.add_compression_marker(str(src), 99, original_size=123456) is True

        with Image.open(str(src)) as img:
            raw = img.info.get("exif")
        exif_dict = piexif.load(raw)
        comment = exif_dict["Exif"][piexif.ExifIFD.UserComment].decode("utf-8")
        parts = comment.split(":")
        assert parts[0] == EXIF_COMPRESSION_MARKER
        assert len(parts) == 5
        assert parts[3].zfill(15) == parts[3] and len(parts[3]) == 15
        assert int(parts[3]) == os.path.getsize(str(src))
        assert int(parts[4]) == 123456

    def test_png_marker_preserves_existing_exif(self, tmp_path):
        # Существующий eXIf-чанк не теряется: UserComment дописывается
        src = tmp_path / "IMG_0001.png"
        existing = {"0th": {}, "Exif": {piexif.ExifIFD.DateTimeOriginal: b"2024:01:01 00:00:00"}}
        make_image(str(src))
        with Image.open(str(src)) as img:
            img.save(str(src), exif=piexif.dump(existing))

        assert ExifHandler.add_compression_marker(str(src), 99) is True
        exif_dict = ExifHandler.read_exif_data(str(src))
        assert exif_dict is not None
        assert piexif.ExifIFD.DateTimeOriginal in exif_dict["Exif"]
        assert piexif.ExifIFD.UserComment in exif_dict["Exif"]

    def test_png_marker_preserves_icc_profile(self, tmp_path):
        # ICC-профиль не теряется при записи маркера (чанк iCCP)
        src = tmp_path / "IMG_0001.png"
        fake_icc = b"FAKE-ICC-PROFILE-DATA" * 200
        make_image(str(src))
        with Image.open(str(src)) as img:
            img.save(str(src), icc_profile=fake_icc)

        assert ExifHandler.add_compression_marker(str(src), 99) is True
        with Image.open(str(src)) as img:
            assert img.info.get("icc_profile") == fake_icc
        # Маркер при этом читается и имеет точный размер
        assert ExifHandler.is_image_compressed(str(src)) is True
        assert ExifHandler.get_marker_file_size(str(src)) == os.path.getsize(str(src))
        assert ExifHandler.should_recompress(str(src)) is False

    def test_jpeg_marker_still_works(self, tmp_path):
        # JPEG-путь не изменён: маркер через piexif.insert
        src = tmp_path / "IMG_0001.jpg"
        make_image(str(src))
        assert ExifHandler.add_compression_marker(str(src), 99) is True
        assert ExifHandler.is_image_compressed(str(src)) is True


class TestQualityConstants:
    """Паритет quality-пресетов с Android Constants.kt"""

    def test_high_quality_80(self):
        assert COMPRESSION_QUALITY_HIGH == 80

    def test_default_quality_70(self):
        assert DEFAULT_COMPRESSION_QUALITY == 70


if __name__ == "__main__":
    import sys
    sys.exit(pytest.main([__file__, "-v"]))

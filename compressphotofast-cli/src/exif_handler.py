import io
import os
import re
import logging
import shutil
import piexif
from datetime import datetime
from typing import Tuple, Dict, Optional, Any
from PIL import Image

from .constants import (
    EXIF_COMPRESSION_MARKER,
    MARKER_SIZE_TOLERANCE_BYTES,
    TIME_DIFFERENCE_ALLOWED_SECONDS,
)

logger = logging.getLogger(__name__)

# Суффикс имени HEIC-файла, заменяющий EXIF-маркер (CompressionMarker в Android)
HEIC_COMPRESSED_SUFFIX = "_compressed"
# Распознавание: regex _compressed\.(heic|heif)$, ignore case (CompressionMarker в Android)
HEIC_COMPRESSED_NAME = re.compile(r"_compressed\.(heic|heif)$", re.IGNORECASE)


def has_heic_compressed_suffix(file_name: Optional[str]) -> bool:
    """
    Проверяет, есть ли у имени файла суффикс _compressed перед расширением .heic/.heif.
    Соответствует CompressionMarker.hasHeicCompressedSuffix в Android.
    """
    return file_name is not None and HEIC_COMPRESSED_NAME.search(file_name) is not None


class ExifHandler:
    EXIF_TAGS_TO_COPY = [
        "DateTimeOriginal",
        "DateTime",
        "DateTimeDigitized",
        "Make",
        "Model",
        "Software",
        "Orientation",
        "Flash",
        "SceneType",
        "SceneCaptureType",
        "ExposureTime",
        "ExposureBiasValue",
        "ExposureProgram",
        "ExposureMode",
        "ExposureIndex",
        "ApertureValue",
        "FNumber",
        "FocalLength",
        "FocalLengthIn35mmFilm",
        "DigitalZoomRatio",
        "PhotographicSensitivity",
        "WhiteBalance",
        "LightSource",
        "SubjectDistance",
        "MeteringMode",
        "Contrast",
        "Saturation",
        "Sharpness",
        "SubjectDistanceRange",
    ]

    GPS_TAGS_TO_COPY = [
        "GPSLatitude",
        "GPSLatitudeRef",
        "GPSLongitude",
        "GPSLongitudeRef",
        "GPSAltitude",
        "GPSAltitudeRef",
        "GPSProcessingMethod",
        "GPSDateStamp",
        "GPSTimeStamp",
    ]

    @staticmethod
    def is_heic_file(file_path: str) -> bool:
        """HEIC/HEIF по расширению имени (аналог isHeicFile в Android ExifUtil)."""
        return os.path.splitext(file_path)[1].lower() in {".heic", ".heif"}

    @staticmethod
    def mark_heic_file_as_compressed(file_path: str) -> bool:
        """
        HEIC не получает EXIF-маркер: пропуск помечается переименованием
        name.heic -> name_compressed.heic (аналог ExifUtil.markHeicFileAsCompressed).
        Идемпотентно: файл с суффиксом не переименовывается повторно.
        """
        try:
            directory, file_name = os.path.split(file_path)
            if has_heic_compressed_suffix(file_name):
                logger.debug(f"У файла уже есть суффикс {HEIC_COMPRESSED_SUFFIX}: {file_name}")
                return True

            name_part, ext = os.path.splitext(file_name)
            new_name = f"{name_part}{HEIC_COMPRESSED_SUFFIX}{ext}"

            # Коллизия: сохраняем контракт суффикса, счётчик перед _compressed
            counter = 1
            while os.path.exists(os.path.join(directory, new_name)):
                new_name = f"{name_part}_{counter}{HEIC_COMPRESSED_SUFFIX}{ext}"
                counter += 1

            new_path = os.path.join(directory, new_name)
            os.rename(file_path, new_path)
            logger.info(f"Переименование: {file_name} -> {new_name}")
            return True
        except OSError as e:
            logger.warning(f"Не удалось переименовать HEIC-файл {file_path}: {e}")
            return False

    @staticmethod
    def read_exif_data(file_path: str) -> Optional[Dict[str, Any]]:
        return ExifHandler._load_exif_dict(file_path)

    @staticmethod
    def _load_exif_dict(file_path: str) -> Optional[Dict[str, Any]]:
        """
        Загружает EXIF-словарь файла. Для PNG piexif.load по пути молча
        не работает (piexif читает только JPEG), поэтому eXIf-чанк читается
        через Pillow, а разбор выполняется piexif.load по байтам в памяти.
        """
        try:
            if os.path.splitext(file_path)[1].lower() == ".png":
                with Image.open(file_path) as img:
                    exif_bytes = img.info.get("exif")
                if not exif_bytes:
                    return None
                return piexif.load(exif_bytes)
            return piexif.load(file_path)
        except Exception:
            return None

    @staticmethod
    def _read_marker_parts(file_path: str) -> Optional[list]:
        """Возвращает поля маркера сжатия из UserComment или None, если маркера нет."""
        exif_dict = ExifHandler.read_exif_data(file_path)
        if exif_dict is None:
            return None

        if "Exif" not in exif_dict:
            return None

        exif_ifd = exif_dict["Exif"]
        user_comment_tag = piexif.ExifIFD.UserComment

        if user_comment_tag not in exif_ifd:
            return None

        user_comment = exif_ifd[user_comment_tag]
        if isinstance(user_comment, bytes):
            try:
                user_comment = user_comment.decode("utf-8", errors="ignore")
            except UnicodeDecodeError:
                return None

        if not isinstance(user_comment, str):
            return None

        if not user_comment.startswith(EXIF_COMPRESSION_MARKER):
            return None

        return user_comment.split(":")

    @staticmethod
    def get_compression_info(file_path: str) -> Tuple[bool, int, int]:
        parts = ExifHandler._read_marker_parts(file_path)
        if parts is None:
            return False, -1, 0

        try:
            if len(parts) >= 3:
                quality = int(parts[1])
                timestamp = int(parts[2])
                return True, quality, timestamp
        except (ValueError, IndexError):
            pass

        return False, -1, 0

    @staticmethod
    def get_marker_file_size(file_path: str) -> Optional[int]:
        """
        Размер файла, записанный в маркере на момент сжатия.

        None — старый формат маркера без размера, заглушка незавершённой
        двухфазной записи (нули) или нечитаемое поле (как в Android ExifUtil).
        """
        parts = ExifHandler._read_marker_parts(file_path)
        if parts is None or len(parts) < 4:
            return None
        try:
            size = int(parts[3].strip("\x00 "))
        except ValueError:
            return None
        return size if size > 0 else None

    @staticmethod
    def is_image_compressed(file_path: str) -> bool:
        """
        HEIC: первичный признак сжатости — суффикс имени (EXIF-маркер недоступен,
        как в Android getCompressionMarker). Далее стандартная проверка EXIF
        (в .heic-имени может быть JPEG-контент с маркером после replace-режима).
        """
        if ExifHandler.is_heic_file(file_path) and has_heic_compressed_suffix(
            os.path.basename(file_path)
        ):
            return True
        is_compressed, _, _ = ExifHandler.get_compression_info(file_path)
        return is_compressed

    @staticmethod
    def is_marker_size_mismatch(file_size: int, marker_file_size: int) -> bool:
        """Расхождение размеров сверх допуска — файл изменён после сжатия."""
        return abs(file_size - marker_file_size) > MARKER_SIZE_TOLERANCE_BYTES

    @staticmethod
    def should_recompress(file_path: str) -> bool:
        """
        Файл с маркером обрабатывается повторно только при расхождении текущего
        размера с размером в маркере сверх допуска (ImageProcessingChecker в Android).
        Маркер без размера — доверяем маркеру и пропускаем.
        HEIC с суффиксом _compressed — маркер без размера, всегда пропуск
        (как HEIC-маркер без размера в Android).
        """
        if ExifHandler.is_heic_file(file_path) and has_heic_compressed_suffix(
            os.path.basename(file_path)
        ):
            return False

        is_compressed, _, _ = ExifHandler.get_compression_info(file_path)
        if not is_compressed:
            return True

        marker_file_size = ExifHandler.get_marker_file_size(file_path)
        if marker_file_size is None:
            return False

        try:
            file_size = os.path.getsize(file_path)
        except OSError:
            return False
        return file_size > 0 and ExifHandler.is_marker_size_mismatch(file_size, marker_file_size)

    MARKER_SIZE_FIELD_WIDTH = 15
    MARKER_ORIG_SIZE_FIELD_WIDTH = 15

    @staticmethod
    def _build_marker(
        quality: int, timestamp: int, size: Optional[int], orig_size: Optional[int]
    ) -> str:
        """
        Build marker string in the format:
        CompressPhotoFast_Compressed:quality:timestamp:size:origSize

        size/origSize use fixed-width zero-padded fields (same as Android),
        so a placeholder write keeps the string length stable.
        """
        size_field = str(size or 0).zfill(ExifHandler.MARKER_SIZE_FIELD_WIDTH)
        orig_size_field = str(orig_size or 0).zfill(
            ExifHandler.MARKER_ORIG_SIZE_FIELD_WIDTH
        )
        return (
            f"{EXIF_COMPRESSION_MARKER}:{quality}:{timestamp}:"
            f"{size_field}:{orig_size_field}"
        )

    @staticmethod
    def _has_any_date(exif_dict: Optional[dict]) -> bool:
        """
        Есть ли в EXIF любая дата (зеркально checkDateAvailability в Android):
        DateTime (0th), DateTimeOriginal/DateTimeDigitized (Exif) или GPSDateStamp (GPS).
        """
        if not exif_dict:
            return False

        zeroth = exif_dict.get("0th") or {}
        if piexif.ImageIFD.DateTime in zeroth:
            return True

        exif_ifd = exif_dict.get("Exif") or {}
        if (
            piexif.ExifIFD.DateTimeOriginal in exif_ifd
            or piexif.ExifIFD.DateTimeDigitized in exif_ifd
        ):
            return True

        gps = exif_dict.get("GPS") or {}
        return piexif.GPSIFD.GPSDateStamp in gps

    @staticmethod
    def _backfill_missing_dates(exif_dict: dict, mtime: float) -> None:
        """
        Добавляет DateTime и DateTimeDigitized из даты модификации файла,
        если ни одной даты в EXIF нет (как addDigitizedDateFromFileMetadata в Android).
        DateTimeOriginal не выдумывается — реальная дата съёмки неизвестна.
        """
        if ExifHandler._has_any_date(exif_dict):
            return

        date_str = datetime.fromtimestamp(mtime).strftime("%Y:%m:%d %H:%M:%S")
        exif_dict.setdefault("0th", {})[piexif.ImageIFD.DateTime] = date_str
        exif_dict.setdefault("Exif", {})[piexif.ExifIFD.DateTimeDigitized] = date_str

    @staticmethod
    def _write_exif_to_png(file_path: str, exif_bytes: bytes) -> bool:
        """
        Запись готовых EXIF-байтов в PNG через eXIf-чанк (Pillow >= 10):
        piexif.insert с PNG-файлом молча не работает. Перекодировка в памяти,
        ICC-профиль сохраняется.
        """
        try:
            with Image.open(file_path) as img:
                img.load()
                save_kwargs = {"format": "PNG"}
                icc_profile = img.info.get("icc_profile")
                if icc_profile:
                    save_kwargs["icc_profile"] = icc_profile
                buf = io.BytesIO()
                img.save(buf, exif=exif_bytes, **save_kwargs)
                data = buf.getvalue()
            with open(file_path, "wb") as f:
                f.write(data)
            return True
        except Exception as e:
            logger.warning(f"Не удалось записать EXIF в PNG {file_path}: {e}")
            return False

    @staticmethod
    def backfill_missing_dates(file_path: str) -> bool:
        """
        Идемпотентная запись только дат (DateTime + DateTimeDigitized из mtime),
        без маркера сжатия. При наличии любой даты в EXIF — no-op (True).
        HEIC не поддерживается (False) — соответствует Android ExifInterface.
        """
        try:
            if ExifHandler.is_heic_file(file_path):
                return False

            existing = ExifHandler.read_exif_data(file_path)
            if existing and ExifHandler._has_any_date(existing):
                return True

            # mtime захватывается до записи: piexif.insert/Pillow переписывают файл
            file_stat = os.stat(file_path)
            exif_dict = existing if existing else {"0th": {}, "Exif": {}}
            if "0th" not in exif_dict:
                exif_dict["0th"] = {}
            if "Exif" not in exif_dict:
                exif_dict["Exif"] = {}

            ExifHandler._backfill_missing_dates(exif_dict, file_stat.st_mtime)

            exif_bytes = piexif.dump(exif_dict)
            if os.path.splitext(file_path)[1].lower() == ".png":
                ok = ExifHandler._write_exif_to_png(file_path, exif_bytes)
            else:
                piexif.insert(exif_bytes, file_path)
                ok = True
            if ok:
                # mtime файла восстанавливается после записи
                os.utime(file_path, (file_stat.st_atime, file_stat.st_mtime))
            return ok
        except Exception as e:
            logger.warning(f"Не удалось дописать даты в {file_path}: {e}")
            return False

    @staticmethod
    def _add_marker_to_png(
        file_path: str,
        quality: int,
        timestamp: int,
        orig_size: Optional[int],
        file_mtime: float,
    ) -> bool:
        """
        Запись маркера в PNG через eXIf-чанк: piexif.insert не работает с
        PNG-файлами, поэтому сборка (piexif.dump по байтам в памяти) и запись
        через Pillow `img.save(exif=...)`.

        Перекодировка выполняется в памяти: ICC-профиль сохраняется, а поле
        size маркера получает точный размер записанного файла (поля маркера
        фиксированной ширины — сухой прогон с заглушкой даёт ту же длину,
        что и финальная запись).
        """
        try:
            with Image.open(file_path) as img:
                img.load()
                icc_profile = img.info.get("icc_profile")
                existing = img.info.get("exif")

                exif_dict: Dict[str, Any] = {"0th": {}, "Exif": {}}
                if existing:
                    try:
                        loaded = piexif.load(existing)
                        if loaded:
                            exif_dict = loaded
                    except Exception:
                        pass

                if "Exif" not in exif_dict:
                    exif_dict["Exif"] = {}

                ExifHandler._backfill_missing_dates(exif_dict, file_mtime)

                save_kwargs = {"format": "PNG"}
                if icc_profile:
                    save_kwargs["icc_profile"] = icc_profile

                def save_with_marker(marker_text: str) -> bytes:
                    exif_dict["Exif"][piexif.ExifIFD.UserComment] = marker_text.encode("utf-8")
                    buf = io.BytesIO()
                    img.save(buf, exif=piexif.dump(exif_dict), **save_kwargs)
                    return buf.getvalue()

                # Сухой прогон: фактический размер файла после записи маркера
                stub = ExifHandler._build_marker(quality, timestamp, 0, orig_size)
                actual_size = len(save_with_marker(stub))

                marker = ExifHandler._build_marker(quality, timestamp, actual_size, orig_size)
                data = save_with_marker(marker)

            with open(file_path, "wb") as f:
                f.write(data)
            return True
        except Exception as e:
            logger.warning(f"Не удалось записать PNG-маркер в {file_path}: {e}")
            return False

    @staticmethod
    def add_compression_marker(
        file_path: str,
        quality: int,
        source_exif: Optional[dict] = None,
        original_size: Optional[int] = None,
        date_source_mtime: Optional[float] = None,
    ) -> bool:
        """
        Add compression marker to file, optionally preserving all source metadata.

        Если в EXIF файла нет ни одной даты, добавляются DateTime/DateTimeDigitized
        из mtime файла (семантическое соответствие Android writeSkipMarker).
        date_source_mtime позволяет задать источник даты явно (mtime исходника
        до перезаписи); по умолчанию используется mtime самого файла.

        Args:
            file_path: Path to file to modify
            quality: Compression quality level
            source_exif: Optional source EXIF dictionary to preserve all metadata
            original_size: Original file size before compression (defaults to
                the current file size, e.g. for inefficient-skip markers)
            date_source_mtime: Optional mtime источника дат (например, mtime
                исходника, снятый до перезаписи файла)

        Returns:
            True if marker added successfully, False otherwise
        """
        try:
            # mtime строго до вставки EXIF: piexif.insert переписывает файл
            if date_source_mtime is not None:
                file_mtime = date_source_mtime
            else:
                file_mtime = os.path.getmtime(file_path)
            marker_size = os.path.getsize(file_path)
            marker_orig_size = original_size if original_size and original_size > 0 else marker_size

            # PNG: маркер через eXIf-чанк (piexif.insert на PNG-файл молча не работает)
            if os.path.splitext(file_path)[1].lower() == ".png":
                timestamp = int(datetime.now().timestamp() * 1000)
                return ExifHandler._add_marker_to_png(
                    file_path, quality, timestamp, marker_orig_size, file_mtime
                )

            marker_bytes = None

            if source_exif:
                # Copy all metadata from source and add marker
                exif_dict = {}
                for ifd_name in ["0th", "Exif", "1st", "GPS", "Interop"]:
                    if ifd_name in source_exif and source_exif[ifd_name]:
                        exif_dict[ifd_name] = {}
                        for tag, value in source_exif[ifd_name].items():
                            exif_dict[ifd_name][tag] = value

                if "Exif" not in exif_dict:
                    exif_dict["Exif"] = {}

                ExifHandler._backfill_missing_dates(exif_dict, file_mtime)

                timestamp = int(datetime.now().timestamp() * 1000)
                marker_data = ExifHandler._build_marker(
                    quality, timestamp, marker_size, marker_orig_size
                )
                marker_bytes = marker_data.encode("utf-8")

                exif_dict["Exif"][piexif.ExifIFD.UserComment] = marker_bytes
            else:
                # Read existing EXIF data
                with open(file_path, 'rb') as f:
                    exif_data = f.read()

                try:
                    exif_dict = piexif.load(exif_data)
                except (piexif.InvalidImageDataError, ValueError, TypeError, Exception):
                    exif_dict = None

                if exif_dict is None or not exif_dict:
                    exif_dict = {"0th": {}, "Exif": {}}

                if "Exif" not in exif_dict:
                    exif_dict["Exif"] = {}

                ExifHandler._backfill_missing_dates(exif_dict, file_mtime)

                timestamp = int(datetime.now().timestamp() * 1000)
                marker_data = ExifHandler._build_marker(
                    quality, timestamp, marker_size, marker_orig_size
                )
                marker_bytes = marker_data.encode("utf-8")

                exif_dict["Exif"][piexif.ExifIFD.UserComment] = marker_bytes

            exif_bytes = None
            try:
                exif_bytes = piexif.dump(exif_dict)
            except Exception:
                if marker_bytes is not None:
                    try:
                        minimal_exif = {
                            "0th": {},
                            "Exif": {piexif.ExifIFD.UserComment: marker_bytes},
                        }
                        exif_bytes = piexif.dump(minimal_exif)
                    except Exception:
                        return False
                else:
                    return False

            piexif.insert(exif_bytes, file_path)
            return True
        except Exception:
            return False

    @staticmethod
    def preserve_file_dates(source_path: str, target_path: str) -> bool:
        try:
            atime = os.path.getatime(source_path)
            mtime = os.path.getmtime(source_path)
            os.utime(target_path, (atime, mtime))
            return True
        except OSError:
            return False

    @staticmethod
    def validate_exif_preservation(source_path: str, target_path: str) -> Dict[str, tuple]:
        """
        Validate if EXIF metadata was preserved in the compressed file.

        Compares key EXIF tags between source and target files.

        Args:
            source_path: Path to the original image file
            target_path: Path to the compressed image file

        Returns:
            Dictionary with format: {tag_name: (present_in_source, present_in_target, values_equal)}
            - present_in_source: bool - if tag exists in source file
            - present_in_target: bool - if tag exists in target file
            - values_equal: bool - if values are equal (both must be present)
        """
        source_exif = ExifHandler.read_exif_data(source_path)
        target_exif = ExifHandler.read_exif_data(target_path)

        if source_exif is None and target_exif is None:
            return {}

        result = {}

        # Key tags to validate
        key_tags = {
            "0th": ["Make", "Model", "Software", "Orientation"],
            "Exif": [
                "DateTimeOriginal",
                "DateTime",
                "DateTimeDigitized",
                "ExposureTime",
                "ExposureBiasValue",
                "FNumber",
                "FocalLength",
                "FocalLengthIn35mmFilm",
                "PhotographicSensitivity",
                "ISOSpeedRatings",
            ],
            "GPS": [
                "GPSLatitude",
                "GPSLatitudeRef",
                "GPSLongitude",
                "GPSLongitudeRef",
                "GPSAltitude",
            ],
        }

        for ifd_name, tag_names in key_tags.items():
            for tag_name in tag_names:
                source_present = False
                target_present = False
                values_equal = False

                # Check source
                if source_exif and ifd_name in source_exif:
                    source_value = source_exif[ifd_name].get(tag_name)
                    if source_value is not None:
                        source_present = True

                # Check target
                if target_exif and ifd_name in target_exif:
                    target_value = target_exif[ifd_name].get(tag_name)
                    if target_value is not None:
                        target_present = True

                # Compare values if both present
                if source_present and target_present:
                    try:
                        values_equal = source_exif[ifd_name][tag_name] == target_exif[ifd_name][tag_name]
                    except Exception:
                        values_equal = False

                result[tag_name] = (source_present, target_present, values_equal)

        return result


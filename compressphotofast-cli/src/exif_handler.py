import os
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
    def read_exif_data(file_path: str) -> Optional[Dict[str, Any]]:
        try:
            exif_dict = piexif.load(file_path)
            return exif_dict
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
        """
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
    def add_compression_marker(
        file_path: str,
        quality: int,
        source_exif: Optional[dict] = None,
        original_size: Optional[int] = None,
    ) -> bool:
        """
        Add compression marker to file, optionally preserving all source metadata.

        Args:
            file_path: Path to file to modify
            quality: Compression quality level
            source_exif: Optional source EXIF dictionary to preserve all metadata
            original_size: Original file size before compression (defaults to
                the current file size, e.g. for inefficient-skip markers)

        Returns:
            True if marker added successfully, False otherwise
        """
        try:
            marker_size = os.path.getsize(file_path)
            marker_orig_size = original_size if original_size and original_size > 0 else marker_size

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
                    return False

                piexif.insert(exif_bytes, file_path)
                return True
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
                    try:
                        minimal_exif = {
                            "0th": {},
                            "Exif": {piexif.ExifIFD.UserComment: marker_bytes},
                        }
                        exif_bytes = piexif.dump(minimal_exif)
                    except Exception:
                        return False

                piexif.insert(exif_bytes, file_path)
                return True
        except Exception:
            return False

    @staticmethod
    def copy_exif_data(source_path: str, target_path: str) -> bool:
        try:
            source_exif = ExifHandler.read_exif_data(source_path)
            if source_exif is None:
                return False

            target_exif = {}
            for ifd_name in ["0th", "Exif", "1st", "GPS", "Interop"]:
                if ifd_name in source_exif and source_exif[ifd_name]:
                    target_exif[ifd_name] = {}
                    for tag, value in source_exif[ifd_name].items():
                        target_exif[ifd_name][tag] = value

            if not target_exif:
                return False

            exif_bytes = piexif.dump(target_exif)

            with Image.open(target_path) as img:
                fmt = img.format or "JPEG"
                if fmt.lower() == "jpeg":
                    try:
                        img.save(target_path, exif=exif_bytes, optimize=True)
                    except Exception:
                        img.save(target_path, optimize=True)
                elif fmt.lower() == "png":
                    img.save(target_path, optimize=True)

            return True
        except Exception:
            return False

    @staticmethod
    def copy_exif_with_marker(source_path: str, target_path: str, quality: int,
                             fallback_on_error: bool = True,
                             original_size: Optional[int] = None) -> bool:
        """
        Copy EXIF data from source to target and add compression marker.

        Args:
            source_path: Path to original image file
            target_path: Path to compressed image file
            quality: Compression quality level
            fallback_on_error: If True, falls back to add_compression_marker on error
            original_size: Original file size before compression (defaults to
                the source file size)

        Returns:
            True if EXIF copied successfully, False otherwise
        """
        try:
            source_exif = ExifHandler.read_exif_data(source_path)

            if source_exif is None or not source_exif:
                return ExifHandler.add_compression_marker(
                    target_path, quality, original_size=original_size
                )

            marker_size = os.path.getsize(target_path)
            marker_orig_size = (
                original_size if original_size and original_size > 0
                else os.path.getsize(source_path)
            )

            target_exif = {}
            for ifd_name in ["0th", "Exif", "1st", "GPS", "Interop"]:
                if ifd_name in source_exif and source_exif[ifd_name]:
                    target_exif[ifd_name] = {}
                    for tag, value in source_exif[ifd_name].items():
                        target_exif[ifd_name][tag] = value

            if "Exif" not in target_exif:
                target_exif["Exif"] = {}

            timestamp = int(datetime.now().timestamp() * 1000)
            marker_data = ExifHandler._build_marker(
                quality, timestamp, marker_size, marker_orig_size
            )
            marker_bytes = marker_data.encode("utf-8")

            target_exif["Exif"][piexif.ExifIFD.UserComment] = marker_bytes

            exif_bytes = None
            try:
                exif_bytes = piexif.dump(target_exif)
            except Exception:
                if fallback_on_error:
                    return ExifHandler.add_compression_marker(target_path, quality, source_exif, original_size=marker_orig_size)
                return False

            with Image.open(target_path) as img:
                fmt = img.format or "JPEG"
                if fmt.lower() == "jpeg":
                    try:
                        if exif_bytes:
                            img.save(
                                target_path,
                                exif=exif_bytes,
                                quality=quality,
                                optimize=True,
                            )
                        else:
                            img.save(target_path, quality=quality, optimize=True)
                    except Exception:
                        if fallback_on_error:
                            return ExifHandler.add_compression_marker(target_path, quality, source_exif, original_size=marker_orig_size)
                        return False
                elif fmt.lower() == "png":
                    img.save(target_path, optimize=True)

            return True
        except Exception:
            if fallback_on_error:
                source_exif = ExifHandler.read_exif_data(source_path)
                return ExifHandler.add_compression_marker(target_path, quality, source_exif)
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


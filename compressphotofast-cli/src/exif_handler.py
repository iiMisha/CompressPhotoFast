import os
import shutil
import piexif
from datetime import datetime
from typing import Tuple, Dict, Optional, Any
from PIL import Image

from .constants import (
    EXIF_COMPRESSION_MARKER,
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
    def get_compression_info(file_path: str) -> Tuple[bool, int, int]:
        exif_dict = ExifHandler.read_exif_data(file_path)
        if exif_dict is None:
            return False, -1, 0

        if "Exif" not in exif_dict:
            return False, -1, 0

        exif_ifd = exif_dict["Exif"]
        user_comment_tag = piexif.ExifIFD.UserComment

        if user_comment_tag not in exif_ifd:
            return False, -1, 0

        user_comment = exif_ifd[user_comment_tag]
        if isinstance(user_comment, bytes):
            try:
                user_comment = user_comment.decode("utf-8", errors="ignore")
            except UnicodeDecodeError:
                return False, -1, 0

        if not isinstance(user_comment, str):
            return False, -1, 0

        if not user_comment.startswith(EXIF_COMPRESSION_MARKER):
            return False, -1, 0

        try:
            parts = user_comment.split(":")
            if len(parts) >= 3:
                quality = int(parts[1])
                timestamp = int(parts[2])
                return True, quality, timestamp
        except (ValueError, IndexError):
            pass

        return False, -1, 0

    @staticmethod
    def is_image_compressed(file_path: str) -> bool:
        is_compressed, _, _ = ExifHandler.get_compression_info(file_path)
        return is_compressed

    @staticmethod
    def should_recompress(file_path: str) -> bool:
        is_compressed, quality, timestamp = ExifHandler.get_compression_info(file_path)
        if not is_compressed:
            return True

        if timestamp == 0:
            return False

        try:
            file_mtime = int(os.path.getmtime(file_path))
            time_diff = file_mtime - timestamp

            if time_diff <= TIME_DIFFERENCE_ALLOWED_SECONDS:
                return False

            return True
        except OSError:
            return True

    @staticmethod
    def add_compression_marker(file_path: str, quality: int) -> bool:
        try:
            exif_dict = ExifHandler.read_exif_data(file_path)

            if exif_dict is None or not exif_dict:
                exif_dict = {"0th": {}, "Exif": {}}

            if "Exif" not in exif_dict:
                exif_dict["Exif"] = {}

            timestamp = int(datetime.now().timestamp() * 1000)
            marker_data = f"{EXIF_COMPRESSION_MARKER}:{quality}:{timestamp}"
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

            with Image.open(file_path) as img:
                fmt = img.format or "JPEG"
                if fmt.lower() == "jpeg":
                    try:
                        if exif_bytes:
                            img.save(
                                file_path,
                                exif=exif_bytes,
                                quality=quality,
                                optimize=True,
                            )
                        else:
                            img.save(file_path, quality=quality, optimize=True)
                    except Exception:
                        img.save(file_path, quality=quality, optimize=True)
                elif fmt.lower() == "png":
                    img.save(file_path, optimize=True)

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
    def copy_exif_with_marker(source_path: str, target_path: str, quality: int) -> bool:
        try:
            source_exif = ExifHandler.read_exif_data(source_path)

            if source_exif is None or not source_exif:
                return ExifHandler.add_compression_marker(target_path, quality)

            target_exif = {}
            for ifd_name in ["0th", "Exif", "1st", "GPS", "Interop"]:
                if ifd_name in source_exif and source_exif[ifd_name]:
                    target_exif[ifd_name] = {}
                    for tag, value in source_exif[ifd_name].items():
                        target_exif[ifd_name][tag] = value

            if "Exif" not in target_exif:
                target_exif["Exif"] = {}

            timestamp = int(datetime.now().timestamp() * 1000)
            marker_data = f"{EXIF_COMPRESSION_MARKER}:{quality}:{timestamp}"
            marker_bytes = marker_data.encode("utf-8")

            target_exif["Exif"][piexif.ExifIFD.UserComment] = marker_bytes

            exif_bytes = None
            try:
                exif_bytes = piexif.dump(target_exif)
            except Exception:
                return ExifHandler.add_compression_marker(target_path, quality)

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
                        img.save(target_path, quality=quality, optimize=True)
                elif fmt.lower() == "png":
                    img.save(target_path, optimize=True)

            return True
        except Exception:
            return ExifHandler.add_compression_marker(target_path, quality)

    @staticmethod
    def preserve_file_dates(source_path: str, target_path: str) -> bool:
        try:
            atime = os.path.getatime(source_path)
            mtime = os.path.getmtime(source_path)
            os.utime(target_path, (atime, mtime))
            return True
        except OSError:
            return False

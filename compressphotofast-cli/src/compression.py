import os
import time
from typing import Tuple, Optional, Dict
from PIL import Image
import io
import piexif

from .constants import (
    COMPRESSION_QUALITY_LOW,
    COMPRESSION_QUALITY_MEDIUM,
    COMPRESSION_QUALITY_HIGH,
    DEFAULT_COMPRESSION_QUALITY,
    MIN_COMPRESSION_QUALITY,
    MAX_COMPRESSION_QUALITY,
    MIN_COMPRESSION_SAVING_PERCENT,
    MIN_PROCESSABLE_FILE_SIZE,
    MIN_BYTES_SAVING,
    MIN_COMPRESSION_RATIO,
    SUPPORTED_EXTENSIONS,
)
from .exif_handler import ExifHandler

# Попытка инициализации поддержки HEIC
try:
    import pillow_heif
    pillow_heif.register_heif_opener()
    HEIC_SUPPORT_AVAILABLE = True
except ImportError:
    HEIC_SUPPORT_AVAILABLE = False


class CompressionResult:
    def __init__(
        self,
        success: bool,
        original_size: int = 0,
        compressed_size: int = 0,
        saved_path: Optional[str] = None,
        message: str = "",
        metadata_preserved: bool = True,
        metadata_details: Optional[dict] = None,
    ):
        self.success = success
        self.original_size = original_size
        self.compressed_size = compressed_size
        self.saved_path = saved_path
        self.message = message
        self.metadata_preserved = metadata_preserved
        self.metadata_details = metadata_details

    @property
    def size_reduction(self) -> float:
        if self.original_size <= 0:
            return 0.0
        return ((self.original_size - self.compressed_size) / self.original_size) * 100

    @property
    def is_efficient(self) -> bool:
        if self.original_size <= 0:
            return False
        return self.compressed_size <= self.original_size * MIN_COMPRESSION_RATIO


class ImageCompressor:
    @staticmethod
    def is_supported_file(file_path: str) -> bool:
        ext = os.path.splitext(file_path)[1].lower()
        # Проверка поддержки HEIC
        if ext in {".heic", ".heif"}:
            if not HEIC_SUPPORT_AVAILABLE:
                return False
        return ext in SUPPORTED_EXTENSIONS

    @staticmethod
    def get_file_size(file_path: str) -> int:
        try:
            return os.path.getsize(file_path)
        except OSError:
            return 0

    @staticmethod
    def is_processable(file_path: str) -> bool:
        file_size = ImageCompressor.get_file_size(file_path)
        return file_size >= MIN_PROCESSABLE_FILE_SIZE

    @staticmethod
    def compress_to_bytes(
        image: Image.Image, quality: int, format: str = "JPEG"
    ) -> Tuple[Optional[bytes], int]:
        output = io.BytesIO()

        try:
            if format.upper() == "JPEG":
                image.save(output, format="JPEG", quality=quality, optimize=True)
            elif format.upper() == "PNG":
                image.save(output, format="PNG", optimize=True)
            elif format.upper() in ["HEIC", "HEIF"]:
                image.convert("RGB").save(
                    output, format="JPEG", quality=quality, optimize=True
                )
            else:
                image.save(output, format=format, quality=quality, optimize=True)

            compressed_bytes = output.getvalue()
            return compressed_bytes, len(compressed_bytes)
        except Exception:
            return None, 0

    @staticmethod
    def test_compression(file_path: str, quality: int) -> Optional[CompressionResult]:
        try:
            original_size = ImageCompressor.get_file_size(file_path)
            if original_size < MIN_PROCESSABLE_FILE_SIZE:
                return CompressionResult(
                    False, original_size, original_size, None, "File too small"
                )

            with Image.open(file_path) as img:
                format = img.format or "JPEG"
                if format.upper() in ["HEIC", "HEIF"]:
                    format = "JPEG"

                compressed_bytes, compressed_size = ImageCompressor.compress_to_bytes(
                    img, quality, format
                )

                if compressed_bytes is None:
                    return None

                return CompressionResult(True, original_size, compressed_size)
        except Exception as e:
            print(f"Error testing compression: {e}")
            return None

    @staticmethod
    def is_compression_efficient(original_size: int, compressed_size: int) -> bool:
        if original_size <= 0:
            return False

        size_reduction_percent = (
            (original_size - compressed_size) / original_size
        ) * 100
        bytes_saving = original_size - compressed_size

        return (
            size_reduction_percent >= MIN_COMPRESSION_SAVING_PERCENT
            and bytes_saving >= MIN_BYTES_SAVING
        )

    @staticmethod
    def find_optimal_quality(file_path: str) -> int:
        original_size = ImageCompressor.get_file_size(file_path)
        qualities = [70, 60, 80, 50, 90]

        best_quality = DEFAULT_COMPRESSION_QUALITY
        best_ratio = 1.0

        for quality in qualities:
            result = ImageCompressor.test_compression(file_path, quality)
            if result and result.success:
                ratio = result.compressed_size / original_size

                if ratio < best_ratio and ratio >= MIN_COMPRESSION_RATIO:
                    best_ratio = ratio
                    best_quality = quality

                if ratio < 0.3:
                    break

        return best_quality

    @staticmethod
    def compress_image(
        file_path: str,
        quality: int,
        output_path: Optional[str] = None,
        preserve_exif: bool = True,
    ) -> CompressionResult:
        try:
            if not ImageCompressor.is_supported_file(file_path):
                ext = os.path.splitext(file_path)[1].lower()
                if ext in {".heic", ".heif"} and not HEIC_SUPPORT_AVAILABLE:
                    return CompressionResult(
                        False, 0, 0, None,
                        "HEIC/HEIF support not available. Install pillow-heif."
                    )
                return CompressionResult(False, 0, 0, None, "Unsupported file format")

            if not ImageCompressor.is_processable(file_path):
                return CompressionResult(
                    False, 0, 0, None, "File too small for compression"
                )

            original_size = ImageCompressor.get_file_size(file_path)

            with Image.open(file_path) as img:
                format = img.format or "JPEG"
                if format.upper() in ["HEIC", "HEIF"]:
                    format = "JPEG"

                if output_path is None:
                    output_path = file_path

                img_copy = img.copy()

                # Read EXIF from source before compression
                source_exif = None
                if preserve_exif:
                    source_exif = ExifHandler.read_exif_data(file_path)

                # Prepare EXIF bytes for initial save
                exif_bytes = None
                if preserve_exif and source_exif and format.upper() == "JPEG":
                    try:
                        exif_bytes = piexif.dump(source_exif)
                    except Exception:
                        exif_bytes = None

                # Save with EXIF for JPEG, without for PNG
                if format.upper() == "JPEG":
                    if exif_bytes:
                        img_copy.save(
                            output_path, format="JPEG", quality=quality, optimize=True, exif=exif_bytes
                        )
                    else:
                        img_copy.save(
                            output_path, format="JPEG", quality=quality, optimize=True
                        )
                elif format.upper() == "PNG":
                    # PNG files are saved without EXIF metadata
                    img_copy.save(output_path, format="PNG", optimize=True)
                else:
                    img_copy.save(
                        output_path, format=format, quality=quality, optimize=True
                    )

            compressed_size = ImageCompressor.get_file_size(output_path)

            if not ImageCompressor.is_compression_efficient(
                original_size, compressed_size
            ):
                if output_path != file_path:
                    try:
                        os.remove(output_path)
                    except OSError:
                        pass
                # Add marker for inefficient compression, preserving all source metadata
                ExifHandler.add_compression_marker(file_path, 99, source_exif)
                if format.upper() == "PNG":
                    return CompressionResult(
                        True,
                        original_size,
                        compressed_size,
                        None,
                        "Compression not efficient (marker added to original)",
                        metadata_preserved=False,
                    )
                else:
                    return CompressionResult(
                        True,
                        original_size,
                        compressed_size,
                        None,
                        "Compression not efficient (marker added with metadata)",
                        metadata_preserved=(source_exif is not None),
                    )

            # Validate metadata preservation
            metadata_preserved = True
            metadata_details = None
            if preserve_exif and format.upper() == "JPEG":
                if source_exif is not None:
                    try:
                        metadata_details = ExifHandler.validate_exif_preservation(file_path, output_path)
                        # Check if any critical tags were lost
                        critical_tags = ["Make", "Model", "DateTimeOriginal", "FNumber", "ExposureTime"]
                        lost_tags = [tag for tag in critical_tags
                                     if tag in metadata_details and
                                     metadata_details[tag][0] and not metadata_details[tag][1]]
                        if lost_tags:
                            metadata_preserved = False
                    except Exception:
                        metadata_preserved = True
                else:
                    metadata_preserved = True
            elif format.upper() == "PNG":
                # PNG files don't support EXIF
                metadata_preserved = False
                metadata_details = None

            return CompressionResult(
                True,
                original_size,
                compressed_size,
                output_path,
                f"Compressed successfully" if metadata_preserved else "Compressed with metadata loss",
                metadata_preserved=metadata_preserved,
                metadata_details=metadata_details,
            )
        except Exception as e:
            return CompressionResult(False, 0, 0, None, f"Error: {str(e)}")


    @staticmethod
    def compress_image_safe(
        file_path: str,
        quality: int,
        replace_mode: bool = False,
        output_dir: Optional[str] = None,
    ) -> CompressionResult:
        original_size = ImageCompressor.get_file_size(file_path)

        if replace_mode:
            backup_path = file_path + ".bak"

            try:
                # Create backup before compression
                try:
                    shutil.copy2(file_path, backup_path)
                except (IOError, OSError) as e:
                    return CompressionResult(
                        False, original_size, 0, None,
                        f"Failed to create backup: {e}"
                    )

                result = ImageCompressor.compress_image(
                    file_path, quality, file_path, preserve_exif=True
                )

                if not result.success or result.saved_path is None:
                    try:
                        shutil.move(backup_path, file_path)
                    except (IOError, OSError):
                        pass
                    # Add marker for inefficient compression after restoring, with source metadata
                    if result.success and "not efficient" in result.message.lower():
                        source_exif = ExifHandler.read_exif_data(backup_path)
                        ExifHandler.add_compression_marker(file_path, 99, source_exif)
                    return result
                else:
                    try:
                        os.remove(backup_path)
                    except OSError:
                        pass
                    ExifHandler.preserve_file_dates(backup_path, file_path)
                    return result

            except Exception as e:
                return CompressionResult(
                    False, original_size, 0, None,
                    f"Compression failed: {e}"
                )
        else:
            if output_dir is None:
                base_dir = os.path.dirname(file_path)
                output_dir = os.path.join(base_dir, "CompressPhotoFast")

            os.makedirs(output_dir, exist_ok=True)

            base_name = os.path.basename(file_path)
            name, ext = os.path.splitext(base_name)
            # HEIC/HEIF файлы конвертируются в JPEG
            if ext.lower() in {".heic", ".heif"}:
                ext = ".jpg"
            output_name = f"{name}_compressed{ext}"
            output_path = os.path.join(output_dir, output_name)

            # Process-safe filename generation with retry logic
            max_attempts = 100
            counter = 1

            for attempt in range(max_attempts):
                if not os.path.exists(output_path):
                    break

                # HEIC/HEIF файлы конвертируются в JPEG
                file_ext = ".jpg" if ext.lower() in {".heic", ".heif"} else ext
                output_name = f"{name}_compressed_{counter}{file_ext}"
                output_path = os.path.join(output_dir, output_name)
                counter += 1

            # Check if we exhausted all attempts
            if os.path.exists(output_path):
                return CompressionResult(
                    False, original_size, 0, None,
                    "Failed to generate unique filename after 100 attempts"
                )

            result = ImageCompressor.compress_image(
                file_path, quality, output_path, preserve_exif=True
            )

            if result.success and result.saved_path:
                ExifHandler.preserve_file_dates(file_path, output_path)

            return result


import shutil

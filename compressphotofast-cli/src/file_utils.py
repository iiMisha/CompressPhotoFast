import os
from pathlib import Path
from typing import List, Tuple, Optional

from .constants import (
    APP_DIRECTORY,
    COMPRESSED_FILE_SUFFIX,
    SUPPORTED_EXTENSIONS,
    MIN_FILE_SIZE,
    MAX_FILE_SIZE,
    OPTIMUM_FILE_SIZE,
)


class FileInfo:
    def __init__(
        self, path: str, name: str, size: int, mtime: float, is_supported: bool = True
    ):
        self.path = path
        self.name = name
        self.size = size
        self.mtime = mtime
        self.is_supported = is_supported
        self.extension = os.path.splitext(name)[1].lower()


def is_valid_file_size(size: int) -> bool:
    return MIN_FILE_SIZE <= size <= MAX_FILE_SIZE


def format_size(size: int) -> str:
    for unit in ["B", "KB", "MB", "GB"]:
        if size < 1024.0:
            return f"{size:.1f} {unit}"
        size /= 1024.0
    return f"{size:.1f} TB"


def format_duration(seconds: float) -> str:
    """
    Форматирует продолжительность времени в читаемый формат.

    Примеры:
    - 45.3 -> "45.3s"
    - 125.0 -> "2m 5s"
    - 3665.0 -> "1h 1m 5s"

    Args:
        seconds: Продолжительность в секундах (может быть float)

    Returns:
        Отформатированная строка времени
    """
    if seconds < 60:
        return f"{seconds:.1f}s"
    elif seconds < 3600:
        minutes = int(seconds // 60)
        secs = seconds % 60
        return f"{minutes}m {secs:.0f}s"
    else:
        hours = int(seconds // 3600)
        minutes = int((seconds % 3600) // 60)
        secs = seconds % 60
        return f"{hours}h {minutes}m {secs:.0f}s"


def is_screenshot(filename: str) -> bool:
    name_lower = filename.lower()
    return (
        "screenshot" in name_lower
        or "screen_shot" in name_lower
        or name_lower.startswith("scr_")
        or ("screen" in name_lower and "shot" in name_lower)
    )


def is_messenger_photo(path: str) -> bool:
    path_lower = path.lower()
    if "/documents/" in path_lower:
        return False
    return (
        "/whatsapp/" in path_lower
        or "/telegram/" in path_lower
        or "/viber/" in path_lower
        or "/messenger/" in path_lower
        or "/messages/" in path_lower
        or "pictures/messages" in path_lower
    )


def find_image_files(
    root_path: str,
    recursive: bool = True,
    skip_screenshots: bool = False,
    skip_messenger: bool = False,
    skip_app_dir: bool = True,
) -> List[FileInfo]:
    root = Path(root_path)
    if not root.exists():
        return []

    files = []

    if recursive:
        pattern = "**/*"
    else:
        pattern = "*"

    for item in root.glob(pattern):
        if not item.is_file():
            continue

        file_path = str(item)

        if skip_app_dir:
            if APP_DIRECTORY in file_path:
                continue

        if skip_messenger and is_messenger_photo(file_path):
            continue

        filename = item.name
        if skip_screenshots and is_screenshot(filename):
            continue

        ext = item.suffix.lower()
        if ext not in SUPPORTED_EXTENSIONS:
            continue

        try:
            size = item.stat().st_size
            mtime = item.stat().st_mtime

            files.append(FileInfo(file_path, filename, size, mtime))
        except OSError:
            continue

    return files


def find_compressed_versions(root_path: str, original_name: str) -> List[str]:
    root = Path(root_path)
    compressed_dir = root / APP_DIRECTORY

    if not compressed_dir.exists():
        return []

    base_name = os.path.splitext(original_name)[0]
    ext = os.path.splitext(original_name)[1]

    pattern = f"{base_name}{COMPRESSED_FILE_SUFFIX}*{ext}"
    compressed_files = []

    for item in compressed_dir.glob(pattern):
        if item.is_file():
            compressed_files.append(str(item))

    return compressed_files


def create_compressed_filename(original_name: str, replace_mode: bool = False) -> str:
    if replace_mode:
        return original_name

    name, ext = os.path.splitext(original_name)
    return f"{name}{COMPRESSED_FILE_SUFFIX}{ext}"


def ensure_output_directory(base_path: str, output_dir: Optional[str] = None) -> str:
    if output_dir is None:
        output_path = os.path.join(base_path, APP_DIRECTORY)
    else:
        output_path = output_dir

    os.makedirs(output_path, exist_ok=True)
    return output_path


def get_unique_filename(directory: str, base_name: str, extension: str) -> str:
    filename = f"{base_name}{extension}"
    filepath = os.path.join(directory, filename)

    counter = 1
    while os.path.exists(filepath):
        filename = f"{base_name}_{counter}{extension}"
        filepath = os.path.join(directory, filename)
        counter += 1

    return filepath


def is_already_small(size: int) -> bool:
    return size < OPTIMUM_FILE_SIZE


def get_total_folder_size(root_path: str, recursive: bool = True) -> int:
    """
    Calculate total size of all files in a folder (including non-image files).

    Args:
        root_path: Path to the folder
        recursive: Whether to include subdirectories (default: True)

    Returns:
        Total size in bytes
    """
    root = Path(root_path)
    if not root.exists() or not root.is_dir():
        return 0

    total_size = 0

    if recursive:
        pattern = "**/*"
    else:
        pattern = "*"

    for item in root.glob(pattern):
        if not item.is_file():
            continue

        try:
            total_size += item.stat().st_size
        except OSError:
            continue

    return total_size

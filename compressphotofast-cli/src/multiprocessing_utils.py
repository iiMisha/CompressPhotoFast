"""
Multiprocessing utilities for parallel image compression.

Provides process-safe wrapper for statistics collection and worker function
for processing individual images in multi-process environment.
"""

from typing import Tuple, Optional
from traceback import format_exc
from datetime import datetime

from .compression import ImageCompressor, CompressionResult
from .date_parser import parse_date_ms
from .exif_handler import ExifHandler
from .file_utils import FileInfo, is_already_small


def process_single_file_dry_run(
    file_info: FileInfo,
    quality: int,
    force: bool
) -> Tuple[FileInfo, bool, str, Optional[CompressionResult]]:
    """
    Worker function to analyze a single image file in dry-run mode.

    This function is designed to run in a separate process and handles:
    - Checking if file needs processing (EXIF, size)
    - Testing compression efficiency
    - Returning results for display in main process

    Args:
        file_info: Information about the file to analyze
        quality: Compression quality to test
        force: Force re-compression of already compressed files

    Returns:
        Tuple containing:
        - file_info: Original file information
        - should_process: Whether the file should be compressed
        - skip_reason: Reason for skipping (empty string if not skipped)
        - test_result: CompressionResult from test_compression (None if skipped/error)
    """
    try:
        should_process = True
        skip_reason = ""
        test_result = None

        # Check if processing is needed
        if not force:
            try:
                if (ExifHandler.is_image_compressed(file_info.path) and
                    not ExifHandler.should_recompress(file_info.path)):
                    should_process = False
                    skip_reason = "Already compressed"
                elif is_already_small(file_info.size):
                    should_process = False
                    skip_reason = "Already small"
            except Exception as e:
                # Error during checking - log but try to process anyway
                skip_reason = f"Check error: {str(e)}"

        # Test compression if needed
        if should_process:
            try:
                test_result = ImageCompressor.test_compression(file_info.path, quality)
                if test_result and not test_result.is_efficient:
                    should_process = False
                    skip_reason = "Compression not efficient"
                    # NOTE: In dry-run mode, we don't add markers to files
                elif not test_result:
                    should_process = False
                    skip_reason = "Test failed"
            except Exception as e:
                # Error during test compression
                return (
                    file_info,
                    False,
                    f"Test error: {type(e).__name__}: {str(e)}",
                    None
                )

        return (file_info, should_process, skip_reason, test_result)

    except KeyboardInterrupt:
        # Worker interrupted - return gracefully
        return (
            file_info,
            False,
            "Interrupted",
            None
        )
    except Exception as e:
        # Unexpected error in worker function
        error_msg = f"Worker error: {type(e).__name__}: {str(e)}\n{format_exc()}"
        return (
            file_info,
            False,
            error_msg,
            None
        )


def process_single_file_date_fix(
    file_info: FileInfo,
    dry_run: bool,
) -> Tuple[FileInfo, str, str, str, str]:
    """
    Worker функции fix-dates: восстановление EXIF-дат одного файла.
    Паритет ExifDateFixUseCase в Android: критерий «есть дата» — любой из
    четырёх тегов; источники по приоритету имя файла → маркер сжатия → mtime.

    Args:
        file_info: Information about the file to process
        dry_run: Only compute the source/date without writing

    Returns:
        Tuple (file_info, status, source, date_str, error):
        - status: fixed / already_ok / heic / no_source / failed
        - source: filename / marker / mtime / "" (empty for non-fixed)
        - date_str: chosen date as "%Y:%m:%d %H:%M:%S" ("" if none)
        - error: error message ("" if no error)
    """
    try:
        path = file_info.path

        # HEIC — EXIF не пишется на обеих платформах
        if ExifHandler.is_heic_file(path):
            return (file_info, "heic", "", "", "")

        # None — легитимный случай «нет EXIF» (PNG без eXIf-чанка, JPEG без EXIF)
        exif_dict = ExifHandler.read_exif_data(path)
        if ExifHandler._has_any_date(exif_dict):
            return (file_info, "already_ok", "", "", "")

        date_ms: Optional[int] = None
        source = ""
        include_original = False

        parsed = parse_date_ms(file_info.name)
        if parsed is not None:
            date_ms = parsed
            source = "filename"
            include_original = True
        else:
            # Timestamp маркера сжатия — миллисекунды; заглушки (0) не источник
            _, _, marker_timestamp = ExifHandler.get_compression_info(path)
            if marker_timestamp > 0:
                date_ms = marker_timestamp
                source = "marker"
            elif file_info.mtime > 0:
                date_ms = int(file_info.mtime * 1000)
                source = "mtime"

        if date_ms is None:
            return (file_info, "no_source", "", "", "")

        date_str = datetime.fromtimestamp(date_ms / 1000).strftime("%Y:%m:%d %H:%M:%S")

        if not dry_run:
            ok = ExifHandler.write_missing_dates(path, date_ms, include_original)
            if not ok:
                return (file_info, "failed", source, date_str, "write failed")

        return (file_info, "fixed", source, date_str, "")

    except KeyboardInterrupt:
        # Worker interrupted - return gracefully
        return (file_info, "failed", "", "", "Interrupted")
    except Exception as e:
        error_msg = f"Worker error: {type(e).__name__}: {str(e)}"
        return (file_info, "failed", "", "", error_msg)


class ProcessSafeStats:
    """
    Process-safe wrapper around CompressionStats.

    Uses multiprocessing.Manager().Lock to ensure safe concurrent access to statistics
    from multiple worker processes.

    All methods that modify state are protected by locks.
    """

    def __init__(self, manager_lock=None):
        """
        Initialize process-safe statistics wrapper.

        Args:
            manager_lock: Optional lock from multiprocessing.Manager().Lock()
                          If None, creates a new lock (not recommended for multiprocessing)
        """
        from .stats import CompressionStats
        self._stats = CompressionStats()
        self._lock = manager_lock

    def add_result(
        self,
        result: CompressionResult,
        skipped: bool = False,
        reason: str = ""
    ) -> None:
        """
        Process-safe addition of compression result.

        Args:
            result: Compression result to add
            skipped: Whether the file was skipped
            reason: Reason for skipping (if applicable)
        """
        if self._lock:
            with self._lock:
                self._stats.add_result(result, skipped, reason)
        else:
            # Fallback for single-process mode
            self._stats.add_result(result, skipped, reason)

    def print_summary(self) -> None:
        """Print summary statistics (called from main process only)."""
        # No lock needed - called from main process after all workers complete
        self._stats.print_summary()

    def start_timing(self) -> None:
        """Начать отсчет времени (только в главном процессе)."""
        self._stats.start_timing()

    def stop_timing(self) -> None:
        """Остановить отсчет времени (только в главном процессе)."""
        self._stats.stop_timing()

    @property
    def total(self) -> int:
        """Get total number of files."""
        return self._stats.total

    @total.setter
    def total(self, value: int) -> None:
        """Set total number of files (called from main process only)."""
        self._stats.total = value

    @property
    def success(self) -> int:
        """Get number of successful compressions."""
        return self._stats.success

    @property
    def processed(self) -> int:
        """Get number of processed files."""
        return self._stats.processed

    @property
    def original_size_total(self) -> int:
        """Get total original size."""
        return self._stats.original_size_total

    @property
    def compressed_size_total(self) -> int:
        """Get total compressed size."""
        return self._stats.compressed_size_total

    @property
    def processed_path(self) -> str:
        """Get processed path."""
        return self._stats.processed_path

    @processed_path.setter
    def processed_path(self, value: str) -> None:
        """Set processed path (called from main process only)."""
        self._stats.processed_path = value

    @property
    def folder_size_before(self) -> int:
        """Get folder size before compression."""
        return self._stats.folder_size_before

    @folder_size_before.setter
    def folder_size_before(self, value: int) -> None:
        """Set folder size before compression."""
        self._stats.folder_size_before = value

    @property
    def folder_size_after(self) -> int:
        """Get folder size after compression."""
        return self._stats.folder_size_after

    @folder_size_after.setter
    def folder_size_after(self, value: int) -> None:
        """Set folder size after compression."""
        self._stats.folder_size_after = value

    @property
    def total_folder_size_before(self) -> int:
        """Get total folder size before compression (all files)."""
        return self._stats.total_folder_size_before

    @total_folder_size_before.setter
    def total_folder_size_before(self, value: int) -> None:
        """Set total folder size before compression (called from main process only)."""
        self._stats.total_folder_size_before = value

    @property
    def total_folder_size_after(self) -> int:
        """Get total folder size after compression (all files)."""
        return self._stats.total_folder_size_after

    @total_folder_size_after.setter
    def total_folder_size_after(self, value: int) -> None:
        """Set total folder size after compression (called from main process only)."""
        self._stats.total_folder_size_after = value

    @property
    def metadata_preserved_count(self) -> int:
        """Get count of files with preserved metadata."""
        return self._stats.metadata_preserved_count

    @property
    def metadata_lost_count(self) -> int:
        """Get count of files with lost metadata."""
        return self._stats.metadata_lost_count



def process_single_file(
    file_info: FileInfo,
    quality: int,
    replace: bool,
    output_dir: Optional[str],
    force: bool
) -> Tuple[FileInfo, Optional[CompressionResult], bool, str, str]:
    """
    Worker function to process a single image file.

    This function is designed to run in a separate process and handles:
    - Checking if file needs processing (EXIF, size)
    - Compressing the image
    - Returning results for display in main process

    Args:
        file_info: Information about the file to process
        quality: Compression quality
        replace: Whether to replace original file
        output_dir: Output directory for compressed files
        force: Force re-compression of already compressed files

    Returns:
        Tuple containing:
        - file_info: Original file information
        - result: CompressionResult (None if skipped)
        - skipped: Whether the file was skipped
        - skip_reason: Reason for skipping (empty string if not skipped)
        - error_message: Error message if exception occurred (empty if no error)
    """
    try:
        # Check if processing is needed
        should_process = True
        skip_reason = ""

        if not force:
            try:
                if (ExifHandler.is_image_compressed(file_info.path) and
                    not ExifHandler.should_recompress(file_info.path)):
                    should_process = False
                    skip_reason = "Already compressed"
                elif is_already_small(file_info.size):
                    should_process = False
                    skip_reason = "Already small"
                    # Гарантия дат для мелких файлов (без маркера, идемпотентно)
                    ExifHandler.backfill_missing_dates(file_info.path)
            except Exception as e:
                # Error during checking - log but try to process anyway
                skip_reason = f"Check error: {str(e)}"

        # Process the file if needed
        if should_process:
            try:
                # Compress the image
                result = ImageCompressor.compress_image_safe(
                    file_info.path,
                    quality,
                    replace_mode=replace,
                    output_dir=output_dir
                )

                return (file_info, result, False, "", "")

            except Exception as e:
                # Error during compression
                error_msg = f"Compression error: {type(e).__name__}: {str(e)}"
                return (
                    file_info,
                    None,
                    False,
                    "",
                    error_msg
                )
        else:
            # File was skipped
            return (
                file_info,
                None,
                True,
                skip_reason,
                ""
            )

    except KeyboardInterrupt:
        # Worker interrupted - return gracefully
        return (
            file_info,
            None,
            False,
            "",
            "Interrupted"
        )
    except Exception as e:
        # Unexpected error in worker function
        error_msg = f"Worker error: {type(e).__name__}: {str(e)}\n{format_exc()}"
        return (
            file_info,
            None,
            False,
            "",
            error_msg
        )

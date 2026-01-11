"""
Statistics tracking for image compression operations.

Provides CompressionStats class for tracking and displaying
compression results and metrics.
"""

from typing import Optional
from rich.console import Console
from rich.table import Table

from .compression import CompressionResult
from .file_utils import format_size, format_duration
import time

console = Console()


class CompressionStats:
    """
    Tracks statistics for image compression operations.

    Attributes:
        total: Total number of files to process
        processed: Number of files processed
        skipped: Number of files skipped
        success: Number of successfully compressed files
        failed: Number of failed compressions
        original_size_total: Total original size of all successfully compressed files
        compressed_size_total: Total compressed size of all successfully compressed files
        skipped_reasons: Dictionary counting reasons for skipping files
    """

    def __init__(self):
        """Initialize compression statistics."""
        self.total = 0
        self.processed = 0
        self.skipped = 0
        self.success = 0
        self.failed = 0
        self.original_size_total = 0
        self.compressed_size_total = 0
        self.skipped_reasons = {}
        self.start_time = None
        self.end_time = None
        self.processed_path = None  # Обрабатываемый путь
        self.folder_size_before = 0  # Общий размер папки до сжатия (только изображения)
        self.folder_size_after = 0   # Общий размер папки после сжатия (только изображения)
        self.total_folder_size_before = 0  # Общий размер папки до сжатия (все файлы)
        self.total_folder_size_after = 0   # Общий размер папки после сжатия (все файлы)

    def start_timing(self) -> None:
        """Начать отсчет времени обработки."""
        self.start_time = time.time()

    def stop_timing(self) -> None:
        """Остановить отсчет времени обработки."""
        self.end_time = time.time()

    def get_elapsed_time(self) -> float:
        """Получить прошедшее время в секундах."""
        if self.start_time is None:
            return 0.0
        end = self.end_time if self.end_time is not None else time.time()
        return end - self.start_time

    def add_result(
        self, result: CompressionResult, skipped: bool = False, reason: str = ""
    ):
        """
        Add a compression result to the statistics.

        Args:
            result: CompressionResult object
            skipped: Whether the file was skipped
            reason: Reason for skipping (if applicable)
        """
        self.processed += 1

        if skipped:
            self.skipped += 1
            if reason:
                self.skipped_reasons[reason] = self.skipped_reasons.get(reason, 0) + 1
        elif result.success:
            self.success += 1
            self.original_size_total += result.original_size
            self.compressed_size_total += result.compressed_size
        else:
            self.failed += 1

    def print_summary(self):
        """Print summary statistics table to console."""
        table = Table(title="Compression Summary")
        table.add_column("Metric", style="cyan")
        table.add_column("Value", style="magenta")

        # Отобразить обрабатываемый путь (если указан)
        if self.processed_path:
            table.add_row("Path", str(self.processed_path), style="cyan")
            # Добавить пустую строку для визуального разделения
            table.add_row("", "")

        table.add_row("Total files", str(self.total))
        table.add_row("Processed", str(self.processed))
        table.add_row("Success", str(self.success), style="green")
        table.add_row("Skipped", str(self.skipped))
        table.add_row("Failed", str(self.failed), style="red")

        # Добавить метрики времени
        elapsed = self.get_elapsed_time()
        table.add_row("Total time", format_duration(elapsed))

        if self.processed > 0:
            avg_time = elapsed / self.processed
            table.add_row("Avg time per file", format_duration(avg_time))

        # Добавить метрики папки перед строками сжатых файлов
        if self.total_folder_size_before > 0:
            table.add_row("Total folder size", format_size(self.total_folder_size_before))
            if self.total_folder_size_after > 0:
                table.add_row("Total folder size after", format_size(self.total_folder_size_after))
                saved = self.total_folder_size_before - self.total_folder_size_after
                saved_percent = (saved / self.total_folder_size_before) * 100
                table.add_row("Total folder saved", format_size(saved), style="green")
                table.add_row("Total folder saved %", f"{saved_percent:.1f}%")
            # Добавить пустую строку для визуального разделения
            table.add_row("", "")

        if self.folder_size_before > 0:
            table.add_row("Images size before", format_size(self.folder_size_before))
            if self.folder_size_after > 0:
                table.add_row("Images size after", format_size(self.folder_size_after))
                saved = self.folder_size_before - self.folder_size_after
                saved_percent = (saved / self.folder_size_before) * 100
                table.add_row("Images saved", format_size(saved), style="green")
                table.add_row("Images saved %", f"{saved_percent:.1f}%")

        if self.success > 0:
            saved = self.original_size_total - self.compressed_size_total
            saved_percent = (saved / self.original_size_total) * 100
            table.add_row("Original size", format_size(self.original_size_total))
            table.add_row("Compressed size", format_size(self.compressed_size_total))
            table.add_row("Saved", format_size(saved), style="green")
            table.add_row("Saved %", f"{saved_percent:.1f}%")

        console.print(table)

        if self.skipped_reasons:
            console.print("\n[bold]Skipped reasons:[/bold]")
            for reason, count in self.skipped_reasons.items():
                console.print(f"  • {reason}: {count}")

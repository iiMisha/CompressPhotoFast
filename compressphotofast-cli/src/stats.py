"""
Statistics tracking for image compression operations.

Provides CompressionStats class for tracking and displaying
compression results and metrics.
"""

from typing import Optional
from rich.console import Console
from rich.table import Table

from .compression import CompressionResult
from .file_utils import format_size

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

        table.add_row("Total files", str(self.total))
        table.add_row("Processed", str(self.processed))
        table.add_row("Success", str(self.success), style="green")
        table.add_row("Skipped", str(self.skipped))
        table.add_row("Failed", str(self.failed), style="red")

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

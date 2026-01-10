import os
import sys
from typing import Optional, List
from pathlib import Path

import click
from rich.console import Console
from rich.table import Table
from rich.progress import (
    Progress,
    SpinnerColumn,
    TextColumn,
    BarColumn,
    TaskProgressColumn,
    TimeRemainingColumn,
)
from rich.panel import Panel

from .compression import ImageCompressor, CompressionResult
from .exif_handler import ExifHandler
from .file_utils import (
    FileInfo,
    find_image_files,
    format_size,
    is_already_small,
    ensure_output_directory,
    create_compressed_filename,
    get_unique_filename,
)
from .constants import (
    COMPRESSION_QUALITY_LOW,
    COMPRESSION_QUALITY_MEDIUM,
    COMPRESSION_QUALITY_HIGH,
    DEFAULT_COMPRESSION_QUALITY,
    APP_DIRECTORY,
)


console = Console()


class CompressionStats:
    def __init__(self):
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


@click.group()
def cli():
    """CompressPhotoFast CLI - Fast image compression tool for Linux/Windows"""
    pass


@cli.command()
@click.argument("path", type=click.Path(exists=True, path_type=Path))
@click.option(
    "--quality",
    "-q",
    type=click.Choice(["low", "medium", "high", "auto"]),
    default="medium",
    help="Compression quality",
)
@click.option(
    "--replace",
    "-r",
    is_flag=True,
    help="Replace original files (instead of creating copies)",
)
@click.option(
    "--output-dir",
    "-o",
    type=click.Path(),
    help="Output directory for compressed files",
)
@click.option("--skip-screenshots", "-s", is_flag=True, help="Skip screenshot files")
@click.option(
    "--skip-messenger", "-m", is_flag=True, help="Skip photos from messenger folders"
)
@click.option(
    "--force",
    "-f",
    is_flag=True,
    help="Force re-compression of already compressed files",
)
@click.option(
    "--dry-run",
    "-n",
    is_flag=True,
    help="Show what would be compressed without actually compressing",
)
def compress(
    path: Path,
    quality: str,
    replace: bool,
    output_dir: Optional[str],
    skip_screenshots: bool,
    skip_messenger: bool,
    force: bool,
    dry_run: bool,
):
    """Compress images in the specified path (file or directory)"""

    quality_map = {
        "low": COMPRESSION_QUALITY_LOW,
        "medium": COMPRESSION_QUALITY_MEDIUM,
        "high": COMPRESSION_QUALITY_HIGH,
        "auto": 0,
    }
    quality_value = quality_map[quality]

    console.print(f"[bold cyan]CompressPhotoFast CLI[/bold cyan]")
    console.print(f"[dim]Path:[/dim] {path}")
    console.print(f"[dim]Quality:[/dim] {quality}")
    console.print(f"[dim]Mode:[/dim] {'Replace' if replace else 'Separate'}")
    console.print(f"[dim]Dry run:[/dim] {'Yes' if dry_run else 'No'}")
    console.print()

    stats = CompressionStats()

    if path.is_file():
        if not ImageCompressor.is_supported_file(str(path)):
            console.print(f"[red]Error:[/red] Unsupported file format: {path}")
            return

        files = [
            FileInfo(str(path), path.name, path.stat().st_size, path.stat().st_mtime)
        ]
    else:
        console.print("[dim]Scanning for images...[/dim]")
        files = find_image_files(
            str(path),
            recursive=True,
            skip_screenshots=skip_screenshots,
            skip_messenger=skip_messenger,
        )

    stats.total = len(files)

    if stats.total == 0:
        console.print("[yellow]No images found to process[/yellow]")
        return

    console.print(f"[dim]Found {stats.total} images[/dim]")
    console.print()

    if not output_dir and not replace:
        output_dir = ensure_output_directory(str(path))
        console.print(f"[dim]Output directory:[/dim] {output_dir}")

    if dry_run:
        _run_dry_run(files, quality_value, force, stats)
    else:
        _run_compression(files, quality_value, replace, output_dir, force, stats)

    stats.print_summary()


def _run_dry_run(
    files: List[FileInfo], quality: int, force: bool, stats: CompressionStats
):
    """Show what would be compressed without actually compressing"""
    table = Table(title="Dry Run - Images to Compress")
    table.add_column("Filename", style="cyan")
    table.add_column("Size", style="magenta")
    table.add_column("Status", style="yellow")
    table.add_column("Reason")

    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        TimeRemainingColumn(),
        console=console,
    ) as progress:
        task = progress.add_task("Analyzing images...", total=len(files))

        for file_info in files:
            should_process = True
            skip_reason = ""

            if not force:
                if ExifHandler.is_image_compressed(
                    file_info.path
                ) and not ExifHandler.should_recompress(file_info.path):
                    should_process = False
                    skip_reason = "Already compressed"
                elif is_already_small(file_info.size):
                    should_process = False
                    skip_reason = "Already small"

            if should_process:
                test_result = ImageCompressor.test_compression(file_info.path, quality)
                if test_result and not test_result.is_efficient:
                    should_process = False
                    skip_reason = "Compression not efficient"
                elif not test_result:
                    should_process = False
                    skip_reason = "Test failed"

            stats.add_result(
                CompressionResult(should_process, file_info.size, 0),
                skipped=not should_process,
                reason=skip_reason,
            )

            status = (
                "[green]Will compress[/green]" if should_process else "[red]Skip[/red]"
            )
            table.add_row(
                file_info.name, format_size(file_info.size), status, skip_reason
            )

            progress.update(task, advance=1)

    console.print(table)


def _run_compression(
    files: List[FileInfo],
    quality: int,
    replace: bool,
    output_dir: Optional[str],
    force: bool,
    stats: CompressionStats,
):
    """Run actual compression"""
    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        console=console,
    ) as progress:
        task = progress.add_task("Compressing images...", total=len(files))

        for file_info in files:
            progress.update(task, description=f"Processing: {file_info.name[:50]}...")

            should_process = True
            skip_reason = ""

            if not force:
                if ExifHandler.is_image_compressed(
                    file_info.path
                ) and not ExifHandler.should_recompress(file_info.path):
                    should_process = False
                    skip_reason = "Already compressed"
                elif is_already_small(file_info.size):
                    should_process = False
                    skip_reason = "Already small"

            if should_process:
                if quality == 0:
                    quality = ImageCompressor.find_optimal_quality(file_info.path)

                result = ImageCompressor.compress_image_safe(
                    file_info.path, quality, replace_mode=replace, output_dir=output_dir
                )

                if result.success and result.saved_path:
                    saved_percent = result.size_reduction
                    console.print(
                        f"  [green]✓[/green] {file_info.name}: "
                        f"{format_size(result.original_size)} → {format_size(result.compressed_size)} "
                        f"([green]{saved_percent:.1f}%[/green])"
                    )
                    stats.add_result(result)
                else:
                    console.print(f"  [red]✗[/red] {file_info.name}: {result.message}")
                    stats.add_result(result)
            else:
                console.print(f"  [yellow]⊘[/yellow] {file_info.name}: {skip_reason}")
                stats.add_result(
                    CompressionResult(False, file_info.size, 0),
                    skipped=True,
                    reason=skip_reason,
                )

            progress.update(task, advance=1)


@cli.command()
@click.argument("path", type=click.Path(exists=True, path_type=Path))
@click.option("--skip-screenshots", "-s", is_flag=True, help="Skip screenshot files")
@click.option(
    "--skip-messenger", "-m", is_flag=True, help="Skip photos from messenger folders"
)
def stats(path: Path, skip_screenshots: bool, skip_messenger: bool):
    """Show statistics about images in the specified path"""

    console.print(f"[bold cyan]Image Statistics[/bold cyan]")
    console.print(f"[dim]Path:[/dim] {path}")
    console.print()

    files = find_image_files(
        str(path),
        recursive=True,
        skip_screenshots=skip_screenshots,
        skip_messenger=skip_messenger,
    )

    if not files:
        console.print("[yellow]No images found[/yellow]")
        return

    total_size = sum(f.size for f in files)
    compressed_count = 0
    uncompressed_count = 0

    for file_info in files:
        if ExifHandler.is_image_compressed(file_info.path):
            compressed_count += 1
        else:
            uncompressed_count += 1

    table = Table(title="Statistics")
    table.add_column("Metric", style="cyan")
    table.add_column("Value", style="magenta")

    table.add_row("Total images", str(len(files)))
    table.add_row("Total size", format_size(total_size))
    table.add_row("Compressed", str(compressed_count))
    table.add_row("Uncompressed", str(uncompressed_count))
    table.add_row("Compressed %", f"{(compressed_count / len(files) * 100):.1f}%")

    console.print(table)


@cli.command()
def version():
    """Show version information"""
    console.print("[bold cyan]CompressPhotoFast CLI[/bold cyan]")
    console.print("[dim]Version 1.0.0[/dim]")
    console.print("[dim]Cross-platform image compression tool[/dim]")


if __name__ == "__main__":
    cli()

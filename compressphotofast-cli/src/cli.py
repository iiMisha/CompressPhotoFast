import os
import sys
from typing import Optional, List
from pathlib import Path
import concurrent.futures
import multiprocessing

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
from .stats import CompressionStats
from .multiprocessing_utils import ProcessSafeStats, process_single_file, process_single_file_dry_run


console = Console()


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

    # Проверка поддержки HEIC и информирование пользователя
    from .compression import HEIC_SUPPORT_AVAILABLE
    import platform

    if not HEIC_SUPPORT_AVAILABLE:
        system = platform.system()
        if system == "Windows":
            instructions = (
                "[yellow]⚠ HEIC/HEIF поддержка не доступна[/yellow]\n\n"
                "Для обработки HEIC файлов установите pillow-heif:\n"
                "  pip install --upgrade pillow-heif\n\n"
                "HEIC файлы будут [bold]пропущены[/bold] при обработке."
            )
        else:  # Linux, macOS
            instructions = (
                "[yellow]⚠ HEIC/HEIF поддержка не доступна[/yellow]\n\n"
                "Для обработки HEIC файлов установите:\n"
                "  1. sudo apt-get install libheif-dev libffi-dev  (Linux)\n"
                "  2. pip install --upgrade pillow-heif\n\n"
                "HEIC файлы будут [bold]пропущены[/bold] при обработке."
            )

        console.print(
            Panel(
                instructions,
                title="Warning",
                border_style="yellow"
            )
        )

    # Use process-safe stats for both compression and dry-run (both are now multi-process)
    # Create a multiprocessing.Manager for shared locks
    manager = multiprocessing.Manager()
    stats = ProcessSafeStats(manager_lock=manager.Lock())

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
    """Show what would be compressed without actually compressing (multi-process)"""

    # Determine number of worker processes (use CPU cores for CPU-bound tasks)
    num_workers = os.cpu_count() or 1

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
        task = progress.add_task(
            f"Analyzing images... ({num_workers} processes)",
            total=len(files)
        )

        # Use ProcessPoolExecutor for parallel processing (CPU-bound task)
        with concurrent.futures.ProcessPoolExecutor(
            max_workers=num_workers,
            mp_context=multiprocessing.get_context('spawn')
        ) as executor:
            # Submit all tasks
            future_to_file = {
                executor.submit(
                    process_single_file_dry_run,
                    file_info,
                    quality,
                    force
                ): file_info for file_info in files
            }

            # Process results as they complete
            for future in concurrent.futures.as_completed(future_to_file):
                file_info = future_to_file[future]

                try:
                    result_data = future.result()
                    file_info, should_process, skip_reason, test_result = result_data

                    # Add to stats
                    if should_process and test_result:
                        stats.add_result(test_result, skipped=False, reason="")
                    else:
                        stats.add_result(
                            CompressionResult(False, file_info.size, 0),
                            skipped=True,
                            reason=skip_reason,
                        )

                    # Add to table
                    status = (
                        "[green]Will compress[/green]" if should_process else "[red]Skip[/red]"
                    )
                    table.add_row(
                        file_info.name, format_size(file_info.size), status, skip_reason
                    )

                except Exception as e:
                    # Future raised exception (shouldn't happen due to try/except in worker)
                    table.add_row(
                        file_info.name,
                        format_size(file_info.size),
                        "[red]Error[/red]",
                        str(e)
                    )
                    stats.add_result(
                        CompressionResult(False, file_info.size, 0),
                        skipped=True,
                        reason=f"Error: {e}"
                    )

                # Update progress bar
                progress.update(
                    task,
                    advance=1,
                    description=f"Analyzing: {file_info.name[:50]}..."
                )

    console.print(table)


def _run_compression(
    files: List[FileInfo],
    quality: int,
    replace: bool,
    output_dir: Optional[str],
    force: bool,
    stats: CompressionStats,
):
    """Run actual compression with process pool for multi-core support"""

    # Determine number of worker processes (use CPU cores for CPU-bound tasks)
    num_workers = os.cpu_count() or 1

    # Ensure stats is ProcessSafeStats for multi-process operation
    if not isinstance(stats, ProcessSafeStats):
        console.print("[yellow]Warning: Using non-process-safe stats in multi-process mode[/yellow]")
        # Fallback to sequential processing
        num_workers = 1

    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        console=console,
    ) as progress:
        task = progress.add_task(
            f"Compressing images... ({num_workers} processes)",
            total=len(files)
        )

        # Use ProcessPoolExecutor for parallel processing (CPU-bound task)
        with concurrent.futures.ProcessPoolExecutor(
            max_workers=num_workers,
            mp_context=multiprocessing.get_context('spawn')
        ) as executor:
            # Submit all tasks
            future_to_file = {
                executor.submit(
                    process_single_file,
                    file_info,
                    quality,
                    replace,
                    output_dir,
                    force
                ): file_info for file_info in files
            }

            # Process results as they complete
            for future in concurrent.futures.as_completed(future_to_file):
                file_info = future_to_file[future]

                try:
                    result_data = future.result()
                    file_info, result, skipped, skip_reason, error_msg = result_data

                    # Display result
                    if error_msg:
                        console.print(f"  [red]✗[/red] {file_info.name}: {error_msg}")
                        # Add failure to stats
                        stats.add_result(
                            CompressionResult(False, file_info.size, 0, None, error_msg)
                        )
                    elif skipped:
                        console.print(f"  [yellow]⊘[/yellow] {file_info.name}: {skip_reason}")
                        stats.add_result(
                            CompressionResult(False, file_info.size, 0),
                            skipped=True,
                            reason=skip_reason,
                        )
                    elif result and result.success and result.saved_path:
                        saved_percent = result.size_reduction
                        console.print(
                            f"  [green]✓[/green] {file_info.name}: "
                            f"{format_size(result.original_size)} → "
                            f"{format_size(result.compressed_size)} "
                            f"([green]{saved_percent:.1f}%[/green])"
                        )
                        stats.add_result(result)
                    elif result:
                        # Compression failed
                        console.print(f"  [red]✗[/red] {file_info.name}: {result.message}")
                        stats.add_result(result)

                except Exception as e:
                    # Future raised exception (shouldn't happen due to try/except in worker)
                    console.print(f"  [red]✗[/red] {file_info.name}: Process error: {e}")
                    stats.add_result(
                        CompressionResult(False, file_info.size, 0, None, str(e))
                    )

                # Update progress bar
                progress.update(
                    task,
                    advance=1,
                    description=f"Processing: {file_info.name[:50]}..."
                )

    # No need to cleanup .lock files - not used in multiprocessing mode


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

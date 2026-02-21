#!/usr/bin/env python3
"""
Duplicate Code Finder
Находит дублирующиеся блоки кода в Kotlin/Java файлах.

Usage:
    python find_duplicates.py <directory> [--min-lines 5] [--threshold 0.8]
"""

import os
import sys
import re
from pathlib import Path
from collections import defaultdict
from difflib import SequenceMatcher
import argparse


def normalize_code(code: str) -> str:
    """Нормализует код для сравнения: убирает отступы, комментарии, пустые строки."""
    lines = code.split("\n")
    normalized = []

    for line in lines:
        # Убираем комментарии
        line = re.sub(r"//.*$", "", line)
        line = re.sub(r"/\*.*?\*/", "", line)

        # Убираем лишние пробелы
        line = line.strip()

        # Пропускаем пустые строки
        if line:
            normalized.append(line)

    return "\n".join(normalized)


def extract_blocks(content: str, min_lines: int = 5) -> list[tuple[str, int, int]]:
    """Извлекает блоки кода из файла.

    Returns:
        List of (normalized_block, start_line, end_line)
    """
    lines = content.split("\n")
    blocks = []

    # Извлекаем функции
    function_pattern = re.compile(
        r"(?:suspend\s+)?fun\s+\w+\s*\([^)]*\)\s*(?::\s*\w+)?\s*\{", re.MULTILINE
    )

    for match in function_pattern.finditer(content):
        start = content[: match.start()].count("\n") + 1

        # Находим конец функции (подсчёт скобок)
        brace_count = 0
        end_line = start
        in_function = False

        for i, line in enumerate(lines[start - 1 :], start=start):
            brace_count += line.count("{") - line.count("}")
            if "{" in line:
                in_function = True
            if in_function and brace_count == 0:
                end_line = i
                break

        if end_line - start >= min_lines:
            block = "\n".join(lines[start - 1 : end_line])
            normalized = normalize_code(block)
            if normalized:
                blocks.append((normalized, start, end_line))

    return blocks


def similarity_ratio(block1: str, block2: str) -> float:
    """Вычисляет сходство двух блоков кода."""
    return SequenceMatcher(None, block1, block2).ratio()


def find_duplicates_in_file(file_path: Path, min_lines: int = 5) -> list[dict]:
    """Находит дубликаты внутри одного файла."""
    try:
        content = file_path.read_text(encoding="utf-8")
    except Exception as e:
        print(f"Error reading {file_path}: {e}")
        return []

    blocks = extract_blocks(content, min_lines)
    duplicates = []

    for i, (block1, start1, end1) in enumerate(blocks):
        for j, (block2, start2, end2) in enumerate(blocks[i + 1 :], i + 1):
            sim = similarity_ratio(block1, block2)
            if sim > 0.8:
                duplicates.append(
                    {
                        "file": str(file_path),
                        "location1": (start1, end1),
                        "location2": (start2, end2),
                        "similarity": sim,
                    }
                )

    return duplicates


def find_duplicates_across_files(
    files: list[Path], min_lines: int = 5, threshold: float = 0.8
) -> list[dict]:
    """Находит дубликаты между файлами."""
    all_blocks = []

    for file_path in files:
        try:
            content = file_path.read_text(encoding="utf-8")
            blocks = extract_blocks(content, min_lines)
            for block, start, end in blocks:
                all_blocks.append(
                    {"file": str(file_path), "block": block, "start": start, "end": end}
                )
        except Exception as e:
            print(f"Error reading {file_path}: {e}")

    duplicates = []

    for i, item1 in enumerate(all_blocks):
        for item2 in all_blocks[i + 1 :]:
            if item1["file"] == item2["file"]:
                continue  # Пропускаем дубликаты внутри файла

            sim = similarity_ratio(item1["block"], item2["block"])
            if sim > threshold:
                duplicates.append(
                    {
                        "file1": item1["file"],
                        "location1": (item1["start"], item1["end"]),
                        "file2": item2["file"],
                        "location2": (item2["start"], item2["end"]),
                        "similarity": sim,
                    }
                )

    return duplicates


def find_kotlin_files(directory: Path) -> list[Path]:
    """Находит все Kotlin файлы в директории."""
    return list(directory.rglob("*.kt"))


def main():
    parser = argparse.ArgumentParser(description="Find duplicate code blocks")
    parser.add_argument("directory", help="Directory to scan")
    parser.add_argument("--min-lines", type=int, default=5, help="Minimum block size")
    parser.add_argument(
        "--threshold", type=float, default=0.8, help="Similarity threshold"
    )
    parser.add_argument("--output", help="Output file for results")

    args = parser.parse_args()

    directory = Path(args.directory)
    if not directory.exists():
        print(f"Directory not found: {directory}")
        sys.exit(1)

    files = find_kotlin_files(directory)
    print(f"Found {len(files)} Kotlin files")

    # Находим дубликаты между файлами
    print("\nSearching for duplicates across files...")
    duplicates = find_duplicates_across_files(files, args.min_lines, args.threshold)

    if not duplicates:
        print("No duplicates found")
        return

    # Сортируем по сходству
    duplicates.sort(key=lambda x: x["similarity"], reverse=True)

    print(f"\nFound {len(duplicates)} duplicate pairs:\n")

    output_lines = []
    for dup in duplicates:
        line = (
            f"Similarity: {dup['similarity']:.1%}\n"
            f"  File 1: {dup['file1']}:{dup['location1'][0]}-{dup['location1'][1]}\n"
            f"  File 2: {dup['file2']}:{dup['location2'][0]}-{dup['location2'][1]}\n"
        )
        print(line)
        output_lines.append(line)

    if args.output:
        with open(args.output, "w") as f:
            f.write("\n".join(output_lines))
        print(f"\nResults saved to {args.output}")


if __name__ == "__main__":
    main()

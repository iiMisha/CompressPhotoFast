#!/usr/bin/env python3
"""
Unused Code Finder
Находит неиспользуемый код в Kotlin файлах.

Usage:
    python find_unused.py <directory>
"""

import os
import sys
import re
from pathlib import Path
from collections import defaultdict
import argparse


def extract_imports(content: str) -> set[str]:
    """Извлекает все импорты из файла."""
    imports = set()
    for line in content.split("\n"):
        match = re.match(r"import\s+([\w.]+)", line.strip())
        if match:
            imports.add(match.group(1))
    return imports


def extract_used_symbols(content: str) -> set[str]:
    """Извлекает все используемые символы из файла."""
    # Простая эвристика: находим все слова, которые могут быть идентификаторами
    symbols = set()

    # Ключевые слова Kotlin, которые не считаются использованными символами
    keywords = {
        "fun",
        "val",
        "var",
        "class",
        "interface",
        "object",
        "package",
        "import",
        "if",
        "else",
        "when",
        "for",
        "while",
        "do",
        "try",
        "catch",
        "finally",
        "return",
        "break",
        "continue",
        "is",
        "as",
        "in",
        "out",
        "typeof",
        "null",
        "true",
        "false",
        "this",
        "super",
        "it",
        "also",
        "let",
        "run",
        "with",
        "apply",
        "use",
        "takeIf",
        "takeUnless",
        "repeat",
        "require",
        "check",
        "error",
        "TODO",
        "suspend",
        "inline",
        "noinline",
        "crossinline",
        "reified",
        "abstract",
        "final",
        "open",
        "annotation",
        "sealed",
        "data",
        "enum",
        "inner",
        "companion",
        "override",
        "private",
        "protected",
        "public",
        "internal",
        "lateinit",
        "tailrec",
        "operator",
        "infix",
        "const",
        "vararg",
        "dynamic",
        "field",
        "property",
        "delegate",
        "get",
        "set",
        "receiver",
        "param",
        "setparam",
        "where",
        "by",
        "catch",
        "finally",
        "out",
        "in",
    }

    # Находим все слова
    words = re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\b", content)

    for word in words:
        if (
            word not in keywords and not word.isupper()
        ):  # Пропускаем ключевые слова и константы
            symbols.add(word)

    return symbols


def extract_private_functions(content: str) -> list[tuple[str, int]]:
    """Извлекает приватные функции из файла.

    Returns:
        List of (function_name, line_number)
    """
    functions = []
    lines = content.split("\n")

    for i, line in enumerate(lines, 1):
        # Ищем private fun
        match = re.match(r"\s*private\s+(?:suspend\s+)?fun\s+(\w+)", line)
        if match:
            functions.append((match.group(1), i))

    return functions


def extract_private_properties(content: str) -> list[tuple[str, int]]:
    """Извлекает приватные свойства из файла.

    Returns:
        List of (property_name, line_number)
    """
    properties = []
    lines = content.split("\n")

    for i, line in enumerate(lines, 1):
        # Ищем private val/var
        match = re.match(r"\s*private\s+(?:val|var)\s+(\w+)", line)
        if match:
            properties.append((match.group(1), i))

    return properties


def extract_local_variables(content: str) -> list[tuple[str, int]]:
    """Извлекает локальные переменные из файла."""
    variables = []
    lines = content.split("\n")

    for i, line in enumerate(lines, 1):
        # Ищем val/var внутри функций (эвристика: наличие отступа)
        if line.strip() and not line.strip().startswith("//"):
            match = re.match(r"\s+(?:val|var)\s+(\w+)\s*[:=]", line)
            if match:
                variables.append((match.group(1), i))

    return variables


def has_hilt_annotations(content: str) -> bool:
    """Проверяет, есть ли Hilt аннотации в файле."""
    hilt_annotations = ["@HiltViewModel", "@Inject", "@Provides", "@Binds", "@Module"]
    return any(ann in content for ann in hilt_annotations)


def has_android_component(content: str) -> bool:
    """Проверяет, есть ли Android компоненты в файле."""
    components = ["BroadcastReceiver", "Service", "Worker", "Activity"]
    return any(comp in content for comp in components)


def analyze_file(file_path: Path) -> dict:
    """Анализирует один файл на наличие unused кода."""
    try:
        content = file_path.read_text(encoding="utf-8")
    except Exception as e:
        print(f"Error reading {file_path}: {e}")
        return {}

    result = {
        "file": str(file_path),
        "unused_imports": [],
        "unused_private_functions": [],
        "unused_private_properties": [],
        "is_hilt": has_hilt_annotations(content),
        "is_android_component": has_android_component(content),
    }

    # Анализ unused imports (упрощённый - проверяем использование последней части импорта)
    imports = extract_imports(content)
    used_symbols = extract_used_symbols(content)

    for imp in imports:
        # Извлекаем имя класса/объекта из импорта
        symbol = imp.split(".")[-1]
        if symbol not in used_symbols:
            result["unused_imports"].append(imp)

    # Анализ unused private functions (упрощённый)
    private_functions = extract_private_functions(content)
    for func_name, line in private_functions:
        # Ищем использование функции (исключая её объявление)
        pattern = rf"\b{func_name}\s*\("
        uses = re.findall(pattern, content)
        if len(uses) <= 1:  # Только объявление
            result["unused_private_functions"].append((func_name, line))

    # Анализ unused private properties
    private_properties = extract_private_properties(content)
    for prop_name, line in private_properties:
        # Ищем использование свойства
        pattern = rf"\b{prop_name}\b"
        uses = re.findall(pattern, content)
        if len(uses) <= 1:  # Только объявление
            result["unused_private_properties"].append((prop_name, line))

    return result


def find_kotlin_files(directory: Path) -> list[Path]:
    """Находит все Kotlin файлы в директории."""
    return list(directory.rglob("*.kt"))


def main():
    parser = argparse.ArgumentParser(description="Find unused code")
    parser.add_argument("directory", help="Directory to scan")
    parser.add_argument("--output", help="Output file for results")

    args = parser.parse_args()

    directory = Path(args.directory)
    if not directory.exists():
        print(f"Directory not found: {directory}")
        sys.exit(1)

    files = find_kotlin_files(directory)
    print(f"Found {len(files)} Kotlin files\n")

    results = []

    for file_path in files:
        result = analyze_file(file_path)
        if result:
            results.append(result)

    # Выводим результаты
    output_lines = []

    # Unused imports
    print("=== UNUSED IMPORTS ===")
    has_unused = False
    for result in results:
        if result["unused_imports"]:
            has_unused = True
            for imp in result["unused_imports"]:
                line = f"  {result['file']}: import {imp}"
                print(line)
                output_lines.append(line)
    if not has_unused:
        print("  No unused imports found")

    # Unused private functions
    print("\n=== UNUSED PRIVATE FUNCTIONS ===")
    has_unused = False
    for result in results:
        # Пропускаем Hilt и Android компоненты
        if result["is_hilt"] or result["is_android_component"]:
            continue

        if result["unused_private_functions"]:
            has_unused = True
            for func_name, line_num in result["unused_private_functions"]:
                line = f"  {result['file']}:{line_num} - {func_name}()"
                print(line)
                output_lines.append(line)
    if not has_unused:
        print("  No unused private functions found")

    # Unused private properties
    print("\n=== UNUSED PRIVATE PROPERTIES ===")
    has_unused = False
    for result in results:
        # Пропускаем Hilt и Android компоненты
        if result["is_hilt"] or result["is_android_component"]:
            continue

        if result["unused_private_properties"]:
            has_unused = True
            for prop_name, line_num in result["unused_private_properties"]:
                line = f"  {result['file']}:{line_num} - {prop_name}"
                print(line)
                output_lines.append(line)
    if not has_unused:
        print("  No unused private properties found")

    if args.output:
        with open(args.output, "w") as f:
            f.write("\n".join(output_lines))
        print(f"\nResults saved to {args.output}")


if __name__ == "__main__":
    main()

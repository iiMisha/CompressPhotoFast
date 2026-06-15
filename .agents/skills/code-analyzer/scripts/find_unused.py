#!/usr/bin/env python3
"""
Unused Code Finder
Находит неиспользуемый код в Kotlin файлах.

Usage:
    python find_unused.py <directory> [--format text|json] [--output FILE]
                                      [--allowlist FILE] [--no-allowlist]

Учитывает:
- import ... as (алиасы)
- allowlist (lifecycle, DI, ALL-CAPS импорты R/UUID/URI и т.п.)
- точечный фильтр Hilt/override/Android-компонентов (по декларации, не всему файлу)
- исключение build/, .gradle/, generated/, .idea/
- идентификаторы из комментариев/строковых литералов НЕ считаются использованием

Выход:
- text (по умолчанию): читаемый отчёт
- json: машиночитаемый список кандидатов с confidence
"""

import argparse
import json
import re
import sys
from pathlib import Path

# Shared scaffolding (см. _common.py) — единый источник правды для обоих скриптов
try:
    from _common import find_allowlist, find_kotlin_files, strip_comments_and_strings
except ImportError:
    # Fallback при запуске из другой директории
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from _common import find_allowlist, find_kotlin_files, strip_comments_and_strings


# === Ключевые слова Kotlin ===
KOTLIN_KEYWORDS = {
    "fun", "val", "var", "class", "interface", "object", "package", "import",
    "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
    "return", "break", "continue", "is", "as", "in", "out", "typeof",
    "null", "true", "false", "this", "super", "it",
    "also", "let", "run", "with", "apply", "use", "takeIf", "takeUnless",
    "repeat", "require", "check", "error", "TODO",
    "suspend", "inline", "noinline", "crossinline", "reified",
    "abstract", "final", "open", "annotation", "sealed", "data", "enum",
    "inner", "companion", "override", "private", "protected", "public",
    "internal", "lateinit", "tailrec", "operator", "infix", "const",
    "vararg", "dynamic", "field", "property", "delegate", "get", "set",
    "receiver", "param", "setparam", "where", "by", "init",
    "value", "constructor",
}

# Аннотации/признаки «НЕ unused» на уровне декларации
KEEP_DECLARATION_ANNOTATIONS = frozenset({
    "@Inject", "@Provides", "@Binds", "@HiltViewModel", "@HiltAndroidApp",
    "@AndroidEntryPoint", "@EntryPoint", "@Module", "@Composable", "@Preview",
    "@Test", "@Before", "@After", "@BeforeClass", "@AfterClass",
    "@JvmStatic", "@JvmField", "@JvmOverloads",
})

_OVERRIDE_RE = re.compile(r"\boverride\b")


def normalize_line(line: str) -> str:
    """Убирает // комментарии и лишние пробелы."""
    line = re.sub(r"//.*$", "", line)
    return line.strip()


def extract_imports(content: str) -> list[tuple[str, str]]:
    """Извлекает импорты. Учитывает 'import x as y'.

    Returns:
        List of (full_import_path, used_symbol)
        used_symbol = alias если есть, иначе последний сегмент.
    """
    imports = []
    for line in content.split("\n"):
        stripped = line.strip()
        m = re.match(r"import\s+([\w.]+)(?:\s+as\s+(\w+))?", stripped)
        if m:
            full, alias = m.group(1), m.group(2)
            used = alias if alias else full.split(".")[-1]
            imports.append((full, used))
    return imports


def extract_used_symbols(content: str) -> set[str]:
    """Собирает идентификаторы из КОДА, исключая комментарии, строковые
    литералы и строки package/import. R, UUID, URI — легитимные символы и
    НЕ фильтруются по регистру.

    import/package-строки исключаются, иначе сама декларация импорта
    маскировала бы неиспользуемый импорт (L5-фикс).
    """
    code_only = strip_comments_and_strings(content)
    code_lines = []
    for line in code_only.split("\n"):
        stripped = line.strip()
        if stripped.startswith("import ") or stripped.startswith("package "):
            continue
        code_lines.append(line)
    code_only = "\n".join(code_lines)

    symbols = set()
    for word in re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\b", code_only):
        if word in KOTLIN_KEYWORDS:
            continue
        symbols.add(word)
    return symbols


def extract_private_declarations(content: str) -> list[dict]:
    """Извлекает приватные функции и свойства (включая generics и все модификаторы).

    Returns:
        List of dicts: {kind, name, line, is_override, annotations}
    """
    decls = []
    lines = content.split("\n")
    annotations_buffer = []

    # `private` + произвольная комбинация модификаторов (с пробелами между ними)
    # + опциональный generic-список `<...>` перед именем fun
    func_re = re.compile(
        r"private\s+(?:(?:suspend|inline|operator|override|infix|tailrec|"
        r"external|reified)\s+)*fun\s+(?:<[^>]*>\s+)?(\w+)"
    )
    prop_re = re.compile(
        r"private\s+(?:(?:const|lateinit)\s+)*(?:val|var)\s+(\w+)"
    )

    for i, raw_line in enumerate(lines, 1):
        line = normalize_line(raw_line)
        line_annotations = re.findall(r"@\w+(?:\.\w+)?", line)

        if not line and not raw_line.strip():
            annotations_buffer = []
            continue

        m_func = func_re.search(line)
        if m_func:
            decls.append({
                "kind": "function",
                "name": m_func.group(1),
                "line": i,
                "is_override": _OVERRIDE_RE.search(line) is not None,
                "annotations": list(annotations_buffer) + line_annotations,
            })
            annotations_buffer = []
            continue

        m_prop = prop_re.search(line)
        if m_prop:
            decls.append({
                "kind": "property",
                "name": m_prop.group(1),
                "line": i,
                "is_override": _OVERRIDE_RE.search(line) is not None,
                "annotations": list(annotations_buffer) + line_annotations,
            })
            annotations_buffer = []
            continue

        # Накапливаем аннотации (отдельная строка с единственной аннотацией)
        if line.startswith("@") and len(line_annotations) == 1 and "(" not in line:
            annotations_buffer.extend(line_annotations)
        else:
            if line:
                annotations_buffer = []

    return decls


def confidence_for_decl(decl: dict) -> str:
    """Определяет уровень уверенности.

    Returns: 'Low' (не трогать), 'High' (можно удалять).

    Проверяет, входит ли хотя бы одна аннотация декларации в keep-список.
    """
    if decl["is_override"]:
        return "Low"
    decl_anns = decl.get("annotations", [])
    if any(ann in KEEP_DECLARATION_ANNOTATIONS for ann in decl_anns):
        return "Low"
    return "High"


def analyze_file(file_path: Path, allowlist: set[str]) -> dict:
    """Анализирует файл на unused код."""
    try:
        content = file_path.read_text(encoding="utf-8")
    except Exception as e:
        print(f"Error reading {file_path}: {e}", file=sys.stderr)
        return {}

    result = {
        "file": str(file_path),
        "unused_imports": [],
        "unused_functions": [],
        "unused_properties": [],
    }

    # Контент без комментариев/строк — для подсчёта использований (L5-фикс)
    code_only = strip_comments_and_strings(content)
    used_symbols = extract_used_symbols(content)

    # === Unused imports ===
    for full, used_symbol in extract_imports(content):
        if used_symbol in allowlist:
            continue
        if used_symbol not in used_symbols:
            result["unused_imports"].append({
                "import": full,
                "symbol": used_symbol,
                "confidence": "High",
            })

    # === Unused private declarations ===
    for decl in extract_private_declarations(content):
        name = decl["name"]
        if name in allowlist:
            continue

        # Подсчёт использований по code_only (без комментариев/строк).
        # Для функций: name( ; для свойств: name как слово.
        if decl["kind"] == "function":
            usage_pattern = rf"\b{re.escape(name)}\s*\("
        else:
            usage_pattern = rf"\b{re.escape(name)}\b"

        uses = len(re.findall(usage_pattern, code_only))

        if uses <= 1:
            confidence = confidence_for_decl(decl)
            if confidence == "Low":
                continue
            entry = {"name": name, "line": decl["line"], "confidence": confidence}
            if decl["kind"] == "function":
                result["unused_functions"].append(entry)
            else:
                result["unused_properties"].append(entry)

    return result


CONF_ORDER = {"High": 0, "Medium": 1, "Low": 2}


def render_text(results: list[dict]) -> str:
    lines = []
    total_imports = total_funcs = total_props = 0

    lines.append("=== UNUSED IMPORTS ===")
    found = False
    for r in results:
        for item in r["unused_imports"]:
            found = True
            total_imports += 1
            lines.append(
                f"  {r['file']}: import {item['import']}  [{item['confidence']}]"
            )
    if not found:
        lines.append("  No unused imports found")

    lines.append("")
    lines.append("=== UNUSED PRIVATE FUNCTIONS ===")
    found = False
    for r in results:
        for item in sorted(r["unused_functions"],
                           key=lambda x: CONF_ORDER.get(x["confidence"], 3)):
            found = True
            total_funcs += 1
            lines.append(
                f"  {r['file']}:{item['line']} - {item['name']}()  [{item['confidence']}]"
            )
    if not found:
        lines.append("  No unused private functions found")

    lines.append("")
    lines.append("=== UNUSED PRIVATE PROPERTIES ===")
    found = False
    for r in results:
        for item in sorted(r["unused_properties"],
                           key=lambda x: CONF_ORDER.get(x["confidence"], 3)):
            found = True
            total_props += 1
            lines.append(
                f"  {r['file']}:{item['line']} - {item['name']}  [{item['confidence']}]"
            )
    if not found:
        lines.append("  No unused private properties found")

    lines.append("")
    lines.append(
        f"Summary: {total_imports} unused imports, "
        f"{total_funcs} unused functions, "
        f"{total_props} unused properties"
    )
    return "\n".join(lines)


def render_json(results: list[dict]) -> str:
    summary = {
        "unused_imports": sum(len(r["unused_imports"]) for r in results),
        "unused_functions": sum(len(r["unused_functions"]) for r in results),
        "unused_properties": sum(len(r["unused_properties"]) for r in results),
    }
    return json.dumps({"results": results, "summary": summary}, indent=2,
                      ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser(description="Find unused Kotlin code")
    parser.add_argument("directory", help="Directory to scan")
    parser.add_argument("--output", help="Output file for results")
    parser.add_argument("--format", choices=["text", "json"], default="text")
    parser.add_argument("--allowlist", help="Path to allowlist file")
    parser.add_argument("--no-allowlist", action="store_true",
                        help="Disable allowlist loading")
    args = parser.parse_args()

    directory = Path(args.directory)
    if not directory.exists():
        print(f"Directory not found: {directory}", file=sys.stderr)
        sys.exit(1)

    allowlist = set() if args.no_allowlist else find_allowlist(args.allowlist)
    files = find_kotlin_files(directory)
    print(f"Found {len(files)} Kotlin files (allowlist: {len(allowlist)} tokens)\n",
          file=sys.stderr)

    results = [r for r in (analyze_file(f, allowlist) for f in files) if r]

    output = render_json(results) if args.format == "json" else render_text(results)
    print(output)
    if args.output:
        Path(args.output).write_text(output, encoding="utf-8")
        print(f"\nResults saved to {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()

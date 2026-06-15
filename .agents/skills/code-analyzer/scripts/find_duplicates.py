#!/usr/bin/env python3
"""
Duplicate Code Finder
Находит дублирующиеся блоки кода в Kotlin/Java файлах.

Использует два подхода:
  1) Function-level — сравнение полных функций (SequenceMatcher)
  2) Sliding-window — скользящее окно по нормализованным строкам (token-bucket),
     детектирует произвольные дублирующиеся блоки внутри функций/методов.

Usage:
    python find_duplicates.py <directory> [--min-lines 6] [--threshold 0.85]
        [--format text|json] [--output FILE] [--allowlist FILE] [--no-allowlist]
        [--no-intrafile] [--no-crossfile]
"""

import argparse
import hashlib
import json
import re
import sys
from collections import defaultdict
from difflib import SequenceMatcher
from pathlib import Path

# Shared scaffolding (см. _common.py) — единый источник правды для обоих скриптов
try:
    from _common import (
        find_allowlist, find_kotlin_files, strip_comments_and_strings,
        clean_line_for_braces,
    )
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from _common import (
        find_allowlist, find_kotlin_files, strip_comments_and_strings,
        clean_line_for_braces,
    )


# Идиомы/паттерны, которые НЕ считаем значимыми дубликатами.
# Кортеж нормализованных строк — окно считается идиомой, если оно содержит
# все строки из какого-либо кортежа.
IDIOM_PATTERNS = (
    ("cursor?.use{", "if(it.moveToFirst()){"),
    ("try{", "}catch(e:IOException){", "returnnull"),
)


def normalize_code(code: str) -> str:
    """Нормализация: убрать комментарии, лишние пробелы, пустые строки.
    Агрессивная нормализация: 'foo ( x )' и 'foo(x)' совпадают.
    """
    lines = []
    for line in code.split("\n"):
        line = re.sub(r"//.*$", "", line)
        line = re.sub(r"/\*.*?\*/", "", line)
        line = line.strip()
        if line:
            line = re.sub(r"\s+", "", line)
            lines.append(line)
    return "\n".join(lines)


def extract_function_range(lines: list[str], start_idx: int) -> tuple[int, int] | None:
    """Для строки-начала fun/init возвращает (start_idx, end_idx) по подсчёту
    скобок. Перед подсчётом удаляет комментарии и строковые/символьные литералы,
    чтобы скобки внутри строк/сырых строк не портили depth (L6-фикс).

    Использует split по тройным кавычкам для корректного отделения кода от
    raw strings как на одной линии, так и через несколько строк.
    """
    depth = 0
    in_func = False
    in_raw_string = False  # внутри многострочного `"""..."""`

    for i in range(start_idx, len(lines)):
        line = lines[i]
        parts = line.split('"""')
        triple_count = len(parts) - 1

        if in_raw_string:
            # Мы внутри multiline raw string — ждём закрывающего `"""`
            if triple_count >= 1:
                # Закрытие на этой линии. Код после закрытия — части с индексами
                # 1, 3, 5... но мы взяли split по всем `"""`, поэтому после
                # первого закрытия снова в "code" режиме.
                in_raw_string = False
                # parts[0] был внутри raw, parts[1] — код после закрытия,
                # parts[2] — следующий raw (если есть), и т.д.
                # Чётные индексы — внутри raw, нечётные — код.
                code_parts = parts[1::2]  # 1, 3, 5, ...
                if triple_count % 2 == 0:
                    # Закрыли и снова открыли — остались в raw
                    in_raw_string = True
                cleaned = clean_line_for_braces("".join(code_parts))
            else:
                continue  # строка полностью внутри raw string — пропускаем
        else:
            if triple_count == 0:
                cleaned = clean_line_for_braces(line)
            elif triple_count % 2 == 1:
                # Нечётное число `"""` → открыли raw string, не закрыли
                in_raw_string = True
                cleaned = clean_line_for_braces(parts[0])
            else:
                # Чётное число `"""` ≥ 2 → N пар raw strings на одной линии.
                # Чётные индексы parts — код снаружи raw, нечётные — внутри raw.
                code_parts = parts[0::2]
                cleaned = clean_line_for_braces("".join(code_parts))

        opens = cleaned.count("{")
        closes = cleaned.count("}")
        if opens:
            in_func = True
        depth += opens - closes
        if in_func and depth <= 0:
            return (start_idx, i)
    return None


# Regex: произвольное количество модификаторов (с пробелами между ними),
# затем fun|init. Учитывает override suspend fun, public open fun и т.п. (L2-фикс).
_FUN_START_RE = re.compile(
    r"^\s*(?:(?:public|private|protected|internal|open|override|suspend|inline|"
    r"operator|infix|final|abstract)\s+)*(?:fun|init)\b"
)


def extract_functions(content: str) -> list[dict]:
    """Извлекает целые функции/методы/init-блоки (включая multi-modifier).

    Returns: list of {name, start_line, end_line, body, normalized}
    """
    lines = content.split("\n")
    functions = []
    i = 0
    while i < len(lines):
        if _FUN_START_RE.match(lines[i]):
            rng = extract_function_range(lines, i)
            if rng:
                start, end = rng
                body = "\n".join(lines[start:end + 1])
                functions.append({
                    "name": _extract_fun_name(lines[i]),
                    "start_line": start + 1,
                    "end_line": end + 1,
                    "body": body,
                    "normalized": normalize_code(body),
                })
                i = end + 1
                continue
        i += 1
    return functions


def _extract_fun_name(line: str) -> str:
    m = re.search(r"\bfun\s+(?:<[^>]*>\s+)?(\w+)", line)
    return m.group(1) if m else "(anonymous/init)"


def sliding_windows(lines: list[str], min_lines: int) -> list[dict]:
    """Скользящее окно по нормализованным строкам.

    Returns: list of {fingerprint, raw_start, raw_end, normalized_text}
    """
    norm_lines = []
    for idx, line in enumerate(lines):
        cleaned = re.sub(r"//.*$", "", line)
        cleaned = re.sub(r"/\*.*?\*/", "", cleaned).strip()
        if not cleaned:
            continue
        norm_lines.append((idx, re.sub(r"\s+", "", cleaned)))

    windows = []
    for i in range(len(norm_lines) - min_lines + 1):
        chunk = norm_lines[i:i + min_lines]
        raw_start = chunk[0][0]
        raw_end = chunk[-1][0]
        text = "\n".join(c[1] for c in chunk)
        fp = hashlib.md5(text.encode()).hexdigest()
        windows.append({
            "fingerprint": fp,
            "normalized_text": text,
            "raw_start": raw_start + 1,  # 1-indexed
            "raw_end": raw_end + 1,
        })
    return windows


def is_idiom(window_text: str) -> bool:
    """Фильтр стандартных идиом: блоки с import/package/annotations и окна,
    подпадающие под IDIOM_PATTERNS. Реально используется — без dead-маркеров.
    """
    if not window_text:
        return True
    # Короткие boilerplate-окна: imports, package, последовательность аннотаций
    if any(window_text.startswith(prefix) for prefix in ("import", "package")):
        return True
    if all(line.startswith("@") for line in window_text.split("\n")):
        return True
    # Полное вхождение идиомы как подстроки (нормализованные строки без пробелов)
    for pattern in IDIOM_PATTERNS:
        joined = "".join(pattern)
        if joined in window_text.replace("\n", ""):
            return True
    return False


def classify_similarity(sim: float) -> str:
    if sim >= 0.9:
        return "High"
    if sim >= 0.7:
        return "Medium"
    return "Low"


def find_function_duplicates(
    files: list[Path], threshold: float, intra: bool, cross: bool
) -> list[dict]:
    """Сравнение целых функций (function-level).

    Дешёвый pre-filter по длине перед SequenceMatcher: ratio() ограничен сверху
    значением min(la, lb)/max(la, lb), поэтому пары с заведомо низким потолком
    пропускаются без вызова SequenceMatcher (P1-фикс).
    """
    file_funcs = []
    for fp in files:
        try:
            content = fp.read_text(encoding="utf-8")
            for fn in extract_functions(content):
                file_funcs.append({"file": str(fp), **fn})
        except Exception as e:
            print(f"Error reading {fp}: {e}", file=sys.stderr)

    duplicates = []
    for i, a in enumerate(file_funcs):
        a_norm = a["normalized"]
        la = len(a_norm)
        if not la:
            continue
        for b in file_funcs[i + 1:]:
            if not cross and a["file"] != b["file"]:
                continue
            if not intra and a["file"] == b["file"]:
                continue
            b_norm = b["normalized"]
            lb = len(b_norm)
            if not lb:
                continue
            # Верхняя граница ratio = 2*min/(la+lb). Пропускаем, если она < threshold.
            upper_bound = 2.0 * min(la, lb) / (la + lb)
            if upper_bound < threshold:
                continue
            sim = SequenceMatcher(None, a_norm, b_norm, autojunk=False).ratio()
            if sim >= threshold:
                duplicates.append({
                    "kind": "function",
                    "file1": a["file"], "loc1": [a["start_line"], a["end_line"]],
                    "file2": b["file"], "loc2": [b["start_line"], b["end_line"]],
                    "name1": a["name"], "name2": b["name"],
                    "similarity": round(sim, 3),
                    "level": classify_similarity(sim),
                    "same_file": a["file"] == b["file"],
                })
    return duplicates


def find_window_duplicates(
    files: list[Path], min_lines: int, intra: bool, cross: bool,
) -> list[dict]:
    """Sliding-window дубликаты через хэш-таблицу (O(n))."""
    buckets = defaultdict(list)
    for fp in files:
        try:
            content = fp.read_text(encoding="utf-8")
        except Exception as e:
            print(f"Error reading {fp}: {e}", file=sys.stderr)
            continue
        lines = content.split("\n")
        for w in sliding_windows(lines, min_lines):
            if is_idiom(w["normalized_text"]):
                continue
            buckets[w["fingerprint"]].append({
                "file": str(fp),
                "raw_start": w["raw_start"],
                "raw_end": w["raw_end"],
                "text": w["normalized_text"],
            })

    duplicates = []
    seen_pairs = set()
    for occs in buckets.values():
        if len(occs) < 2:
            continue
        for i in range(len(occs)):
            for j in range(i + 1, len(occs)):
                a, b = occs[i], occs[j]
                if not cross and a["file"] != b["file"]:
                    continue
                if not intra and a["file"] == b["file"]:
                    continue
                pair_key = (a["file"], a["raw_start"], b["file"], b["raw_start"])
                if pair_key in seen_pairs:
                    continue
                seen_pairs.add(pair_key)
                duplicates.append({
                    "kind": "window",
                    "file1": a["file"],
                    "loc1": [a["raw_start"], a["raw_end"]],
                    "file2": b["file"],
                    "loc2": [b["raw_start"], b["raw_end"]],
                    "name1": "(block)",
                    "name2": "(block)",
                    "similarity": 1.0,
                    "level": "High",
                    "same_file": a["file"] == b["file"],
                    "preview": a["text"][:200],
                })
    return duplicates


def dedupe_overlapping(dups: list[dict]) -> list[dict]:
    """Убираем пересекающиеся window-дубликаты в одном файле — оставляем один."""
    result = []
    occupied = defaultdict(list)
    for d in sorted(dups, key=lambda x: (x["kind"] != "window",
                                         x["file1"], x["loc1"][0])):
        if d["kind"] != "window":
            result.append(d)
            continue
        f1, s1, e1 = d["file1"], d["loc1"][0], d["loc1"][1]
        f2, s2, e2 = d["file2"], d["loc2"][0], d["loc2"][1]
        overlap1 = any(not (e1 < os or s1 > oe) for os, oe in occupied[f1])
        overlap2 = any(not (e2 < os or s2 > oe) for os, oe in occupied[f2])
        if overlap1 or overlap2:
            continue
        occupied[f1].append((s1, e1))
        occupied[f2].append((s2, e2))
        result.append(d)
    return result


def render_text(dups: list[dict]) -> str:
    if not dups:
        return "No duplicates found\nSummary: 0 duplicates"
    dups_sorted = sorted(dups, key=lambda x: x["similarity"], reverse=True)
    lines = [f"Found {len(dups_sorted)} duplicate pairs:\n"]
    for d in dups_sorted:
        loc = "intra-file" if d["same_file"] else "cross-file"
        lines.append(f"[{d['level']}] {d['similarity']:.1%} ({d['kind']}, {loc})")
        lines.append(
            f"  {d['name1']}: {d['file1']}:{d['loc1'][0]}-{d['loc1'][1]}"
        )
        lines.append(
            f"  {d['name2']}: {d['file2']}:{d['loc2'][0]}-{d['loc2'][1]}"
        )
        if d["kind"] == "window" and d.get("preview"):
            lines.append(f"  preview: {d['preview']}")
        lines.append("")
    lines.append(f"Summary: {len(dups_sorted)} duplicates")
    return "\n".join(lines)


def render_json(dups: list[dict]) -> str:
    summary = {
        "total": len(dups),
        "high": sum(1 for d in dups if d["level"] == "High"),
        "medium": sum(1 for d in dups if d["level"] == "Medium"),
        "low": sum(1 for d in dups if d["level"] == "Low"),
        "function_level": sum(1 for d in dups if d["kind"] == "function"),
        "window_level": sum(1 for d in dups if d["kind"] == "window"),
    }
    return json.dumps({"duplicates": dups, "summary": summary},
                      indent=2, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser(description="Find duplicate code blocks")
    parser.add_argument("directory", help="Directory to scan")
    parser.add_argument("--min-lines", type=int, default=6,
                        help="Sliding window size")
    parser.add_argument("--threshold", type=float, default=0.85,
                        help="Function-level similarity threshold")
    parser.add_argument("--format", choices=["text", "json"], default="text")
    parser.add_argument("--output", help="Output file for results")
    parser.add_argument("--allowlist", help="Path to allowlist file")
    parser.add_argument("--no-allowlist", action="store_true",
                        help="Disable allowlist (reserved for future use)")
    parser.add_argument("--no-intrafile", action="store_true",
                        help="Skip intra-file duplicates")
    parser.add_argument("--no-crossfile", action="store_true",
                        help="Skip cross-file duplicates")
    args = parser.parse_args()

    directory = Path(args.directory)
    if not directory.exists():
        print(f"Directory not found: {directory}", file=sys.stderr)
        sys.exit(1)

    files = find_kotlin_files(directory)
    print(f"Found {len(files)} Kotlin files", file=sys.stderr)

    intra = not args.no_intrafile
    cross = not args.no_crossfile
    # Allowlist зарезервирован для будущих паттерн-исключений; пока не применяется.
    _ = find_allowlist(args.allowlist) if not args.no_allowlist else set()

    print("Analyzing function-level duplicates...", file=sys.stderr)
    fn_dups = find_function_duplicates(files, args.threshold, intra, cross)

    print("Analyzing sliding-window duplicates...", file=sys.stderr)
    win_dups = find_window_duplicates(files, args.min_lines, intra, cross)
    win_dups = dedupe_overlapping(win_dups)

    all_dups = fn_dups + win_dups

    output = render_json(all_dups) if args.format == "json" else render_text(all_dups)
    print(output)
    if args.output:
        Path(args.output).write_text(output, encoding="utf-8")
        print(f"\nResults saved to {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()

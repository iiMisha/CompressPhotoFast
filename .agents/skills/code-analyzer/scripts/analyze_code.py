#!/usr/bin/env python3
"""
Code Analyzer - Анализ Kotlin/Java кода для поиска проблем
"""

import os
import re
import argparse
from pathlib import Path
from typing import List, Dict, Tuple
from collections import defaultdict


class CodeAnalyzer:
    def __init__(self, root_path: str, verbose: bool = False):
        self.root_path = Path(root_path)
        self.verbose = verbose
        self.issues = defaultdict(list)
        self.functions = []

    def find_kotlin_files(self) -> List[Path]:
        return list(self.root_path.rglob("*.kt"))

    def analyze_file(self, file_path: Path) -> None:
        try:
            content = file_path.read_text(encoding="utf-8")
        except:
            return

        rel_path = file_path.relative_to(self.root_path)

        self._check_long_functions(content, rel_path)
        self._check_deep_nesting(content, rel_path)
        self._check_magic_numbers(content, rel_path)
        self._check_empty_catch(content, rel_path)
        self._check_todos(content, rel_path)
        self._check_long_parameter_list(content, rel_path)
        self._check_unused_imports_placeholder(content, rel_path)
        self._extract_functions(content, rel_path)
        self._check_duplicate_code(rel_path)

    def _add_issue(
        self,
        category: str,
        file_path,
        line: int,
        message: str,
        severity: str = "MEDIUM",
    ):
        self.issues[category].append(
            {
                "file": str(file_path),
                "line": line,
                "message": message,
                "severity": severity,
            }
        )

    def _check_long_functions(self, content: str, file_path) -> None:
        func_pattern = re.compile(
            r"(?:fun|private fun|public fun|internal fun)\s+(\w+)\s*\([^)]*\)\s*(?::\s*\w+)?\s*\{",
            re.MULTILINE,
        )

        for match in func_pattern.finditer(content):
            func_name = match.group(1)
            start = match.end()

            brace_count = 1
            pos = start
            while pos < len(content) and brace_count > 0:
                if content[pos] == "{":
                    brace_count += 1
                elif content[pos] == "}":
                    brace_count -= 1
                pos += 1

            func_length = content[start:pos].count("\n")

            if func_length > 50:
                line_num = content[: match.start()].count("\n") + 1
                self._add_issue(
                    "LONG_FUNCTION",
                    file_path,
                    line_num,
                    f"Функция '{func_name}' содержит {func_length} строк (рекомендуется <50)",
                    "MEDIUM",
                )

    def _check_deep_nesting(self, content: str, file_path) -> None:
        lines = content.split("\n")
        max_nesting = 0
        max_line = 1

        for i, line in enumerate(lines):
            stripped = line.lstrip()
            if stripped and not stripped.startswith(("//", "/*", "*", "*/")):
                indent = len(line) - len(line.lstrip())
                nesting = indent // 4
                if nesting > max_nesting:
                    max_nesting = nesting
                    max_line = i + 1

        if max_nesting > 4:
            self._add_issue(
                "DEEP_NESTING",
                file_path,
                max_line,
                f"Глубина вложенности {max_nesting} (рекомендуется <4)",
                "LOW",
            )

    def _check_magic_numbers(self, content: str, file_path) -> None:
        magic_pattern = re.compile(r"(?<![.\w])([0-9]{3,}|0x[0-9A-Fa-f]{2,})(?![.\w])")

        for match in magic_pattern.finditer(content):
            line_num = content[: match.start()].count("\n") + 1
            self._add_issue(
                "MAGIC_NUMBER",
                file_path,
                line_num,
                f"Magic number: {match.group(1)} - вынеси в константу",
                "LOW",
            )

    def _check_empty_catch(self, content: str, file_path) -> None:
        empty_catch_pattern = re.compile(r"catch\s*\([^)]+\)\s*\{\s*\}")

        for match in empty_catch_pattern.finditer(content):
            line_num = content[: match.start()].count("\n") + 1
            self._add_issue(
                "EMPTY_CATCH",
                file_path,
                line_num,
                "Пустой catch блок - добавь обработку или логирование",
                "HIGH",
            )

    def _check_todos(self, content: str, file_path) -> None:
        todo_pattern = re.compile(r"//\s*(TODO|FIXME|HACK|XXX):?\s*(.*)", re.IGNORECASE)

        for match in todo_pattern.finditer(content):
            line_num = content[: match.start()].count("\n") + 1
            self._add_issue(
                "TODO",
                file_path,
                line_num,
                f"Комментарий: {match.group(2).strip()}",
                "LOW",
            )

    def _check_long_parameter_list(self, content: str, file_path) -> None:
        param_pattern = re.compile(
            r"(?:fun|private fun|public fun)\s+\w+\s*\(([^)]*)\)"
        )

        for match in param_pattern.finditer(content):
            params = match.group(1)
            param_count = len([p for p in params.split(",") if p.strip()])

            if param_count > 5:
                line_num = content[: match.start()].count("\n") + 1
                self._add_issue(
                    "MANY_PARAMS",
                    file_path,
                    line_num,
                    f"Слишком много параметров: {param_count} (рекомендуется <6)",
                    "MEDIUM",
                )

    def _check_unused_imports_placeholder(self, content: str, file_path) -> None:
        import_pattern = re.compile(r"^import\s+([\w.]+);?\s*$", re.MULTILINE)
        imports = import_pattern.findall(content)

        if len(imports) > 20:
            line_num = content.count("\n") // 2
            self._add_issue(
                "MANY_IMPORTS",
                file_path,
                line_num,
                f"Много импортов: {len(imports)} - проверь неиспользуемые",
                "LOW",
            )

    def _extract_functions(self, content: str, file_path) -> None:
        func_pattern = re.compile(
            r"(?:fun|private fun|public fun|internal fun)\s+(\w+)\s*\([^)]*\)(?::\s*\w+)?\s*\{",
            re.MULTILINE,
        )

        for match in func_pattern.finditer(content):
            func_name = match.group(1)
            start = match.end()

            brace_count = 1
            pos = start
            while pos < len(content) and brace_count > 0:
                if content[pos] == "{":
                    brace_count += 1
                elif content[pos] == "}":
                    brace_count -= 1
                pos += 1

            func_body = content[start:pos].strip()
            line_num = content[: match.start()].count("\n") + 1

            self.functions.append(
                {
                    "name": func_name,
                    "file": str(file_path),
                    "line": line_num,
                    "body": re.sub(r"\s+", " ", func_body),
                }
            )

    def _check_duplicate_code(self, file_path) -> None:
        func_by_name = defaultdict(list)
        for func in self.functions:
            func_by_name[func["name"]].append(func)

        for funcs in func_by_name.values():
            if len(funcs) > 1:
                bodies = [f["body"] for f in funcs]
                if len(set(bodies)) < len(bodies):
                    self._add_issue(
                        "DUPLICATE_FUNCTION",
                        file_path,
                        funcs[0]["line"],
                        f"Дублирующаяся функция '{funcs[0]['name']}' найдена в {len(funcs)} местах",
                        "HIGH",
                    )

    def analyze_duplicates(self) -> None:
        for i, f1 in enumerate(self.functions):
            for f2 in self.functions[i + 1 :]:
                if f1["file"] != f2["file"]:
                    sim = self._calculate_similarity(f1["body"], f2["body"])
                    if sim > 0.7:
                        self._add_issue(
                            "DUPLICATE_CODE",
                            f2["file"],
                            f2["line"],
                            f"Похожий код с {Path(f1['file']).name}:{f1['line']} (similarity: {int(sim * 100)}%)",
                            "MEDIUM",
                        )

    def _calculate_similarity(self, s1: str, s2: str) -> float:
        s1_set = set(s1.split())
        s2_set = set(s2.split())

        if not s1_set or not s2_set:
            return 0.0

        intersection = len(s1_set & s2_set)
        union = len(s1_set | s2_set)

        return intersection / union if union > 0 else 0.0

    def run(self) -> Dict:
        print(f"🔍 Анализирую код в: {self.root_path}")

        kotlin_files = self.find_kotlin_files()
        print(f"📁 Найдено {len(kotlin_files)} Kotlin файлов")

        for f in kotlin_files:
            self.analyze_file(f)

        self.analyze_duplicates()

        return self.issues

    def print_report(self) -> None:
        print("\n" + "=" * 60)
        print("📊 ОТЧЁТ АНАЛИЗА КОДА")
        print("=" * 60)

        total = sum(len(v) for v in self.issues.values())
        print(f"\nВсего проблем: {total}\n")

        severity_order = {"HIGH": 0, "MEDIUM": 1, "LOW": 2}

        all_issues = []
        for category, items in self.issues.items():
            for item in items:
                all_issues.append((category, item))

        all_issues.sort(
            key=lambda x: (severity_order.get(x[1]["severity"], 3), x[1]["file"])
        )

        for category, item in all_issues:
            icon = {"HIGH": "🔴", "MEDIUM": "🟡", "LOW": "🟢"}.get(
                item["severity"], "⚪"
            )
            print(f"{icon} [{item['severity']}] {category}")
            print(f"   📄 {item['file']}:{item['line']}")
            print(f"   💬 {item['message']}\n")

        report_file = Path("code_analysis_report.txt")
        with open(report_file, "w", encoding="utf-8") as f:
            f.write("CODE ANALYSIS REPORT\n")
            f.write("=" * 40 + "\n\n")
            for category, item in all_issues:
                f.write(f"[{item['severity']}] {category}\n")
                f.write(f"  {item['file']}:{item['line']}\n")
                f.write(f"  {item['message']}\n\n")

        print(f"📄 Отчёт сохранён в: {report_file}")

        high_count = len(
            [
                i
                for c, items in self.issues.items()
                for i in items
                if i["severity"] == "HIGH"
            ]
        )
        if high_count > 0:
            print(f"\n⚠️  Найдено {high_count} HIGH приоритет проблем!")


def main():
    parser = argparse.ArgumentParser(description="Анализ Kotlin кода")
    parser.add_argument("--path", default=".", help="Путь для анализа")
    parser.add_argument("--verbose", action="store_true", help="Подробный вывод")

    args = parser.parse_args()

    analyzer = CodeAnalyzer(args.path, args.verbose)
    analyzer.run()
    analyzer.print_report()


if __name__ == "__main__":
    main()

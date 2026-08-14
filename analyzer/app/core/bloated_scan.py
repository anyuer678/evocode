"""超大方法/类/文件扫描（确定性，零误报）。

体积过大的方法/类/文件是职责过重的信号：难测试、难复用、变更波及面大。
Sonar 有复杂度维度但无体积；此扫描补充行数维度，与复杂度互补。

- 方法 > 150 行 → MAJOR（巨型方法）
- 类 > 500 行 → MAJOR（上帝类）
- 文件 > 1200 行 → MINOR（巨型文件）

返回与质量 issue 同构的列表（source=BLOATED）。方法/类用花括号深度匹配
（Java/JS/TS/Go/C#/C++/PHP/Kotlin/Ruby），Python 用缩进块（def/class）。
"""

from __future__ import annotations

import re
from pathlib import Path

_METHOD_LIMIT = 150
_CLASS_LIMIT = 500
_FILE_LIMIT = 1200

_PY_DEF = re.compile(r"^\s*(?:async\s+)?def\s+\w+\s*\(")
_PY_CLASS = re.compile(r"^\s*class\s+\w+")
_BRACE_DEF = re.compile(
    r"^\s*(?:(?:public|private|protected|static|final|async|export|abstract|"
    r"override|synchronized|internal|open)\s+)*"
    r"(?:function\s+\w+|[\w$<>,\s]+\s+\w+\s*\()\s*[^)]*\)\s*(?:throws[\s\w,]+)?\{"
)
_BRACE_CLASS = re.compile(
    r"^\s*(?:public|internal|open|final|abstract|sealed|export)?\s*"
    r"class\s+\w+[^{]*\{"
)


def _brace_block_end(lines: list[str], start: int) -> int:
    """花括号块：从含 { 的行开始匹配到深度归零。"""
    depth = 0
    for i in range(start, len(lines)):
        depth += lines[i].count("{") - lines[i].count("}")
        if depth <= 0 and i > start:
            return i
    return len(lines) - 1


def _indent_block_end(lines: list[str], start: int) -> int:
    """Python 缩进块：到同级或更浅缩进结束。"""
    base = len(lines[start]) - len(lines[start].lstrip())
    for i in range(start + 1, len(lines)):
        if not lines[i].strip():
            continue
        ind = len(lines[i]) - len(lines[i].lstrip())
        if ind <= base:
            return i - 1
    return len(lines) - 1


def _scan_file(rel: str, text: str, lang: str) -> list[dict]:
    issues: list[dict] = []
    lines = text.splitlines()
    n = len(lines)

    # 文件级
    if n > _FILE_LIMIT:
        issues.append({
            "ruleKey": "BLOATED-FILE",
            "kind": "SMELL",
            "severity": "MINOR",
            "message": f"文件过长（{n} 行，阈值 {_FILE_LIMIT}）",
            "suggestion": (
                f"将 {n} 行文件按职责拆分（每文件 ≤{_FILE_LIMIT} 行）："
                "按类/函数/配置分组到独立文件。"
            ),
            "line": 1,
        })

    is_py = lang == "python"
    # 方法级
    i = 0
    while i < n:
        line = lines[i]
        if is_py:
            if _PY_DEF.match(line):
                end = _indent_block_end(lines, i)
                size = end - i + 1
                if size > _METHOD_LIMIT:
                    name = line.strip().split("(")[0].split()[-1]
                    issues.append(_method_issue(name, i + 1, size, rel))
                i = max(end + 1, i + 1)
                continue
        else:
            if _BRACE_DEF.match(line):
                end = _brace_block_end(lines, i)
                size = end - i + 1
                if size > _METHOD_LIMIT:
                    name = line.strip().split("(")[0].split()[-1]
                    issues.append(_method_issue(name, i + 1, size, rel))
                i = max(end + 1, i + 1)
                continue
        i += 1

    # 类级
    i = 0
    while i < n:
        line = lines[i]
        if is_py:
            if _PY_CLASS.match(line):
                end = _indent_block_end(lines, i)
                size = end - i + 1
                if size > _CLASS_LIMIT:
                    name = line.strip().split()[1]
                    issues.append(_class_issue(name, i + 1, size, rel))
                i = max(end + 1, i + 1)
                continue
        else:
            if _BRACE_CLASS.match(line):
                end = _brace_block_end(lines, i)
                size = end - i + 1
                if size > _CLASS_LIMIT:
                    m = re.search(r"class\s+(\w+)", line)
                    name = m.group(1) if m else "?"
                    issues.append(_class_issue(name, i + 1, size, rel))
                i = max(end + 1, i + 1)
                continue
        i += 1

    return issues


def _method_issue(name: str, line: int, size: int, rel: str) -> dict:
    return {
        "ruleKey": "BLOATED-METHOD",
        "kind": "SMELL",
        "severity": "MAJOR",
        "message": f"方法 {name} 过长（{size} 行，阈值 {_METHOD_LIMIT}）",
        "suggestion": (
            f"将 {name} 拆分为多个小方法（每个 ≤40 行）：按步骤/分支/职责提取，"
            "降低单方法体积便于测试。"
        ),
        "line": line,
    }


def _class_issue(name: str, line: int, size: int, rel: str) -> dict:
    return {
        "ruleKey": "BLOATED-CLASS",
        "kind": "SMELL",
        "severity": "MAJOR",
        "message": f"类 {name} 过大（{size} 行，阈值 {_CLASS_LIMIT}）",
        "suggestion": (
            f"将 {name} 按职责拆分为多个类（单一职责）："
            "数据/行为/工具方法分离，降低类体积与耦合。"
        ),
        "line": line,
    }


def bloated_scan(
    code_dir: Path,
    skip_dirs: frozenset[str] | None = None,
) -> list[dict]:
    """扫描超大方法/类/文件。返回与质量 issue 同构的 dict 列表。"""
    skip = skip_dirs or frozenset(
        {".git", "node_modules", ".venv", "venv", "dist", "build", ".idea"}
    )
    results: list[dict] = []
    for p in code_dir.rglob("*"):
        if not p.is_file():
            continue
        rel_parts = p.relative_to(code_dir).parts
        if any(part in skip for part in rel_parts):
            continue
        lang = _LANG_OF.get(p.suffix)
        if lang is None:
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        rel = p.relative_to(code_dir).as_posix()
        for issue in _scan_file(rel, text, lang):
            issue["filePath"] = rel
            results.append(issue)
    return results


_LANG_OF = {
    ".java": "java",
    ".py": "python",
    ".js": "js",
    ".ts": "ts",
    ".jsx": "js",
    ".tsx": "ts",
    ".go": "go",
    ".rb": "rb",
    ".php": "php",
    ".kt": "kt",
    ".cs": "cs",
    ".cpp": "cpp",
    ".c": "c",
}

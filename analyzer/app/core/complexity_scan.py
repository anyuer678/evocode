"""认知复杂度扫描（确定性启发式，不依赖 LLM/tree-sitter）。

对每个函数/方法块估算认知复杂度：分支（if/for/while/catch/switch/ternary）+ 嵌套深度。
超过阈值标记为复杂度债务，返回与质量 issue 同构的列表（source=COMPLEXITY）。

复杂度分 = 分支数 + 嵌套惩罚（每层嵌套 +1 递归叠加），与 Sonar 认知复杂度思路一致。
高复杂度函数难以测试与维护，是重构的首要目标。
"""

from __future__ import annotations

import re
from pathlib import Path

_BRANCH_RE = re.compile(
    r"\b(?:if|elif|else if|for|while|catch|switch)\b|\bcase\s|\b(?:&&|\|\||\?)\b"
)
_FUNCTION_START = re.compile(
    # Java/Kotlin/C#/Go/TS：方法签名 {；Python：def/async def；
    r"(\bdef\s+\w+\s*\(|\b(?:public|private|protected|static|final|async|export|"
    r"function|class\s+\w+[^{]*\))\s*\w*\s*\([^)]*\)\s*(?:throws[^{]*)?\{)"
)
_PY_DEF = re.compile(r"^\s*(?:async\s+)?def\s+\w+\s*\(")
# 排除控制流头（for/if/while/switch/catch/do），避免把循环体当函数
_FUNC_LIKE = re.compile(
    r"^\s*(?!(?:for|if|while|switch|catch|do)\b)(?:(?:public|private|protected|"
    r"static|final|async|export)\s+)*(?:function\s+\w+|[\w$<>,\s]+\s+\w+\s*\()"
    r"\s*[^)]*\)\s*(?:throws[\s\w,]+)?\{"
)


def _python_block_end(lines: list[str], start: int) -> int:
    """Python：函数体到下一个同级 def/文件尾。"""
    base_indent = len(lines[start]) - len(lines[start].lstrip())
    for i in range(start + 1, len(lines)):
        if not lines[i].strip():
            continue
        indent = len(lines[i]) - len(lines[i].lstrip())
        if indent <= base_indent and lines[i].strip():
            return i - 1
    return len(lines) - 1


def _braced_block_end(lines: list[str], start: int) -> int:
    """花括号语言：从函数头所在行开始匹配到 }。"""
    depth = 0
    in_block = False
    for i in range(start, len(lines)):
        line = lines[i]
        # 跳过字符串/注释里的括号（粗粒度：只统计普通字符）
        depth += line.count("{") - line.count("}")
        if not in_block and "{" in line:
            in_block = True
        if in_block and depth <= 0 and i > start:
            return i
    return len(lines) - 1


def _complexity_of(lines: list[str], start: int, end: int) -> int:
    """块内复杂度：分支数 + 嵌套惩罚（每层 if/for/while 内的分支加权）。"""
    score = 0
    depth = 0
    for i in range(start, min(end + 1, len(lines))):
        line = lines[i]
        for _ in _BRANCH_RE.findall(line):
            score += 1 + depth
        # 缩进/括号粗估嵌套（仅行首花括号/缩进增量）
        stripped = line.lstrip()
        if stripped.startswith(("if ", "for ", "while ", "catch", "switch", "else ")):
            depth += 1
        elif stripped.startswith(("}", "elif", "else:")):
            depth = max(0, depth - 1)
    return score


def _scan_file(rel: str, text: str, lang: str) -> list[dict]:
    lines = text.splitlines()
    issues: list[dict] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        is_py = lang == "python"
        if (is_py and _PY_DEF.match(line)) or (not is_py and _FUNC_LIKE.match(line)):
            start = i
            end = _python_block_end(lines, i) if is_py else _braced_block_end(lines, i)
            score = _complexity_of(lines, start, end)
            if score >= 12:
                name = line.strip().split("(")[0].split()[-1]
                issues.append({
                    "ruleKey": "COMPLEX-FUNCTION",
                    "kind": "SMELL",
                    "severity": "MAJOR" if score >= 20 else "MINOR",
                    "message": f"函数 {name} 认知复杂度 {score}（阈值 12）",
                    "suggestion": (
                        f"将 {name} 按职责拆分为多个小函数（每个 ≤10 复杂度）："
                        f"提取条件分支为独立方法、用表驱动替代长 if-else、"
                        f"减少嵌套层级（early-return）。"
                    ),
                    "line": start + 1,
                })
            i = end + 1 if end > i else i + 1
        else:
            i += 1
    return issues


def complexity_scan(code_dir: Path, skip_dirs: frozenset[str] | None = None) -> list[dict]:  # noqa: E501
    """扫描高复杂度函数。返回与质量 issue 同构的 dict 列表。"""
    skip = skip_dirs or frozenset({".git", "node_modules", ".venv", "venv", "dist", "build", ".idea"})  # noqa: E501
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

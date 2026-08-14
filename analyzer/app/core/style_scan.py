"""代码风格一致性扫描（确定性，零误报）。

检测全仓范围一致的风格问题：tab/space 混用、行尾空白、BOM 头、行尾 CRLF 混用。
这些不影响运行但破坏 diff 可读性、引发合并冲突，是团队协作的真实痛点。

返回与质量 issue 同构的列表（source=STYLE）。每类只报 1 条摘要（文件+行数），避免刷屏。
"""

from __future__ import annotations

import re
from pathlib import Path

_TRAILING_WS = re.compile(r"[ \t]+$")
_BOM = "\ufeff"


def _scan_file(rel: str, text: str) -> list[dict]:
    issues: list[dict] = []
    lines = text.split("\n")
    trailing_lines: list[int] = []
    mixed_lines: list[int] = []
    crlf_lines: list[int] = []
    has_tab_indent = False
    has_space_indent = False

    for i, line in enumerate(lines, 1):
        content = line.rstrip("\r")
        if _TRAILING_WS.search(content):
            trailing_lines.append(i)
        # 行首缩进同时含 tab 和 space
        stripped = content.lstrip()
        leading = content[: len(content) - len(stripped)]
        if "\t" in leading and " " in leading:
            mixed_lines.append(i)
        if content.startswith("\t"):
            has_tab_indent = True
        elif content.startswith(" "):
            has_space_indent = True
        if line.endswith("\r"):
            crlf_lines.append(i)

    if len(trailing_lines) >= 3:
        issues.append({
            "ruleKey": "TRAILING-WHITESPACE",
            "kind": "SMELL",
            "severity": "MINOR",
            "message": f"行尾存在多余空白（{len(trailing_lines)} 行，如 {trailing_lines[0]}-{trailing_lines[-1]}）",  # noqa: E501
            "suggestion": "清理行尾空白：编辑器启用 trim trailing whitespace；提交前运行格式化。",  # noqa: E501
            "line": trailing_lines[0],
        })
    if len(mixed_lines) >= 1:
        issues.append({
            "ruleKey": "MIXED-INDENT",
            "kind": "SMELL",
            "severity": "MINOR",
            "message": f"同一行混用 tab/space 缩进（{len(mixed_lines)} 行）",
            "suggestion": "统一缩进风格（项目约定 2/4 空格或 tab），配置 editorconfig 并全局格式化。",  # noqa: E501
            "line": mixed_lines[0],
        })
    if has_tab_indent and has_space_indent:
        issues.append({
            "ruleKey": "INCONSISTENT-INDENT",
            "kind": "SMELL",
            "severity": "MINOR",
            "message": "文件内同时使用 tab 与 space 缩进（不一致）",
            "suggestion": "统一为单一缩进风格（推荐 4 空格），用 editorconfig 固定并全局替换。",  # noqa: E501
            "line": 1,
        })
    if len(crlf_lines) >= 3 and len(crlf_lines) < len(lines) - 3:
        issues.append({
            "ruleKey": "MIXED-EOL",
            "kind": "SMELL",
            "severity": "MINOR",
            "message": f"文件行尾 LF/CRLF 混用（{len(crlf_lines)} 行 CRLF）",
            "suggestion": "统一行尾为 LF（.gitattributes 配 text eol=lf），一次性转换并提交。",  # noqa: E501
            "line": crlf_lines[0],
        })
    if text.startswith(_BOM):
        issues.append({
            "ruleKey": "UTF8-BOM",
            "kind": "SMELL",
            "severity": "MINOR",
            "message": "文件含 UTF-8 BOM 头",
            "suggestion": "移除 BOM（保存为 UTF-8 无 BOM），避免脚本/构建工具解析异常。",  # noqa: E501
            "line": 1,
        })
    return issues


def style_scan(
    code_dir: Path,
    skip_dirs: frozenset[str] | None = None,
) -> list[dict]:
    """扫描风格一致性问题。返回与质量 issue 同构的 dict 列表。"""
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
        if p.suffix not in _SOURCE_EXTS:
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        rel = p.relative_to(code_dir).as_posix()
        for issue in _scan_file(rel, text):
            issue["filePath"] = rel
            results.append(issue)
    return results


_SOURCE_EXTS = frozenset({
    ".java", ".py", ".js", ".ts", ".jsx", ".tsx", ".go", ".rb", ".php", ".kt",
    ".cs", ".cpp", ".c", ".h", ".sh", ".sql", ".yml", ".yaml", ".json", ".html", ".css",
})

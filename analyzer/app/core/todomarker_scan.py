"""遗留标记扫描（确定性，零误报）。

检测代码中的 TODO/FIXME/HACK/XXX 注释——它们是未完成工作、已知缺陷、
临时方案的最直接标记，是技术债的源头之一（对应 rule_advice 的 S1135/S1134）。

返回与质量 issue 同构的列表（source=TODO_MARKER）。只统计注释行（// # /* */），
避免把字符串里的标记当注释。每文件最多报告 10 条。
"""

from __future__ import annotations

import re
from pathlib import Path

_TODO_RE = re.compile(r"(?i)(TODO|FIXME|HACK|XXX)\b\s*:?\s*(.{0,80})")
# 行内注释起点：//、#（不在字符串内则粗判）、/*、*
_INLINE_COMMENT = re.compile(r"(//|#|/\*|\*)")
_MAX_PER_FILE = 10

# 标记类型 → 建议
_MARKER_ADVICE = {
    "TODO": "逐项落实 TODO：完成的删除注释，规划中的转 issue 跟踪，避免遗留为长期债务。",  # noqa: E501
    "FIXME": "定位标记对应代码，修复缺陷后删除注释；无法立即修复的登记到技术债。",
    "HACK": "HACK 通常是绕过问题的临时方案，需评估是否引入隐患；尽快用正规实现替换。",
    "XXX": "XXX 表示危险/待处理区域，确认是否仍有问题，处理完删除标记。",
}


def _scan_file(rel: str, text: str) -> list[dict]:
    issues: list[dict] = []
    for i, line in enumerate(text.splitlines(), 1):
        # 定位行内注释起点（// # /* *）；无注释则跳过
        cm = _INLINE_COMMENT.search(line)
        if not cm:
            continue
        comment = line[cm.start() :]
        m = _TODO_RE.search(comment)
        if not m:
            continue
        marker = m.group(1).upper()
        detail = m.group(2).strip()
        message = f"遗留 {marker} 标记"
        if detail:
            message += f"：{detail[:40]}"
        issues.append({
            "ruleKey": f"LEFTOVER-{marker}",
            "kind": "SMELL",
            "severity": "MINOR" if marker == "TODO" else "MAJOR",
            "message": message,
            "suggestion": _MARKER_ADVICE[marker],
            "line": i,
        })
        if len(issues) >= _MAX_PER_FILE:
            break
    return issues


def todomarker_scan(
    code_dir: Path,
    skip_dirs: frozenset[str] | None = None,
) -> list[dict]:
    """扫描遗留标记。返回与质量 issue 同构的 dict 列表。"""
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
    ".cs", ".cpp", ".c", ".h", ".sh", ".sql",
})

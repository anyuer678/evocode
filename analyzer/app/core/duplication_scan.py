"""重复代码检测（确定性启发式，不依赖 LLM/tree-sitter）。

对每个源码文件做行归一化（去空白、去字符串字面量）后，用固定窗口大小滑窗
计算行块哈希；跨文件相同哈希 ≥ 阈值即判定重复。真实痛点：复制粘贴代码导致
改动需多处同步、缺陷传播。

返回与质量 issue 同构的列表（source=DUPLICATION）。归一化避免"仅空白/换行
不同"的误报，同时保留足够的语义相似度。
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path

# 检测窗口：连续行数 ≥ 6 视为可判定的重复块（避免短片段误报）
_WINDOW = 6
_MAX_PER_FILE = 10  # 单文件最多报告条数，避免重复刷屏

_STRING_RE = re.compile(r'["\'][^"\']{0,200}["\']')
_WS_RE = re.compile(r"\s+")


def _normalize(line: str) -> str:
    """行归一化：去字符串字面量、去空白、转小写。"""
    line = _STRING_RE.sub('"S"', line)
    line = _WS_RE.sub("", line)
    return line.lower()


def _windows(text: str) -> dict[str, list[tuple[int, list[str]]]]:
    """返回 {窗口哈希: [(起始行, 窗口行列表)]}。"""
    raw_lines = text.splitlines()
    lines = [_normalize(ln) for ln in raw_lines]
    out: dict[str, list[tuple[int, list[str]]]] = {}
    for i in range(len(lines) - _WINDOW + 1):
        block = lines[i : i + _WINDOW]
        # 跳过空行/纯括号块（结构性噪声）
        if all(not b for b in block):
            continue
        if all(set(b) <= set("{}()[];") for b in block):
            continue
        h = hashlib.md5("\n".join(block).encode("utf-8")).hexdigest()
        out.setdefault(h, []).append((i + 1, raw_lines[i : i + _WINDOW]))
    return out


def _scan_file(rel: str, text: str) -> list[dict]:
    issues: list[dict] = []
    windows = _windows(text)
    for _h, occurrences in windows.items():
        if len(occurrences) < 2:
            continue
        # 审查：跳过重叠窗口（起始行差 < _WINDOW 的相邻窗口是同一块的滑动，非重复）
        cands = sorted(o[0] for o in occurrences)
        non_overlap = [cands[0]]
        for c in cands[1:]:
            if c - non_overlap[-1] >= _WINDOW:
                non_overlap.append(c)
        if len(non_overlap) < 2:
            continue
        first_line = non_overlap[0]
        second_line = non_overlap[1]
        # 报告一次（该文件内首个重复位置），避免刷屏
        issues.append({
            "ruleKey": "DUPLICATED-BLOCK",
            "kind": "SMELL",
            "severity": "MAJOR",
            "message": (
                f"重复代码块：本文件 {first_line}-{first_line + _WINDOW - 1} 与 "
                f"{second_line}-{second_line + _WINDOW - 1} 行内容相同"
            ),
            "suggestion": (
                f"抽取公共逻辑为独立函数/方法（如 #extract {first_line}-{second_line}），"  # noqa: E501
                "两处改为调用；后续修 bug 只需改一处。"
            ),
            "line": first_line,
        })
        if len(issues) >= _MAX_PER_FILE:
            break
    return issues


def duplication_scan(
    code_dir: Path,
    skip_dirs: frozenset[str] | None = None,
) -> list[dict]:
    """扫描重复代码块（跨文件）。返回与质量 issue 同构的 dict 列表。"""
    skip = skip_dirs or frozenset(
        {".git", "node_modules", ".venv", "venv", "dist", "build", ".idea"}
    )
    # 全量归一化窗口 → 全局哈希索引（跨文件去重）
    global_index: dict[str, list[tuple[str, int]]] = {}
    file_texts: list[tuple[str, str]] = []
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
        file_texts.append((rel, text))
        # 同文件重复（_scan_file 内检测），补 filePath
        for issue in _scan_file(rel, text):
            issue["filePath"] = rel
            results.append(issue)
        for h, occs in _windows(text).items():
            for occ_line, _ in occs:
                global_index.setdefault(h, []).append((rel, occ_line))

    seen_pairs: set[tuple[str, str]] = set()
    for _h, occs in global_index.items():
        if len(occs) < 2:
            continue
        # 跨文件重复（同文件内已在 _scan_file 报告）
        files = sorted({f for f, _ in occs})
        if len(files) < 2:
            continue
        key = tuple(files[:2])
        if key in seen_pairs:
            continue
        seen_pairs.add(key)
        f1, l1 = occs[0]
        f2, l2 = next((f, ln) for f, ln in occs if f != f1)
        results.append({
            "ruleKey": "DUPLICATED-BLOCK",
            "kind": "SMELL",
            "severity": "MAJOR",
            "message": (
                f"跨文件重复代码：{f1}:{l1} 与 {f2}:{l2} 内容相同（{_WINDOW} 行）"
            ),
            "suggestion": (
                f"把 {f1}:{l1} 与 {f2}:{l2} 的公共逻辑抽到共享模块（util/service），"
                "两处改为调用，避免修 bug 漏改。"
            ),
            "filePath": f1,
            "line": l1,
        })
        if len(results) >= 50:
            break
    return results


_SOURCE_EXTS = frozenset({
    ".java", ".py", ".js", ".ts", ".jsx", ".tsx", ".go", ".rb", ".php", ".kt",
    ".cs", ".cpp", ".c", ".h", ".sh", ".sql",
})

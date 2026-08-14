"""错误处理反模式扫描（确定性启发式，不依赖 LLM）。

检测吞异常的常见写法：空 catch 块、except 后仅 pass、异常后仅注释。
吞异常会隐藏故障、掩盖依赖问题，是生产事故的主要诱因之一。

返回与质量 issue 同构的列表（source=ERROR_HANDLING）。
"""

from __future__ import annotations

import re
from pathlib import Path

# 空 catch：catch (...) { } 或 catch (...) 后紧跟 }
_EMPTY_CATCH = re.compile(
    r"catch\s*\([^)]*\)\s*(?:\{[\s/]*\}|:\s*\})"
)
# Python：except ...: pass（或仅注释）
_EMPTY_EXCEPT = re.compile(
    r"^\s*except\s*(?:[^:]*?)?\s*:\s*(?:\n\s*(?:pass|#.*))?",
    re.MULTILINE,
)
_PASS_ONLY = re.compile(r"^\s*except\s*[^:]*:\s*\n(?:\s*#.*\n)*\s*pass\s*\n", re.MULTILINE)  # noqa: E501


def _scan_file(rel: str, text: str, lang: str) -> list[dict]:
    issues: list[dict] = []
    lines = text.splitlines()
    if lang == "python":
        for m in _PASS_ONLY.finditer(text):
            line = text[: m.start()].count("\n") + 1
            issues.append({
                "ruleKey": "SWALLOWED-EXCEPTION",
                "kind": "SMELL",
                "severity": "MAJOR",
                "message": "except 后仅 pass，异常被静默吞掉",
                "suggestion": (
                    "记录异常（logger.exception）或给出有意义的错误处理；"
                    "若确需忽略，注释说明原因并只捕获窄范围异常。"
                ),
                "line": line,
            })
        return issues

    # 花括号语言：空 catch 块
    i = 0
    brace_depth = 0
    for i in range(len(lines)):
        line = lines[i]
        stripped = line.strip()
        if "catch" in stripped and "{" in stripped:
            # 同一行 catch (...) { }
            if _EMPTY_CATCH.search(stripped):
                issues.append({
                    "ruleKey": "SWALLOWED-EXCEPTION",
                    "kind": "SMELL",
                    "severity": "MAJOR",
                    "message": "空 catch 块，异常被静默吞掉",
                    "suggestion": (
                        "在 catch 内记录异常（logger.error）并处理恢复逻辑；"
                        "无法处理时重新抛出包装后的异常。"
                    ),
                    "line": i + 1,
                })
            elif stripped.endswith("{"):
                # 多行空 catch：catch (...) {\n  // comment?\n}
                brace_depth = 1
                for j in range(i + 1, len(lines)):
                    inner = lines[j].strip()
                    brace_depth += inner.count("{") - inner.count("}")
                    if brace_depth <= 0:
                        # 块内只有注释/空白 → 空
                        body = lines[i + 1 : j]
                        if all(not b.strip() or b.strip().startswith(("//", "*", "/*", "//")) for b in body):  # noqa: E501
                            issues.append({
                                "ruleKey": "SWALLOWED-EXCEPTION",
                                "kind": "SMELL",
                                "severity": "MAJOR",
                                "message": "空 catch 块，异常被静默吞掉",
                                "suggestion": (
                                    "在 catch 内记录异常（logger.error）并处理恢复逻辑；"  # noqa: E501
                                    "无法处理时重新抛出包装后的异常。"
                                ),
                                "line": i + 1,
                            })
                        break
    return issues


def errorhandling_scan(
    code_dir: Path,
    skip_dirs: frozenset[str] | None = None,
) -> list[dict]:
    """扫描错误处理反模式。返回与质量 issue 同构的 dict 列表。"""
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

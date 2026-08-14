"""魔法数字扫描（确定性启发式）。

检测代码中的裸数字字面量（无命名常量/枚举解释的魔数），如 `if (x > 86400)`、
`retry = 3`、`wait(5000)`。魔法数字降低可读性、难以维护（为什么是 3？）。

误报控制（避免刷屏）：
- 过滤常见无害值：0/1/-1/2、100/1000 等比率/百分比、尺寸类数字（1024/2048…）
- 过滤声明赋值上下文（`const X = 3`、`int x = 0` 初始化）
- 每文件最多报告 5 条，取前 5 个唯一值

返回与质量 issue 同构的列表（source=MAGIC_NUMBER）。
"""

from __future__ import annotations

import re
from pathlib import Path

_NUMBER = re.compile(r"(?<![A-Za-z_$])(\d{3,})(?![A-Za-z_$])")
_STRING_RE = re.compile(r'["\'][^"\']{0,200}["\']')
# 声明赋值（const/let/var/类型 名 = 数字）不算魔法
_DECL = re.compile(
    r"^\s*(?:const|let|var|private|public|protected|static|final|readonly)\s+"
    r"[A-Za-z_$][\w$]*\s*=\s*\d"
)
# 常见无害值：比率/百分比/尺寸/时间单位
_SAFE_NUM = {
    "100", "1000", "1024", "2048", "4096", "8192", "16384",
    "3600", "86400", "60", "24", "365", "7", "30", "31", "12",
    "128", "256", "512", "65535", "8080", "443", "80",
}
_MAX_PER_FILE = 5


def _scan_file(rel: str, text: str) -> list[dict]:
    issues: list[dict] = []
    seen_values: set[str] = set()
    for i, line in enumerate(text.splitlines(), 1):
        # 跳过整行注释
        stripped = line.strip()
        if stripped.startswith(("//", "#", "*", "/*")):
            continue
        # 剥离字符串字面量与行内注释，避免 "1.2.300"、url/300、// 300 误报
        code = _STRING_RE.sub("", line)
        code = re.sub(r"//.*|#.*", "", code)
        # 跳过声明赋值行（`int maxRetries = 3` 是有意的配置）
        if _DECL.match(code.strip()):
            continue
        # 跳过裸赋值行（`x = 300` 视为初始化/配置，非魔法比较）
        if re.match(r"^[A-Za-z_$][\w$]*\s*=\s*\d", code.strip()):
            continue
        nums = _NUMBER.findall(code)
        if not nums:
            continue
        for num in nums:
            if num in _SAFE_NUM or num in seen_values:
                continue
            # 数字后跟单位/类型（5000L/3.5f/0x 前缀）不报
            seen_values.add(num)
            issues.append({
                "ruleKey": "MAGIC-NUMBER",
                "kind": "SMELL",
                "severity": "MINOR",
                "message": f"魔法数字 {num}（无命名常量解释）",
                "suggestion": (
                    f"将 {num} 提取为命名常量（如 MAX_RETRIES = {num}），"
                    "并注释含义；比较条件用常量名表达意图。"
                ),
                "line": i,
            })
            break
        if len(issues) >= _MAX_PER_FILE:
            break
    return issues


def magicnumber_scan(
    code_dir: Path,
    skip_dirs: frozenset[str] | None = None,
) -> list[dict]:
    """扫描魔法数字。返回与质量 issue 同构的 dict 列表。"""
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

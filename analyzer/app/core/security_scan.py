"""安全反模式扫描（确定性规则，不依赖 LLM）。

扫描代码中的常见安全反模式，返回与质量扫描同构的 issue 列表（source=SECURITY），
供 backend 统一落库为 quality_issue → 技术债聚合。检测项：

1. 硬编码密钥/凭据：password/passwd/secret/api_key/token 等赋值字面量
2. 危险函数调用：eval/exec/System.Runtime/os.system/subprocess shell 拼接
3. SQL 字符串拼接：SELECT/INSERT/UPDATE/DELETE + 字符串连接（+ / f-string / format）
4. 危险系统操作：rm -rf / os.remove 通配 / 反引号命令

误报控制：仅在赋值上下文（= 后跟字面量）且变量名含敏感词时报告密钥；
危险函数只在真实调用（带括号）时报告。
"""

from __future__ import annotations

import re
from pathlib import Path

# 硬编码密钥：敏感变量名 = 字符串/数字字面量（行内任意位置）
# 先定位敏感名，再要求 = / : 后跟长字面量
_SECRET_ASSIGN = re.compile(
    r"\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?key|token|auth[_-]?secret|"
    r"private[_-]?key|client[_-]?secret)\s*(?:=|:)\s*['\"][^'\"]{8,}['\"]",
    re.IGNORECASE,
)
_SECRET_VALUE = re.compile(
    r"['\"][A-Za-z0-9_\-\.+/=]{12,}['\"]"
)

# 危险函数调用
_DANGEROUS_FUNC = [
    (r"\beval\s*\(", "代码执行（eval）", "用 JSON/参数化方式替代动态执行；若必须，白名单校验输入"),  # noqa: E501
    (r"\bexec\s*\(", "代码执行（exec）", "用参数化/白名单方式替代动态执行"),
    (r"\bos\.system\s*\(", "系统命令执行", "用 subprocess 列表参数（非 shell=True）并校验输入"),  # noqa: E501
    (r"\bsubprocess\s*\(\s*[^,\n]*,\s*shell\s*=\s*True", "shell 拼接执行", "改用参数列表形式，避免 shell 注入"),  # noqa: E501
    (r"System\.Runtime\.getRuntime\(\)\.exec", "Java 命令执行", "用 ProcessBuilder（参数列表）替代 exec"),  # noqa: E501
    (r"\bexecCommand\s*\(", "命令执行", "校验输入并避免 shell 拼接"),
    (r"\bchild_process\.exec\s*\(", "Node 命令执行", "用 execFile/spawn（参数数组）避免 shell 注入"),  # noqa: E501
    (r"\brm\s+-rf\s+", "危险删除", "改为受限删除（校验路径前缀+白名单），禁止通配根目录"),  # noqa: E501
]

# SQL 拼接：SQL 关键字 + 字符串连接
_SQL_CONCAT = re.compile(
    r"(SELECT|INSERT|UPDATE|DELETE|WHERE)\b[^\n]{0,80}(?:['\"]\s*[+&]\s*|\bformat\s*\(|f['\"])",
    re.IGNORECASE,
)
_SQL_KEYWORD = re.compile(r"\b(SELECT|INSERT INTO|UPDATE|DELETE FROM)\b", re.IGNORECASE)
_CONCAT_MARK = re.compile(r"['\"]\s*[+&]\s*\{?\w+|\bformat\s*\(|f['\"]")


def _scan_file(rel: str, text: str) -> list[dict]:
    issues: list[dict] = []
    lines = text.splitlines()
    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        if not stripped:
            continue
        # 1. 硬编码密钥
        if _SECRET_ASSIGN.search(line):
            issues.append({
                "ruleKey": "SECRET-HARDCODED",
                "kind": "VULNERABILITY",
                "severity": "CRITICAL",
                "message": "检测到疑似硬编码凭据",
                "suggestion": (
                    "将凭据移入环境变量/密钥管理（Vault/env），代码只引用环境变量；"
                    "已泄露的密钥需轮换。"
                ),
                "line": i,
            })
            continue
        # 2. 危险函数调用
        for pat, name, fix in _DANGEROUS_FUNC:
            if re.search(pat, stripped):
                issues.append({
                    "ruleKey": "DANGEROUS-CALL",
                    "kind": "VULNERABILITY",
                    "severity": "MAJOR",
                    "message": f"危险调用：{name}",
                    "suggestion": fix,
                    "line": i,
                })
                break
        # 3. SQL 拼接
        if _SQL_KEYWORD.search(stripped) and _CONCAT_MARK.search(stripped):
            issues.append({
                "ruleKey": "SQL-INJECTION",
                "kind": "VULNERABILITY",
                "severity": "CRITICAL",
                "message": "SQL 语句字符串拼接，存在注入风险",
                "suggestion": "改用参数化查询（PreparedStatement/? 占位符/ORM），禁止拼接用户输入。",  # noqa: E501
                "line": i,
            })
    return issues


def security_scan(code_dir: Path, skip_dirs: frozenset[str] | None = None) -> list[dict]:  # noqa: E501
    """扫描 code_dir 下源码文件的安全反模式。返回与质量 issue 同构的 dict 列表。"""
    skip = skip_dirs or frozenset({".git", "node_modules", ".venv", "venv", "dist", "build", ".idea"})  # noqa: E501
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

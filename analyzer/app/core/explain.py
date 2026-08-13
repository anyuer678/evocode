"""质量 issue 解释（docs/06-API契约.md §5.4，TD-01）。

规则版：按 ruleKey/severity 模板生成确定性解释（无 Key 可用，与 report/doc
降级语义一致）；LLM 可用时用真实上下文增强（explanation/suggestion/codeExample）。
"""

from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger("evocode.analyzer.explain")

# ruleKey → 规则说明模板（Sonar 常见规则；未命中走 severity 兜底）
_RULE_HINTS: dict[str, dict[str, str]] = {
    "java:S112": {
        "name": "Generic exceptions should never be thrown",
        "why": "抛通用异常（Exception/Throwable）会掩盖错误类型，调用方无法"
        "针对性处理，也不利于日志排查。",
    },
    "java:S106": {
        "name": "Standard outputs should not be used directly to log anything",
        "why": "System.out/err 直出日志无级别、无格式、难检索，"
        "应改用日志框架（SLF4J/Logback）。",
    },
    "java:S1172": {
        "name": "Unused method parameters should be removed",
        "why": "未使用的入参会误导调用方并增加维护噪音。",
    },
    "java:S1481": {
        "name": "Unused local variables should be removed",
        "why": "未使用的局部变量是死代码，影响可读性与静态分析。",
    },
    "java:S1135": {
        "name": 'Track uses of "TODO" tags',
        "why": "TODO 是未完成工作的信号，应纳入 backlog 跟踪而不是遗留在代码里。",
    },
    "java:S6541": {
        "name": "Methods should not have too many parameters",
        "why": "参数过多（>4）的接口难以调用与测试，可考虑参数对象或 Builder。",
    },
    "java:S00100": {
        "name": "Method names should comply with a naming convention",
        "why": "方法名应为小驼峰，命名不一致会破坏可读性。",
    },
    "javascript:S3776": {
        "name": "Cognitive Complexity of functions should not be too high",
        "why": "认知复杂度高说明分支/嵌套过多，是重构信号（拆分函数/提取条件）。",
    },
    "javascript:S1125": {
        "name": "Boolean checks should not be inverted",
        "why": "反转的布尔判断（如 !(a != b)）增加理解成本，直接写正逻辑更清晰。",
    },
    "python:S3776": {
        "name": "Cognitive Complexity of functions should not be too high",
        "why": "认知复杂度高说明分支/嵌套过多，是重构信号（拆分函数/提取条件）。",
    },
    "python:S107": {
        "name": "Functions should not have too many parameters",
        "why": "参数过多（>5）的接口难以调用与测试，可考虑参数对象或拆分子函数。",
    },
    "python:S1135": {
        "name": 'Track uses of "TODO" tags',
        "why": "TODO 是未完成工作的信号，应纳入 backlog 跟踪而不是遗留在代码里。",
    },
}

_SEVERITY_HINTS: dict[str, dict[str, str]] = {
    "BLOCKER": {"why": "阻断级问题：存在崩溃/安全/数据一致性风险，应优先修复。"},
    "CRITICAL": {"why": "严重问题：可能导致功能异常或资源泄漏，建议尽快修复。"},
    "MAJOR": {"why": "主要问题：影响可维护性或潜在缺陷，纳入近期修复计划。"},
    "MINOR": {"why": "次要问题：代码风格/可读性优化，低优先级。"},
    "INFO": {"why": "提示信息：非缺陷，供参考。"},
}

_RULE_PREFIX_HINTS: list[tuple[str, str]] = [
    ("java:S", "Java 静态规则"),
    ("javascript:S", "JavaScript 静态规则"),
    ("typescript:S", "TypeScript 静态规则"),
    ("python:S", "Python 静态规则"),
    ("css:S", "CSS 静态规则"),
]

_EXPLAIN_SYSTEM = (
    "你是资深代码审查专家。基于给定的质量规则问题与代码上下文，输出 JSON："
    '{"explanation": "问题说明（中文，结合代码上下文）", '
    '"suggestion": "修复建议（中文，具体可执行）", '
    '"codeExample": "修复示意代码（简短）"}'
)


def explain(
    issue: dict[str, Any], file_snippet: str | None, llm: Any | None = None
) -> dict[str, Any]:
    """解释质量 issue：LLM 可用 → 增强；否则规则版模板。返回
    {explanation, suggestion, codeExample, source}。
    """
    rule_key = str(issue.get("ruleKey") or "")
    severity = str(issue.get("severity") or "MAJOR").upper()
    message = str(issue.get("message") or "")
    file_path = str(issue.get("filePath") or "")
    line = issue.get("line")

    if llm is not None and llm.available():
        try:
            user = _llm_user(issue, file_snippet)
            data = llm.chat_json(_EXPLAIN_SYSTEM, user)
            return {
                "explanation": str(data.get("explanation") or ""),
                "suggestion": str(data.get("suggestion") or ""),
                "codeExample": str(data.get("codeExample") or ""),
                "source": "LLM",
            }
        except Exception as exc:
            logger.warning("explain LLM 失败，规则版降级 ruleKey=%s：%s", rule_key, exc)

    hint = _RULE_HINTS.get(rule_key)
    if hint is None:
        # 未命中规则表 → 按语言前缀 + severity 兜底
        prefix_hint = next(
            (
                label
                for prefix, label in _RULE_PREFIX_HINTS
                if rule_key.startswith(prefix)
            ),
            "静态分析",
        )
        hint = {
            "name": f"{prefix_hint}规则 {rule_key or '（未知）'}",
            "why": _SEVERITY_HINTS.get(severity, _SEVERITY_HINTS["MAJOR"])["why"],
        }

    loc = f"{file_path}" + (f":{line}" if line else "")
    explanation = (
        f"【{hint['name']}】{message or '未提供规则消息'}。"
        f"{hint['why']}" + (f"（位置：{loc}）" if loc else "")
    )
    suggestion = (
        f"参考规则 {rule_key or '—'} 的修复约定；"
        f"若为 {severity} 级，优先安排修复。"
        f"{_SEVERITY_HINTS.get(severity, _SEVERITY_HINTS['MAJOR'])['why']}"
    )
    return {
        "explanation": explanation,
        "suggestion": suggestion,
        "codeExample": "",
        "source": "RULES",
    }


def _llm_user(issue: dict[str, Any], file_snippet: str | None) -> str:
    parts = [
        f"规则问题：ruleKey={issue.get('ruleKey')} severity={issue.get('severity')} "
        f"file={issue.get('filePath')} line={issue.get('line')}",
        f"规则消息：{issue.get('message')}",
    ]
    if file_snippet:
        parts.append(f"代码上下文：\n```\n{file_snippet}\n```")
    return "\n".join(parts)

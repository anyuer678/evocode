"""报告 prompt 模板（契约 §5.2；AD-12 只发结构化摘要，不发文件全文）。

版本常量 REPORT_PROMPT_VERSION 随模板变更递增，随报告落库（C-1）。
"""

from __future__ import annotations

import json

REPORT_PROMPT_VERSION = "report-1.0"

SYSTEM_PROMPT = (
    "你是资深软件架构师与代码质量评审专家，负责为软件项目生成体检报告。"
    "只允许输出一个 JSON 对象，不要输出任何其他文字、Markdown 或解释。"
)

USER_TEMPLATE = """请基于以下项目扫描摘要生成健康报告 JSON。

项目扫描摘要：
{scan_summary}

历史报告摘要（可能为空）：
{history_summary}

规则版基础评分（仅供参考，可在 ±10 分内修正，修正必须在 summary 中说明理由）：
{score_base}

输出 JSON 必须严格符合以下结构（全部使用中文）：
{{
  "healthScore": <0-100 整数>,
  "level": "<EXCELLENT|GOOD|FAIR|POOR>",
  "summary": "<一句话总评，说明相对基础分的修正理由>",
  "dimensions": [
    {{"key": "quality", "score": <0-100>, "stars": <1-5>, "summary": "..."}},
    {{"key": "structure", "score": ..., "stars": ..., "summary": "..."}},
    {{"key": "dependency", "score": ..., "stars": ..., "summary": "..."}},
    {{"key": "scale", "score": ..., "stars": ..., "summary": "..."}}
  ],
  "risks": [
    {{"level": "HIGH|MEDIUM|LOW", "title": "...", "detail": "...", "suggestion": "...",
      "references": [{{"file": "...", "line": 12}}]}}
  ],
  "recommendations": [{{"phase": "第一阶段", "items": ["..."]}}]
}}

约束：
1. dimensions 必须恰好 4 项（quality/structure/dependency/scale），顺序固定；
2. stars 由 score 映射：0-20→1 星，每 20 分一星，最高 5 星；
3. 没有把握的高危断言不要编造文件/行号，references 可以为空数组；
4. 扫描摘要未提供的维度（如 Sonar 质量指标）在 summary 中注明「未评估」。"""


def build_user_prompt(
    scan_summary: dict, history_reports: list[dict], score_base: dict
) -> str:
    return USER_TEMPLATE.format(
        scan_summary=json.dumps(scan_summary, ensure_ascii=False),
        history_summary=json.dumps(
            history_reports[-3:], ensure_ascii=False
        ),  # 只带最近 3 期摘要，省 token
        score_base=json.dumps(score_base, ensure_ascii=False),
    )

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


# ---- P6 AI 医生（02 附录 D.6）----

DOCTOR_PROMPT_VERSION = "doctor-1.0"

DOCTOR_SYSTEM_TEMPLATE = (
    "你是 EvoCode 的软件医生助手，服务于项目 {project_name}"
    "（{language} / {framework} / {loc} 行）。\n"
    "背景资料（仅以下内容可信）：\n"
    "1. 项目摘要：{project_summary}\n"
    "2. 最近分析报告摘要：{latest_report_summary}\n"
    "3. 检索到的代码片段（每条带 [path:line] 标记）：\n"
    "{knowledge_chunks}\n"
    "4. 会话历史：{history}\n"
    "\n"
    "规则：\n"
    '1. 只能引用"背景资料"中出现过的文件，引用格式必须为 [path:line]，禁止编造；\n'
    "2. 涉及代码分析时先给出结论再给证据（引用）；\n"
    '3. 无法从背景资料回答时，明确说"当前分析范围无法确认"，'
    "可建议用户发起一次新分析；\n"
    "4. 不执行代码、不改写代码文件、不输出机密信息；\n"
    "5. 用中文，简洁、结构化（列表/小标题）。"
)


def build_doctor_prompt(
    *,
    project_name: str,
    language: str,
    framework: str,
    loc: int,
    project_summary: str,
    latest_report_summary: str,
    knowledge_chunks: str,
    history: str,
    query: str,
    file_ref: dict | None = None,
) -> tuple[str, str]:
    """返回 (system, user)。fileRef 为用户 @ 的文件全文（契约 §5.7）。"""
    system = DOCTOR_SYSTEM_TEMPLATE.format(
        project_name=project_name or "未知项目",
        language=language or "未知",
        framework=framework or "未知",
        loc=loc or 0,
        project_summary=project_summary or "（无）",
        latest_report_summary=latest_report_summary or "（无）",
        knowledge_chunks=knowledge_chunks or "（无检索结果）",
        history=history or "（无）",
    )
    user_parts = [f"用户问题：{query}"]
    if file_ref and file_ref.get("path"):
        content = (file_ref.get("content") or "")[:4000]  # 全文发送，截断保护
        user_parts.append(f"用户正在查看文件 {file_ref['path']}：\n{content}")
    user_parts.append("请按规则回答，涉及代码时使用 [path:line] 引用。")
    return system, "\n\n".join(user_parts)

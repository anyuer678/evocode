"""报告生成编排（docs/06-API契约.md §5.2；AD-9 永不卡死）。

流程：规则版兜底 → 若有 LLM 则尝试生成并校验结构 → 失败降级 RULES。
LLM 版 healthScore 会被夹回规则基础分 ±10（AD-14：修正必须有限度、可解释）。
"""

from __future__ import annotations

import logging

from ..schemas import ScanResult
from .llm import LLMClient
from .prompts import SYSTEM_PROMPT, build_user_prompt
from .report_rules import build_rules_report

logger = logging.getLogger("evocode.analyzer.report")

_DIMENSION_KEYS = ("quality", "structure", "dependency", "scale")


def _scan_summary(scan: ScanResult) -> dict:
    """只发结构化摘要（AD-12），绝不携带文件内容。"""
    return {
        "languages": scan.languages,
        "locTotal": scan.locTotal,
        "fileCount": scan.fileCount,
        "ignoredCount": scan.ignoredCount,
        "frameworks": scan.frameworks,
        "hasBackend": scan.hasBackend,
        "hasFrontend": scan.hasFrontend,
        "dbHint": scan.dbHint,
        "skippedBigFiles": scan.skippedBigFiles,
        "truncated": scan.truncated,
    }


def _tech_stack(scan: ScanResult) -> dict:
    return {"languages": scan.languages, "frameworks": scan.frameworks}


def _clamp_health(score: int, base: int) -> int:
    """AD-14：LLM 修正限制在基础分 ±10 内。"""
    return max(0, min(100, max(base - 10, min(base + 10, int(score)))))


def _validated_llm_report(raw: dict, base: int) -> dict | None:
    """校验 LLM 输出结构；不合法返回 None（触发降级）。"""
    if not isinstance(raw, dict):
        return None
    health = raw.get("healthScore")
    dims = raw.get("dimensions")
    if not isinstance(health, int | float) or not isinstance(dims, list):
        return None
    if len(dims) != 4 or [d.get("key") for d in dims] != list(_DIMENSION_KEYS):
        return None
    for d in dims:
        if not isinstance(d.get("score"), int | float):
            return None
    report = {
        "healthScore": _clamp_health(health, base),
        "level": raw.get("level") or _level_of(_clamp_health(health, base)),
        "summary": str(raw.get("summary") or ""),
        "dimensions": [
            {
                "key": d["key"],
                "score": int(d["score"]),
                "stars": _stars_of(int(d["score"])),
                "summary": str(d.get("summary") or ""),
            }
            for d in dims
        ],
        "risks": _normalize_risks(raw.get("risks")),
        "recommendations": _normalize_recommendations(raw.get("recommendations")),
    }
    return report


def _normalize_risks(risks: object) -> list[dict]:
    if not isinstance(risks, list):
        return []
    out = []
    for r in risks:
        if isinstance(r, dict) and r.get("title"):
            out.append(
                {
                    "level": str(r.get("level") or "MEDIUM"),
                    "title": str(r["title"]),
                    "detail": str(r.get("detail") or ""),
                    "suggestion": str(r.get("suggestion") or ""),
                    "references": r.get("references")
                    if isinstance(r.get("references"), list)
                    else [],
                }
            )
    return out


def _normalize_recommendations(recs: object) -> list[dict]:
    if not isinstance(recs, list):
        return []
    out = []
    for r in recs:
        if isinstance(r, dict) and r.get("items"):
            out.append(
                {"phase": str(r.get("phase") or ""), "items": list(r.get("items"))}
            )
    return out


def _level_of(score: int) -> str:
    if score >= 90:
        return "EXCELLENT"
    if score >= 75:
        return "GOOD"
    if score >= 60:
        return "FAIR"
    return "POOR"


def _stars_of(score: int) -> int:
    return max(1, min(5, round(score / 20)))


def generate_report(
    scan: ScanResult,
    history_reports: list[dict] | None = None,
    llm: LLMClient | None = None,
    quality: dict | None = None,
) -> tuple[dict, str, dict]:
    """生成报告。返回 (report, source, score_base)；source ∈ {LLM, RULES}。

    quality 为 Sonar 指标（metrics，available=true 时生效）；None 时质量维度走代理指标。
    """
    history_reports = history_reports or []
    rules = build_rules_report(scan, history_reports, quality)
    score_base = {"healthScore": rules.health_score, "scoreDetail": rules.score_detail}

    if llm is not None and llm.available():
        try:
            user = build_user_prompt(_scan_summary(scan), history_reports, score_base)
            raw = llm.chat_json(SYSTEM_PROMPT, user)
            llm_report = _validated_llm_report(raw, rules.health_score)
            if llm_report is not None:
                llm_report["techStack"] = _tech_stack(scan)
                llm_report["scoreDetail"] = rules.score_detail
                return llm_report, "LLM", score_base
            logger.warning("LLM 输出结构不合法，降级规则版")
        except Exception as exc:
            logger.warning("LLM 调用失败，降级规则版：%s", exc)

    report = {
        "healthScore": rules.health_score,
        "level": rules.level,
        "summary": rules.summary,
        "techStack": _tech_stack(scan),
        "dimensions": rules.dimensions,
        "risks": rules.risks,
        "recommendations": rules.recommendations,
        "scoreDetail": rules.score_detail,
    }
    return report, "RULES", score_base

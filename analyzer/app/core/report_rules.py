"""规则版健康报告（docs/02-开发指导.md §10 评分模型；AD-9 降级路径）。

healthScore = round(quality*0.4 + structure*0.3 + dependency*0.15 + scale*0.15)
规则版附加 scoreDetail 记录各子分，保证可解释、可复现。
v0.1 仅输入扫描摘要（quality/arch/evolution 未接入）：
- quality 用扫描代理指标（大文件/截断）近似，明确标注「Sonar 未接入」；
- dependency 无依赖清单（P3 接入），给中性分。
"""

from __future__ import annotations

from dataclasses import dataclass

from ..schemas import ScanResult


@dataclass
class RuleReport:
    """规则版报告组装结果（含 scoreBase 供 LLM 修正参考）。"""

    health_score: int
    level: str
    summary: str
    dimensions: list[dict]
    risks: list[dict]
    recommendations: list[dict]
    score_detail: dict[str, int]


def _stars(score: int) -> int:
    """0-20→1 星，每 20 分一星，封顶 5 星（契约 §3.7 示例：76→4 星）。"""
    return max(1, min(5, round(score / 20)))


def _level(score: int) -> str:
    if score >= 90:
        return "EXCELLENT"
    if score >= 75:
        return "GOOD"
    if score >= 60:
        return "FAIR"
    return "POOR"


def _scale_score(loc_total: int) -> int:
    """规模维度：按代码量分档（v0.1 无复杂度数据，规模即评估充分度）。"""
    if loc_total < 1_000:
        return 95
    if loc_total < 10_000:
        return 90
    if loc_total < 50_000:
        return 80
    if loc_total < 200_000:
        return 65
    return 50


def _structure_score(scan: ScanResult) -> int:
    """结构维度：前后端分层 + 顶层目录规范性（扫描可见信号）。"""
    score = 70
    if scan.hasBackend and scan.hasFrontend:
        score += 10
    elif scan.hasBackend or scan.hasFrontend:
        score += 5
    top_dirs = {f.path.split("/")[0] for f in scan.files if "/" in f.path}
    if top_dirs & {"src", "backend", "frontend", "lib", "app"}:
        score += 10
    if scan.fileCount > 0:
        score += 5
    return min(score, 95)


def _dependency_score(scan: ScanResult) -> int:
    """依赖维度：P3 前无清单，识别到框架给偏高中性分。"""
    return 75 if scan.frameworks else 70


def _quality_score_sonar(quality: dict) -> int:
    """质量维度（Sonar 已接入）：按真实指标评分（02 §10 质量 40%）。"""
    score = 85
    bugs = int(quality.get("bugs") or 0)
    vulns = int(quality.get("vulnerabilities") or 0)
    smells = int(quality.get("codeSmells") or 0)
    dup = float(quality.get("duplicationRate") or 0)
    cov = quality.get("coverageRate")
    compl = quality.get("complexity")
    score -= min(bugs, 5) * 3  # 每个 bug -3，最多 -15
    score -= min(vulns, 3) * 5  # 每个漏洞 -5，最多 -15
    if smells > 50:
        score -= min((smells - 50) // 50, 10)  # 每 50 个异味 -1，最多 -10
    if dup > 10:
        score -= min(int((dup - 10) // 5), 6)  # 每 5% 重复 -1，最多 -6
    if cov is not None and float(cov) < 50:
        score -= 8
    if compl is not None and float(compl) > 5:
        score -= 5
    return max(score, 40)


def _quality_score(scan: ScanResult, quality: dict | None = None) -> tuple[int, dict]:
    """质量维度：Sonar 接入用真实指标，否则用扫描代理指标。

    返回 (分数, 维度 summary)。
    """
    if quality:
        score = _quality_score_sonar(quality)
        summary = (
            f"Sonar 扫描：bug {quality.get('bugs') or 0} 个 / "
            f"漏洞 {quality.get('vulnerabilities') or 0} 个 / "
            f"异味 {quality.get('codeSmells') or 0} 个 / "
            f"重复率 {quality.get('duplicationRate') or 0}%"
        )
        return score, summary
    score = 75
    score -= min(scan.skippedBigFiles, 3) * 5  # 每个 >2MB 大文件 -5，最多 -15
    if scan.truncated:
        score -= 5
    return max(score, 40), (
        f"基于扫描代理指标（大文件 {scan.skippedBigFiles} 个"
        + ("、结果截断" if scan.truncated else "")
        + "）；Sonar 质量分析未接入"
    )


def build_rules_report(
    scan: ScanResult, history_reports: list[dict], quality: dict | None = None
) -> RuleReport:
    """基于扫描摘要（+可选 Sonar 质量指标）生成规则版报告（可复现，不依赖 LLM）。"""
    q_score, quality_summary = _quality_score(scan, quality)
    structure = _structure_score(scan)
    dependency = _dependency_score(scan)
    scale = _scale_score(scan.locTotal)
    health = round(q_score * 0.4 + structure * 0.3 + dependency * 0.15 + scale * 0.15)

    frontend_hint = "分层清晰" if scan.hasBackend and scan.hasFrontend else "结构一般"
    dimensions = [
        {
            "key": "quality",
            "score": q_score,
            "stars": _stars(q_score),
            "summary": quality_summary,
        },
        {
            "key": "structure",
            "score": structure,
            "stars": _stars(structure),
            "summary": f"前后端{frontend_hint}，识别文件 {scan.fileCount} 个",
        },
        {
            "key": "dependency",
            "score": dependency,
            "stars": _stars(dependency),
            "summary": ("已识别框架：" + "、".join(scan.frameworks[:5]))
            if scan.frameworks
            else "未识别到主流框架；依赖清单分析未接入（P3 启用）",
        },
        {
            "key": "scale",
            "score": scale,
            "stars": _stars(scale),
            "summary": f"代码量 {scan.locTotal} 行",
        },
    ]

    risks: list[dict] = []
    if quality:
        bugs = int(quality.get("bugs") or 0)
        vulns = int(quality.get("vulnerabilities") or 0)
        smells = int(quality.get("codeSmells") or 0)
        if vulns > 0:
            risks.append(
                {
                    "level": "HIGH",
                    "title": f"存在 {vulns} 个安全漏洞",
                    "detail": (
                        f"Sonar 扫描发现 {vulns} 个漏洞，可能带来可利用风险；修复优先级最高。"  # noqa: E501
                    ),
                    "suggestion": (
                        f"先修复全部 {vulns} 个漏洞（BLOCKER/CRITICAL 优先）→ 复查依赖与输入校验"  # noqa: E501
                        " → 回归测试；质量分析页可按文件定位每个漏洞。"
                    ),
                    "references": [],
                }
            )
        if bugs > 0:
            risks.append(
                {
                    "level": "MEDIUM",
                    "title": f"存在 {bugs} 个 Bug",
                    "detail": f"Sonar 识别到 {bugs} 个潜在缺陷，可能引发运行时异常或逻辑错误。",  # noqa: E501
                    "suggestion": (
                        f"按严重级修复这 {bugs} 个 Bug（先 CRITICAL/BLOCKER）→ 每条补对应测试"  # noqa: E501
                        " → 全量回归；质量分析页列有每个 Bug 的文件与行号。"
                    ),
                    "references": [],
                }
            )
        if smells > 0:
            risks.append(
                {
                    "level": "LOW",
                    "title": f"存在 {smells} 个代码异味",
                    "detail": f"Sonar 标记 {smells} 处代码异味，长期积累降低可维护性。",
                    "suggestion": (
                        f"从高频规则（如未使用变量/重复代码/复杂度过高）入手批量清理 {smells} 处异味；"  # noqa: E501
                        "质量分析页可按规则分组定位。"
                    ),
                    "references": [],
                }
            )
    if scan.skippedBigFiles > 0:
        risks.append(
            {
                "level": "HIGH",
                "title": f"存在 {scan.skippedBigFiles} 个超大文件（>2MB 已跳过）",
                "detail": "超大文件通常意味着职责过重或包含生成代码，影响可维护性。",
                "suggestion": (
                    f"拆分 {scan.skippedBigFiles} 个超大文件（按模块/功能拆分），并将生成物"  # noqa: E501
                    "（dist/build/vendor）移出源码目录后重新扫描。"
                ),
                "references": [],
            }
        )
    if scan.locTotal > 200_000:
        risks.append(
            {
                "level": "MEDIUM",
                "title": "代码规模较大，建议分层分模块治理",
                "detail": f"共 {scan.locTotal} 行代码，单仓维护成本随规模上升。",
                "suggestion": (
                    f"先按模块边界拆分 {scan.locTotal} 行代码中的业务模块，评估哪些可独立成"  # noqa: E501
                    "服务/库；从高频变更模块开始。"
                ),
                "references": [],
            }
        )
    if not scan.frameworks:
        risks.append(
            {
                "level": "LOW",
                "title": "未识别到主流框架",
                "detail": "可能是脚本/原生项目，或扫描规则未覆盖。",
                "suggestion": "补充项目元信息（package.json / requirements.txt 等）。",
                "references": [],
            }
        )
    if scan.truncated:
        risks.append(
            {
                "level": "MEDIUM",
                "title": "文件过多导致扫描截断",
                "detail": "报告基于部分文件生成，可能低估问题。",
                "suggestion": "清理构建产物与冗余文件后重新扫描。",
                "references": [],
            }
        )

    recommendations: list[dict] = []
    phases: list[list[str]] = [[], [], []]
    high_risks = [r["title"] for r in risks if r["level"] == "HIGH"]
    if high_risks:
        phases[0].append("优先处理高优先级风险：" + "；".join(high_risks))
    phases[1].append("接入质量分析（Sonar），补齐 bugs / 复杂度 / 重复率指标")
    phases[1].append("补充依赖清单，开启依赖与 EOL 版本检查（P3）")
    if scan.hasFrontend:
        phases[2].append("前端代码同步纳入质量与架构分析范围")
    for i, items in enumerate(phases, start=1):
        if items:
            recommendations.append({"phase": f"第{i}阶段", "items": items})

    summary = (
        f"规则评分 {health} 分（{_level(health)}）：质量 {q_score} / 结构 {structure} "
        f"/ 依赖 {dependency} / 规模 {scale}。"
        + (
            "存在超大文件等高风险项，建议优先拆分。"
            if scan.skippedBigFiles
            else "整体结构基本清晰。"
        )
    )
    if history_reports:
        prev = history_reports[-1].get("healthScore")
        if isinstance(prev, int):
            delta = health - prev
            summary += f" 较上期 {delta:+d} 分。"

    return RuleReport(
        health_score=health,
        level=_level(health),
        summary=summary,
        dimensions=dimensions,
        risks=risks,
        recommendations=recommendations,
        score_detail={
            "quality": q_score,
            "structure": structure,
            "dependency": dependency,
            "scale": scale,
        },
    )

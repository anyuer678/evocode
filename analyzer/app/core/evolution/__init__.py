"""P5 演化分析编排（docs/06-API契约.md §5.6）。

git log 统计 → trend/topFiles/authors 聚合 → 规则热点判定。
非 git 目录 → `{available: false}`（契约 05.6：200 + available=false，非 404）。
产物为纯统计 dict，落库（V004/V007）由 backend 完成（P5b）。
"""

from __future__ import annotations

import logging

from .gitlog import (
    build_authors,
    build_top_files,
    build_trend,
    detect_hotspots,
    git_log_entries,
    is_git_repo,
)

logger = logging.getLogger("evocode.analyzer.evolution")


def evolution_scan(git_dir: str, range_days: int | None = None) -> dict:
    """主入口：非 git 目录 → available=false；否则聚合统计 + 规则热点。"""
    if not is_git_repo(git_dir):
        logger.info("evolution not available (no git repo): %s", git_dir)
        return {
            "available": False,
            "commits": [],
            "trend": [],
            "topFiles": [],
            "authors": [],
            "hotspots": [],
        }

    commits = git_log_entries(git_dir, range_days)
    trend = build_trend(commits)
    top_files = build_top_files(commits)
    authors = build_authors(commits)
    hotspots = detect_hotspots(top_files, len(commits))
    logger.info(
        "evolution done git_dir=%s commits=%s weeks=%s files=%s authors=%s hotspots=%s",
        git_dir,
        len(commits),
        len(trend),
        len(top_files),
        len(authors),
        len(hotspots),
    )
    # 契约 commits 不携带文件级明细，剥离后输出
    slim = [{k: v for k, v in c.items() if k != "files"} for c in commits]
    return {
        "available": True,
        "commits": slim,
        "trend": trend,
        "topFiles": top_files,
        "authors": authors,
        "hotspots": hotspots,
    }

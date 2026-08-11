"""git 演化统计（docs/06-API契约.md §5.6，P5）。

subprocess 调系统 git（不引入 Python git 库，保持 analyzer 依赖最小）：
`git log --numstat` 一次性取全量 → 聚合 trend（按周）/ topFiles / authors
→ 规则判定 hotspots（变更次数/新增行数阈值，证据数组结构见 07 §5.6）。
非 git 目录由上层返回 `{available: false}`（契约 05.6）。
"""

from __future__ import annotations

import logging
import os
import re
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path

logger = logging.getLogger("evocode.analyzer.evolution")

# Windows 无控制台部署（服务/计划任务/pythonw）时抑制 git 子进程弹出的黑窗口
_SUBPROCESS_KW = (
    {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
)

# git log pretty 字段，用 \x1f 分隔避免作者名/提交消息中的空格歧义
_PRETTY = "COMMIT%x1f%h%x1f%an%x1f%ae%x1f%ad%x1f%s"
_COMMIT_LINE_RE = re.compile(
    r"^COMMIT\x1f([0-9a-f]+)\x1f(.*?)\x1f(.*?)\x1f(.*?)\x1f(.*)$"
)
_NUMSTAT_RE = re.compile(r"^(\d+|-)\t(\d+|-)\t(.+)$")


def is_git_repo(git_dir: str) -> bool:
    """目录存在且 `git rev-parse --git-dir` 成功才算 git 仓库。"""
    if not Path(git_dir).is_dir():
        return False
    try:
        r = subprocess.run(
            ["git", "-C", git_dir, "rev-parse", "--git-dir"],
            capture_output=True,
            text=True,
            timeout=15,
            **_SUBPROCESS_KW,
        )
        return r.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def _run_git(git_dir: str, args: list[str]) -> str | None:
    try:
        r = subprocess.run(
            ["git", "-C", git_dir, *args],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=60,
            **_SUBPROCESS_KW,
        )
        if r.returncode != 0:
            logger.warning("git %s failed: %s", args[0], r.stderr[:300])
            return None
        return r.stdout
    except (OSError, subprocess.SubprocessError) as e:
        logger.warning("git run failed: %s", e)
        return None


def git_log_entries(git_dir: str, range_days: int | None = None) -> list[dict] | None:
    """解析 `git log --numstat` → commit 列表（含文件级变更明细）。

    git 执行失败/超时 → None（与「空仓库」区分：空仓库是 []）。
    """
    args = [
        "-c", "core.quotepath=false",
        "log", "--numstat",
        f"--pretty=format:{_PRETTY}",
        "--date=iso-strict",
        "--no-renames",
    ]
    if range_days and range_days > 0:
        args.append(f"--since={range_days} days ago")
    out = _run_git(git_dir, args)
    if out is None:
        # git log 失败：区分「空仓库（无任何提交）」与「真故障（超时/损坏）」
        if _run_git(git_dir, ["rev-parse", "--verify", "HEAD"]) is None:
            return []  # HEAD 不存在 → 空仓库，非故障
        return None

    commits: list[dict] = []
    cur: dict | None = None
    for line in out.splitlines():
        m = _COMMIT_LINE_RE.match(line)
        if m:
            if cur is not None:
                commits.append(cur)
            cur = {
                "hash": m.group(1),
                "authorName": m.group(2),
                "authorEmail": m.group(3),
                "committedAt": m.group(4),
                "message": m.group(5),
                "linesAdded": 0,
                "linesRemoved": 0,
                "filesChanged": 0,
                "_files": {},
            }
            continue
        if cur is None:
            continue
        n = _NUMSTAT_RE.match(line)
        if n:
            added = 0 if n.group(1) == "-" else int(n.group(1))
            removed = 0 if n.group(2) == "-" else int(n.group(2))
            path = n.group(3)
            cur["linesAdded"] += added
            cur["linesRemoved"] += removed
            cur["filesChanged"] += 1
            f = cur["_files"].setdefault(path, {"added": 0, "removed": 0})
            f["added"] += added
            f["removed"] += removed
    if cur is not None:
        commits.append(cur)

    for c in commits:
        c["files"] = c.pop("_files")
    return commits


def _week_start(committed_at: str) -> str:
    """ISO-8601 时间戳 → 所在周周一（YYYY-MM-DD）。

    先归一到 UTC 再取日期，避免混合时区仓库在周界处错分周；
    无法解析时返回空串（该 commit 计入「未知周」，不阻断整体）。
    """
    try:
        dt = datetime.fromisoformat(committed_at.replace("Z", "+00:00"))
        dt = dt.astimezone(timezone.utc)  # noqa: UP017（datetime.UTC 需 import datetime 模块而非类）
    except ValueError:
        logger.warning("无法解析 committed_at=%s，计入未知周", committed_at)
        return ""
    return (dt.date() - timedelta(days=dt.weekday())).isoformat()


def build_trend(commits: list[dict]) -> list[dict]:
    """按 ISO 周聚合 commits/linesAdded/linesRemoved（升序，契约 06 §3.13 trend）。"""
    acc: dict[str, dict] = {}
    for c in commits:
        week = _week_start(c["committedAt"])
        a = acc.setdefault(
            week, {"week": week, "commits": 0, "linesAdded": 0, "linesRemoved": 0}
        )
        a["commits"] += 1
        a["linesAdded"] += c["linesAdded"]
        a["linesRemoved"] += c["linesRemoved"]
    return [acc[k] for k in sorted(acc)]


def build_top_files(commits: list[dict], top_n: int = 10) -> list[dict]:
    """按文件聚合 commitCount/linesAdded/linesRemoved，按变更次数降序取 TOP-N。"""
    acc: dict[str, dict] = {}
    for c in commits:
        for path, f in c["files"].items():
            a = acc.setdefault(
                path,
                {
                    "filePath": path,
                    "commitCount": 0,
                    "linesAdded": 0,
                    "linesRemoved": 0,
                },
            )
            a["commitCount"] += 1
            a["linesAdded"] += f["added"]
            a["linesRemoved"] += f["removed"]
    ranked = sorted(acc.values(), key=lambda x: (-x["commitCount"], -x["linesAdded"]))
    return ranked[:top_n]


def build_authors(commits: list[dict]) -> list[dict]:
    """按作者聚合 commits/linesAdded，按提交数降序。"""
    acc: dict[str, dict] = {}
    for c in commits:
        name = c["authorName"] or "unknown"
        a = acc.setdefault(
            name, {"authorName": name, "commits": 0, "linesAdded": 0}
        )
        a["commits"] += 1
        a["linesAdded"] += c["linesAdded"]
    return sorted(acc.values(), key=lambda x: -x["commits"])


def detect_hotspots(
    top_files: list[dict], total_commits: int, top_n: int | None = None
) -> list[dict]:
    """规则判定热点模块（07 枚举 HotspotLevel：HIGH/MEDIUM；evidence 见 07 §5.6）。

    规则（阈值可随数据规模调整）：
    - HIGH：变更占比 ≥ 15% 且新增 ≥ 300 行，或新增 ≥ 2000 行
    - MEDIUM：变更 ≥ 3 次
    top_n 缺省评估全部候选（输入已是 top10，避免第 6~10 名满足 MEDIUM 被漏报）。
    AI 风险中心结论（ai_conclusion）由 backend 侧可选调用 D.5 Prompt 补充，不在此阻塞。
    """
    if total_commits == 0:
        return []
    hotspots: list[dict] = []
    for tf in top_files[:top_n] if top_n else top_files:
        share = tf["commitCount"] / total_commits
        evidence = [
            f"变更 {tf['commitCount']} 次",
            f"新增 {tf['linesAdded']} 行",
            f"删除 {tf['linesRemoved']} 行",
            f"占全部提交的 {share * 100:.0f}%",
        ]
        if (share >= 0.15 and tf["linesAdded"] >= 300) or tf["linesAdded"] >= 2000:
            level = "HIGH"
        elif tf["commitCount"] >= 3:
            level = "MEDIUM"
        else:
            continue
        hotspots.append(
            {"module": tf["filePath"], "riskLevel": level, "evidence": evidence}
        )
    return hotspots

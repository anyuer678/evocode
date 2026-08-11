"""T-E-01~08：P5 演化统计——git log 解析、周聚合、TOP 文件、作者、热点、端点。"""

import os
import subprocess
from datetime import UTC, datetime, timedelta
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.evolution import evolution_scan
from app.main import app

client = TestClient(app)


def _iso_days_ago(days: int) -> str:
    ts = datetime.now(UTC) - timedelta(days=days)
    return ts.strftime("%Y-%m-%dT%H:%M:%S+00:00")


def _git(repo: Path, *args: str) -> None:
    subprocess.run(
        ["git", "-C", str(repo), *args],
        capture_output=True,
        text=True,
        check=True,
        timeout=30,
    )


def make_git_repo(tmp_path: Path, commits: list[dict]) -> Path:
    """每个条目一个 commit（{path, days_ago, author?}），内容按序号递增避免空 diff。"""
    repo = tmp_path / "gitrepo"
    repo.mkdir()
    _git(repo, "init", "-q", "-b", "main")
    _git(repo, "config", "user.name", "tester")
    _git(repo, "config", "user.email", "t@test.local")
    for i, c in enumerate(commits):
        f = repo / c["path"]
        f.parent.mkdir(parents=True, exist_ok=True)
        # 每行内容含序号 → 行 diff 全量唯一，numstat 才能计足新增/删除行数
        f.write_text("\n".join(f"{i}-{j}" for j in range(40)) + "\n", encoding="utf-8")
        _git(repo, "add", "-A")
        env = os.environ.copy()
        when = c.get("date") or _iso_days_ago(c.get("days_ago", 0))
        env["GIT_AUTHOR_DATE"] = when
        env["GIT_COMMITTER_DATE"] = when
        if c.get("author"):
            env["GIT_AUTHOR_NAME"] = c["author"]
            env["GIT_COMMITTER_NAME"] = c["author"]
        msg = c.get("message", f"commit {i}")
        subprocess.run(
            ["git", "-C", str(repo), "commit", "-q", "-m", msg],
            capture_output=True,
            text=True,
            check=True,
            timeout=30,
            env=env,
        )
    return repo


# ---------- 核心函数 ----------


def test_non_git_dir_available_false(tmp_path: Path):
    plain = tmp_path / "plain"
    plain.mkdir()
    data = evolution_scan(str(plain))
    assert data["available"] is False
    assert data["commits"] == []
    assert data["hotspots"] == []


def test_git_commits_count_and_fields(tmp_path: Path):
    repo = make_git_repo(
        tmp_path,
        [
            {"path": "src/a.py", "days_ago": 2, "author": "alice"},
            {"path": "src/b.py", "days_ago": 1, "author": "bob"},
            {"path": "src/a.py", "days_ago": 0, "author": "alice"},
        ],
    )
    data = evolution_scan(str(repo))
    assert data["available"] is True
    assert len(data["commits"]) == 3
    first = data["commits"][0]
    assert first["hash"]
    assert first["authorName"] in {"alice", "bob"}
    assert first["committedAt"]
    assert first["linesAdded"] > 0
    assert first["filesChanged"] == 1


def test_trend_weekly_aggregation(tmp_path: Path):
    # 前两个 commit 同一周（07-20 周一 / 07-21 周二），第三个另一周（08-10 周一）→ 2 周
    repo = make_git_repo(
        tmp_path,
        [
            {"path": "a.py", "date": "2026-07-20T10:00:00+00:00"},
            {"path": "a.py", "date": "2026-07-21T10:00:00+00:00"},
            {"path": "b.py", "date": "2026-08-10T10:00:00+00:00"},
        ],
    )
    data = evolution_scan(str(repo))
    assert len(data["trend"]) == 2  # 两周
    weeks = [t["week"] for t in data["trend"]]
    assert weeks == sorted(weeks)
    assert weeks[0] == "2026-07-20"
    assert weeks[1] == "2026-08-10"
    total_commits = sum(t["commits"] for t in data["trend"])
    assert total_commits == 3
    total_added = sum(t["linesAdded"] for t in data["trend"])
    assert total_added == sum(c["linesAdded"] for c in data["commits"])


def test_top_files_ranking(tmp_path: Path):
    repo = make_git_repo(
        tmp_path,
        [
            {"path": "hot.py", "days_ago": 5},
            {"path": "hot.py", "days_ago": 4},
            {"path": "hot.py", "days_ago": 3},
            {"path": "warm.py", "days_ago": 2},
            {"path": "cold.py", "days_ago": 1},
        ],
    )
    data = evolution_scan(str(repo))
    assert data["topFiles"][0]["filePath"] == "hot.py"
    assert data["topFiles"][0]["commitCount"] == 3
    assert len(data["topFiles"]) == 3


def test_authors_aggregation(tmp_path: Path):
    repo = make_git_repo(
        tmp_path,
        [
            {"path": "a.py", "days_ago": 3, "author": "alice"},
            {"path": "b.py", "days_ago": 2, "author": "bob"},
            {"path": "c.py", "days_ago": 1, "author": "alice"},
        ],
    )
    data = evolution_scan(str(repo))
    by_name = {a["authorName"]: a for a in data["authors"]}
    assert by_name["alice"]["commits"] == 2
    assert by_name["bob"]["commits"] == 1
    assert data["authors"][0]["authorName"] == "alice"  # 提交数降序


def test_hotspots_high_and_medium(tmp_path: Path):
    # hot.py 变更 5/20 = 25% ≥ 15% 且新增行数大 → HIGH；warm.py 变更 3 次 → MEDIUM
    commits = [{"path": "hot.py", "days_ago": i} for i in range(20, 0, -1)]
    commits[0] = {"path": "hot.py", "days_ago": 20}
    commits[15] = {"path": "warm.py", "days_ago": 4}
    commits[16] = {"path": "warm.py", "days_ago": 3}
    commits[17] = {"path": "warm.py", "days_ago": 2}
    repo = make_git_repo(tmp_path, commits)
    data = evolution_scan(str(repo))
    levels = {h["module"]: h["riskLevel"] for h in data["hotspots"]}
    assert levels.get("hot.py") == "HIGH"
    assert levels.get("warm.py") == "MEDIUM"
    hot = next(h for h in data["hotspots"] if h["module"] == "hot.py")
    assert len(hot["evidence"]) >= 3
    assert any("变更" in e for e in hot["evidence"])


def test_range_days_filter(tmp_path: Path):
    repo = make_git_repo(
        tmp_path,
        [
            {"path": "old.py", "days_ago": 100},
            {"path": "new.py", "days_ago": 5},
            {"path": "new.py", "days_ago": 1},
        ],
    )
    data = evolution_scan(str(repo), range_days=30)
    assert len(data["commits"]) == 2
    assert data["topFiles"][0]["filePath"] == "new.py"


# ---------- 端点 ----------


def test_evolution_endpoint_non_git_available_false(tmp_path: Path):
    plain = tmp_path / "plain"
    plain.mkdir()
    resp = client.post(
        "/analyze/v1/evolution",
        json={"projectId": 1, "gitDir": str(plain), "rangeDays": 30},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["available"] is False


def test_evolution_endpoint_ok(tmp_path: Path):
    repo = make_git_repo(
        tmp_path,
        [
            {"path": "src/main.py", "days_ago": 7},
            {"path": "src/main.py", "days_ago": 1},
        ],
    )
    resp = client.post(
        "/analyze/v1/evolution",
        json={"projectId": 1, "gitDir": str(repo), "rangeDays": 30},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["available"] is True
    assert len(body["commits"]) == 2
    assert len(body["trend"]) >= 1
    assert body["topFiles"][0]["filePath"] == "src/main.py"
    assert len(body["authors"]) == 1


def test_evolution_endpoint_missing_dir(tmp_path: Path):
    resp = client.post(
        "/analyze/v1/evolution",
        json={"projectId": 1, "gitDir": str(tmp_path / "nope"), "rangeDays": 30},
    )
    assert resp.status_code == 404


def test_empty_git_repo_available_true_empty(tmp_path: Path):
    repo = tmp_path / "emptyrepo"
    repo.mkdir()
    _git(repo, "init", "-q", "-b", "main")
    data = evolution_scan(str(repo))
    assert data["available"] is True
    assert data["commits"] == []
    assert data["trend"] == []


def test_git_failure_available_false(monkeypatch, tmp_path: Path):
    """git log 执行失败/超时 → available=false（区分于空仓库）。"""
    import app.core.evolution.gitlog as gl

    monkeypatch.setattr(gl, "_run_git", lambda *a, **k: None)
    data = evolution_scan(str(tmp_path))
    assert data["available"] is False


def test_trend_cross_year_week_aggregation(tmp_path: Path):
    # 2025-12-29（周一）与 2026-01-05（周一）跨年但各成一周；UTC 归一周界稳定
    repo = make_git_repo(
        tmp_path,
        [
            {"path": "a.py", "date": "2025-12-29T10:00:00+00:00"},
            {"path": "a.py", "date": "2025-12-30T10:00:00+00:00"},
            {"path": "b.py", "date": "2026-01-05T10:00:00+00:00"},
        ],
    )
    data = evolution_scan(str(repo))
    weeks = [t["week"] for t in data["trend"]]
    assert weeks == ["2025-12-29", "2026-01-05"]
    assert data["trend"][0]["commits"] == 2


def test_mixed_timezone_commit_goes_to_utc_week(tmp_path: Path):
    # +08:00 的 2026-07-27T02:00 与 UTC 2026-07-26T18:00 是同一瞬间 → 归入同一周
    repo = make_git_repo(
        tmp_path,
        [
            {"path": "a.py", "date": "2026-07-26T18:00:00+00:00"},
            {"path": "b.py", "date": "2026-07-27T02:00:00+08:00"},
        ],
    )
    data = evolution_scan(str(repo))
    assert len(data["trend"]) == 1
    assert data["trend"][0]["week"] == "2026-07-20"
    assert data["trend"][0]["commits"] == 2


def test_range_days_rejected_by_schema(tmp_path: Path):
    """rangeDays 超界（0 / 负数 / 超上限）→ 422。"""
    plain = tmp_path / "plain"
    plain.mkdir()
    resp = client.post(
        "/analyze/v1/evolution",
        json={"projectId": 1, "gitDir": str(plain), "rangeDays": 0},
    )
    assert resp.status_code == 422
    resp = client.post(
        "/analyze/v1/evolution",
        json={"projectId": 1, "gitDir": str(plain), "rangeDays": -5},
    )
    assert resp.status_code == 422

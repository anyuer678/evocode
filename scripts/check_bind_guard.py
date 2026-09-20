"""启动绑定护栏：拒绝非本机 host（除非显式 opt-in）。

用法:
    python scripts/check_bind_guard.py
    python scripts/check_bind_guard.py --host 0.0.0.0   # 应退出码 1

环境变量:
    EVOCODE_ALLOW_PUBLIC_BIND=1   允许非本机（不推荐；仍无认证）
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

LOCAL_OK = {"127.0.0.1", "localhost", "::1", "[::1]"}


def is_local(host: str) -> bool:
    h = (host or "").strip().lower()
    return h in LOCAL_OK


def check_backend_yml(repo: Path) -> list[str]:
    issues: list[str] = []
    yml = repo / "backend" / "src" / "main" / "resources" / "application.yml"
    if not yml.is_file():
        return issues
    text = yml.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"address:\s*\$\{SERVER_ADDRESS:([^}]+)\}", text)
    if m and not is_local(m.group(1)):
        issues.append(f"application.yml server.address default is not loopback: {m.group(1)}")
    if "address:" in text and "127.0.0.1" not in text:
        issues.append("application.yml missing 127.0.0.1 bind default")
    return issues


def check_compose(repo: Path) -> list[str]:
    issues: list[str] = []
    for name in ("docker-compose.yml", "docker-compose.full.yml"):
        p = repo / name
        if not p.is_file():
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            s = line.strip()
            if s.startswith("- '") or s.startswith('- "') or s.startswith("- "):
                if ":" in s and re.search(r"\d+:\d+", s):
                    if "127.0.0.1" not in s and "profiles" not in s:
                        # allow expose-only lines without host publish
                        if re.search(r"\"?\d+:\d+", s) or re.search(r"'\d+:\d+", s):
                            issues.append(f"{name}: port mapping without 127.0.0.1: {s}")
    return issues


def main() -> int:
    ap = argparse.ArgumentParser(description="EvoCode bind guard")
    ap.add_argument("--host", default=os.environ.get("SERVER_ADDRESS", "127.0.0.1"))
    ap.add_argument("--repo", default=str(Path(__file__).resolve().parents[1]))
    args = ap.parse_args()

    allow_public = os.environ.get("EVOCODE_ALLOW_PUBLIC_BIND", "") == "1"
    if not is_local(args.host) and not allow_public:
        print(
            f"REFUSED: host={args.host!r} is not loopback. "
            f"EvoCode has no auth; bind to 127.0.0.1 only. "
            f"Set EVOCODE_ALLOW_PUBLIC_BIND=1 to override (not recommended).",
            file=sys.stderr,
        )
        return 1

    repo = Path(args.repo)
    issues = check_backend_yml(repo) + check_compose(repo)
    if issues:
        print("BIND GUARD ISSUES:")
        for i in issues:
            print(" -", i)
        return 2

    print(f"OK: bind checks passed (requested host={args.host})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

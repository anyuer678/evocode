"""Analyzer 宿主启动入口：强制默认仅监听 127.0.0.1。

用法:
    python -m analyzer.run
    # 等价于 uvicorn app.main:app --host 127.0.0.1 --port 8091
"""

from __future__ import annotations

import os
import sys


def main() -> int:
    host = os.environ.get("ANALYZER_HOST", "127.0.0.1")
    port = int(os.environ.get("ANALYZER_PORT", "8091"))
    allow_public = os.environ.get("EVOCODE_ALLOW_PUBLIC_BIND", "") == "1"
    if host not in ("127.0.0.1", "localhost", "::1") and not allow_public:
        print(
            f"REFUSED: ANALYZER_HOST={host!r} not loopback; "
            f"no-auth service must not bind public interfaces.",
            file=sys.stderr,
        )
        return 1
    try:
        import uvicorn
    except ImportError:
        print("uvicorn not installed", file=sys.stderr)
        return 1
    # 导入路径：analyzer 包内 app.main
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    uvicorn.run("app.main:app", host=host, port=port, log_level="info")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

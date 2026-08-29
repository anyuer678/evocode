#!/usr/bin/env bash
# 停止 start-dev.sh 启动的三个服务（infra 保留，docker compose down 自行决定）
cd "$(dirname "$0")/.." || exit 1
for f in logs/*.pid; do
  [ -f "$f" ] || continue
  p=$(cat "$f"); echo "stopping $f (pid $p)"
  kill "$p" 2>/dev/null; rm -f "$f"
done
echo "done（postgres/redis 仍在 docker 内运行，如需停止：docker compose down）"

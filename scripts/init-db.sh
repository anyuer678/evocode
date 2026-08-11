#!/usr/bin/env bash
# EvoCode 数据库初始化（mac/linux）：按序执行 db/migration/V*.sql
# 依赖：docker compose up -d postgres（容器 evocode-postgres）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTAINER="evocode-postgres"

# 1. 读取 .env（若存在）
if [ -f "$ROOT/.env" ]; then
  set -a; . "$ROOT/.env"; set +a
fi
DB="${POSTGRES_DB:-evocode}"
USER="${POSTGRES_USER:-evocode}"

# 2. 等待 postgres 就绪
echo "等待 postgres 就绪..."
until docker exec "$CONTAINER" pg_isready -U "$USER" -d "$DB" >/dev/null 2>&1; do sleep 2; done
echo "postgres 就绪"

# 3. 建版本跟踪表
docker exec -i "$CONTAINER" psql -U "$USER" -d "$DB" -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE IF NOT EXISTS schema_version (
  version    VARCHAR(50) PRIMARY KEY,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
SQL

# 4. 按序执行未应用迁移
APPLIED=$(docker exec -i "$CONTAINER" psql -U "$USER" -d "$DB" -t -A -c "SELECT version FROM schema_version ORDER BY version" 2>/dev/null)
for f in "$ROOT"/db/migration/V*.sql; do
  ver="$(basename "$f" .sql)"
  if printf '%s\n' "$APPLIED" | grep -qx "$ver"; then
    echo "跳过（已应用）：$ver"; continue
  fi
  echo "应用迁移：$ver ..."
  docker exec -i "$CONTAINER" psql -U "$USER" -d "$DB" -v ON_ERROR_STOP=1 < "$f"
  docker exec -i "$CONTAINER" psql -U "$USER" -d "$DB" -q -c "INSERT INTO schema_version(version) VALUES ('$ver')" >/dev/null
  echo "完成：$ver"
done
echo "数据库初始化完成"

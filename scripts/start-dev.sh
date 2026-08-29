#!/usr/bin/env bash
# EvoCode 一键启动（Linux/macOS 版，对应 start-dev.bat）
# backend :18080  analyzer :8091  frontend :5173
set -u
cd "$(dirname "$0")/.." || exit 1

FAIL=""
log() { printf '\n%s\n' "$*"; }

log "================================================
  EvoCode one-click launcher (bash)
  backend :18080  analyzer :8091  frontend :5173
================================================"

# ---------- 0. dependency check ----------
command -v docker >/dev/null 2>&1 || { echo "[fail] docker not found"; exit 1; }
[ -x backend/mvnw ] || { echo "[fail] backend/mvnw missing (需要可执行的 mvnw)"; exit 1; }
PY=analyzer/.venv/bin/python
[ -x "$PY" ] || { echo "[fail] analyzer/.venv/bin/python missing —— 先创建 venv 并安装依赖"; exit 1; }
command -v npm >/dev/null 2>&1 || echo "[note] npm not found, frontend will be skipped"

mkdir -p logs

# ---------- 1. infra ----------
log "[1/5] starting postgres/redis (docker compose) ..."
docker compose -f docker-compose.yml up -d || {
  if docker ps --format '{{.Names}}' | grep -q '^evocode-postgres$'; then
    echo "  [ok] evocode-postgres already running, continuing"
  else
    echo "[fail] docker compose up failed"; exit 1
  fi
}

tries=0
until docker exec evocode-postgres pg_isready -U evocode -d evocode >/dev/null 2>&1; do
  tries=$((tries + 1))
  [ "$tries" -gt 30 ] && { echo "[fail] postgres not ready after 60s"; exit 1; }
  sleep 2
done
echo "      postgres ready"

# ---------- 2. migration (idempotent) ----------
log "[2/5] db migration (idempotent) ..."
bash scripts/init-db.sh || { echo "[fail] migration failed"; exit 1; }

# ---------- 3. analyzer ----------
log "[3/5] starting analyzer :8091 (logs/analyzer.log) ..."
: "${ANALYZER_PG_DSN:=postgresql://evocode:evocode_dev@127.0.0.1:5432/evocode}"
export ANALYZER_PG_DSN
( cd analyzer && ANALYZER_PG_DSN="$ANALYZER_PG_DSN" nohup "$PY" -m uvicorn app.main:app \
    --host 127.0.0.1 --port 8091 > ../logs/analyzer.log 2>&1 & echo $! > ../logs/analyzer.pid )

# ---------- 4. backend ----------
log "[4/5] packaging + starting backend :18080 (logs/backend.log) ..."
( cd backend && ./mvnw -q -DskipTests package ) || { echo "[fail] backend build failed"; exit 1; }
JAR=$(ls -t backend/target/*.jar 2>/dev/null | grep -v '\.orig' | head -1)
[ -n "$JAR" ] || { echo "[fail] backend jar not found"; exit 1; }
nohup java -jar "$JAR" > logs/backend.log 2>&1 & echo $! > logs/backend.pid

# ---------- 5. frontend ----------
if command -v npm >/dev/null 2>&1; then
  log "[5/5] starting frontend :5173 (logs/frontend.log) ..."
  ( cd frontend && [ -d node_modules ] || npm install --silent; nohup npm run dev > ../logs/frontend.log 2>&1 & echo $! > ../logs/frontend.pid )
else
  log "[5/5] skipped frontend (npm not found)"
fi

# ---------- health checks ----------
log "health checks ..."
ok=1
for i in $(seq 1 30); do
  curl -sf http://127.0.0.1:8091/health >/dev/null 2>&1 && break
  sleep 2
  [ "$i" = 30 ] && { echo "[warn] analyzer health check timeout (logs/analyzer.log)"; ok=0; }
done
for i in $(seq 1 30); do
  curl -sf http://127.0.0.1:18080/actuator/health >/dev/null 2>&1 && break
  sleep 2
  [ "$i" = 30 ] && { echo "[warn] backend health check timeout (logs/backend.log)"; ok=0; }
done

log "================================================"
[ "$ok" = 1 ] && echo "  all services up" || echo "  started with warnings (see logs/)"
echo "  stop: bash scripts/dev-down.sh"
log "================================================"

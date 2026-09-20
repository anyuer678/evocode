# EvoCode 全栈 compose（生产向本地基线）

> 状态：`local-tool` · **无认证** · 所有端口仅 `127.0.0.1` · 勿公网  
> 与现有 `docker-compose.yml`（基础设施）兼容；本文件尝试补齐可文档化的全栈编排说明。

**重要**：backend / analyzer / frontend 当前以宿主进程为主（`scripts/start-dev.bat`），Docker 镜像构建文件若不完整，本 compose 仅保证 **基础设施 + 端口绑定策略**；应用请按 README 在宿主启动，并遵守下方绑定铁律。

## 绑定铁律（启动校验）

| 组件 | 允许绑定 | 禁止 |
|------|----------|------|
| PostgreSQL / Redis / Sonar | `127.0.0.1` | `0.0.0.0` |
| Backend | `127.0.0.1`（`server.address`） | 公网 IP / `0.0.0.0` |
| Analyzer | uvicorn `--host 127.0.0.1` | 其它 host（除非 `EVOCODE_ALLOW_PUBLIC_BIND=1`） |
| Frontend dev | Vite 默认本机 | 公网 preview 无鉴权 |

校验脚本：`python scripts/check_bind_guard.py`

## 启动顺序

```bash
# 1) 基础设施
docker compose -f docker-compose.full.yml --env-file .env up -d postgres redis

# 2) 校验绑定策略
python scripts/check_bind_guard.py

# 3) 应用（宿主，见 README）
# backend: SERVER_ADDRESS=127.0.0.1 BACKEND_PORT=18080
# analyzer: python -m uvicorn app.main:app --host 127.0.0.1 --port 8091
# frontend: npm run dev
```

## 与 LLM

无 `LLM_API_KEY` 时报告走规则版降级（`source=RULES`），属预期行为。

# AD-017：本机端口冲突处理（redis 6380 绑定偏离）

- **日期**：2026-08-10
- **背景**：P0 骨架搭建时发现本机存在另一项目（polycode）的容器组运行中，占用 6379（polycode-redis）、8080（polycode-gateway）、8081（polycode-auth）等端口；且均绑定 0.0.0.0，EvoCode 无法绑定 127.0.0.1 同名端口。
- **决策**：
  1. redis 宿主端口改绑 `127.0.0.1:6380`（容器内仍 6379），docker-compose.yml 已注明原因；
  2. backend/analyzer 端口 8080/8081 保持契约不变，本机联调时可用 `BACKEND_PORT`/`uvicorn --port` 临时切换（smoke.ps1 支持 `SMOKE_*_URL` 覆盖）；**不停止 polycode 容器**（用户其他项目，勿动）；
  3. 后续若 polycode 不再运行，可回退 redis 至 6379（改 compose 一行）。
- **影响**：redis 仅 P3+ 缓存用，P0/P1/P2 不受影响；文档基线（02 §12.1 端口表）以本 AD 为偏离记录，redis 配置项化后无代码硬编码。
- **验证**：`docker compose up -d postgres redis` 成功；`scripts/init-db.ps1` 全链路通过（4 表 + schema_version）。

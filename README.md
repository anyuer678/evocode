# EvoCode

> **AI Software Evolution Platform** · 基于大模型的软件维护与演化平台
> 让 AI 持续理解、诊断、维护已有软件 —— 做软件的"体检 + 医生 + 健康档案"

## 定位

**不做** AI 写代码工具（区别于 Cursor / Copilot），**不做**普通 Code Review，而是软件健康管理平台：

```
人：  体检  →  诊断  →  治疗建议  →  健康档案
      ↓        ↓          ↓          ↓
软件：扫描  →  分析  →  重构建议  →  演化记录
```

核心闭环：`导入项目 → 扫描(结构/语言/技术栈) → 质量(静态扫描) → 架构(调用关系) → AI 综合诊断 → 技术债登记 → 报告存档 → 演化跟踪`

## 系统架构

```
               Web UI (Vue3 + TS + Vite + ECharts)
                          │  /api/v1
                Spring Boot 3 (Java 17)
              项目管理 · 任务编排 · 持久化
                          │  /analyze/v1 (仅 127.0.0.1)
              Python Analyzer (FastAPI)
      扫描/语言/技术栈 │ 质量(Sonar) │ 架构(tree-sitter) │ 演化(git) │ AI(LLM+RAG)
                          │
        PostgreSQL(pgvector) · Redis · 磁盘代码库(data/) · LLM API(OpenAI 兼容)
```

## 文档索引

| 文档 | 内容 | 何时读 |
|---|---|---|
| [docs/01-需求分析.md](docs/01-需求分析.md) | 定位/竞品/用户故事/44 条 FR/界面/NFR/验收用例 | 开发任何功能前对照 |
| [docs/02-开发指导.md](docs/02-开发指导.md) | MVP 边界/技术决策/完整 DDL/API 契约/流水线/评分模型/开发顺序/部署 | 写代码前与遇到技术选型时 |
| [docs/03-开发规范.md](docs/03-开发规范.md) | 三端编码规范/契约/安全/日志/测试/DoD | 每次提交前对照 |
| [docs/04-架构设计.md](docs/04-架构设计.md) | 4+1 视图/类级分解/插件 SPI/线程模型/时序/演进规划 A0-A3 | 搭骨架、加模块、做扩展前 |
| [docs/05-架构审查.md](docs/05-架构审查.md) | 架构交叉审查（P0×3/P1×8/P2×6）+ 修订清单 | 已修订并落实，作为决策记录 |
| [docs/06-API契约.md](docs/06-API契约.md) | 全端点请求/响应示例、错误码全表、SSE 协议、内部 API | **接口实现的唯一契约来源** |
| [docs/07-数据字典.md](docs/07-数据字典.md) | 16 表字段级字典、枚举、JSONB 结构、迁移规则 | 写实体/DTO/前端类型时查表 |
| [docs/08-测试计划.md](docs/08-测试计划.md) | 单元/契约/集成/安全/性能用例、阶段门禁、回归清单 | 开发与答辩验证时执行 |

辅助目录：`docs/decisions/`（AD 决策记录）、`docs/devlog/`（周记）、`docs/screenshots/`（演示截图）。

## 当前进度（P1 已完成，见 docs/devlog/2026-08-10-p1.md）

```
✅ P0 三端骨架 + 基础设施 + 脚本，三端门禁全绿（mvnw test / ruff+pytest / npm lint+build，init-db 实测通过）
✅ P1 项目+扫描（project CRUD / zip 上传 / GitHub clone / 档案快扫 / 文件地图，三端闭环联调通过）
✅ P2 v0.1 MVP 完成（上传→扫描→AI 报告端到端）：P2a 分析任务链路 + P2b LLM/规则版报告 + P2c 报告前端（体检报告/分析历史/发起与重新生成）
✅ P3 质量完成（P3a /analyze/v1/quality + P3b FULL 流程接入 + P3c 质量前端：issues 查询端点/聚合指标/详情页质量区；Sonar 不可用降级 N/A 不影响其他）
✅ P4 架构完成（P4a /analyze/v1/architecture：tree-sitter 节点/调用边/分层违规 + 节点指标；P4b 落库 V003 + GET /architecture 查询端点；P4c 前端架构视图：ECharts 分层图 + 违规列表 + 2010 空态）
🟡 P5 演化（P5a /analyze/v1/evolution 已完成：git log 统计 + 周趋势/TOP 文件/作者 + 规则热点；P5b 落库 V004/V007 + GET /evolution 查询端点待做；P5c 前端演化页待做）
⬜ P5 演化 → P6 AI 医生 → P7 技术债+Dashboard = v1.0
```

## 快速开始

前置：JDK 17+、Node 20+、Python 3.11+（analyzer/.venv 需先建好）、Docker Desktop（postgres/redis）、Git。

**推荐：一键启动**（Windows，双击或命令行运行）

```bat
scripts\start-dev.bat     :: 基础设施 → 数据库迁移 → analyzer → backend → frontend → 健康检查
scripts\dev-down.bat      :: 停止（按端口停止 backend/analyzer/frontend）
```

- 各服务在**独立窗口**运行（`EvoCode-analyzer` / `EvoCode-backend` / `EvoCode-frontend`），关闭窗口即停；主窗口做健康检查，失败会停留显示错误原因（不闪退）。
- 端口约定：**backend 18080**（默认 8080 与 polycode-gateway 冲突，须经 `BACKEND_PORT` 覆盖）、**analyzer 8081**（backend 的 `analyzer-url` 默认即此）、**frontend 5173**。
- 数据库迁移自动执行（幂等，`init-db.ps1`，`schema_version` 记录跳过已应用）。

**手动分步**（等价，排查时用）

```bash
docker compose up -d                          # postgres(pgvector) / redis
scripts/init-db.ps1                           # 执行 db/migration/V*.sql（幂等，win）
cd analyzer && .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8081
cd backend && $env:BACKEND_PORT=18080; java -jar target\evocode-backend-0.1.0-SNAPSHOT.jar
# 注：backend 弃用 mvn spring-boot:run（本机报 ClassNotFound），统一 java -jar 打包产物
cd frontend && npm install && npm run dev
```

打开 http://localhost:5173 → 创建项目（上传 zip 或 GitHub 地址）→ 发起分析 → 查看体检报告。

## 仓库结构

```
backend/     Spring Boot 3 后端（业务/任务编排/持久化）
analyzer/    Python FastAPI 分析服务（扫描/质量/架构/演化/AI）
frontend/    Vue3 前端
scripts/     一键启动/初始化/冒烟脚本
docs/        需求、开发指导、开发规范 + decisions/devlog/screenshots
samples/     演示用示例项目（zip，小体积）【规划中，P7 产品化时创建】
```

## 开发路线（里程碑）

```
v0.1 代码体检 MVP（上传→扫描→AI 报告）→ v0.2 质量(Sonar) → v0.3 架构分析
→ v0.4 演化分析 → v0.5 AI 医生 → v1.0 技术债+文档+Dashboard（毕业设计级）
```

详细任务分解与工时见 `docs/02-开发指导.md` §11。

## 重要说明（务必先读）

> 本文档体系是**纲要与基线**，不是死规定。文档未覆盖、或与实际情况冲突时，**具体情况具体分析**：
> 1. 先记录：把新决策写进 `docs/decisions/AD-xxx.md`，周记记入 `docs/devlog/`
> 2. 再执行：合理偏离纲要，优先保证"能跑、能演示、能讲清"
> 3. 后回填：把偏离结果同步回对应文档
>
> 唯一硬约束：三端验证命令必须通过（backend `mvn test` / analyzer `ruff + pytest` / frontend `npm run lint && build`）。

## 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.2 | 2026-08-10 | 文档体系初版（需求/指导/规范详细版） |
| v1.3 | 2026-08-10 | 新增 README、灵活性声明、技术附录（规则表/Prompt/配置/代码骨架） |
| v1.4 | 2026-08-10 | 新增架构设计（4+1 视图、类级分解、SPI、演进规划 A0-A3） |
| v1.5 | 2026-08-10 | 架构审查（05）并全量修订；新增 API 契约（06）：LLM 出口唯一化、文件内容/重新生成端点、幂等重建、SSE 协议 |
| v1.6 | 2026-08-10 | 第二轮架构审查修订（会话列表/删除、错误码 2007/2008、dependency/knowledge_chunk/hotspot 落库）；新增数据字典（07）、测试计划（08） |
| v1.7 | 2026-08-10 | 第三轮交叉审查（05 §10）C-1~C-17 全部执行（报告来源落库、AC-2 用例、2009 克隆错误码、LLM 残留清理等）；**P0 骨架完成**：三端脚手架 + docker-compose + scripts + V001 迁移，三端门禁验证全绿（详见 docs/devlog/2026-08-10-p0.md） |
| v1.8 | 2026-08-10 | **P1 项目+扫描完成**：后端 project CRUD / zip 上传 / GitHub clone / 快扫 / 文件地图与内容接口，前端三页面（列表/创建/详情），analyzer 扫描链路，三端联调闭环通过；修复 PG timestamptz↔LocalDateTime 映射、分页格式对齐契约（详见 docs/devlog/2026-08-10-p1.md） |
| v1.9 | 2026-08-10 | **P2a 分析任务链路完成**：发起 FULL 分析（排他 2002）/ 历史分页 / 状态轮询 + 异步状态机 QUEUED→SCAN→SCAN_DONE→DONE（AnalysisController/AnalysisService/AnalysisRunner），后端测试 54/54，实库联调闭环（详见 docs/devlog/2026-08-10-p2a.md） |
| v2.0 | 2026-08-10 | **P2b 报告生成完成**：analyzer /analyze/v1/report（LLM Provider 抽象 OpenAI 兼容 + 规则版降级，健康分=质量40/结构30/依赖15/规模15±10 修正，scoreDetail 可解释）；后端 REPORT 阶段接入 + GET /analyses/{id}/report + POST /report/regenerate（2008），测试 61/61，实库闭环（详见 docs/devlog/2026-08-10-p2b.md） |
| v2.1 | 2026-08-10 | **P2c 报告前端完成，v0.1 MVP 端到端可用**：详情页体检报告视图（健康分/维度星级/风险/建议 + LLM/规则来源标注）、分析历史（点击切换）、发起完整分析 + 重新生成按钮、任务 2s 轮询；vite proxy 支持 VITE_PROXY_TARGET 覆盖后端端口（详见 docs/devlog/2026-08-10-p2c.md） |
| v2.2 | 2026-08-10 | **P3a 质量分析端点完成**：analyzer /analyze/v1/quality（SonarClient：可用性探测 token/host/scanner 三条件 + sonar-scanner 执行 + CE 轮询 + 指标/issues 归一），Sonar 不可用/未配置 → 200 + metrics.available=false 降级 N/A（详见 docs/devlog/2026-08-10-p3a.md） |
| v2.3 | 2026-08-10 | **P3b FULL 流程接入质量完成**：V002 quality_issue 迁移 + 实体/Mapper/级联清理；AnalysisRunner REPORT 前调 /analyze/v1/quality，issues 落库、质量指标传入报告（Sonar 接入用真实指标评分 + 漏洞/Bug 风险，不可用走代理指标）；测试 63/63 + 70 例（详见 docs/devlog/2026-08-10-p3b.md） |
| v2.4 | 2026-08-10 | **P3c 质量前端完成，P3 闭环**：GET /projects/{id}/quality-issues（筛选/分页/metrics 聚合）+ 详情页质量区（指标徽章/issues 列表/severity 色标/空态引导）；测试 68/68（详见 docs/devlog/2026-08-10-p3c.md） |
| v2.5 | 2026-08-10 | **P4a 架构分析端点完成**：analyzer /analyze/v1/architecture（tree-sitter Python/Java 解析 + 节点/调用边提取 + 分层违规检测 + 出入度指标），依赖 tree-sitter 系；测试 74 例（详见 docs/devlog/2026-08-10-p4a.md） |
| v2.6 | 2026-08-10 | **P4b 架构落库 + 查询端点完成**：V003 三表（architecture_node/edge/violation）+ FULL 流程接入架构阶段（失败降级不阻塞报告）+ GET /projects/{id}/architecture（analysisId 缺省取最新，无数据 404/2010）+ 项目删除级联；backend 测试 74 例，实库闭环 3 节点/3 边/1 违规（详见 docs/devlog/2026-08-10-p4b.md） |
| v2.7 | 2026-08-11 | **审查修复**：analyzer 加 `GET /` 根路由（提示前端入口 :5173，消除 404 误导，测试 75/75）；vite proxy 默认指向 18080（`VITE_PROXY_TARGET` 仍可覆盖）；修复 `ErrorCode` 2010 缩进、`.env.example` 默认 `BACKEND_PORT=18080`、`smoke.ps1` 注释旧脚本名；文档对齐（p4b 迁移说明、README samples 标注规划中） |
| v2.8 | 2026-08-11 | **P4c 前端架构视图完成（P4 收官）**：详情页新增"架构分析"区块——ECharts 分层图（5 泳道按 nodeType 分层、违规边标红、点击违规高亮节点、hover 显示出入度）+ 违规列表（严重级徽标/描述/修复建议/ai_note 占位）；2010 空态友好提示；无头 Edge 真渲染冒烟通过（mock 契约数据）；修复 4 个前端 bug（axios 双前缀、字符串 ref 不回填、nextTick 时序、非 2xx 丢失业务 code），详见 `docs/devlog/2026-08-10-p4c.md` |
| v2.9 | 2026-08-11 | **P5a 演化统计端点完成**：analyzer `/analyze/v1/evolution`（git log 解析 + 周聚合趋势/TOP 文件/作者 + 规则热点 HIGH/MEDIUM + evidence 证据数组；非 git 目录 → 200 + `available:false`）；不引入 Python git 库（subprocess 调系统 git）；测试 85/85 + 真实仓库冒烟（详见 `docs/devlog/2026-08-11-p5.md`） |

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

## 功能特性

| 能力域 | 说明 |
|---|---|
| 项目导入 | zip 上传 / GitHub 克隆 / 档案快扫 / 文件地图 |
| 健康评分 | 规则版 + LLM 增强综合评分（质量/结构/依赖/规模 4 维，可复现） |
| 质量分析 | Sonar 静态扫描（不可用降级 N/A）+ issue 规则解释 |
| 架构分析 | tree-sitter 跨语言节点/调用边/分层违规（Python/Java/JS/TS/Go） |
| 演化分析 | git log 统计 + 周趋势/TOP 文件/作者 + 规则热点 |
| 依赖分析 | pom/package 解析 + 内置 EOL 风险规则表（Spring Boot/Vue/React/Node 等） |
| AI 医生 | RAG 检索增强问答 + SSE 流式生成 + 引用溯源（防幻觉） |
| 技术债 | 四源聚合（ARCH/QUALITY/EVOLUTION/DEPEND）+ 状态机 + 手动登记 |
| 文档生成 | README/架构/API 三类文档（LLM 生成 + 无 Key 规则版降级） |
| 报告 | AI 报告 + 历史趋势对比 + Markdown 导出 + 报告拆表（analysis_report） |
| Dashboard | 跨项目总览 + 健康分布/语言构成/状态分布 + 深浅色主题 |
| 可靠性 | 分析进度 SSE 实时推送、任务中断启动恢复、Redis 列表缓存降级 |

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + TypeScript + Vite + ECharts + Naive UI + Pinia + vitest |
| 后端 | Spring Boot 3.3（Java 17）+ MyBatis-Plus + Lombok |
| 分析器 | Python 3.11 + FastAPI + tree-sitter（Python/Java/JavaScript/TypeScript/Go）+ pgvector |
| AI | OpenAI 兼容 LLM API（DeepSeek/OpenAI/Ollama）+ RAG（bge-m3 向量，关键词兜底） |
| 基础设施 | PostgreSQL(pgvector) + Redis（列表缓存，AD-018）+ Docker Compose |
| 质量工具 | SonarQube（可选 `--profile full`）+ ESLint/Prettier + ruff + ArchUnit |

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

## 里程碑进度（当前 v1.3 收官：前端 Naive UI 全量重构 + 技术债清零 + A0→A2 架构演进达成）

```
✅ P0 三端骨架 + 基础设施 + 脚本，三端门禁全绿（mvnw test / ruff+pytest / npm lint+build，init-db 实测通过）
✅ P1 项目+扫描（project CRUD / zip 上传 / GitHub clone / 档案快扫 / 文件地图，三端闭环联调通过）
✅ P2 v0.1 MVP 完成（上传→扫描→AI 报告端到端）：P2a 分析任务链路 + P2b LLM/规则版报告 + P2c 报告前端
✅ P3 质量完成（/analyze/v1/quality + FULL 流程接入 + 质量前端；Sonar 不可用降级 N/A）
✅ P4 架构完成（/analyze/v1/architecture：tree-sitter 节点/调用边/分层违规；落库 V003 + 查询端点；前端 ECharts 分层图）
✅ P5 演化完成（/analyze/v1/evolution：git log 统计 + 周趋势/TOP 文件/作者 + 规则热点；V004/V007 落库 + 查询端点 + 前端演化页）
✅ P6 AI 医生完成（v0.5）：RAG 切片/embedding/pgvector（V005/V006）+ /rag/index·search + /analyze/v1/chat SSE 生成端 + 会话 CRUD/SseEmitter 透传 + 前端 AI 医生页（流式/引用卡片/Monaco）
✅ P7 技术债 + 文档完成（v0.6）：四源聚合（ARCH/QUALITY/EVOLUTION/DEPEND）+ 状态机接口 + 前端技术债区块；三类文档生成（README/架构/API）+ 前端文档区块
✅ P8 Dashboard + 美化 + 深浅色完成（v1.0）：/dashboard 跨项目总览 + 设计 token 双主题 + 导航/一键切换
✅ 端到端真库验证 + 两轮三端审查修复（v1.0 质量门禁全绿：analyzer 119 / backend 120 / frontend lint+build）
✅ P9a UI 精致化完成（v1.1）：设计 token 精修 + 项目列表卡片化（健康分环形/语言徽章/筛选工具栏/视图切换）+ 空态/骨架/动效 + vitest 单测（TD-06，12/12）
✅ P9b 项目操作/导出完成：backend PATCH /projects/{id}（重命名/描述）+ GET /projects/{id}/report/export（Markdown 下载）；前端 ⋮ 操作菜单；TD-08 docgen 规则版降级（无 Key 也可生成）
✅ P9c 历史报告对比完成：backend GET /projects/{id}/report/history（SUCCEEDED 聚合摘要，healthScore 数值防御）；前端报告区「历史趋势」折叠区（健康分折线 + 参考线 + 两期维度对比 + 风险 diff 三色 + 点击切换基准期）
✅ P9d 依赖分析完成：analyzer /analyze/v1/dependency（pom/package 解析 + EOL 规则表）+ V009 dependency 表落库 + 前端依赖区块（风险分组/依赖表/EOL 徽标/统计卡）；TD-04 DEPEND 源改读表
✅ P9e 进度通知/搜索完成（v1.1 收官）：backend GET /projects/{id}/analyses/events SSE（AnalysisProgressPublisher 按 projectId 广播，状态机变更点推送）+ 前端详情页实时进度条/完成 Toast/断线轮询兜底；healthScore 排序白名单；TD-01 analyzer /analyze/v1/explain（规则版 + LLM 增强）；TD-12 README 启动命令收敛
✅ 架构演进 A1/A2 达成 + 技术债清零（v1.2）：TD-09 架构扫描语言扩展（JS/TS/Go）+ TD-10 token 估算精确化 + SPI-1 解析器注册表（新增语言 ≤1 天配置级，languages 过滤修复）+ TD-05 Redis 列表读缓存（AD-018，含降级）+ SPI-6 报告拆表（analysis_report 表，healthScore 列化）；三端门禁 backend 155 / analyzer 158 / frontend 全绿；FULL 链路集成冒烟通过（详见 docs/metrics/2026-08-13-v1.2.md）
✅ 收官完善（v1.2+）：AD-019 任务中断启动恢复（StartupTaskRecovery）+ 安全冒烟脚本（security-smoke.ps1，T-S-01/05/07/08）+ samples/demo-store 演示项目；frontend lint 清零（0 errors / 0 warnings）
✅ 前端全面重构 + 审查修复（v1.3）：**Naive UI 全量迁移**（框架/项目列表/新建/Dashboard/详情 9 子视图全部组件化）；analyzer 端口 8081→8091（避本机 polycode-auth 冲突）；修复报告显示（FAILED 项目加载历史/图表隐藏容器崩溃/详情导航切换失效/doctor 首问丢失）等；多轮审查（后端/前端/契约/安全）；vitest 组件测试 23 用例
```

## 快速开始

前置：JDK 17+、Node 20+、Python 3.11+（`analyzer/.venv` 需先建好：`cd analyzer && python -m venv .venv && .venv\Scripts\pip install -r requirements.txt`）、Docker Desktop（postgres/redis）、Git。

**推荐：一键启动**（Windows，双击或命令行运行）

```bat
scripts\start-dev.bat     :: 基础设施 → 数据库迁移 → analyzer → backend → frontend → 健康检查
scripts\dev-down.bat      :: 停止（按端口停止 backend/analyzer/frontend；容器保留数据）
```

- 各服务在**独立窗口**运行（`EvoCode-analyzer` / `EvoCode-backend` / `EvoCode-frontend`），关闭窗口即停；主窗口做健康检查，失败会停留显示错误原因（不闪退）。
- 端口约定：**backend 18080**、**analyzer 8091**（backend 的 `analyzer-url` 默认即此）、**frontend 5173**。
- 数据库迁移自动执行（幂等，`init-db.ps1` 按序跑 `db/migration/V*.sql`，`schema_version` 记录跳过已应用；V001–V011）。
- **SonarQube 为可选组件**：默认一键启动不拉起（体积大）；需要真实质量指标时 `docker compose --profile full up -d` 单独启动。未启动/未配置时质量维度降级为代理指标（`metrics.available=false`，前端显示 N/A），不阻塞分析全链路（TD-07）。

**配置（可选，根目录 `.env`，复制 `.env.example` 后按需改）**

```env
# AI 医生 / 文档生成（不配则降级：报告走规则版、AI 医生返回 LLM_NO_KEY、文档无法生成）
LLM_API_KEY=sk-xxx
LLM_BASE_URL=http://127.0.0.1:11434/v1   # Ollama / DeepSeek 等 OpenAI 兼容端点
# RAG 向量化（不配 embedding 网关则关键词检索兜底）
LLM_EMBEDDING_MODEL=bge-m3
# analyzer 直连 PG（start-dev.bat 已内置默认值；改过 DB 密码时在此覆盖）
ANALYZER_PG_DSN=postgresql://evocode:evocode_dev@127.0.0.1:5432/evocode
```

- 配置文件优先级：进程环境变量 > `analyzer/.env` > **根 `.env`**（analyzer/backend 均从根 `.env` 读取，一处配置全局生效）。

**手动分步**（等价，排查时用）

```bash
docker compose up -d                          # postgres(pgvector) / redis
scripts/init-db.ps1                           # 执行 db/migration/V*.sql（幂等，win）
cd analyzer && $env:ANALYZER_PG_DSN="postgresql://evocode:evocode_dev@127.0.0.1:5432/evocode"
cd analyzer && .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8091
cd backend && $env:BACKEND_PORT=18080; java -jar target\evocode-backend-0.1.0-SNAPSHOT.jar
# 注：backend 弃用 mvn spring-boot:run（本机报 ClassNotFound），统一 java -jar 打包产物
cd frontend && npm install && npm run dev
```

打开 http://localhost:5173 → 创建项目（上传 zip 或 GitHub 地址）→ 发起分析 → 查看体检报告 / 架构 / 演化 / AI 医生 / 技术债 / 文档。

## 常见问题

| 症状 | 排查/解决 |
|---|---|
| 页面打开但**接口 500**（如项目列表加载失败） | **先跑 `scripts\check-env.ps1` 自动自检**；多半是 backend 未就绪（vite proxy 连不上 18080 会返回 500）：确认 `start-dev.bat` 主窗口健康检查里 `[backend] OK`；或 `curl http://127.0.0.1:18080/api/v1/health` |
| `start-dev.bat` 卡在 `[1/5]` docker compose 失败 | Docker Desktop 引擎未启动/崩溃：启动 Docker Desktop 等引擎就绪后重跑；容器已在运行时 bat 会继续（不会卡死） |
| 前端窗口报 `npm` 找不到 package.json | 子窗口 cd 失败：确保双击 `start-dev.bat`（不要手动在仓库根跑 npm）；前端窗口标题应为 `EvoCode-frontend` |
| 分析结果里演化/技术债为空 | 项目是 zip 上传（非 git）→ 演化 `available=false` 属正常；技术债需架构/质量/演化数据支撑 |
| AI 医生/文档生成报 LLM 未配置 | 根目录 `.env` 配 `LLM_API_KEY`（见上方配置节），重启服务 |
| 未启动 Redis，项目列表接口会报错吗 | 不会：TD-05 列表缓存走 Spring Cache + Redis，Redis 不可达时 `CacheErrorHandler` 降级直查库（仍 200），仅失去缓存加速（AD-018） |

> 手动分步（等价）仅一处：见上方「快速开始 → 手动分步」——统一以 `scripts/` 入口为准（start-dev.bat / init-db.ps1），不在此重复命令。

## 仓库结构

```
backend/     Spring Boot 3 后端（业务/任务编排/持久化）
analyzer/    Python FastAPI 分析服务（扫描/质量/架构/演化/AI）
frontend/    Vue3 前端
scripts/     一键启动/初始化/冒烟脚本
docs/        需求、开发指导、开发规范 + decisions/devlog/screenshots
samples/     演示用示例项目（zip，小体积）：demo-store 多语言订单服务
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
| v1.2 | 2026-08 | 文档体系初版（需求/指导/规范详细版） |
| v1.3 | 2026-08 | 新增 README、灵活性声明、技术附录（规则表/Prompt/配置/代码骨架） |
| v1.4 | 2026-08 | 新增架构设计（4+1 视图、类级分解、SPI、演进规划 A0-A3） |
| v1.5 | 2026-08 | 架构审查（05）并全量修订；新增 API 契约（06）：LLM 出口唯一化、文件内容/重新生成端点、幂等重建、SSE 协议 |
| v1.6 | 2026-08 | 第二轮架构审查修订（会话列表/删除、错误码 2007/2008、dependency/knowledge_chunk/hotspot 落库）；新增数据字典（07）、测试计划（08） |
| v1.7 | 2026-08 | 第三轮交叉审查（05 §10）C-1~C-17 全部执行（报告来源落库、AC-2 用例、2009 克隆错误码、LLM 残留清理等）；**P0 骨架完成**：三端脚手架 + docker-compose + scripts + V001 迁移，三端门禁验证全绿（详见 docs/devlog/2026-08-10-p0.md） |
| v1.8 | 2026-08 | **P1 项目+扫描完成**：后端 project CRUD / zip 上传 / GitHub clone / 快扫 / 文件地图与内容接口，前端三页面（列表/创建/详情），analyzer 扫描链路，三端联调闭环通过；修复 PG timestamptz↔LocalDateTime 映射、分页格式对齐契约（详见 docs/devlog/2026-08-10-p1.md） |
| v1.9 | 2026-08 | **P2a 分析任务链路完成**：发起 FULL 分析（排他 2002）/ 历史分页 / 状态轮询 + 异步状态机 QUEUED→SCAN→SCAN_DONE→DONE（AnalysisController/AnalysisService/AnalysisRunner），后端测试 54/54，实库联调闭环（详见 docs/devlog/2026-08-10-p2a.md） |
| v2.0 | 2026-08 | **P2b 报告生成完成**：analyzer /analyze/v1/report（LLM Provider 抽象 OpenAI 兼容 + 规则版降级，健康分=质量40/结构30/依赖15/规模15±10 修正，scoreDetail 可解释）；后端 REPORT 阶段接入 + GET /analyses/{id}/report + POST /report/regenerate（2008），测试 61/61，实库闭环（详见 docs/devlog/2026-08-10-p2b.md） |
| v2.1 | 2026-08 | **P2c 报告前端完成，v0.1 MVP 端到端可用**：详情页体检报告视图（健康分/维度星级/风险/建议 + LLM/规则来源标注）、分析历史（点击切换）、发起完整分析 + 重新生成按钮、任务 2s 轮询；vite proxy 支持 VITE_PROXY_TARGET 覆盖后端端口（详见 docs/devlog/2026-08-10-p2c.md） |
| v2.2 | 2026-08 | **P3a 质量分析端点完成**：analyzer /analyze/v1/quality（SonarClient：可用性探测 token/host/scanner 三条件 + sonar-scanner 执行 + CE 轮询 + 指标/issues 归一），Sonar 不可用/未配置 → 200 + metrics.available=false 降级 N/A（详见 docs/devlog/2026-08-10-p3a.md） |
| v2.3 | 2026-08 | **P3b FULL 流程接入质量完成**：V002 quality_issue 迁移 + 实体/Mapper/级联清理；AnalysisRunner REPORT 前调 /analyze/v1/quality，issues 落库、质量指标传入报告（Sonar 接入用真实指标评分 + 漏洞/Bug 风险，不可用走代理指标）；测试 63/63 + 70 例（详见 docs/devlog/2026-08-10-p3b.md） |
| v2.4 | 2026-08 | **P3c 质量前端完成，P3 闭环**：GET /projects/{id}/quality-issues（筛选/分页/metrics 聚合）+ 详情页质量区（指标徽章/issues 列表/severity 色标/空态引导）；测试 68/68（详见 docs/devlog/2026-08-10-p3c.md） |
| v2.5 | 2026-08 | **P4a 架构分析端点完成**：analyzer /analyze/v1/architecture（tree-sitter Python/Java 解析 + 节点/调用边提取 + 分层违规检测 + 出入度指标），依赖 tree-sitter 系；测试 74 例（详见 docs/devlog/2026-08-10-p4a.md） |
| v2.6 | 2026-08 | **P4b 架构落库 + 查询端点完成**：V003 三表（architecture_node/edge/violation）+ FULL 流程接入架构阶段（失败降级不阻塞报告）+ GET /projects/{id}/architecture（analysisId 缺省取最新，无数据 404/2010）+ 项目删除级联；backend 测试 74 例，实库闭环 3 节点/3 边/1 违规（详见 docs/devlog/2026-08-10-p4b.md） |
| v2.7 | 2026-08 | **审查修复**：analyzer 加 `GET /` 根路由（提示前端入口 :5173，消除 404 误导，测试 75/75）；vite proxy 默认指向 18080（`VITE_PROXY_TARGET` 仍可覆盖）；修复 `ErrorCode` 2010 缩进、`.env.example` 默认 `BACKEND_PORT=18080`、`smoke.ps1` 注释旧脚本名；文档对齐（p4b 迁移说明、README samples 标注规划中） |
| v2.8 | 2026-08 | **P4c 前端架构视图完成（P4 收官）**：详情页新增"架构分析"区块——ECharts 分层图（5 泳道按 nodeType 分层、违规边标红、点击违规高亮节点、hover 显示出入度）+ 违规列表（严重级徽标/描述/修复建议/ai_note 占位）；2010 空态友好提示；无头 Edge 真渲染冒烟通过（mock 契约数据）；修复 4 个前端 bug（axios 双前缀、字符串 ref 不回填、nextTick 时序、非 2xx 丢失业务 code），详见 `docs/devlog/2026-08-10-p4c.md` |
| v2.9 | 2026-08 | **P5a 演化统计端点完成**：analyzer `/analyze/v1/evolution`（git log 解析 + 周聚合趋势/TOP 文件/作者 + 规则热点 HIGH/MEDIUM + evidence 证据数组；非 git 目录 → 200 + `available:false`）；不引入 Python git 库（subprocess 调系统 git）；测试 85/85 + 真实仓库冒烟（详见 `docs/devlog/2026-08-11-p5.md`） |
| v3.0 | 2026-08 | **P5b 演化落库 + 查询端点完成**：V004（commit_stat/file_change_stat）+ V007（hotspot）迁移；FULL 流程 REPORT 前接入（失败降级不阻塞报告）；`GET /projects/{id}/evolution?range=30d/90d/180d/all`（非 Git/无数据 → 200 + `available:false`，非 404）；项目删除级联；backend 测试 82/82（详见 `docs/devlog/2026-08-11-p5.md`） |
| v3.1 | 2026-08 | **P5c 前端演化页完成（P5 收官）**：详情页新增"演化分析"区块——提交趋势折线 / TOP 变更文件条形 / 作者占比环形图（ECharts 按需引入）+ 风险中心热点卡片（riskLevel 徽标/evidence/aiConclusion）+ range 切换 + 非 Git 降级空态；无头 Edge 真渲染冒烟通过；P5 三阶段全部落地（详见 `docs/devlog/2026-08-11-p5.md`） |
| v3.2 | 2026-08 | **P5 代码审查修复**：级联删除加事务；演化查询项目不存在 → 404/2001；git 失败与空仓库语义区分（available）；周聚合时区归一 + Windows 弹窗抑制 + rangeDays 校验；前端请求竞态守卫 + ECharts 实例泄漏（v-show 常驻）+ 架构/演化与质量解耦（READY 判断）；文档契约同步（topFiles 字段/404 语义/删除时序/索引）；三端测试 90+83+全绿（详见 `docs/devlog/2026-08-11-p5.md` 审查修复段） |
| v3.3 | 2026-08 | **P6a RAG 基础完成**：V005/V006 迁移（tech_debt/generated_doc/chat_session/chat_message/knowledge_chunk，vector(1024)+HNSW）；analyzer 切片（tree-sitter 符号 ≤800 token 滑切）+ `embed()`（/embeddings 默认 bge-m3）+ PG 直连（只碰 knowledge_chunk，项目级全量重建）+ `POST /analyze/v1/rag/index`（无 PG → 200 stored=false 降级）/ `/rag/search`（向量 cosine + 关键词 LIKE 合并去重）；无 key → 纯关键词兜底；analyzer 测试 107/107（详见 `docs/devlog/2026-08-11-p6.md`） |
| v3.4 | 2026-08 | **P6b AI 医生会话/SSE 完成**：analyzer `POST /analyze/v1/chat` SSE 生成端（D.6 prompt、温度 0.7、history ≤6 轮、检索空兜底话术、引用 [path:line] 集合校验防幻觉、delta/citations/done/error 事件流）；backend 会话 CRUD（GET/POST/DELETE chats + 消息分页）+ 防重复 2007 + 标题自动生成 + SseEmitter 透传（JDK HttpClient 流式读 analyzer，done 落库带 citations + messageId）；backend 测试 97/97（详见 `docs/devlog/2026-08-11-p6.md`） |
| v3.5 | 2026-08 | **P6c 前端 AI 医生页完成**：详情页新增 AI 医生区块——会话列表（新建/删除）+ 对话（SSE 流式渲染 + 输入区 + 可选 @文件全文发送）+ 引用卡片（点击 → Monaco 按需加载弹层定位行号）；`api/chat.ts` 用 fetch + ReadableStream 逐行解析 SSE 事件（断线/无 done → CONNECTION_LOST 提示）；渲染先转义防 XSS；frontend lint/build 全绿（详见 `docs/devlog/2026-08-11-p6.md`） |
| v3.6 | 2026-08 | **P6d AI 医生收官（v0.5 交付）**：三端回归全绿（analyzer 107 / backend 97 / frontend lint+build）；真实 HTTP 冒烟验证 RAG 降级语义（无 PG：index 200 stored=false / search 503 / chat SSE 兜底话术帧）与 SSE 帧逐字节符合契约 §4.2；端到端真库验证待 Docker 恢复（环境阻塞，清单见 devlog）；遗留增强：Monaco 体积、历史滚动摘要、AI 标题（详见 `docs/devlog/2026-08-11-p6.md`） |
| v3.7 | 2026-08 | **P7a 技术债闭环完成**：backend 分析完成后四源聚合（ARCH/QUALITY/EVOLUTION 库内产物 + DEPEND 报告风险），同 analysis 幂等重建；`GET /projects/{id}/tech-debts` 分页筛选 + `POST /tech-debts/{id}/status` 状态机（OPEN→DOING/DONE/WONTFIX、DOING→DONE，非法迁移/必填缺失 2011，新增 2012）；前端技术债区块（severity/source/status 徽标 + 筛选 + 处理弹窗）；backend 测试 108/108 + frontend lint/build 全绿（详见 `docs/devlog/2026-08-11-p7.md`） |
| v3.8 | 2026-08 | **P7b 文档生成完成**：analyzer `POST /analyze/v1/doc`（D.7 三类 prompt：README/架构/API，API 从 controller 源码正则提取端点，LLM_NO_KEY 400 / LLM_FAILED 502）；backend docs 接口（GET 列表 + POST generate 同步调 analyzer 落库 upsert version 递增 + POST edit version+1/edited=true，新增 2013）；前端文档区块（三 tab + 轻量安全 Markdown 渲染 + 生成/重新生成 edited 确认 + 编辑模式）；三端测试 analyzer 116 + backend 116 + frontend lint/build 全绿（详见 `docs/devlog/2026-08-11-p7.md`） |
| v3.9 | 2026-08 | **P7 技术债与文档收官（v0.6 交付）**：真实 HTTP 冒烟验证 doc 路由降级（docType 白名单 400、无 LLM key → 400 LLM_NO_KEY 带 code）；端到端真库验证待 Docker/LLM 恢复（清单见 devlog）；遗留增强：AI_DOCTOR/MANUAL 债源、依赖清单落库、Dashboard + 全面美化 + 深浅色（详见 `docs/devlog/2026-08-11-p7.md`） |
| v3.10 | 2026-08 | **P8 Dashboard + 美化 + 深浅色完成（v1.0 收官）**：全局 `/dashboard`（统计卡 + ECharts 健康分布/语言构成/状态分布 + 最近分析列表，纯前端聚合复用项目列表）；完整设计 token（`variables.css` 浅色 + `[data-theme='dark']` 深色双主题）+ 顶部导航（Dashboard/项目）+ 一键切换（localStorage 持久化）；35 处硬编码色值收编为语义变量（保护 var fallback）；lint/build 全绿 + 无头 Edge 浅色渲染冒烟通过（详见 `docs/devlog/2026-08-11-p8.md`） |
| v3.11 | 2026-08 | **P6-P8 代码审查修复（三端并行）**：backend 高危 **V006 缺 deleted 列（@TableLogic 真库必崩）已补** + 8 项（重复落库/符号链接穿越/doc 超时与事务/force 2014/cancelStream/title 截断/错误码 3004/docs type 契约）；frontend 高危 **doc 表格 XSS（已转义）与 SSE 丢尾部块致 onDone 失效（已重构解析）** + 6 项（streaming 复位/会话竞态/编辑态保护/Monaco 容错/深色图表与徽章）；analyzer PG 宕机降级（503 + chat 兜底）+ `ANALYZER_PG_DSN` 生效修复；三端测试 analyzer 118 / backend 117 / frontend lint+build 全绿（详见 `docs/devlog/2026-08-11-p8.md` 审查段） |
| v3.12 | 2026-08 | **端到端真库验证完成（Docker 恢复后全链路实测）**：真实项目上传→分析→技术债/RAG/chat/文档/删除级联全链路跑通，**修复 12 个真库才暴露的 bug**：init-db 脚本被 docker stderr 终止、迁移索引幂等 + V006 缺 pgvector 扩展/deleted 列、**zip 嵌套目录上传失败**（Compress-Archive 反斜杠目录条目）、**演化串父仓库历史** + GBK 解码崩溃、tech_debt 项目级重建、available=false 清演化数据、**RAG 多关键词检索空**（占位符顺序）、**JDK HttpClient HTTP/2 丢 body → chat 422**、文档 3004 映射（FastAPI detail 包装）、**项目删除级联补 P6/P7 新表**；实测：FULL 分析 DONE/100/healthScore 82、RAG index 4 chunks + search 命中、chat SSE 透传 error LLM_NO_KEY 落库、删除级联全清；三端测试 analyzer 119 / backend 119 / frontend lint+build 全绿（详见 `docs/devlog/2026-08-11-p8.md` 端到端段） |
| v3.13 | 2026-08 | **P9a UI 精致化完成（v1.1）**：设计 token 精修（字体栈/3 级阴影/4pt 间距/tabular-nums/焦点环/过渡）+ 项目列表卡片化（健康分环形 SVG/语言徽章/状态点/筛选工具栏/卡片-列表视图切换 localStorage）+ 空态三态内联 SVG + 骨架屏 + 卡片入场 stagger 动效 + App.vue 导航下划线/毛玻璃 + dashboard 空态与图表条件渲染；TD-06 引入 vitest（`npm run test` 进门禁，12/12）；frontend lint+build 全绿 + 无头渲染冒烟（详见 `docs/devlog/2026-08-11-p9a.md`） |
| v3.14 | 2026-08 | **P9b 项目操作与报告导出 + TD-08**：backend PATCH /projects/{id}（重命名/描述，1001/1002/2001）+ GET /projects/{id}/report/export（ReportExportService 纯字符串 Markdown + Content-Disposition 下载）；前端 ⋮ 操作菜单（重命名弹窗/导出下载/删除确认）；TD-08 docgen 规则版降级（无 Key 按 README/ARCH/API 模板产出 source=RULES，不再 400）；DocResp.source 透传；三端测试 backend 128 / analyzer 全量 / frontend lint+build+vitest 12/12 全绿（详见 docs/devlog/2026-08-11-p9b.md） |
| v3.15 | 2026-08 | **P9c 历史报告对比完成**：backend `GET /projects/{id}/report/history?limit=10`（聚合 SUCCEEDED + report_json 摘要：healthScore/level/dimensions/risks/source，healthScore 数值防御、limit 1~20、data.items 包裹）；前端报告区「历史趋势」折叠区——健康分折线（≥80/≥60 参考线）+ 两期维度对比条形 + 风险 diff 三色（新增/消失/持续）+ 折线点点击切换基准期 + 空态；契约 06 §3.7 补充；三端测试 backend 135 / analyzer 全绿 / frontend lint+build+vitest 12/12，无头 Edge 冒烟（趋势/对比/风险 diff/基准切换）通过（详见 docs/devlog/2026-08-11-p9c.md） |
| v3.16 | 2026-08 | **P9d 依赖分析完成**：analyzer `POST /analyze/v1/dependency`（pom.xml 跨行正则 + package.json 解析，剥离 dependencyManagement；`dep_eol_rules.py` 内置 EOL 规则表：Spring Boot 2.5-2.7/Spring 5.3/Vue 2/React 16-17/Node 14-17/Python 3.7-3.8/Django 2，未命中 → risk:null 不误报）；**V009 dependency 表**（07 §3.4 13 字段，TD-02 迁移-字典对齐）；backend DependencyService 落库（available=false 清空）+ `GET /projects/{id}/dependencies` + AnalysisRunner 接入 FULL（失败降级）；**TD-04** 技术债 DEPEND 源改读 dependency 表（替代 report_json.risks 临时方案）；前端依赖区块（统计卡/风险分组/依赖表/EOL 徽标/未知版本/空态）；三端测试 backend 141 / analyzer 全量 / frontend 全绿，无头 Edge 冒烟（统计卡 3/2/2、分组、空态）通过（详见 docs/devlog/2026-08-11-p9d.md） |
| v3.17 | 2026-08 | **P9e 进度通知/搜索完成（P9 v1.1 收官）**：backend `GET /projects/{id}/analyses/events` SSE（`AnalysisProgressPublisher` 按 projectId 广播，AnalysisRunner 状态机 5 变更点推送，断线不重放、前端轮询兜底）；healthScore 排序白名单（复用列表子查询列）；**TD-01** analyzer `POST /analyze/v1/explain`（规则版按 ruleKey/severity 模板 + LLM 增强，source 区分）；**TD-12** README 启动命令收敛到 scripts 入口；前端 EventSource 实时进度条 + 完成/失败 Toast；三端测试 backend 149 / analyzer 全量 / frontend 全绿（详见 docs/devlog/2026-08-11-p9e.md） |
| v3.18 | 2026-08 | **架构演进 A1/A2 达成 + 技术债清零（v1.2）**：**TD-09** 架构扫描语言扩展（JS/TS/Go tree-sitter parser，混合语言目录端到端）；**TD-10** token 估算精确化（内置估算器 + chunker token 预算驱动）；**SPI-1** 解析器注册表（BaseParser + ParserRegistry，新增语言 ≤1 天配置级，languages 过滤修复）；**TD-05** Redis 列表读缓存（AD-018：Spring Cache + TTL 60s + CacheErrorHandler 降级）；**SPI-6** 报告拆表（V010 analysis_report 表，healthScore/level/summary 列化 + ReportStorageService 唯一读写入口，修复 P9e healthScore 排序 NULLS LAST 语法 bug）；三端门禁 backend 155 / analyzer 158 / frontend lint+build+vitest 12 全绿；FULL 链路集成冒烟通过（详见 docs/devlog/2026-08-13-*.md 与 docs/metrics/2026-08-13-v1.2.md） |
| v3.19 | 2026-08 | **SPI-5 压测评估 + AD-019 启动恢复**：并发压测基线（4 项目并发 FULL 全部 SUCCEEDED；backend 崩溃/重启后残留 PENDING/RUNNING 任务无声丢失——无恢复机制）；新增 StartupTaskRecovery（启动扫描残留 → 标记 FAILED「服务重启导致任务中断，请重新发起分析」+ 项目 ANALYZING → READY）；backend 158/158（详见 docs/devlog/2026-08-13-ad019.md） |
| v3.20 | 2026-08 | **项目全面审查修复（两波）**：安全（FileController 符号链接穿越 toRealPath 拦截、backend 绑定 127.0.0.1、ECharts tooltip XSS 转义、analyzer codeDir 允许根白名单 ANALYZER_ALLOWED_ROOTS、Sonar token 改环境变量）；可靠性（SSE error 去重 + \\r\n\\n\r\n\\r\n\ 切分修复、180s 超时、Parser 线程隔离、分析取消检查、quality issues 补 analysisId、delete 事务 + analysis_report 清理、create TOCTOU 唯一索引 V011、任务恢复落 5003）；质量（错误码语义 2015、LLM 4xx 不重试 + 连接快速失败、status.json 原子写、枚举 fallback、列表请求守卫、失败态）；**Spring Boot 3.3.5 → 3.3.13（Tomcat CVE-2025-24813）**；三端门禁 backend 159 / analyzer 158+ruff / frontend lint+vitest 12+build 全绿 |
| v3.21 | 2026-08 | **前端 Naive UI 全量重构 + 审查修复（v1.3）**：弃手写 CSS，全部页面组件化（NConfigProvider 主题 / 项目列表 NDataTable / 新建 NForm / Dashboard NStatistic+条形图 / 详情 NLayoutSider 分区导航 / 9 子视图含 ECharts/NDataTable/NModal）；analyzer 端口 8081→8091（避本机 polycode-auth 8081 冲突，全链路配置同步）；修复：报告显示（FAILED 项目加载历史、size=50 覆盖旧 SUCCEEDED、无报告不请求 404）、图表隐藏容器崩溃（pane v-show→v-if）、详情导航切换失效（n-menu 改自定义导航）、doctor 首问丢失（activeId 同步）/重入/卸载 abort、doc 未保存确认；多轮审查（后端 / 前端组件 / 前后端契约 / 5 子视图）；vitest 组件测试 23 用例（happy-dom + @vue/test-utils）；frontend lint + vitest 23 + build 全绿 |

## License

本项目按 **GPL-3.0** 协议以「现状」（AS IS）提供。作者与贡献者不对使用本项目产生的任何直接、间接、偶然或后果性损失负责，包括但不限于：实际生产/生活环境中的业务故障、数据丢失、服务中断、安全事件等任何恶劣结果。若需将本项目用于实际生产或业务场景，请自行充分评估风险，并按需修改代码以满足你的实际需求；任何因使用本项目（含修改后版本）造成的影响，均由使用者自行承担。

完整协议文本见 [LICENSE](LICENSE)。

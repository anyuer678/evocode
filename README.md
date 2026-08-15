# EvoCode

[![Version](https://img.shields.io/badge/版本-v1.4-blue)](https://github.com/anyuer678/evocode)
[![License](https://img.shields.io/badge/License-GPL--3.0-orange)](LICENSE)
[![Frontend](https://img.shields.io/badge/前端-Vue3%20%2B%20Naive%20UI-42b883)](https://anyuer678.github.io/evocode/frontend/)
[![Backend](https://img.shields.io/badge/后端-Spring%20Boot%203-6db33f)](https://github.com/anyuer678/evocode)
[![Analyzer](https://img.shields.io/badge/分析器-FastAPI%20%2B%20tree--sitter-009688)](https://github.com/anyuer678/evocode)
[![Docs](https://img.shields.io/badge/文档站-online-2563eb)](https://anyuer678.github.io/evocode/)

> **AI 软件体检与演化平台** —— 让 AI 持续理解、诊断、维护你的软件，做软件的「体检 + 医生 + 健康档案」。

**不做** AI 写代码工具（区别于 Cursor / Copilot），**不做**普通 Code Review，而是**软件健康管理平台**：

```
人：  体检  →  诊断  →  治疗建议  →  健康档案
      ↓        ↓          ↓          ↓
软件：扫描  →  分析  →  重构建议  →  演化记录
```

## 在线演示

| | 地址 | 说明 |
|---|---|---|
| 🖥️ 前端演示 | [anyuer678.github.io/evocode/frontend/](https://anyuer678.github.io/evocode/frontend/) | 界面交互预览（纯静态，无后端数据） |
| 📚 文档站 | [anyuer678.github.io/evocode/](https://anyuer678.github.io/evocode/) | 需求 / 架构 / API 契约 / 数据字典 全量文档 |

## 功能特性

| 能力域 | 说明 |
|---|---|
| 🧬 12 类静态分析 | 质量 / 架构(分层+环) / 演化 / 依赖 + **安全 / 复杂度 / 重复代码 / 错误处理 / 风格 / 遗留标记 / 超大方法类 / 魔法数字**（Sonar 不可用也生效） |
| 🩺 诊断建议 | 每条问题带「影响 + 可操作修复」（规则引擎，不依赖 LLM）；**文件预览逐行标注**，悬停看建议 |
| 🏥 健康评分 | 规则版 + LLM 增强综合评分（质量/结构/依赖/规模 4 维，可复现） |
| 💬 AI 医生 | RAG 检索增强问答 + SSE 流式生成 + 引用溯源（防幻觉） |
| 🗂️ 技术债 | 多源聚合 + 状态机 + 手动登记，闭环跟踪 |
| 📄 文档生成 | README / 架构 / API 三类文档（LLM 生成，无 Key 走规则版降级） |
| 📈 报告 | AI 报告 + 历史趋势对比 + Markdown 导出 |
| 📦 项目导入 | zip 上传 / GitHub 克隆 / 档案快扫 / 文件地图 |

## 快速开始

**方式一：Windows 一键启动**（推荐）

```bat
scripts\start-dev.bat     :: 基础设施 → 数据库迁移 → analyzer → backend → frontend → 健康检查
scripts\dev-down.bat      :: 停止服务（容器保留数据）
```

**方式二：手动分步**（任意平台）

```bash
docker compose up -d                              # postgres(pgvector) / redis
scripts/init-db.sh                                # 执行 db/migration/V*.sql（幂等）

cd analyzer && pip install -r requirements.txt
cd analyzer && python -m uvicorn app.main:app --host 127.0.0.1 --port 8091

cd backend && java -jar target/evocode-backend-0.1.0-SNAPSHOT.jar

cd frontend && npm install && npm run dev
```

打开 http://localhost:5173 → 创建项目（上传 zip 或 GitHub 地址）→ 发起分析 → 查看体检报告 / 架构 / 演化 / AI 医生 / 技术债 / 文档。

**配置（可选）**：复制 `.env.example` 为 `.env`，按需配置：

```env
# AI 医生 / 文档生成（不配则降级：报告走规则版、AI 医生返回 LLM_NO_KEY、文档无法生成）
LLM_API_KEY=sk-xxx
LLM_BASE_URL=http://127.0.0.1:11434/v1   # Ollama / DeepSeek 等 OpenAI 兼容端点
```

> 前置要求：JDK 17+、Node 20+、Python 3.11+、Docker Desktop、Git。
> SonarQube 为可选组件（`docker compose --profile full up -d`），未配置时质量维度自动降级，不阻塞分析链路。

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

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + TypeScript + Vite + ECharts + Naive UI + Pinia + vitest |
| 后端 | Spring Boot 3.3（Java 17）+ MyBatis-Plus + Lombok |
| 分析器 | Python 3.11 + FastAPI + tree-sitter（Python/Java/JS/TS/Go）+ pgvector |
| AI | OpenAI 兼容 LLM API（DeepSeek/OpenAI/Ollama）+ RAG（bge-m3 向量，关键词兜底） |
| 基础设施 | PostgreSQL(pgvector) + Redis + Docker Compose |
| 质量工具 | SonarQube（可选）+ ESLint/Prettier + ruff |

## 文档

完整文档见 [📚 文档站](https://anyuer678.github.io/evocode/)，源码在 `docs/`：

- [需求分析](docs/01-需求分析.md) · [架构设计](docs/04-架构设计.md) · [API 契约](docs/06-API契约.md) · [数据字典](docs/07-数据字典.md)
- [开发规范](docs/03-开发规范.md) · [测试计划](docs/08-测试计划.md) · 决策记录 `docs/decisions/`

## 常见问题

| 症状 | 排查 |
|---|---|
| 页面接口 500（项目列表加载失败） | 先跑 `scripts\check-env.ps1`；多半是 backend 未就绪，`curl http://127.0.0.1:18080/api/v1/health` 确认 |
| `start-dev.bat` 卡在 docker 步骤 | Docker Desktop 引擎未启动，启动后重跑 |
| 演化/技术债为空 | zip 上传（非 git）→ 演化不可用属正常；技术债需架构/质量/演化数据支撑 |
| AI 医生报 LLM 未配置 | `.env` 配 `LLM_API_KEY` 后重启 |

## 贡献

欢迎参与！报告 Bug、提需求、改进代码均可——详见 [CONTRIBUTING.md](CONTRIBUTING.md)。所有贡献默认按本项目 GPL-3.0 许可证发布。

## 免责声明

本项目仅供学习交流与演示用途，不构成任何形式的商业服务或技术承诺。软件按「现状」提供，不作任何明示或暗示的保证，包括但不限于适销性、特定用途适用性与非侵权性。

您理解并同意：使用本项目即表示您自行承担全部风险。如您在使用过程中发现缺陷或问题，欢迎通过 GitHub Issues 反馈，但作者不因使用本软件所直接或间接产生的任何损失（包括但不限于数据丢失、业务中断、第三方索赔）承担责任。

本项目以功能演示与学习交流为主要目的，其架构设计、安全基线、容错机制与性能表现均未按生产级标准进行验证与加固，不适用于实际生产环境或关键业务场景。任何将本项目部署于生产系统、对外提供服务、或将其接入真实业务工作流的做法，均属使用者的自主决策行为；由此产生的任何直接或间接不良后果，包括但不限于服务中断、数据损坏或泄露、业务损失、合规风险、以及因依赖本软件而引发的第三方纠纷，**开发者均不承担任何责任**。若您确有生产级使用需求，请在充分评估与自行加固（包括但不限于安全审计、压力测试、代码审查）后，自行承担相应风险。

## License

本项目按 **GPL-3.0** 协议提供。完整协议文本见 [LICENSE](LICENSE)。

详细版本历史见 [CHANGELOG.md](CHANGELOG.md)。

# EvoCode 文档站

**AI 软件体检与演化平台** —— 扫描分析项目健康、诊断技术债、AI 生成修复建议与文档，做软件的持续健康管理。

```
人：  体检  →  诊断  →  治疗建议  →  健康档案
      ↓        ↓          ↓          ↓
软件：扫描  →  分析  →  重构建议  →  演化记录
```

核心闭环：`导入项目 → 扫描(结构/语言/技术栈) → 质量(静态扫描) → 架构(调用关系) → AI 综合诊断 → 技术债登记 → 报告存档 → 演化跟踪`

---

## 文档导航

| 类别 | 文档 |
|---|---|
| **产品** | [需求分析](01-需求分析.md) · [开发指导](02-开发指导.md) · [模块详细设计](09-模块详细设计.md) |
| **工程规范** | [开发规范](03-开发规范.md) · [测试计划](08-测试计划.md) |
| **架构** | [架构设计](04-架构设计.md) · [架构审查](05-架构审查.md) · [API 契约](06-API契约.md) · [数据字典](07-数据字典.md) |
| **治理** | [技术债管理方案](10-技术债管理方案.md) · [技术债监控与质量指标体系](11-技术债监控与质量指标体系.md) |
| **决策记录** | [AD-017 Redis 端口](decisions/AD-017-redis-port.md) · [AD-018 Redis 缓存](decisions/AD-018-redis-cache.md) · [AD-019 任务恢复](decisions/AD-019-task-recovery.md) |
| **里程碑度量** | [v1.1](metrics/2026-08-13-v1.1.md) · [v1.2](metrics/2026-08-13-v1.2.md) |

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + TypeScript + Vite + ECharts + Naive UI + Pinia + vitest |
| 后端 | Spring Boot 3.3（Java 17）+ MyBatis-Plus + Lombok |
| 分析器 | Python 3.11 + FastAPI + tree-sitter（Python/Java/JavaScript/TypeScript/Go）+ pgvector |
| AI | OpenAI 兼容 LLM API + RAG（bge-m3 向量，关键词兜底） |
| 基础设施 | PostgreSQL(pgvector) + Redis + Docker Compose |

## 快速开始

```bat
scripts\start-dev.bat     :: 一键启动（基础设施 → 迁移 → analyzer → backend → frontend）
```

详见 [README](https://github.com/anyuer678/evocode#readme) 与 [02-开发指导.md](02-开发指导.md)。

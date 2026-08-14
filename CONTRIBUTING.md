# 贡献指南

感谢你对 EvoCode 的关注！本项目是「AI 软件体检与演化平台」，欢迎以任何方式参与——报告问题、提需求、改进代码、完善文档。

> 所有贡献者默认同意将贡献按本项目 **GPL-3.0** 许可证发布（见 [LICENSE](LICENSE)）。

---

## 目录

- [快速导航](#快速导航)
- [报告 Bug / 提需求](#报告-bug--提需求)
- [本地开发](#本地开发)
- [提交代码（PR）](#提交代码pr)
- [代码规范](#代码规范)
- [测试与门禁](#测试与门禁)

---

## 快速导航

| 想做什么 | 看这里 |
|---|---|
| 了解项目定位与功能 | [README](README.md) |
| 理解系统架构 | [docs/04-架构设计.md](docs/04-架构设计.md) |
| 接口契约（后端/分析器） | [docs/06-API契约.md](docs/06-API契约.md) |
| 数据表结构 | [docs/07-数据字典.md](docs/07-数据字典.md) |
| 编码规范（三端） | [docs/03-开发规范.md](docs/03-开发规范.md) |
| 里程碑与路线 | [README 里程碑](README.md#里程碑进度当前-v13-收官前端-naive-ui-全量重构--技术债清零--a0a2-架构演进达成) |

---

## 报告 Bug / 提需求

**在 [Issues](https://github.com/anyuer678/evocode/issues) 提交前，请确认：**

1. 已搜索是否已有相同/相关 Issue；
2. 提供可复现信息：
   - 操作系统与版本（Windows/macOS/Linux）
   - 启动方式（`start-dev.bat` 或手动）与相关日志
   - 问题步骤 + 期望行为 + 实际行为
   - 截图或错误堆栈（后端 `backend.log` / 前端 DevTools Console）

**Issue 模板**：Bug 请带 `[Bug]` 前缀，需求请带 `[Feature]` 前缀。

---

## 本地开发

### 环境要求

- JDK 17+、Node 20+、Python 3.11+、Git
- Docker Desktop（PostgreSQL + Redis）

### 一键启动

```bat
scripts\start-dev.bat
```

等价手动步骤见 [README 快速开始](README.md#快速开始)。数据库迁移由 `scripts/init-db.ps1` 幂等执行（`db/migration/V*.sql`）。

### 分支约定

- `main`：稳定分支，仅接受通过门禁的 PR
- 开发请开 `feat/xxx` 或 `fix/xxx` 分支

---

## 提交代码（PR）

1. **Fork** 本仓库并克隆到本地；
2. 创建特性分支：`git checkout -b feat/your-feature`；
3. 完成改动，**本地通过全部门禁**（见下）；
4. 提交（Commit Message 用 Conventional Commits 风格）：

   ```
   feat(analyzer): 新增 XX 分析维度
   fix(backend): 修复 XX 问题
   docs: 更新 API 契约
   test: 补充 XX 测试
   ```

5. Push 并开 PR 到 `main`，PR 描述说明：改动内容、为什么、验证方式。

### PR 合并要求

- [ ] 三端门禁全绿（见下）
- [ ] 有对应测试（新功能至少 1 个用例）
- [ ] 不引入新依赖（如必须，说明理由）
- [ ] 契约变更同步更新 [docs/06-API契约.md](docs/06-API契约.md)

---

## 代码规范

详细规范见 [docs/03-开发规范.md](docs/03-开发规范.md)，摘要：

- **后端**（Java 17 / Spring Boot 3）：分层清晰，错误用统一 `ErrorCode`，写业务异常而非裸 throw；
- **分析器**（Python 3.11 / FastAPI）：类型标注，`ruff` 全规则，LLM 不可用时必须降级而非报错；
- **前端**（Vue 3 / TS / Naive UI）：组件化，禁止 `any` 滥用，样式走设计 token。

---

## 测试与门禁

本地提交前必须全部通过：

| 端 | 命令 | 说明 |
|---|---|---|
| backend | `cd backend && ./mvnw.cmd test` | JUnit 全量 |
| analyzer | `cd analyzer && .venv\Scripts\python -m pytest tests -q && .venv\Scripts\python -m ruff check app tests` | pytest + ruff |
| frontend | `cd frontend && npm run test && npm run lint && npm run build` | vitest + eslint/prettier + build |

> Windows 下 `mvnw.cmd` 可能因 stderr 误报非零退出码——以 `Tests run:` 汇总行为准。

---

## 致谢

再次感谢你的贡献。有疑问可在 Issue 中留言，或直接开 PR 讨论。

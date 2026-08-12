# EvoCode API 契约（详细版）

> 对外 REST（`/api/v1`）+ analyzer 内部 API（`/analyze/v1`）+ SSE 协议
> 版本：v1.0 · 2026-08-10 · 对应架构修订（05 审查 P0-1/P0-3/P1-1/2/3/4 已落实）

> **使用说明**：本文档是接口实现的**唯一契约来源**。字段名与结构变更必须先改本文档，再同步三端代码。LLM 出口唯一在 analyzer（backend 永不直连 LLM）。

---

## 目录

1. 通用约定
2. 错误码全表
3. 对外 API（按资源分组，含请求/响应示例）
4. SSE 事件协议（AI 医生）
5. Analyzer 内部 API
6. 分页与排序规范
7. 契约变更流程

---

## 1. 通用约定

| 项 | 约定 |
|---|---|
| Base URL | 对外 `/api/v1`；内部 `/analyze/v1`（仅 127.0.0.1） |
| 响应包装 | `{"code": 0, "message": "ok", "data": ...}`；code=0 成功 |
| 时间 | ISO-8601 UTC：`2026-08-10T10:00:00Z` |
| 字段 | JSON camelCase；枚举值全大写 |
| 分页 | 查询参数 `page`(从1) `size`(默认10, 上限100)，响应 `data` 内 `{total, page, size, items}` |
| 上传 | `multipart/form-data` |
| 流式 | `text/event-stream`，UTF-8，无 gzip（见 §4） |
| 鉴权 | v1.0 前无（单用户本地部署）；预留 `Authorization: Bearer` 解析位，不校验 |

---

## 2. 错误码全表

### 2.1 HTTP 状态码

| HTTP | 场景 |
|---|---|
| 200 | 成功 |
| 201 | 创建成功（项目） |
| 202 | 任务已接受（异步） |
| 400 | 参数/请求体错误（业务 code 1xxx/2xxx） |
| 404 | 资源不存在（2001 项目不存在等） |
| 502 | analyzer 不可达（3001） |
| 500 | 系统错误（5xxx） |

> HTTP 映射约定（GlobalExceptionHandler 统一实现）：**1xxx/2xxx → 400；3xxx → 502；5xxx → 500**；业务 code 始终在 body 中携带。

### 2.2 业务错误码（body.code）

| 段 | code | 含义 | 触发点 |
|---|---|---|---|
| 1xxx 参数 | 1001 | 参数缺失 | 校验注解 |
| | 1002 | 参数格式错误 | 校验注解 |
| | 1003 | 分页参数非法 | 控制器 |
| 2xxx 业务 | 2001 | 项目不存在 | 详情/分析/删除 |
| | 2002 | 该项目已有运行中分析任务 | 发起分析 |
| | 2003 | 上传文件非法（zip 损坏/超限/恶意路径） | 上传 |
| | 2004 | 项目状态不允许该操作（如分析中删除→按 7.5 流程不返回此码，走 CANCELLED） | — |
| | 2005 | 文件内容越权或超限 | content 接口 |
| | 2006 | 会话不存在 | chat |
| | 2007 | 发送过于频繁/重复提交（AI 医生） | chat（同内容 2s 内重复） |
| | 2008 | 该分析正在重新生成报告中 | regenerate |
| | 2009 | 仓库克隆失败（不存在/私有/超时） | 创建项目-GIT |
| | 2010 | 该项目尚无架构分析（含 analysisId 指定的分析无架构数据） | 架构查询 |
| 3xxx 分析器 | 3001 | analyzer 不可达/内部错误 | AnalyzerClient |
| | 3002 | 扫描超时（部分结果已保留） | AnalysisAsyncRunner |
| | 3003 | LLM 失败（已降级，此码仅在降级也失败时返回） | report 流程 |
| 5xxx 系统 | 5001 | 磁盘空间不足 | 上传/解压 |
| | 5002 | 数据库异常 | 兜底 |
| | 5003 | 服务重启导致任务中断 | 启动恢复 |

> 注意：`LLM_NO_KEY`/`LLM_FAILED` 通常**不**以错误形式返回（触发规则版降级，报告 source=RULES）；只有降级路径本身失败才返回 3003。

---

## 3. 对外 API（详细）

### 3.1 创建项目

#### POST /api/v1/projects

**方式 A：zip 上传（multipart/form-data）**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| name | string | 是 | 项目名（≤100，去首尾空白） |
| description | string | 否 | 描述 |
| file | file | 是 | .zip，≤200MB，解压后 ≤500MB |

```jsonc
// 201 响应
{ "code": 0, "message": "ok",
  "data": { "id": 1, "name": "Chatez", "sourceType": "ZIP", "status": "CREATED",
            "storagePath": "data/projects/1", "langStats": null, "locTotal": 0,
            "fileCount": 0, "frameworkTags": [], "lastAnalyzedAt": null,
            "createdAt": "2026-08-10T10:00:00Z" } }
// 400：{ "code": 1001, "message": "name 不能为空" }
// 400：{ "code": 2003, "message": "上传文件非法：zip 内存在非法路径（../）" }
```

校验顺序（实现必须遵循）：① 扩展名 .zip → ② 大小 ≤200MB → ③ 解压至临时目录（逐文件路径校验）→ ④ 解压体积/文件数校验 → ⑤ 原子移入 `data/projects/{id}` → ⑥ 触发快扫（异步，3 分钟内出档案）。任何一步失败：清理临时目录，返回 2003。

**方式 B：GitHub 仓库**

```jsonc
// Content-Type: application/json
// req
{ "name": "Chatez", "repoUrl": "https://github.com/xxx/chatez", "cloneDepth": 1 }
// 201 响应同方式 A（sourceType=GIT，repoUrl 回显）
// 400：{ "code": 1002, "message": "repoUrl 格式非法" }
// 400：{ "code": 2009, "message": "仓库克隆失败：仓库不存在或为私有" }
```

规则：默认 `--depth 1`；`cloneDepth=0` 全量克隆（供演化分析 FR-8.4）；超时 5 分钟；支持 `GIT_PROXY` 配置。

### 3.2 项目列表

#### GET /api/v1/projects

| 参数 | 类型 | 说明 |
|---|---|---|
| page / size | int | 分页（size ≤100） |
| keyword | string | 名称模糊匹配 |
| language | string | 语言筛选（lang_stats 键） |
| status | string | CREATED/ANALYZING/READY/FAILED |
| sort | string | `createdAt`(默认) / `lastAnalyzedAt` / `locTotal` / `name` |
| order | string | asc / desc |

```jsonc
{ "code": 0, "data": { "total": 5, "page": 1, "size": 10, "items": [
  { "id": 1, "name": "Chatez", "sourceType": "GIT",
    "langStats": { "Java": 61.2, "JavaScript": 30.4, "OTHER": 8.4 },
    "frameworkTags": ["Spring Boot", "Vue", "Electron"],
    "locTotal": 20431, "fileCount": 412, "status": "READY",
    "healthScore": 82, "lastAnalyzedAt": "2026-08-10T10:00:00Z",
    "createdAt": "2026-08-01T08:00:00Z" } ] } }
```

> `healthScore` 取该项目最近一次成功分析的报告值（JOIN 子查询，避免全表扫描）。

### 3.3 项目详情

#### GET /api/v1/projects/{id}

```jsonc
{ "code": 0, "data": {
  "id": 1, "name": "Chatez", "description": null, "sourceType": "GIT",
  "repoUrl": "https://github.com/xxx/chatez", "status": "READY",
  "langStats": { "Java": 61.2, "JavaScript": 30.4, "OTHER": 8.4 },
  "frameworkTags": ["Spring Boot", "Vue", "Electron"],
  "locTotal": 20431, "fileCount": 412, "ignoredCount": 88,
  "lastAnalyzedAt": "2026-08-10T10:00:00Z",
  "latestAnalysis": { "id": 10, "status": "SUCCEEDED", "reportJson": { …见 3.5 } },
  "createdAt": "2026-08-01T08:00:00Z" } }
// 404：{ "code": 2001, "message": "项目不存在" }
```

> `latestAnalysis.reportJson` 体积 ≤1MB 时内联返回；超过则只返回 `{analysisId}`，前端单独拉报告接口（防列表页卡顿）。

### 3.4 删除项目

#### DELETE /api/v1/projects/{id}

```jsonc
// 200：{ "code": 0, "message": "ok", "data": null }
```

删除时序（对应架构 §7.5）：① 存在 RUNNING 任务 → 置 CANCELLED → ② 等执行线程退出（≤60s）→ ③ 事务级联清库（chat_message→chat_session→knowledge_chunk→tech_debt→generated_doc→commit_stat/file_change_stat/hotspot→arch_*→quality_issue/dependency→file_node→analysis→project）→ ④ 删磁盘目录（失败仅记日志）。

### 3.5 发起分析

#### POST /api/v1/projects/{id}/analyses

```jsonc
// req: { "type": "FULL" }        // v0.1 仅 FULL；P3+ 增加 QUALITY/ARCH/EVOLUTION
// 202 响应
{ "code": 0, "message": "ok",
  "data": { "id": 10, "projectId": 1, "type": "FULL", "status": "PENDING",
            "progress": 0, "stage": "QUEUED", "createdAt": "2026-08-10T10:00:00Z" } }
// 400：{ "code": 2002, "message": "该项目已有运行中的分析任务" }
// 404：{ "code": 2001, "message": "项目不存在" }
```

### 3.6 分析历史与状态轮询

#### GET /api/v1/projects/{id}/analyses

```jsonc
{ "code": 0, "data": { "total": 3, "items": [
  { "id": 10, "type": "FULL", "status": "SUCCEEDED", "progress": 100,
    "stage": "DONE", "errorMessage": null, "startedAt": "...", "finishedAt": "...",
    "source": "LLM", "healthScore": 82 },
  { "id": 9, "type": "FULL", "status": "FAILED", "progress": 70, "stage": "REPORT",
    "errorMessage": "LLM 调用失败且降级也失败", ... } ] } }
```

#### GET /api/v1/analyses/{id}（轮询，2s 间隔）

```jsonc
{ "code": 0, "data": { "id": 10, "status": "RUNNING", "progress": 45,
                       "stage": "SCAN", "errorMessage": null } }
```

状态机与进度映射：

| 阶段 | 进度 | stage 值 | 说明 |
|---|---|---|---|
| 排队 | 0 | QUEUED | |
| 扫描 | 5→70 | SCAN | 调 /analyze/scan；超时保留部分 |
| 扫描落库 | 70 | SCAN_DONE | |
| 报告 | 75→95 | REPORT | 调 /analyze/report（LLM 或规则版） |
| 完成 | 100 | DONE | |
| 失败 | — | — | status=FAILED + errorMessage |

### 3.7 报告

#### GET /api/v1/analyses/{id}/report

```jsonc
{ "code": 0, "data": {
  "analysisId": 10, "generatedAt": "2026-08-10T10:01:00Z",
  "source": "LLM",                       // LLM / RULES
  "promptVersion": "report-1.2",
  "report": {
    "healthScore": 82, "level": "GOOD",
    "summary": "整体结构清晰，但 User 模块耦合度偏高、存在 EOL 依赖，较上期 -3 分。",
    "techStack": { "languages": {"Java": 61.2, "JavaScript": 30.4},
                   "frameworks": ["Spring Boot", "Vue", "Electron"] },
    "dimensions": [
      { "key": "quality", "score": 76, "stars": 4, "summary": "超长方法 23 处" },
      { "key": "structure", "score": 88, "stars": 4, "summary": "分层规范" },
      { "key": "dependency", "score": 55, "stars": 3, "summary": "Spring Boot 2.5 已 EOL" },
      { "key": "scale", "score": 90, "stars": 5, "summary": "评估充分" } ],
    "risks": [
      { "level": "HIGH", "title": "Spring Boot 2.5 已停止官方支持",
        "detail": "pom.xml 使用 2.5.14，OSS 支持已于 2023-11 结束",
        "suggestion": "升级至 3.5，注意 javax→jakarta 迁移",
        "references": [{ "file": "pom.xml", "line": 3 }] } ],
    "recommendations": [
      { "phase": "第一阶段", "items": ["升级 Spring Boot 3.x", "拆分 UserService"] } ] } } }
// 404：{ "code": 2001, "message": "该分析不存在或无报告" }
```

#### POST /api/v1/analyses/{id}/report/regenerate（P2 起）

```jsonc
// 不重扫：仅重跑 LLM 报告步骤（用 analysis 已存的扫描摘要）
// 前置：analysis.status = SUCCEEDED 且 report_json 非空；否则 404/2001
// 状态转移：SUCCEEDED → RUNNING(stage=REPORT) → SUCCEEDED（覆盖 report_json，记录 regeneratedAt）
// 202：{ "code": 0, "data": { "id": 10, "status": "RUNNING", "stage": "REPORT", "progress": 75 } }
// 完成后由前端轮询 GET /analyses/{id} 与 /report
// 400：{ "code": 2008, "message": "该分析正在重新生成报告中" }
```

#### GET /api/v1/projects/{id}/report/history（P9c）

```jsonc
// 报告历史：聚合 SUCCEEDED + report_json 非空的分析摘要（按 id 倒序，最新在前）
// 参数：limit（默认 10，上限 20；非法 → 400/1003）
// 200：{ "code": 0, "data": { "items": [
//   { "analysisId": 27, "createdAt": "2026-08-10T10:01:00Z", "healthScore": 82, "level": "GOOD",
//     "dimensions": [ { "key": "quality", "score": 76, "stars": 4 }, … ],
//     "risks": [ { "level": "HIGH", "title": "Spring Boot 2.5 已停止官方支持" }, … ],
//     "source": "RULES" }, … ] } }
// 404：{ "code": 2001, "message": "项目不存在" }；无 SUCCEEDED 分析 → items: []
// 注：healthScore 有数值防御（非数字历史脏数据 → null，前端显示 —）；risks 为摘要（不含 detail/引用），供两期 diff
```

### 3.8 项目地图与文件内容

#### GET /api/v1/projects/{id}/files

| 参数 | 说明 |
|---|---|
| page/size | 分页 |
| language | 语言筛选 |
| keyword | 路径模糊匹配 |
| sort | `path`(默认) / `loc` / `sizeBytes` |

```jsonc
{ "code": 0, "data": { "total": 412, "items": [
  { "path": "src/main/java/com/chatez/service/UserService.java",
    "language": "Java", "loc": 180, "sizeBytes": 4210 } ] } }
```

#### GET /api/v1/projects/{id}/files/content?path=<相对路径>

```jsonc
// 200：{ "code": 0, "data": { "path": "src/.../UserService.java",
//       "language": "Java", "loc": 180, "content": "package com.chatez...", "truncated": false } }
// 400：{ "code": 2005, "message": "文件越权或超限" }   // ../、绝对路径、非 file_node 路径、>2MB、二进制
```

安全实现（强制）：① `path` 必须存在于该项目的 file_node 表（白名单）→ ② 规范化后必须位于 `data/projects/{id}/` 内 → ③ 大小 ≤2MB → ④ 二进制检测（前 512 字节含 NUL → 拒绝）→ ⑤ 读全文 UTF-8，失败返回 2005。**该接口是 FR-6.3 引用预览的唯一数据来源。**

### 3.9 依赖（P3）

#### GET /api/v1/projects/{id}/dependencies?riskLevel=

```jsonc
{ "code": 0, "data": { "total": 34, "items": [
  { "ecosystem": "maven", "name": "spring-boot-starter-parent", "version": "2.5.14",
    "latestVersion": "3.5.0", "riskLevel": "HIGH", "isEol": true,
    "riskReason": "2.x 已结束 OSS 支持（2023-11）",
    "suggestion": "升级 3.x，注意 javax→jakarta 迁移",
    "aiAdvice": { "impact": "…", "steps": ["…"], "risks": ["…"], "estimate": "0.5 人日" } } ] } }
```

`aiAdvice` 为异步生成（`ai_status` 字段约定：`NONE`=未生成 / `PENDING` / `DONE` / `FAILED`，与 07 §4 枚举一致）。

### 3.10 质量（P3）

#### GET /api/v1/projects/{id}/quality-issues?severity=&kind=&status=

```jsonc
{ "code": 0, "data": {
  "metrics": { "bugs": 23, "vulnerabilities": 5, "codeSmells": 88,
               "duplicationRate": 12.4, "coverageRate": null, "complexity": 3.2,
               "available": true, "comparedWithLast": { "bugs": 5, "vulnerabilities": 0 } },
  "total": 116, "items": [
    { "id": 501, "severity": "CRITICAL", "kind": "BUG", "ruleKey": "s3776",
      "filePath": "src/.../UserService.java", "line": 88,
      "message": "Method too long", 
      "aiExplanation": "该函数同时承担用户验证、数据库操作与邮件发送…",
      "aiSuggestion": "拆分为 UserAuthService / UserQueryService / UserMailService",
      "aiStatus": "DONE", "status": "OPEN" } ] } }
```

`metrics.available=false` 表示 Sonar 未启用（质量维度 N/A）。

#### POST /api/v1/quality-issues/{id}/explain（重新解释）

```jsonc
// 202：{ "code": 0, "data": { "id": 501, "aiStatus": "PENDING" } }
// 说明：首次解释随分析自动触发；此接口用于失败重试或用户主动要求
```

### 3.11 架构（P4）

#### GET /api/v1/projects/{id}/architecture?analysisId=

```jsonc
{ "code": 0, "data": {
  "nodes": [ { "id": 11, "nodeKey": "com.chatez.controller.UserController",
               "name": "UserController", "nodeType": "CONTROLLER",
               "filePath": "src/.../UserController.java", "metrics": { "outDegree": 3, "inDegree": 0 } } ],
  "edges": [ { "id": 21, "sourceNodeId": 11, "targetNodeId": 12,
               "relation": "CALL" } ],
  "violations": [ { "id": 31, "violationType": "LAYER_VIOLATION",
                    "description": "Controller 直接调用 Repository（违反分层）",
                    "sourceNodeId": 11, "targetNodeId": 13,
                    "severity": "HIGH",
                    "suggestion": "将数据访问迁移到 Service 层",
                    "aiNote": "该违规使 Controller 承担数据访问职责…" } ] } }
// 404：{ "code": 2010, "message": "该项目尚无架构分析" }（analysisId 缺省取最新一次架构分析）
```

### 3.12 技术债（P7）

```jsonc
// GET /api/v1/projects/{id}/tech-debts?status=
{ "code": 0, "data": { "total": 7, "items": [
  { "id": 101, "source": "ARCH", "title": "支付模块耦合严重",
    "level": "HIGH", "description": "…", "suggestion": "拆分 PaymentService",
    "status": "OPEN", "refAnalysisId": 10,
    "createdAt": "...", "resolvedAt": null } ] } }

// POST /api/v1/tech-debts/{id}/status  更新状态
// req: { "status": "DOING" | "DONE" | "WONTFIX",
//        "resolveNote": "已拆分验证…",       // DONE 必填
//        "wonfixReason": "优先级低…" }        // WONTFIX 必填
// 200：{ "code": 0, "data": null }
```

状态机约束：仅允许 OPEN→DOING、OPEN→DONE、OPEN→WONTFIX、DOING→DONE；非法迁移返回 2xxx。

### 3.13 演化（P5）

#### GET /api/v1/projects/{id}/evolution?range=30d

```jsonc
{ "code": 0, "data": {
  "range": "30d",
  "trend": [ { "week": "2026-07-20", "commits": 12, "linesAdded": 3200, "linesRemoved": 800 } ],
  "topFiles": [ { "filePath": "src/.../UserService.java", "commitCount": 45,
                  "linesAdded": 12000, "linesRemoved": 3000 } ],
  "authors": [ { "authorName": "李工", "commits": 87, "linesAdded": 15000 } ],
  "hotspots": [ { "module": "User", "riskLevel": "HIGH",
                  "evidence": ["变更 45 次", "新增 12000 行", "耦合度全项目最高"],
                  "aiConclusion": "User 模块正在成为风险中心，建议优先重构" } ] } }
// 200 + available=false：非 Git 来源 / 无提交历史项目（P5 决策：不返回 404）
// 404：{ "code": 2001, "message": "项目不存在" }
// 注：topFiles 为最新一次分析的全量 TOP-N（落库窗口 365d），不受 range 过滤
```

### 3.14 依赖（P9d）

#### GET /api/v1/projects/{id}/dependencies

```jsonc
{ "code": 0, "data": {
  "available": true,
  "dependencies": [
    { "name": "org.springframework.boot:spring-boot-starter-web", "version": "2.5.14",
      "type": "MAVEN", "file": "pom.xml", "risk": "HIGH",
      "reason": "Spring Boot 2.5 已 EOL（OSS 支持 2023-11 结束）", "latest": "3.2+", "isEol": true },
    { "name": "vue", "version": "2.6.14", "type": "NPM", "file": "package.json",
      "risk": "HIGH", "reason": "Vue 2 已 EOL（2023-12-31 结束支持）", "latest": "3.x", "isEol": true },
    { "name": "axios", "version": "1.7.0", "type": "NPM", "file": "package.json",
      "risk": null, "reason": null, "latest": null, "isEol": false } ] } }
// 200 + available=false：无 Maven/npm 依赖文件（缺 pom.xml/package.json）→ 非错误
// 404：{ "code": 2001, "message": "项目不存在" }
// 注：risk=null 表示未命中 EOL 规则表（"未知版本，建议人工确认"，不误报）；
//     数据来源 dependency 表（FULL 分析落库，07 §3.4），取最新分析
```

### 3.15 文档（P7）

```jsonc
// GET /api/v1/projects/{id}/docs?type=README|ARCH|API
//   type 可选筛选；data 为列表（README/ARCH/API 三类各至多一条）
{ "code": 0, "data": [ { "id": 201, "docType": "README", "title": "Chatez 项目说明",
                       "content": "# Chatez\n…", "version": 1, "edited": false,
                       "createdAt": "..." } ] }

// POST /api/v1/projects/{id}/docs/{docType}/generate?force=false
//   生成/重新生成（同步调 analyzer，耗时 10-30s）
//   200：{ "code": 0, "data": { "id": 201, "docType": "README", "title": "…", "version": 2, … } }
//   400：{ "code": 2014, "message": "文档已被人工编辑，重新生成需确认（force）" }
//        （edited=true 且未带 force=true 时拒绝覆盖）
//   502：{ "code": 3001, "message": "文档服务不可达…" }
//   400：{ "code": 3004, "message": "LLM 未配置…" }（analyzer 返回 LLM_NO_KEY 时）

// POST /api/v1/docs/{id}/edit
// req: { "content": "# 人工修改后的 README…" }
// 200：{ "code": 0, "data": { "id": 201, "version": 2, "edited": true } }
```

### 3.16 会话管理（AI 医生，P6）

```jsonc
// GET /api/v1/projects/{id}/chats   （按 lastMessageAt desc）
{ "code": 0, "data": { "total": 3, "items": [
  { "id": 81, "title": "为什么这个项目难维护", "messageCount": 4,
    "createdAt": "...", "lastMessageAt": "..." } ] } }

// POST /api/v1/projects/{id}/chats   → 建会话（title 占位"新会话"，首条消息后自动生成）
// 201：{ "code": 0, "data": { "id": 81, "title": "新会话", "messageCount": 0, "createdAt": "..." } }

// DELETE /api/v1/chats/{id}   → 级联删除该会话全部消息
// 200：{ "code": 0, "data": null }

### 3.17 分析进度 SSE（P9e）

#### GET /api/v1/projects/{id}/analyses/events

```jsonc
// 200 text/event-stream（复用 §4 SSE 帧格式；event 名 = analysis-progress）
// data 载荷（AnalysisProgressPublisher.ProgressEvent）：
//   { "analysisId": 26, "status": "RUNNING", "stage": "SCAN", "progress": 30,
//     "message": "扫描中…" }
// 触发点：AnalysisRunner 状态机变更——RUNNING(SCAN,5) / RUNNING(SCAN_DONE,70) /
//         RUNNING(REPORT,75) / SUCCEEDED(DONE,100) / FAILED
// 断线：不重放历史（前端回退 GET /analyses/{id} 2s 轮询）；无订阅者时 no-op
// 用途：详情页 EventSource 实时进度条 + 完成/失败 Toast
```

---

## 4. SSE 事件协议（AI 医生）

### 4.1 发起

```
POST /api/v1/chats/{id}/messages
Content-Type: application/json
Accept: text/event-stream

{ "content": "为什么这个项目维护困难？", "fileRef": "src/.../UserService.java" }
```

- `fileRef` 可选：用户显式 @ 的文件（走"全文发送"开关后携带内容，默认省略）
- 响应：`200 text/event-stream`，连接保持至 `done` 或错误

### 4.2 事件类型

| event | data 格式 | 语义 |
|---|---|---|
| `delta` | `{"content":"…"}` | 增量文本（前端追加） |
| `citations` | `{"items":[{"file":"…","line":12,"excerpt":"…"}]}` | 引用列表（流结束前一次性发送；前端校验存在性后渲染） |
| `done` | `{"messageId": 9527}` | 正常结束；后端已落库 |
| `error` | `{"code":"LLM_FAILED","message":"…"}` | 异常结束；后端落库失败消息（role=ASSISTANT, content=错误说明） |

### 4.3 完整示例流

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
X-Accel-Buffering: no          ← 关键：禁用中间层缓冲

event: delta
data: {"content":"根据最近一次分析，主要原因有：\n\n1. "}

event: delta
data: {"content":"**依赖风险**：pom.xml:3 使用 Spring Boot 2.5…"}

event: citations
data: {"items":[{"file":"pom.xml","line":3,"excerpt":"<parent>…2.5.14…"},
       {"file":"src/.../UserService.java","line":1,"excerpt":"public class UserService…"}]}

event: done
data: {"messageId": 9527}
```

### 4.4 前端处理规则

- 用 fetch + ReadableStream 逐行解析 `event:`/`data:`，不做 EventSource（需自定义 headers）
- 断线/超时（>180s 无事件）→ 提示"连接中断"，可重发（同 content 2s 内重复提交返回 **2007**）
- `citations` 到齐后渲染引用卡片，点击 → 调 `GET /files/content?path=` → Monaco 定位行号
- `error` 事件到达后关闭流，展示错误横幅

### 4.5 后端与 analyzer 转发约定

- backend → analyzer `POST /analyze/v1/chat`（同结构 SSE，**透传不缓冲**）
- analyzer 断流 → backend 向客户端发 `error` 事件并落库
- 超时：LLM 首 token 60s / 总时长 180s

---

## 5. Analyzer 内部 API（仅 127.0.0.1:8081）

> 统一错误体（非 200）：`{"error": {"code": "…", "message": "…"}}`，映射规则见 §2/开发指导 §6.2。

### 5.1 POST /analyze/v1/scan

```jsonc
// req
{ "projectId": 1, "codeDir": "data/projects/1" }
// 200（超时/超限时加字段 truncated: true / skippedBigFiles: N）
{ "languages": {"Java": 61.2, "JavaScript": 30.4, "OTHER": 8.4},
  "locTotal": 20431, "fileCount": 412, "ignoredCount": 88,
  "frameworks": ["Spring Boot", "Vue", "Electron"],
  "hasBackend": true, "hasFrontend": true, "dbHint": ["MySQL"],
  "files": [ { "path": "src/.../UserService.java", "language": "Java",
               "loc": 180, "sizeBytes": 4210 } ],
  "skippedBigFiles": 1 }
```

### 5.2 POST /analyze/v1/report

```jsonc
// req
{ "projectId": 1, "scan": {…}, "quality": null, "arch": null, "evolution": null,
  "historyReports": [ { "healthScore": 85, "summary": "…" } ],
  "regenerate": false }
// 200
{ "source": "LLM" | "RULES",
  "promptVersion": "report-1.2",
  "report": { "healthScore": 82, "level": "GOOD", "summary": "…",
              "dimensions": […], "risks": […], "recommendations": […] } }
// LLM 无 Key/失败 → 内部降级：source=RULES（HTTP 仍 200，不视为失败）
```

### 5.3 POST /analyze/v1/quality

```jsonc
// req { "projectId": 1, "codeDir": "data/projects/1" }
// 200 { "metrics": {…}, "issues": [ { "ruleKey","severity","kind","filePath","line","message" } ] }
// Sonar 不可达 → 200 + { "available": false }（非错误）
```

### 5.4 POST /analyze/v1/explain

```jsonc
// req { "issue": { "ruleKey","severity","message","filePath","line" },
//       "fileSnippet": "…上下文…" }
// 200 { "explanation": "…", "suggestion": "…", "codeExample": "…" }
```

### 5.5 POST /analyze/v1/architecture

```jsonc
// req { "projectId": 1, "codeDir": "data/projects/1", "languages": ["java"] }
// 200 { "nodes": [ { "nodeKey","name","nodeType","filePath","metrics" } ],
//       "edges": [ { "sourceNodeKey","targetNodeKey","relation" } ],
//       "violations": [ { "violationType","description","sourceNodeKey","targetNodeKey",
//                         "severity","suggestion" } ] }
```

### 5.6 POST /analyze/v1/evolution

```jsonc
// req { "projectId": 1, "gitDir": "data/projects/1", "rangeDays": 30 }
// 200 { "commits": [ { "hash","authorName","committedAt","linesAdded","linesRemoved",
//                      "filesChanged","message" } ],
//       "trend": [ { "week","commits","linesAdded","linesRemoved" } ],
//       "topFiles": [ { "filePath","commitCount","linesAdded","linesRemoved" } ],
//       "authors": [ { "authorName","commits","linesAdded" } ],
//       "hotspots": [ { "module","riskLevel","evidence" } ] }
// 非 git 目录 / git 执行失败 → 200 + { "available": false }；空仓库（有 .git 无提交）→ available=true + 空数组
```

### 5.7 POST /analyze/v1/chat（SSE 生成端，P6）

```jsonc
// req（stream 输入：JSON body 一次提交）
{ "projectId": 1,
  "systemContext": { "projectSummary": "…", "latestReportSummary": "…" },
  "history": [ { "role": "user"|"assistant", "content": "…" } ],   // 已截断：≤6轮+摘要
  "query": "为什么这个项目维护困难？",
  "fileRef": null | { "path": "…", "content": "…" } }
// 响应：SSE 事件流（delta/citations/done/error），协议与 §4 一致
```

### 5.8 POST /analyze/v1/rag/index · /analyze/v1/rag/search（P6）

```jsonc
// index: req { "projectId": 1, "codeDir": "data/projects/1", "languages": ["java","python"] }
//        analyzer 直读磁盘切片（不经 HTTP 传文件内容，避免大内存与扩大隐私面）
//        → 200 { "chunks": 312, "embeddingModel": "bge-m3" }
// search: req { "projectId": 1, "query": "…", "topK": 8 }
//        → 200 { "chunks": [ { "file","chunkIndex","content","meta","score" } ] }
// 错误：PG 未配置/宕机 → 503 {"error":{"code":"RAG_UNAVAILABLE","message":"…"}}
```

### 5.9 POST /analyze/v1/doc（P7b 契约新增）

```jsonc
// req（JSON body 一次提交）
{ "projectId": 1, "docType": "README" | "ARCH" | "API",
  "scan": {…}|null,        // README/API 用（backend 由 file_node 重建）
  "arch": {…}|null,        // ARCH 用（backend 取最新 analysis 架构）
  "projectInfo": { "name": "…", "description": "…" },
  "codeDir": "data/projects/1"|null }   // API 用：analyzer 直读磁盘扫描 controller
// → 200 { "docType": "README", "title": "…", "content": "markdown…" }
// 错误（统一错误体）：
//   400 {"error":{"code":"LLM_NO_KEY","message":"文档生成失败：…"}}  无 LLM key
//   502 {"error":{"code":"LLM_FAILED","message":"文档生成失败：…"}}  LLM 调用失败
```

### 5.10 POST /analyze/v1/dependency（P9d 契约新增）

```jsonc
// req: { "projectId": 1, "codeDir": "data/projects/1" }
// 200: { "available": true, "dependencies": [
//   { "name": "org.springframework.boot:spring-boot-starter-web", "version": "2.5.14",
//     "type": "MAVEN", "file": "pom.xml",
//     "risk": "HIGH", "reason": "Spring Boot 2.5 已 EOL（OSS 支持 2023-11 结束）",
//     "latest": "3.2+", "isEol": true }, … ] }
// 无 Maven/npm 依赖文件（缺 pom.xml/package.json）→ 200 + { "available": false, "dependencies": [] }（非错误）
// 404: {"error":{"code":"…","message":"codeDir not found"}}  codeDir 不存在
// 注：risk=null 表示未命中 EOL 规则表（"未知版本，建议人工确认"，不误报）；
//     EOL 规则表内置常量（dep_eol_rules.py），可扩展
```

### 5.11 错误体与降级语义（P6-P7 审查修订）

- analyzer 内部 API 非 200 一律 `{"error": {"code": "…", "message": "…"}}`（§4.5 前为裸字符串/缺包装处已统一）
- `/analyze/v1/chat` 的 `done` 事件 data 为 `{}`（analyzer 无 messageId 概念），由 backend 落库后补发 `{"messageId": id}`（§4.2 对前端保持原样）
- PG 未配置 与 PG 宕机（psycopg.Error）降级语义一致：index → 200 `stored=false`；search → 503 `RAG_UNAVAILABLE`；chat → 兜底话术 SSE（不截断）

---

## 6. 分页与排序规范

- 分页响应统一：`data = { total, page, size, items }`（列表类）
- `sort` 白名单（后端校验，不在白名单返回 1002，防注入）：
  - projects: `createdAt / lastAnalyzedAt / locTotal / name / healthScore`（P9e 起含 healthScore，复用列表子查询 health_score 列）
  - files: `path / loc / sizeBytes`
  - quality-issues: `severity / line / filePath`
- 排序默认值：时间类 `desc`，其余 `asc`

---

## 7. 契约变更流程

1. 修改本文档对应端点（含示例 JSON）
2. analyzer：改 `schemas.py` + 同步本文档 §5
3. backend：改 DTO + 错误码表（§2）如有变化
4. frontend：改 `types/` 与 api 模块
5. PR 描述标注"契约变更"，跑三端验证 + 契约一致性检查（03 规范 §10.2）
6. 破坏性变更（字段删除/语义改变）必须升版本号并在文档头部记录

**契约变更记录**：

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-08-10 | 初版：全端点 + SSE 协议 + analyzer 错误契约（含 05 审查 P0-1/P0-3/P1-1~4 修复） |
| v1.1 | 2026-08-10 | 第二轮审查修订：新增会话列表/删除端点（§3.15）、错误码 2007/2008、HTTP 映射约定、rag/index 直读磁盘、命名统一 `source`、regenerate 状态转移 |
| v1.2 | 2026-08-10 | 第三轮审查修订：ai_status 口径统一为 NONE、克隆失败独立错误码 2009（映射 400）、analysis 落库补 report_source/prompt_version（见 02 V001） |

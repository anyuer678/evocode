# 更新日志

## v3.22（2026-08）建议引擎 + 12 类规则扫描 + 逐行分析

- **诊断建议引擎**：`rule_advice` 26 条 Sonar 规则映射「影响 + 可操作修复」，不依赖 LLM
- **新增 12 类确定性扫描**（Sonar 不可用也生效）：
  - 安全反模式（硬编码密钥 / 危险函数 / SQL 拼接 / 反序列化 / 弱哈希 / 明文密码比较）
  - 认知复杂度、架构环依赖、重复代码、错误处理反模式、风格一致性
  - 遗留标记（TODO/FIXME）、超大方法/类/文件、魔法数字
- **文件预览逐行标注**：问题按行高亮（红/橙/蓝）+ 悬停看建议
- 依赖建议打通前后端（suggestion 接口返回 + 前端建议列）
- 审查修复：Sonar 不可用时规则扫描结果被丢弃（P0）、suggestion 不落库（P1）、5 处误报

## v3.21（2026-08）前端 Naive UI 全量重构

- 全部页面组件化（NConfigProvider 主题 / NDataTable / NForm / NLayoutSider 分区导航 / 9 子视图）
- analyzer 端口 8081 → 8091（避本机冲突）
- 修复报告显示、图表隐藏崩溃、导航切换失效、AI 医生首问丢失等

## v3.20（2026-08）全面审查修复

- 安全：FileController 符号链接拦截、backend 绑定 127.0.0.1、ECharts tooltip XSS 转义
- 可靠性：SSE 切分修复、180s 超时、Parser 线程隔离、任务恢复
- **Spring Boot 3.3.5 → 3.3.13**（Tomcat CVE-2025-24813）

## v3.19（2026-08）项目全面审查（两波）

- 安全（Sonar token 环境变量化、codeDir 白名单）、可靠性（delete 事务、create 唯一索引 V011）
- 质量（错误码语义 2015、LLM 4xx 不重试、status.json 原子写）

## v1.2（2026-08）架构演进 A1/A2 + 技术债清零

- TD-09 架构扫描语言扩展（JS/TS/Go）、TD-10 token 估算精确化
- TD-05 Redis 列表缓存（AD-018，含降级）、报告拆表（analysis_report）

## v1.1（2026-08）P9 收官

- P9a UI 精致化：设计 token 精修、项目列表卡片化、vitest 单测
- P9b 项目操作/导出（Markdown 下载）
- P9c 历史报告对比（健康分折线 + 维度对比 + 风险 diff）
- P9d 依赖分析（pom/package 解析 + EOL 规则表）
- P9e 进度 SSE 推送 + 搜索排序

## v1.0（2026-08）MVP + 技术债 + Dashboard

- P0-P8：三端骨架、项目+扫描、AI 报告、质量(Sonar)、架构(tree-sitter)、演化(git)、AI 医生(RAG)、技术债+文档、Dashboard
- 端到端真库验证 + 两轮三端审查修复

## v0.1-v0.6（2026-07/08）里程碑

- v0.1 代码体检 MVP（上传→扫描→AI 报告）
- v0.2 质量（Sonar）
- v0.3 架构分析
- v0.4 演化分析
- v0.5 AI 医生（RAG + SSE）
- v0.6 技术债 + 文档生成

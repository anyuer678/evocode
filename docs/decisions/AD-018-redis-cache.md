# AD-018：Redis 启用方向——列表读缓存（TD-05 闭环）

- **日期**：2026-08-13
- **背景**：TD-05 记录 Redis 端口已预留（AD-017 已解决端口冲突）但代码无任何使用，属「预留未用」工程债。台账 §5.3 给出两个候选方向：① 分析任务队列（替换内存 `@Async`）② 读缓存（file_node/列表页），均要求「出 AD-018」。
- **决策**：
  1. **选读缓存，不选任务队列**：列表读缓存直接对应验收「列表接口 P95 <500ms」，且只动读路径、风险低；任务队列需替换 `AnalysisRunner` 的内存 `@Async` 状态机（AD-5），并发语义复杂，留待 A2 有真实并发压测数据后再评估（届时再出 AD-019）。
  2. **首个落地点 = 项目列表 `GET /projects`**（Dashboard/项目列表页高频读），`Spring Cache` + Redis，TTL 60s。
  3. **一致性策略**：项目增/删/改走 `@CacheEvict` 精确失效；分析完成引起的 `healthScore`/`lastAnalyzedAt` 变化由 60s TTL 兜底（Dashboard 场景 1 分钟延迟可接受，记为边界）。
  4. **序列化**：`GenericJackson2JsonRedisSerializer`（JSON 可读可调试，避免 JDK 序列化的脆弱性与跨版本不兼容）。
  5. **降级**：Redis 不可达时 `CacheErrorHandler` 忽略缓存异常 → 接口直接查库返回（200），不因缓存故障 500（延续 TD-08 降级对称原则）。
- **影响**：backend 新增 `spring-boot-starter-data-redis` 依赖；`application.yml` 增加 `spring.data.redis`（`127.0.0.1:6380`，对齐 AD-017）。无 Redis 时列表功能不受影响（降级直查库）。
- **验证**：真库（postgres + redis）下同一列表参数两次请求，第二次命中缓存；停掉 redis 后列表接口仍 200（降级生效）。

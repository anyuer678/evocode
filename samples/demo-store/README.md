# demo-store（演示项目）

一个「订单服务」多语言示例项目，用于演示 EvoCode 分析能力（小体积，约 20 个文件）：

- **Java**：Spring Boot 分层后端（Controller → Service → Repository → PaymentService），含依赖 `spring-boot-starter-parent 2.7.18`（命中 EOL 规则）
- **Vue 2** 前端（`vue ^2.7.14`，命中 EOL 规则）
- **Python**：`app/` 订单 API 服务
- **Go**：`cmd/server/` 订单服务

上传 `demo-store.zip` → 发起 FULL 分析，可看到：健康评分、架构分层图（4 语言节点 + 调用边）、依赖 EOL 风险（Spring Boot 2.7 / Vue 2）、技术债、AI 医生。

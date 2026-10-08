# 小智医疗学习项目

Java大模型导诊学习演示：Qwen + LangChain4j、Pinecone RAG、Vue界面、MongoDB账号/会话与MySQL演示预约。不是医疗服务，不连接真实医院，不提供诊断或处方。

## 当前功能与进度

- 账号注册登录、CSRF、账号数据归属、服务端历史与会话列表。
- 同步/流式聊天、Mongo模型记忆、资料来源展示、多文档及文本PDF知识库。
- 演示排班、号源、草稿人工确认、预约取消、幂等及事务控制。
- 三个独立框架对照示例；后端完整88项、前端41项及示例5/7/12项验证。
- 提交75174a6已验收5项CI任务、5份报告；包含本机发布包及全仓库文件检查。本轮双库恢复演练已通过，待对应提交CI。
- 第12项：本机单实例发布包、双库隔离合成数据恢复演练已完成；尚未备份现有业务数据或部署服务器，历史云模型慢请求根因仍未确认。

## 启动入口

开发模式：后端8081 + Vue5173，打开 http://127.0.0.1:5173/ 。
发布模式：Vue内置于JAR，打开 http://127.0.0.1:8081/ ，无需Vite。
旧/demo.html与GET /api/chat已禁用，请使用登录后的Vue页面。

每次重新开发先启动原MongoDB、确认MySQL84服务，再启动后端和前端。具体命令、环境变量、停止方法见[运行手册](docs/RUNBOOK.md)。
发布包构建和启动见[发布说明](docs/RELEASE.md)，数据保护见[备份与恢复](docs/BACKUP_RESTORE.md)，演练步骤及结果见[双库恢复演练](docs/BACKUP_DRILL.md)。

## 文档

- [学习路线](docs/roadmap.md) / [学习记录](docs/learning-log.md)
- [自动测试与CI](docs/CI_TESTING.md) / [浏览器验收](docs/BROWSER_ACCEPTANCE.md)
- [账号与归属](docs/ACCOUNT_AUTH.md) / [聊天历史](docs/CHAT_HISTORY.md)
- [模型诊断](docs/MODEL_DIAGNOSTICS.md) / [本轮文件检查](docs/PROJECT_REVIEW.md)


**技术与学习方式**

| 技术方向 | 使用技术 | 已实现的能力 |
|---|---|---|
| 后端与安全 | Java 17、Spring Boot 3.2.6、Spring Security | 登录鉴权、接口访问控制、账号数据隔离 |
| 大模型应用 | LangChain4j 1.0.0-beta3、DashScope／通义千问 | 模型接入、提示词管理、工具调用、SSE 流式回复 |
| 数据与预约 | MyBatis-Plus 3.5.7、MySQL 8.4、MongoDB 8 | 预约与会话持久化、状态流转、幂等提交、号源并发控制、取消释放 |
| RAG 知识库 | PDFBox、Pinecone、BM25、RRF | 多文档与文本型 PDF 解析、分段与向量化、混合检索、来源展示 |
| 检索优化 | gte-rerank-v2、受限纠错检索 | 专用重排序接入、依据充分性检查、问题改写与重查，以及效果和延迟对照实验 |
| 上下文与 Agent | 记忆窗口、上下文预算、结构化需求、规则路由 | 追问展开、问题分流、查询与澄清、预约草稿、人工确认、业务结果核对 |
| 工具与观测 | MCP、OpenTelemetry／OTLP | 只读工具服务、链路追踪、模型用量与延迟记录、异常定位 |
| 前端交互 | Vue 3、Vite、Element Plus | 登录工作台、会话管理、流式展示、资料来源展开、预约确认与断网恢复 |
| 测试与评测 | JUnit、Vitest、Playwright、真实数据库集成测试 | 权限隔离、预约并发、重复提交、流式异常、多轮对话与最终数据库状态验证 |
| 构建与交付 | Maven、Node.js、GitHub Actions | 自动化 CI、前后端一体化 JAR、版本发布、发布包内容与完整性校验 |
| 框架对照 | `@AiService`、LangChain4j Pinecone、Flux、官方 Agentic | 独立集成示例、顺序编排实践、框架与自定义方案对比 |

**学习与实践方法**

围绕具体功能，按以下流程推进：

理解目的 → 查阅课程与官方资料 → 实现功能 → 测试验证 → 对比效果 → 记录结论 → 提交推送 → 验收 CI 与发布包。

通过检索题集、多轮对话、真实数据库测试和浏览器测试验证实现；结合正确性、稳定性、延迟、token 用量与维护成本比较方案。学习记录保留设计理由、失败案例、修复过程和评测结果，使技术选型与改进过程可以复查。

**参考与项目管理**

参考课程：[尚硅谷《小智医疗：Java大模型应用项目全流程实战》](https://www.bilibili.com/video/BV1cpLTz1EVp/)。

学习仓库 `F:\xiaozhi-learning` 与参考初版 `F:\xiaozhi-medical` 分开管理，依赖版本通过各模块 `pom.xml` 和 `package-lock.json` 管理。

源码、测试、脚本、文档及演示评测资料纳入版本控制；密钥通过环境配置管理，业务数据独立存储与备份，发布产物通过 GitHub Releases 分发，并保留第三方资料来源。

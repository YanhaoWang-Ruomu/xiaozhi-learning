# 小智医疗学习项目

基于 Java 的大模型导诊学习演示：Qwen + LangChain4j、Pinecone RAG、Vue工作台、MongoDB账号与会话、MySQL演示预约，以及独立Python评测服务。不连接真实医院，不提供诊断或处方。

## 已实现的能力

- 账号注册登录、CSRF、访问控制、账号数据隔离、服务端历史与会话列表。
- 同步与SSE流式聊天、会话记忆、提示词与上下文预算、多文档及文本PDF知识库、资料来源展示。
- 演示排班与号源、预约草稿、人工确认、取消、幂等提交、并发容量与事务控制。
- BM25与向量混合检索、RRF融合、特征排序及专用重排序；受限纠错检索与效果对照。
- 问题路由、阶段工具控制、预约结果核对、可恢复人工确认流程与多轮评测。
- 只读MCP服务、OpenTelemetry/OTLP追踪、模型用量与延迟记录。
- 前后端一体化JAR、GitHub CI与版本发布、发布包校验、双库隔离恢复演练。
- Python异步评测任务、SQLite持久化、重复提交处理、失败与重启恢复；可复现界面录像。

完整后端280项、前端50项、浏览器8项及四个框架示例5/7/12/9项验证；Python服务新增16项检查。测试报告由CI保留，各指标的题集、时间和统计口径见[项目展示与评测](docs/PORTFOLIO.md)。源码更新与已下载的发布包分别管理。

## 启动入口

| 方式 | 打开地址 | 启动内容 |
|---|---|---|
| 开发 | http://127.0.0.1:5173/ | 数据库、Java后端8081、Vue开发服务5173 |
| 发布包 | http://127.0.0.1:8081/ | 数据库、内置Vue的JAR |
| Python评测 | http://127.0.0.1:8012/docs | 独立Python服务；不需要业务数据库 |

每次重新开发先启动原MongoDB、确认MySQL84，再启动后端和前端。旧 `/demo.html` 与 `GET /api/chat` 已禁用。具体命令、环境变量和停止方法见[运行手册](docs/RUNBOOK.md)；Python命令见[服务说明](python-eval/README.md)。

[发布说明](docs/RELEASE.md) / [备份与恢复](docs/BACKUP_RESTORE.md) / [双库恢复演练](docs/BACKUP_DRILL.md) / [演示录制](docs/DEMO.md)。

## 技术与学习方式

| 技术方向 | 使用技术 | 实现用途 |
|---|---|---|
| 后端与安全 | Java17、Spring Boot3.2.6、Spring Security | 登录鉴权、接口控制、账号数据隔离 |
| 大模型应用 | LangChain4j community1.0.0-beta3、DashScope/Qwen | 模型接入、提示词、记忆、工具调用、SSE回复 |
| 数据与预约 | MyBatis-Plus3.5.7、MySQL8.4、MongoDB8 | 数据持久化、预约状态、幂等、事务与号源管理 |
| RAG知识库 | PDFBox、Pinecone、BM25、RRF | 文档解析、分段向量化、混合检索、来源展示 |
| 检索优化 | gte-rerank-v2、受限纠错检索 | 相关性排序、依据检查、改写重查、质量与耗时对照 |
| 上下文与Agent | 记忆窗口、预算、结构化需求、规则路由 | 追问展开、问题分流、查询澄清、草稿与人工确认 |
| 工具与观测 | MCP、OpenTelemetry/OTLP | 只读工具复用、链路关联、用量和错误定位 |
| 前端 | Vue3、Vite、Element Plus | 会话工作台、流式展示、来源展开、确认与断网恢复 |
| Python服务 | Python3.14、FastAPI、asyncio、SQLite | 评测任务接口、独立进程、持久化与恢复 |
| 测试与评测 | JUnit、Vitest、Playwright、pytest | 权限、并发、流式异常、多轮行为与最终数据库状态验证 |
| 构建与交付 | Maven、Node.js、GitHub Actions | 自动检查、前后端一体化、版本发布与产物校验 |
| 框架对照 | `@AiService`、Pinecone适配器、Flux、官方Agentic | 独立集成和顺序编排示例、技术选型比较 |

依赖版本以各模块 `pom.xml`、`package-lock.json` 和 `python-eval/requirements.txt` 为准。围绕功能按“理解目的 → 查阅课程与官方资料 → 实现 → 验证 → 比较效果 → 记录结论 → 提交推送 → 验收CI与发布包”推进。

检索题集、多轮原文、实际数据库和浏览器检查共同支撑结论；比较正确性、稳定性、延迟、用量与维护成本。主项目实现、独立框架示例和可选实验分别保留说明，详细范围见[AI工程](docs/AI_ENGINEERING.md)、[Agent六方向](docs/AGENT_DIRECTIONS.md)。

## 文档与来源

- [学习路线](docs/roadmap.md) / [学习记录](docs/learning-log.md)
- [自动测试与CI](docs/CI_TESTING.md) / [浏览器验收](docs/BROWSER_ACCEPTANCE.md)
- [账号与归属](docs/ACCOUNT_AUTH.md) / [聊天历史](docs/CHAT_HISTORY.md)
- [模型诊断](docs/MODEL_DIAGNOSTICS.md) / [项目文件检查](docs/PROJECT_REVIEW.md)
- [实测汇总](evals/portfolio/20261009/REPORT.md) / [失败与评测复核](docs/AI_EVALUATION_REVIEW.md)

参考课程：[尚硅谷《小智医疗：Java大模型应用项目全流程实战》](https://www.bilibili.com/video/BV1cpLTz1EVp/)。学习仓库 `F:\xiaozhi-learning` 与参考初版 `F:\xiaozhi-medical` 分开管理。

源码、测试、脚本、文档与无敏感数据的评测资料纳入版本控制；密钥通过环境配置管理，业务数据独立备份，发布产物与录像通过Releases或CI附件分发。保留第三方课程、代码和资料的原来源与授权。

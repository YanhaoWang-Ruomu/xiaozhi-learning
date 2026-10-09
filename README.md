# 小智医疗学习项目

基于 Java 与大模型的医疗导诊学习演示项目，集成 Qwen、LangChain4j、混合检索 RAG、Vue 工作台、MongoDB 账号与会话管理，以及 MySQL 演示预约业务；独立 Python 服务负责评测任务与报告。

围绕“交流需求—检索资料—查询排班—生成草稿—人工确认”，实现可追溯的问答与预约流程，并通过自动化测试、AI 评测和持续集成验证功能。

本项目仅用于学习与能力展示，不连接真实医院，不办理真实医疗预约，不提供诊断或处方。

## 核心功能

- **账号与会话：** 注册登录、CSRF 防护、账号数据隔离、服务端聊天历史、会话列表与搜索。
- **大模型交互：** 同步与 SSE 流式回复、模型记忆、提示词管理、追问展开、上下文预算及用量记录。
- **RAG 知识问答：** 多文档与文本型 PDF 解析、向量化、Pinecone 检索、BM25 与 RRF 混合检索、资料来源展示。
- **检索优化实验：** 专用重排序、依据充分性检查、受限改写重查、带来源原文摘录，以及效果与延迟对照。
- **Agent 业务流程：** 问题路由、结构化预约需求、排班查询、信息澄清、草稿生成与业务结果核对。
- **演示预约：** 科室、医生、日期与时段查询；人工确认、预约取消、号源扣减与释放、幂等提交及事务控制。
- **前端工作台：** 会话管理、流式展示、回复复制、来源展开、预约确认、移动端布局与断网恢复。
- **工具与观测：** 只读 MCP 工具服务、OpenTelemetry／OTLP 追踪、模型用量与耗时记录。
- **Python 评测服务：** FastAPI 任务接口、异步子进程、SQLite 持久化、并发幂等、排队限额、失败与重启恢复。
- **框架对照：** 四个独立示例，覆盖 @AiService、LangChain4j Pinecone、Flux 与官方 Agentic 顺序编排。

## 验证与交付

- **后端测试：** 完整后端 280 项通过，覆盖业务规则、真实数据库、权限隔离、预约并发与流式生命周期。
- **前端测试：** 50 项测试通过，并通过 8 项 Chromium 浏览器检查验证关键交互、断网恢复和页面布局。
- **Python 验证：** 16 项自动检查、实际本机 HTTP 任务验证；固定依赖在 Linux 与 Windows CI 中复现。
- **界面演示：** 可重复录制登录、SSE、来源、预约确认、取消及刷新恢复；全程标明受控 HTTP 服务范围。
- **框架验证：** 四个独立对照工程纳入 CI，其中官方 Agentic 示例包含 9 项测试。
- **多轮评测：** 最终同步与流式各完成 6 组 11 轮自动检查和回答复核，分别记录程序预约与真实模型问答结果。
- **检索评测：** 固定题集对照检索效果与延迟，保存独立挑战样本、原始回答和失败记录。
- **发布交付：** v0.3.0 已发布，对应提交 3f06845 的 6 项 CI 任务全部通过；发布包内置新版 Vue 工作台，并校验前端资源、后端类、启动配置及文件完整性。
- **数据保护：** 已完成双库隔离合成数据恢复演练，提供备份、恢复与版本回退说明。

当前交付范围为本机单实例运行。检索实验的配置与验收结果见相关文档；公网部署另行推进。

[下载最新发布包](https://github.com/YanhaoWang-Ruomu/xiaozhi-learning/releases/latest)

## 启动入口

| 运行方式 | 启动内容 | 访问地址 |
|---|---|---|
| 开发模式 | 后端 8081 + Vue 5173 | http://127.0.0.1:5173/ |
| 发布模式 | Vue 内置于 JAR，无需启动 Vite | http://127.0.0.1:8081/ |
| Python 评测 | 独立服务，不需要业务数据库 | http://127.0.0.1:8012/docs |

启动顺序：启动原 MongoDB 数据目录 → 确认 MySQL84 服务 → 启动后端或发布包 → 开发模式下再启动 Vue。

开发后端与发布包默认共用 8081，切换前先停止原后端。IDEA 构建目录可能保留旧前端，重新启动 Java 不会自动重新构建 Vue；发布包使用本次构建并校验的页面资源。

旧 `/demo.html` 与 `GET /api/chat` 已禁用，请使用登录后的 Vue 页面。

- Python安装、接口与恢复：[服务说明](python-eval/README.md)
- 展示报告与录制：[可复现评测](docs/PORTFOLIO.md) / [演示视频](docs/DEMO.md)
- 启动命令、环境变量与停止方法：[运行手册](docs/RUNBOOK.md)
- 发布包构建、启动与回退：[发布说明](docs/RELEASE.md)
- 数据保护：[备份与恢复](docs/BACKUP_RESTORE.md)
- 恢复演练步骤与结果：[双库恢复演练](docs/BACKUP_DRILL.md)

## 文档导航

| 内容 | 文档 |
|---|---|
| 项目展示 | [指标与复现](docs/PORTFOLIO.md) · [原始记录汇总](evals/portfolio/20261009/REPORT.md) · [录像步骤](docs/DEMO.md) |
| 学习进度 | [学习路线](docs/roadmap.md) · [学习记录](docs/learning-log.md) |
| 自动化验证 | [测试与 CI](docs/CI_TESTING.md) · [浏览器验收](docs/BROWSER_ACCEPTANCE.md) |
| 账号与会话 | [账号与归属](docs/ACCOUNT_AUTH.md) · [聊天历史](docs/CHAT_HISTORY.md) |
| Agent 实现 | [六个方向的实现与验收](docs/AGENT_DIRECTIONS.md) · [多轮评测](docs/AGENT_MULTITURN_EVALS.md) |
| 检索实验 | [纠错式 RAG](docs/CORRECTIVE_RAG.md) · [专用重排序](docs/DEDICATED_RERANK.md) |
| 诊断与检查 | [模型诊断](docs/MODEL_DIAGNOSTICS.md) · [阶段文件检查记录](docs/PROJECT_REVIEW.md) |
| 版本交付 | [发布说明](docs/RELEASE.md) · [版本更新说明](docs/AI_RELEASE_NOTES.md) |

**技术与学习方式**

| 技术方向 | 使用技术 | 项目中的应用与能力 |
|---|---|---|
| 后端与安全 | Java 17、Spring Boot 3.2.6、Spring Security | 登录鉴权、数据归属隔离、接口设计 |
| 大模型接入 | LangChain4j 1.0.0-beta3、DashScope／通义千问 | 模型调用、工具调用、SSE 流式聊天 |
| 数据与预约 | MyBatis-Plus 3.5.7、MySQL 8.4、MongoDB 8 | 预约持久化、会话历史、状态流转、幂等提交、号源并发控制 |
| RAG 知识库 | PDFBox、Pinecone、BM25、RRF、可选 gte-rerank-v2 | 多文档解析、分段、混合检索、重排序、来源展示与纠错检索实验 |
| 上下文与 Agent | 提示词管理、记忆窗口、上下文预算、结构化需求、有界路由 | 追问展开，组织“查询→澄清→草稿→人工确认”，核对业务结果与引用来源 |
| 工具与观测 | MCP、OpenTelemetry／OTLP | 只读工具复用、链路追踪、token 与延迟记录、故障定位 |
| 前端交互 | Vue 3、Vite、Element Plus | 会话管理、流式回复、预约确认、断网恢复 |
| Python 服务 | Python 3.14、FastAPI、asyncio、SQLite | 评测接口、独立进程、任务持久化与恢复 |
| 测试与评测 | JUnit、Vitest、Playwright、pytest、真实数据库集成测试 | 权限与并发验证、多轮 Agent 评测、回答复核、最终数据库状态核验 |
| 构建与交付 | Maven、Node.js、GitHub Actions | 自动化 CI、前端构建、前后端一体化 JAR、发布包校验 |
| 框架对照 | `@AiService`、LangChain4j Pinecone、Flux、官方 Agentic | 通过独立示例比较框架方案与自定义实现，解释技术选型 |

**学习流程**

理解目的 → 对照课程与官方资料 → 实现 → 测试与效果对比 → 检查差异 → 更新学习记录 → 提交推送 → 验收 CI 与发布包。

以正确性、稳定性、延迟、成本和维护难度判断改进，保留失败案例，区分“已接入”“已验证”和“默认启用”。

**实现边界**

- 纠错 RAG 默认关闭，专用重排序按需选择。
- 有界规则路由不等于通用自主 Agent，引用来源一致不等于回答事实全部正确。
- 独立框架示例不代表主项目已经迁移；发布包生成不代表公网部署完成。

**参考与管理**

参考课程：[尚硅谷《小智医疗：Java大模型应用项目全流程实战》](https://www.bilibili.com/video/BV1cpLTz1EVp/)。

学习仓库 `F:\xiaozhi-learning` 与参考初版 `F:\xiaozhi-medical` 分开管理。版本以各模块 pom.xml、package-lock.json 和 python-eval/requirements.txt 为准。

仅提交源码、测试、文档、演示评测资料和无密钥配置；保留第三方来源，业务数据与密钥不入库。Git 推送不能代替数据库备份。

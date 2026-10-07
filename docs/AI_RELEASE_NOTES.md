本版为小智医疗导诊学习项目的 AI 工程演示版本，不办理真实医疗预约。

- 固定题集评测与三种检索对照，保存真实回答、工具选择和数据库状态证据。
- BM25 + 向量候选 + RRF + 特征重排作为默认检索，可切回vector；模型重排为额外付费调用的实验选项。
- 完整轮次记忆预算、指代追问展开和数据库结构化预约状态。
- OpenTelemetry请求关联、模型用量、工具及保存追踪；默认不上传正文。
- 官方SDK只读MCP工具，可复用知识搜索和演示排班查询。
- 带明确人工确认、版本校验与中断恢复的预约工作流实验页面。

使用方式、评测限制与命令见docs/AI_ENGINEERING.md和docs/AI_EVALUATION_REVIEW.md。解压后按docs/RELEASE.md配置数据库与环境变量；复制config/application.properties.example为config/application.properties，再运行start.ps1。需要Java17及已初始化的MySQL/MongoDB；首次运行不要直接连接未备份的业务库。

ZIP附SHA256校验文件和内部逐文件清单。GitHub发布是可运行安装包交付，尚未部署公网网站；本机启动脚本只监听127.0.0.1。Langfuse云项目、服务器/HTTPS与多节点运行未冒充完成。

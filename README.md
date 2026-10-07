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

## 技术与学习方式

Java17、Spring Boot3.2.6、LangChain4j community 1.0.0-beta3、MyBatis-Plus3.5.7、MySQL8.4、MongoDB8、Vue3。以各模块pom.xml和package-lock.json为准。
每一步：理解目的 → 实现 → 验证 → 检查差异 → commit/push → 验收CI。独立示例不等于主项目已迁移到同一实现。

参考课程：尚硅谷《小智医疗：Java大模型应用项目全流程实战》
https://www.bilibili.com/video/BV1cpLTz1EVp/
本学习仓库F:\xiaozhi-learning与参考初版F:\xiaozhi-medical分开。

仅提交源码、测试、学习记录和无密钥配置。运行数据、API Key、数据库密码、安装包及发布产物不进入仓库。Git推送不能代替数据库备份。第三方课程、代码和资料保留原来源，不擅自添加授权。


## AI ????

?????????? [AI_ENGINEERING](docs/AI_ENGINEERING.md)????????? [????](docs/AI_EVALUATION_REVIEW.md)??????? AI ??????

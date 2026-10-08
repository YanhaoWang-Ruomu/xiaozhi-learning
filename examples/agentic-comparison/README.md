# 官方 Agentic 编排对照

这个独立 Maven 工程使用真实的 langchain4j-agentic 1.21.0-beta31，主项目的 LangChain4j 1.0.0-beta3 不变。Java 17 可运行。

复现的是已有演示预约的一段有界流程：明确场次 → 查询 → 核验 → 返回待确认决定。官方 sequenceBuilder 与普通 Java 调用共用相同的查询和验证组件，对照状态、查询次数、失败恢复和隔离。9 项测试执行官方框架，不是自行仿造同名注解。

这里的 @Agent 是官方支持的非 AI agent：没有让模型决定扣号，没有调用真实医院，没有写数据库。它验证编排语义，不验证 LLM 自主规划，也不应把 0 次 appointmentWrites 当成真实数据库事务测试；主项目的 MySQL/Mongo 多轮契约负责后者。

## 运行

从仓库根目录运行：

    mvn -B -ntp -f examples/agentic-comparison/pom.xml verify

CI 独立执行并保留测试报告。场景包括明确选择、缺少选择、聊天确认、无余量、查询异常、返回错误场次、改口、故障后重试及零容量。每次调用创建独立 AgenticScope，不模拟跨进程恢复；主项目的持久工作流另有数据库恢复实现。

## 选型结论

官方框架提供统一的流程组合与共享状态；两步有界预约仍用显式 Java 更容易审查，尤其是执行权限和人工确认。当前不替换主项目流程。若以后引入模型理解阶段，也只能生成建议输入，服务器必须重新校验选择、所属账户和实时余量。

来源：
- https://docs.langchain4j.dev/tutorials/agents/ （官方标注模块为实验性；Non-AI agents、Sequential workflow）
- https://github.com/langchain4j/langchain4j/blob/1.21.0/langchain4j-agentic/pom.xml （锁定发布版依赖）

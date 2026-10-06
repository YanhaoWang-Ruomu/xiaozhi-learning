# 第10项之一：@AiService 与手动 AiServices.builder 对照

这是独立 Maven 学习示例，位于 examples/aiservice-comparison。它不作为 backend 的依赖，不参与主项目组件扫描。

版本固定为 Java 17、Spring Boot 3.2.6、LangChain4j 1.0.0-beta3，与当前后端对应。beta3 中的接口名称是 ChatLanguageModel，手动配置方法为 chatLanguageModel；注解属性名则是 chatModel。不要直接照搬新版文档替换这些名称。

## 先运行

打开 IDEA 的 PowerShell 终端：

```powershell
cd F:\xiaozhi-learning\examples\aiservice-comparison
$env:JAVA_HOME = 'F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1'
& 'F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd' test
& 'F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd' spring-boot:run
```

第一条 Maven 命令验证五项测试；第二条运行命令行演示。末尾应出现 AISERVICE_COMPARISON_OK，随后退出码为0。这里运行结束是正常行为：示例没有Web服务器，不占用8081或5173端口。

不需要API Key，也不需要启动MySQL、MongoDB或主后端。首次Maven解析依赖需要联网；模型本身只是本地确定性替身，不调用云模型，不产生模型费用。

若希望在IDEA中阅读并直接运行，可以右键该目录的pom.xml选择“添加为Maven项目 / Add as Maven Project”，再运行LabApplication.main。不要把示例Java文件复制进backend。

## 观察什么

示例依次对两个助手发送“round-1”“round-2”“查询演示规则”：

```text
MANUAL      ECHO=round-1;USER_MESSAGES=1
DECLARATIVE ECHO=round-1;USER_MESSAGES=1
MANUAL      ECHO=round-2;USER_MESSAGES=2
DECLARATIVE ECHO=round-2;USER_MESSAGES=2
MANUAL      TOOL_RESULT=DEMO_ONLY;MEMORY_ID=comparison-a;MANUAL_CONFIRMATION_REQUIRED
DECLARATIVE TOOL_RESULT=DEMO_ONLY;MEMORY_ID=comparison-a;MANUAL_CONFIRMATION_REQUIRED
ISOLATED    ECHO=new-conversation;USER_MESSAGES=1
AISERVICE_COMPARISON_OK
```

- MANUAL：由手写@Bean方法调用AiServices.builder创建。
- DECLARATIVE：starter扫描@AiService接口并注册Spring Bean。
- USER_MESSAGES：本次送到模拟模型的用户消息数量，第二轮能看到第一轮。
- ISOLATED：新的会话编号得到独立记忆。
- TOOL_RESULT：模拟模型发起工具请求，AI Services真正执行本地只读工具，再把结果交回模拟模型。

这些输出验证框架装配流程，不代表验证了真实大模型的理解、准确性或工具选择能力。

## 推荐阅读顺序

| 文件 | 重点 |
|---|---|
| AssistantContract.java | 两种助手继承同一提示词与方法；@MemoryId、@UserMessage、@V如何区分参数用途 |
| ManualAssistant.java + LabConfiguration.java | 找到manualAssistant的@Bean和builder链 |
| DeclarativeAssistant.java | @AiService(wiringMode=EXPLICIT)如何按名称选择组件 |
| RecordingChatModel.java | 看实际收到的ChatRequest，以及工具请求/结果两轮往返 |
| AiServiceComparisonTest.java | 每个结论对应哪条断言 |
| LabApplication.java | 执行对照、打印结果并关闭Spring上下文 |

手动创建的关键代码：

```java
return AiServices.builder(ManualAssistant.class)
        .chatLanguageModel(model)
        .chatMemoryProvider(memory)
        .tools(tools)
        .build();
```

声明式创建的关键代码：

```java
@AiService(wiringMode = EXPLICIT,
        chatModel = "labModel",
        chatMemoryProvider = "declarativeMemory",
        tools = "labTools")
public interface DeclarativeAssistant extends AssistantContract {}
```

两个方式最终都通过AI Services创建接口实现。注解方式把这部分装配交给starter，减少手写配置；模型、提示词、记忆、工具和权限仍需要明确设计。

示例使用EXPLICIT：容器故意同时放入另一个模型Bean和另一个带@Tool的方法类。测试确认只使用labModel和labTools，避免自动收集所有工具或出现模型选择歧义。

## 五项测试

1. starter确实扫描并注册唯一的声明式助手Bean，同一Bean保持单例。
2. 两种助手给模型的消息一致，@V日期占位符被替换，只装配指定工具。
3. 同一助手不同MemoryId互不串话，两个对照助手各自拥有独立记忆。
4. 两种方式均真正执行指定工具，@ToolMemoryId由框架注入，模型参数不传会话编号。
5. 8条消息的记忆窗口会裁剪旧内容，系统消息保持一条；有限模型窗口不等于完整服务端历史。

## 与主项目的关系

| 主项目当前AiConfig设置 | 若以后使用声明式方式，对应位置 |
|---|---|
| .chatLanguageModel(model) | chatModel="qwenChatModel" |
| .streamingChatLanguageModel(streamingModel) | streamingChatModel="qwenStreamingChatModel" |
| 内联chatMemoryProvider，窗口20条且使用Mongo存储 | 先提取成命名Bean，再通过chatMemoryProvider指定 |
| .tools(appointmentTools) | tools="appointmentTools" |
| .retrievalAugmentor(knowledgeRetrievalAugmentor) | retrievalAugmentor="knowledgeRetrievalAugmentor" |

本轮保留主项目手动配置。当前示例已覆盖同步装配、提示词、变量、记忆和工具；尚未证明主项目的流式生命周期、RAG来源、Mongo持久化和账号权限在改用注解后全部等价。若未来迁移，需要逐项验证，且移除旧的同接口@Bean，避免重复实例和记忆分叉。

@MemoryId和@ToolMemoryId传递会话标识，本身不负责用户权限；主项目第9项的服务端归属校验必须继续保留。两种创建方式不会自动提升模型回答质量。

下一小步：LangChain4j Pinecone集成对照；随后Flux流式输出。这里只完成第10项的第一个子项。

## 核对资料

- 官方说明：https://docs.langchain4j.dev/tutorials/spring-boot-integration/ （持续更新，示例API以本工程固定版本为准）
- beta3发布源码：https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-spring-boot-starter/1.0.0-beta3/langchain4j-spring-boot-starter-1.0.0-beta3-sources.jar
- 本轮实际核对源码中的AiService.java、AiServiceFactory.java、AiServiceScannerProcessor.java，并通过固定版本构建运行验证。

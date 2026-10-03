# 学习记录

## 2026-10-03 · 阶段 0：建立学习仓库

### 本次完成

- 建立独立的学习仓库。
- 准备 README、学习路线、Git 忽略规则和文本格式规则。
- 明确后续采用“理解 → 实现 → 双重验证 → 提交”的学习方式。

### 当前理解

- Java 是语言；JDK 负责开发和运行 Java 程序。
- Maven 管理依赖、编译、测试和打包。
- Spring Boot 提供后端应用的基础结构。
- Git 记录本地版本；GitHub 保存推送后的仓库和版本历史。
- commit 是一次版本记录，push 将本地提交同步到远程。

这些是入门解释，实际掌握情况需要通过后续操作验证。

### 尚未完成

- 尚未创建学习版 Spring Boot 工程。
- 尚未接入模型或实现任何业务功能。
- 参考初版已经完成的功能不计入本仓库的学习进度。

### 下一步

认识开发工具，创建最小 Spring Boot 项目并运行。

## 后续记录模板

- 日期 / 阶段：
- 本次目标：
- 我的理解：
- 修改了哪些文件：
- 遇到的问题与解决办法：
- 如何验证、验证结果：
- 对应提交：
- 下一步：

## 2026-10-03：运行第一个 Spring Boot 后端

- 创建 backend Maven 模块，使用 Java 17 和 Spring Boot 3.2.6。
- 在 pom.xml 中加入 Web 依赖和 Spring Boot Maven 插件。
- 创建 XiaozhiApplication 启动类，设置端口为 8081。
- 创建 HelloController，提供 GET /api/hello 接口。
- 浏览器访问接口，成功显示“小智医疗后端已启动”。

遇到的问题：
启动时提示 org.springframework.boot 包不存在。
点击“加载 Maven 更改”并完成同步后，重新运行成功。

我的理解：
pom.xml 声明项目需要的依赖，修改后需要让 Maven 同步。
启动类负责启动应用，Controller 负责接收请求并返回结果。

## 2026-10-03：完成第一次模型调用

- 添加 LangChain4j DashScope 1.0.0-beta3 依赖。
- 创建 QwenHello，从 DASHSCOPE_API_KEY 环境变量读取密钥。
- 调用 qwen-plus，成功收到回复，程序正常退出。

遇到的问题：
调用曾返回 401 Invalid API-key provided。
重新创建并配置完整的百炼 API Key，重启 IDEA 后调用成功。

我的理解：
build() 创建模型客户端，chat() 才真正发送请求。
密钥保存在本机环境变量中，代码只保存环境变量名称。

### 2026-10-03：完成大模型 HTTP 接口

- 新增 AiConfig，通过 @Bean 将 QwenChatModel 交给 Spring 管理。
- 新增 ChatController，通过构造方法注入模型对象。
- 实现 GET /api/chat，通过 message 参数接收用户问题。
- 浏览器验证默认问候和自定义问题均能返回模型回复。
- 当前每次请求独立，尚未加入会话记忆。

### 2026-10-03：实现内存会话记忆

- 增加 langchain4j 依赖，通过 AiServices 创建 ChatAssistant。
- 使用 @MemoryId 和 conversationId 区分会话。
- 使用 MessageWindowChatMemory，每个会话最多保留 20 条消息。
- 验证 demo1 能记住测试名字，demo2 不知道该名字。
- 当前记录保存在程序内存中，重启后清空，尚未接入 MongoDB。

### 2026-10-03：实现 MongoDB 会话记忆持久化

- 添加 Spring Boot MongoDB 依赖和数据库连接配置。
- 实现 MongoChatMemoryStore，支持读取、更新和删除会话记忆。
- 使用 xiaozhi_learning 数据库和 chat_memory 集合。
- 验证 Java 后端重启后，原会话仍能读取测试暗号。
- 验证新会话不能读取其他会话的暗号。
- 当前持久化最多 20 条消息的记忆窗口，不是完整聊天历史。
- 本机数据目录：F:\xiaozhi-learning-data\mongodb。
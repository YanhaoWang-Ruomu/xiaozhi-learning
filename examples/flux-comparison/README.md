# Flux 与 SseEmitter 流式输出对照

这是路线图第 10 项的第三个独立示例。目录：`F:\xiaozhi-learning\examples\flux-comparison`。

使用 Java 17、Spring Boot 3.2.6 和 LangChain4j 1.0.0-beta3，与当前项目版本对齐。这里没有修改主项目的聊天控制器，没有连接通义千问、MongoDB、MySQL 或 Pinecone，也不会操作预约。

## 运行

在 IDEA 的 PowerShell 终端执行：

```powershell
cd F:\xiaozhi-learning\examples\flux-comparison
$env:JAVA_HOME = 'F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1'
& 'F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd' test
```

成功时，测试汇总为 `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`，最后出现 `BUILD SUCCESS`。
测试过程中还会打印 `FLUX_COMPARISON_OK mvc=PASS lifecycle=OFFLINE`；以最终测试汇总为准。

这个模块没有需要长期运行的 Spring Boot 启动类。测试结束后进程退出是正常现象，不需要启动数据库或填写 API Key。首次运行 Maven 需要下载依赖。

## 代码从哪里看

| 文件 | 学习内容 |
|---|---|
| `pom.xml` | 在 MVC 工程中加入 Reactor 和版本匹配的 LangChain4j 适配器，不引入 WebFlux starter |
| `src/main/java/com/ruomu/examples/flux/EventFluxBridge.java` | 将 TokenStream 回调映射为有名字的 SSE 事件，保存后再发 done |
| `src/test/java/com/ruomu/examples/flux/ControlledTokenStream.java` | 手动触发模型回调的替身，不模拟模型语言能力 |
| `src/test/java/com/ruomu/examples/flux/FluxComparisonTest.java` | 调用真实 beta3 适配器，比较开始时机、错误、取消、超时和完成处理 |
| `src/test/java/com/ruomu/examples/flux/MvcWireContractTest.java` | 用 Spring MVC MockMvc 比较 Flux 与 SseEmitter 的实际 SSE 序列化结果 |

## 三种方案的区别

| 对照项 | 当前主项目 SseEmitter | beta3 原生 Flux<String> 适配器 | 本例事件桥接 |
|---|---|---|---|
| 启动 | 控制器调度任务，调用 TokenStream.start | adapt 时就启动，不等订阅 | 首次订阅启动；同一轮拒绝再次订阅 |
| 传输数据 | status/token/sources/done/failed | 只有 partial response 文字片段 | 显式建立对应事件及 JSON 字段 |
| 最终完整回复 | 保存在历史后发送 done | 完成回调只触发流完成，不输出完整回复对象 | save 回调成功后发送 done |
| 来源 | 已接入主项目 RAG | 不自动转成 Flux 元素 | 演示 source/index/text 字段 |
| 预约草稿 | 根据真实工具结果核实 | 不自动生成业务事件 | 无预约工具，drafts 永远为空 |
| 客户端取消 | 关闭传输，模型结束前不释放占用 | 取消订阅不能调用不存在的 TokenStream.cancel | 仍等待模型回调保存及释放 |

因此，把接口返回值直接改成 `Flux<String>` 并不能保持当前前端协议。是否采用 Flux 是组织异步数据流的选择，不能仅凭写法更短认定它更先进。

## 测试覆盖

1. 真实适配器在订阅前启动，缓存片段；最终完整回复和来源不会自动成为元素。
2. 原生适配器传播模型错误。
3. 原生适配器只允许一个订阅者，取消不会撤销已经开始的模型请求。
4. 事件桥接保留来源、片段、工具状态与完整回复；保存发生在 done 前，重复终态不重复保存或释放。
5. 模型错误输出 failed，不把内部异常内容返回给用户。
6. 保存失败不输出 done。
7. LENGTH 截断不作为成功回复保存。
8. 客户端取消后不提前释放模型占用，模型随后完成时仍保存。
9. 无数据超时结束下游传输，但模型随后完成时仍保存和释放。
10. `timeout(Duration)` 检查元素间隔，不能当作整轮总时限。
11. 同一轮再次订阅不会启动第二次模型请求。
12. MVC 下 Flux 和 SseEmitter 输出相同的 status/sources/token/done 事件及 JSON；failed 的行为在生命周期测试中验证。

## 需要理解的边界

- 本例使用真实 LangChain4j Reactor 适配器和真实 Spring MVC 序列化；模型回调由替身触发，保存和锁释放是计数回调。它没有验证真实 Qwen SDK、浏览器 TCP 断线、实际数据库事务或预约工具。
- `thenCancel()` 验证的是 Reactor 订阅取消，不是假称完成了浏览器断网实测。
- 这里的 `timeout(5 秒)` 是无数据间隔超时。即使不断有 token 到达，也可能超过整轮总时限。主项目 SseEmitter 的 Servlet 异步超时也不是同一个计时器。
- 不要在 `doFinally(CANCEL)` 中直接释放仍在执行工具的会话锁。停止向网页发送数据，并不等于模型停止、工具停止或预约回滚。
- 本例没有加入 `retry()`。重新创建冷流或重新订阅可能重复调用模型；业务操作必须靠服务端幂等与状态核实，不能靠重试流保证安全。
- `EventFluxBridge` 使用 ERROR 溢出策略，没有无限队列；它不能让这个回调式模型按下游需求减速，也没有模拟慢客户端和生产负载。
- 如果模型永远不回调，示例不会释放模型占用。生产迁移需要明确的模型超时、任务终态和资源回收方案，不能用传输层取消代替。
- 示例来源固定为合法测试资料；没有复制主项目的来源校验、账号权限、历史失败标记、并发额度、工具结果核实及线程调度。不要直接替换主项目控制器。

## 对主项目的决定

继续保留当前 SseEmitter 实现。三个框架对照示例用于理解方案和行为，不表示已经迁移生产逻辑，也不表示云端端到端验证完成。

下一步进入路线图第 11 项：盘点主项目现有测试，补齐关键状态、重复提交、号源并发、权限隔离和资料来源测试，再配置 CI。

## 参考

- [Spring MVC 异步与响应式返回值](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)
- [LangChain4j Reactor 1.0.0-beta3 源码包](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-reactor/1.0.0-beta3/langchain4j-reactor-1.0.0-beta3-sources.jar)：`TokenStreamToFluxAdapter` 是本例实际调用的适配器。
- [Reactor Flux API](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html)：文档随版本更新，本例行为以固定依赖和测试为准。

# 主项目流式聊天回归测试

2026-10-06：`ChatControllerStreamTest` 的27项场景全部通过。与原有50项合并后，Windows完整后端 `mvn verify` 共77项，0失败、0错误、0跳过，打包成功。本轮没有修改主项目业务实现。

## 单独运行本轮测试

在新的PowerShell窗口执行，无需启动MongoDB、MySQL、后端或网页，也不需要模型/Pinecone Key：

```powershell
cd F:\xiaozhi-learning\backend
$env:JAVA_HOME = 'F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1'
& 'F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd' '-Dtest=ChatControllerStreamTest' test
```

预期 `Tests run: 27, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`。

运行完整77项时，使用[MYSQL_TESTING.md](MYSQL_TESTING.md)中的临时MySQL脚本，MongoDB须先运行。默认 `mvn test` 执行60项离线场景，账号和MySQL集成类显式跳过；跳过不等于集成验证成功。

## 测试验证什么

| 范围 | 验证行为 |
|---|---|
| SSE协议 | 正常的status → sources → token → done顺序；JSON中的中文和换行可还原；SSE Content-Type、禁止缓存和代理缓冲响应头 |
| 回复与来源 | 最终回复来自完整完成回调；空token不发送；来源缺失和非法编号被过滤；done包含的内容与传给历史服务的内容一致 |
| 草稿 | 只采纳目标工具实际返回的草稿ID，再调用草稿服务读取；重复ID仅展示一次；错误JSON或缺少ID时failed，不虚构草稿 |
| 完成与保存 | 保存成功后才能发done；保存异常发failed；即使写中断状态也失败，仍释放会话；内部异常详情不进入SSE回复 |
| 模型异常 | 错误回调、准备阶段异常、start异常、LENGTH截断及空白最终回复不能作为成功保存 |
| 请求与启动 | 非法输入400、缺身份401、任务队列拒绝503；历史开始失败、身份检查失败和队列拒绝后可重试同一会话 |
| 重复请求 | 同一会话的流式和同步请求共享409保护；8线程同时请求同一会话只开始1轮；全局8轮容量，第9轮429 |
| 重复回调 | 完成后的重复完成/错误不重复释放容量；完成和错误竞争只写入一个终态；保存尚未返回时新请求仍409 |
| 连接结束 | Servlet error、completion、timeout回调结束传输但不提前释放正在运行的模型占用；模型随后完成仍保存，随后失败则标记中断并释放 |
| 启动前断开 | 排队期间或模型准备期间断开，不启动TokenStream；排队任务执行清理后可以重试 |

27是参数化场景展开后的总数，不代表27个独立业务功能。

## 真实组件与替身边界

- 使用实际主项目 `ChatController`，真实Spring MVC异步处理、SseEmitter和JSON转换器；没有复制独立Flux示例作为主项目测试。
- `ControlledChatStream` 只手动触发模型回调。模型、工具执行、草稿查询和历史持久化均使用替身，不访问云服务和业务数据库。
- 存储异常证明控制器的处理与调用顺序，不证明真实Mongo网络故障后的恢复。数据库及权限验证由其他测试分别承担。
- 断线/超时通过MockAsyncContext的Servlet监听器回调驱动；没有实际等待180秒，也没有建立真实TCP连接。浏览器断网、反向代理缓冲、慢客户端及真实模型取消不在本轮覆盖范围。
- 独立MVC环境不加载完整SecurityFilterChain；这里只验证控制器身份检查。完整账号/CSRF/归属隔离仍由AccountIsolationTest负责。
- 线程屏障和锁存器控制竞争，不靠sleep碰运气；8线程测试不是多节点或压力性能证明。

连接超时不等于模型或预约工具已经停止。本轮验证的是等待真实完成/错误回调后释放占用的既定行为。若模型永久不返回终态，本测试不证明占用一定会恢复；不能用直接提前释放来代替可靠的模型终止机制。

## CI和进度

Project checks仍为5个任务。后端除了执行全部测试，还显式检查ChatControllerStreamTest至少27项且失败、错误、跳过均为0；Java报告仍在backend-test-reports中。

前一阶段提交975642f、Actions运行37450526301已由用户截图确认5个任务全绿、4份报告、1分24秒。本轮27项流式测试及77项完整后端在本机通过，工作流actionlint通过；新增内容的云端结果待本轮推送后验收。

下一步补前端关键交互回归。第11项尚未整体完成，第12项打包部署尚未开始。

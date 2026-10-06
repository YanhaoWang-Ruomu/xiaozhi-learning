# 第11项：自动化测试与CI（第一阶段）

## 当前结论

2026-10-06，在Windows本机验证：后端34项测试通过，0失败、0错误、0跳过；三个框架示例分别5、7、12项测试通过，`mvn verify`成功；Vue生产构建成功。

这是测试体系的第一阶段，不代表第11项全部完成。GitHub工作流已配置，但首次云端运行必须在推送后查看Actions结果。

## 覆盖盘点

| 内容 | 当前证据 | 尚未覆盖 |
|---|---|---|
| 放号、预约日期窗口 | `AppointmentBookingPolicyTest`，10项，固定时间验证上海午夜、放号前1纳秒、正好放号、未来3天范围及非法输入 | 实际确认事务、号源扣减 |
| RAG参考资料注入 | `KnowledgeRetrievalAugmentorTest`，6项，阈值、最多2个来源、500字符边界、FOUND/NO_MATCH/FAILED/SKIPPED状态 | 真实模型回答是否正确引用资料 |
| 知识来源校验 | `KnowledgeSearchServiceTest`，17项，真实服务校验来源/正文/版本/模型/类型/编号、重复ID、分数、索引完整性、向量和重复同步 | 真实Pinecone索引传播与语义效果；本例使用网络替身 |
| 登录和归属权限 | 现有`AccountIsolationTest`，1个综合流程，真实Mongo临时库和Spring Security；验证会话、草稿、预约越权、CSRF、旧数据一次导入等 | 预约服务是替身，不是MySQL业务端到端测试 |
| 框架学习示例 | @AiService 5项、Pinecone 7项、Flux 12项，分别独立构建 | 不能代替主项目回归测试 |
| Vue | 锁定依赖安装后的构建门禁；本地现有依赖构建通过 | 浏览器交互和端到端测试 |
| 预约状态、重复提交、取消释放、号源并发 | 既有人工验收和手动检查代码 | 下一阶段必须补真实MySQL隔离测试，不能用单元替身声称验证了数据库锁 |
| 主项目流式聊天 | 现有人工验收；独立Flux生命周期示例 | 真实ChatController保存失败、断线和并发占用的回归测试 |

盘点发现：旧`AppointmentFlowCheck`/`AppointmentSessionFlowCheck`是main入口手动程序，不由Surefire自动发现；至少场次检查程序没有登录会话和CSRF处理，不可直接作为当前CI门禁。`AppointmentReleaseRuleCheck`的固定时间边界已纳入JUnit测试，旧程序保留作学习参考。

## 在本机运行

无需启动网页或Spring Boot后端。云模型Key和MySQL均不需要。

只运行不需要数据库的检查：

```powershell
cd F:\xiaozhi-learning\backend
$env:JAVA_HOME = 'F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1'
& 'F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd' test
```

预期汇总：34项被发现，33项通过，1项账号集成测试被显式跳过。`Skipped: 1`不是账号测试成功。

完整运行本轮后端门禁（MongoDB必须在`127.0.0.1:27017`运行）：

```powershell
cd F:\xiaozhi-learning\backend
$env:JAVA_HOME = 'F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1'
& 'F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd' '-Dxiaozhi.auth.integration=true' verify
```

预期：`Tests run: 34, Failures: 0, Errors: 0, Skipped: 0`以及`BUILD SUCCESS`。这个命令会测试并打包，不启动业务后端。

账号测试使用随机命名的`xiaozhi_auth_test_*`临时库，结束时删除它自己创建的库，不使用业务库；异常终止时可能留下临时库，不能据此前缀批量删除其他数据。Mongo未运行时该命令应失败，不自动降级为跳过。

## CI如何运行

工作流：`.github/workflows/ci.yml`，名称`Project checks`。

- 推送`main`、面向`main`的Pull Request或手动触发时运行。
- 后端任务启动临时Mongo 8.0服务容器，Java17执行带账号测试开关的`mvn verify`；随后读取JUnit报告，确保账号测试有执行且没有跳过。
- 三个独立框架示例并行执行`mvn verify`。
- Vue使用Node24执行`npm ci`和`npm run build`。本机已验证现有依赖构建；云端干净安装是否成功以首次Actions运行结果为准。
- 不需要配置真实DASHSCOPE、Pinecone或MySQL密码。工作流不部署、不发布、不操作本机数据库。
- Java测试报告保留7天，在对应运行页面的Artifacts下载。检查失败时先查看红色步骤及报告，不只看最后一行。
- 工作流只有读取仓库权限；并发提交会取消同分支旧运行。分支保护和必需检查尚未配置。

推送后在GitHub仓库打开`Actions` → `Project checks` → 本次提交。应出现后端、三个示例、前端共5个任务。只有全部绿色才能确认这次云端检查通过。本地成功不能代替云端结果。

## 下一阶段

1. 在独立MySQL测试库或容器中验证真实事务：重复确认同一编号、取消幂等、取消返还一次容量、已取消状态不可恢复。
2. 用同时竞争的请求验证场次和每日容量不超售、同一个幂等键不能切换场次、异常回滚不留下部分数据。
3. 将MySQL测试接入CI，再补主项目流式协议和必要的前端回归；根据实际云端结果决定是否设置必需检查。

每个测试都应明确替身与真实组件的边界，不通过增加重复断言来夸大覆盖率。

## 参考

- [GitHub：Maven构建和测试](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven)
- [GitHub：服务容器](https://docs.github.com/en/actions/tutorials/use-containerized-services/use-docker-service-containers)
- [actionlint](https://github.com/rhysd/actionlint)：检查工作流语法及表达式，不能代替GitHub实际运行。

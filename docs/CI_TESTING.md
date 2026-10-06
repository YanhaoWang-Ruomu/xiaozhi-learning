# 第11项：自动化测试与CI

## 当前结论

2026-10-06，Windows后端完整验证：50项，0失败、0错误、0跳过，`mvn verify`和打包成功。包括33项默认离线测试、1项真实Mongo账号隔离测试、16项真实MySQL预约测试。

此前三个独立框架示例分别5、7、12项通过，Vue生产构建通过。提交77383fe的5个GitHub任务和4份测试报告已由用户截图验收，upload-artifact升级v6后的警告消失。本次新增MySQL工作流经actionlint通过，云端结果待推送后确认。

## 覆盖盘点

| 内容 | 当前证据 | 边界/缺口 |
|---|---|---|
| 放号和日期 | AppointmentBookingPolicyTest，10项固定时间边界 | 不单独证明事务正确性 |
| RAG资料注入 | KnowledgeRetrievalAugmentorTest，6项状态、数量和长度边界 | 无真实模型回答验证 |
| 知识来源 | KnowledgeSearchServiceTest，17项身份校验、同步和异常 | Pinecone网络替身，无云端语义效果验证 |
| 登录和归属 | AccountIsolationTest，1个综合流程，真实Mongo和Spring Security | 预约服务替身，非全业务端到端 |
| 预约事务 | AppointmentMySqlIntegrationTest，16项，真实MySQL/MyBatis/事务与Mongo草稿 | 8线程受控竞争；不包含迁移、网络中断或压力性能 |
| 框架示例 | @AiService 5项、Pinecone 7项、Flux 12项 | 独立示例不代替主项目回归 |
| Vue | npm ci及生产构建门禁 | 待补浏览器交互回归 |
| 主项目流式 | 现有人工验收 | 待补保存失败、断线、并发占用和协议回归 |

旧AppointmentFlowCheck/AppointmentSessionFlowCheck是main入口手动程序，不由Surefire自动发现；不能直接作为当前账号系统下的CI门禁。

## 本机命令

只运行不依赖数据库的33项检查：

```powershell
cd F:\xiaozhi-learning\backend
$env:JAVA_HOME = 'F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1'
& 'F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd' test
```

默认禁用账号和MySQL集成测试。报告中的跳过不代表它们通过。

完整50项使用临时MySQL脚本；Mongo必须已经运行在127.0.0.1:27017：

```powershell
cd F:\xiaozhi-learning
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-mysql.ps1 -JavaHome "F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1" -MavenCommand "F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd"
```

预期50项、0失败、0错误、0跳过、BUILD SUCCESS以及MYSQL_TEST_RUNNER_EXIT=0。脚本不启动业务后端，不需要云模型Key。隔离方式、测试边界和故障排查见[MYSQL_TESTING.md](MYSQL_TESTING.md)。

## CI运行方式

`.github/workflows/ci.yml`的Project checks在推送main、面向main的PR或手动触发时运行：

- 后端：Java17、Mongo8.0和MySQL8.4临时服务；MySQL映射13307，执行两个集成开关均开启的mvn verify。
- 报告门禁：检查账号至少1项、MySQL至少16项，失败、错误、跳过均为0，防止未执行被误认成功。
- 三个独立示例：分别mvn verify。
- Vue：Node24、npm ci、npm run build。
- 共5个任务，Java报告使用upload-artifact@v6保留7天。无真实云端密钥、业务数据库密码或部署步骤；仓库权限只读。

推送后打开Actions → Project checks → 本次提交，检查5个任务及Artifacts。只有本次云端执行全部绿色才表示本次CI通过。本机结果不替代云端结果。分支保护及必需检查尚未配置。

## 后续

第11项第二阶段的本机MySQL验证已完成，云端待验收；接着补主项目流式与必要的前端回归。第11项尚未整体完成，第12项打包部署尚未开始。

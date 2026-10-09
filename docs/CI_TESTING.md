# 第11项：自动化测试与CI

## 当前结论

2026-10-07，Windows后端完整验证：88项，0失败、0错误、0跳过，`mvn verify`和打包成功。包括33项原有离线测试、27项主项目流式测试、2项离线/网站安全配置测试、9项模型网络与错误诊断测试、1项真实Mongo账号隔离测试、16项真实MySQL预约测试。

此前三个独立框架示例分别5、7、12项通过，Vue生产构建通过。提交77383fe的5个GitHub任务和4份测试报告已由用户截图验收，upload-artifact升级v6后的警告消失。提交975642f的MySQL阶段已由截图确认5个任务全部通过、4份报告、耗时1分24秒（Actions运行37450526301）。第三阶段提交2319b5e已由GitHub API核对：Actions运行37452460334完成且5个任务全部成功。第四阶段提交f40aee7已截图验收：5个任务成功、5份报告、1分5秒。后续依赖修复本机审计0项、41项前端回归及构建通过；依赖修复提交cbe9c7c、Actions运行37456983741已核对成功，5个任务及5份报告通过。

## 覆盖盘点

| 内容 | 当前证据 | 边界/缺口 |
|---|---|---|
| 放号和日期 | AppointmentBookingPolicyTest，10项固定时间边界 | 不单独证明事务正确性 |
| RAG资料注入 | KnowledgeRetrievalAugmentorTest，6项状态、数量和长度边界 | 无真实模型回答验证 |
| 知识来源 | KnowledgeSearchServiceTest，17项身份校验、同步和异常 | Pinecone网络替身，无云端语义效果验证 |
| 登录和归属 | AccountIsolationTest，1个综合流程，真实Mongo和Spring Security | 预约服务替身，非全业务端到端 |
| 预约事务 | AppointmentMySqlIntegrationTest，16项，真实MySQL/MyBatis/事务与Mongo草稿 | 8线程受控竞争；不包含迁移、网络中断或压力性能 |
| 框架示例 | @AiService 5项、Pinecone 7项、Flux 12项 | 独立示例不代替主项目回归 |
| Vue | 41项SSE解析及组件交互测试、报告门禁、npm ci和生产构建 | 此41项仍使用jsdom/API替身；另有浏览器验收记录，见BROWSER_ACCEPTANCE.md；依赖审计已清零 |
| 主项目流式 | ChatControllerStreamTest，27项；真实控制器、MVC SSE序列化、终态与并发占用 | 模型/持久化为替身，Servlet回调模拟断线/超时；非真实浏览器TCP及云模型端到端 |

旧AppointmentFlowCheck/AppointmentSessionFlowCheck是main入口手动程序，不由Surefire自动发现；不能直接作为当前账号系统下的CI门禁。

## 本机命令

只运行不依赖数据库的71项检查（其中1项使用本机HTTP服务）：

```powershell
cd F:\xiaozhi-learning\backend
$env:JAVA_HOME = 'F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1'
& 'F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd' test
```

默认禁用账号和MySQL集成测试。报告中的跳过不代表它们通过。

完整88项使用临时MySQL脚本；Mongo必须已经运行在127.0.0.1:27017：

```powershell
cd F:\xiaozhi-learning
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-mysql.ps1 -JavaHome "F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1" -MavenCommand "F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd"
```

预期88项、0失败、0错误、0跳过、BUILD SUCCESS以及MYSQL_TEST_RUNNER_EXIT=0。脚本不启动业务后端，不需要云模型Key。隔离方式、测试边界和故障排查见[MYSQL_TESTING.md](MYSQL_TESTING.md)。

## CI运行方式

`.github/workflows/ci.yml`的Project checks在推送main、面向main的PR或手动触发时运行：

- 后端：Java17、Mongo8.0和MySQL8.4临时服务；MySQL映射13307，执行两个集成开关均开启的mvn verify。
- 报告门禁：检查账号至少1项、MySQL至少16项、主项目流式至少27项、安全配置至少2项、网络配置至少3项、错误摘要至少5项、SDK空闲流超时至少1项，失败、错误、跳过均为0，防止未执行被误认成功。
- 三个独立示例：分别mvn verify。
- Vue：Node24、npm ci、npm run test:ci、npm audit --audit-level=low、npm run build；四组测试至少17/8/10/6项，全通过且不得跳过；审计涵盖开发依赖，JSON随前端报告上传。
- 共5个任务，Java四份报告加前端frontend-test-reports共5份，使用upload-artifact@v6保留7天。无真实云端密钥、业务数据库密码或部署步骤；仓库权限只读。

推送后打开Actions → Project checks → 本次提交，检查5个任务及Artifacts。只有本次云端执行全部绿色才表示本次CI通过。本机结果不替代云端结果。分支保护及必需检查尚未配置。

## 后续

第11项第三阶段主项目流式与完整后端已完成本机及云端验收，见[STREAM_TESTING.md](STREAM_TESTING.md)。第四阶段前端41项本机及云端已验收，运行步骤与边界见[FRONTEND_TESTING.md](FRONTEND_TESTING.md)。依赖审计13项已修复为0，回归通过，CI审计门禁已通过提交cbe9c7c的云端验收，详见[DEPENDENCY_AUDIT.md](DEPENDENCY_AUDIT.md)。浏览器账号、预约、历史及停止接收已分步骤复核，详见[BROWSER_ACCEPTANCE.md](BROWSER_ACCEPTANCE.md)。安全配置修复提交bf3cee0已截图验收：5任务、5报告、1分16秒。本轮新增网络超时配置、安全错误摘要及9项回归，完整88项本机通过，真实云模型部分输出后停止/收尾/刷新通过，详见[MODEL_DIAGNOSTICS.md](MODEL_DIAGNOSTICS.md)。历史ApiException根因仍未确定；本轮待提交及云端验收。物理断网、生产代理、HTTPS和多节点压力不在已验收范围，第12项打包部署尚未开始。


## 2026-10-07 发布包检查补充

提交f2a6980已由截图验收5任务、5报告、1分30秒。本轮前端任务增加release-ui JAR资源断言和Windows脚本语法解析，仍5任务及5份测试报告，待新提交云端验收。Windows发布构建重新执行Vue41项与后端71项（另17项数据库测试明确跳过），三个示例5/7/12项通过；不把跳过与上一阶段88项完整执行混为一谈。实际发布JAR由Edge完成10项检查，见PROJECT_REVIEW.md与RELEASE.md。


## 2026-10-07 双库恢复演练补充

发布包提交75174a6已截图验收5任务、5报告、1分34秒。本轮增加scripts/test-backup-restore.ps1的PowerShell解析和backup-restore-probe.mjs的Node语法检查，actionlint通过；任务和报告仍各5项，对应新提交尚待云端验收。

双库恢复专项在Windows隔离实例执行：MySQL9表28行、Mongo5集合9文档的内容及结构/索引一致，恢复后的6组真实HTTP业务断言通过，清理成功。具体步骤、失败修复与边界见BACKUP_DRILL.md。不是新增6项JUnit测试，后端88项、前端41项与示例5/7/12项统计不变；本轮构建使用-DskipTests，没有冒充重跑完整套件。CI不下载Mongo工具或执行该Windows恢复演练。


## 2026-10-07：检索评测器离线门禁

- 新增RetrievalEvalTest共10项，默认随backend的mvn verify运行；CI检查报告存在且10项以上、0失败/错误/跳过。
- 10项测试验证24题数据集原文、PDF解析依据和评分计算，不访问云模型/Pinecone/业务数据库，不产生真实AI质量分数。
- scripts/test-ai-retrieval.ps1加入Windows脚本解析检查；真实云端采集只在显式-Live运行，不进入CI。
- 预期完整后端由历史88项增加至98项；本轮本机只运行新增10项并通过，尚未宣称98项完整套件已验收。前端41项和5个CI任务结构保持不变。
- actionlint与PowerShell解析本机通过，云端待提交推送后验收。
- 真实24题检索运行及指标边界见AI_EVALS.md；原始基线位于evals/baselines/vector-v1-20261006。


## 2026-10-07：浏览器与OTLP

本机后端122项、前端49项、Chromium浏览器7项通过。CI新增OtlpExportTest四项门禁和Playwright检查；使用独立HTTP测试服务，不调用模型和业务数据库。浏览器报告、失败trace及截图并入frontend-test-reports。部署边界见UI_ACCEPTANCE.md。


## 2026-10-08：多轮 Agent 检查

后端门禁增加AgentMultiTurnContractTest八项与AgentMultiTurnGraderTest八项，完整后端138项。本机失败0、错误0、跳过0。CI同时上传target/agent-multiturn/contracts中的逐步数据库状态；真实Qwen评测显式运行，不进入无密钥CI。固定场景、运行命令和失败基线见AGENT_MULTITURN_EVALS.md。

### 2026-10-08 预约工具查询凭据

后端完整测试更新为 155 项：新增 AppointmentToolFailureTest 10 项、AppointmentQueryContextTest 7 项，并加入 CI 报告检查。真实模型六组复测为 5 组通过、1 组失败，报告独立保存，不混入离线测试成功率。浏览器测试保持 8 项。

### 2026-10-08 按阶段开放工具

新增 StagedAppointmentModelsTest 19 项并加入 CI 报告检查，完整后端共 174 项。同步与流式入口均验证阶段过滤和执行前拦截；真实同步模型固定六组、11 轮本次全部通过。失败首轮与成功复测报告均保留，真实流式和回答语义不包含在此次自动通过结论中。

### 2026-10-08 回答与流式评测回归

增加 AgentAnswerChecksTest 11项、AgentEvalResponseTest 6项，检查已知回答问题、流式片段采集、工具结果保存、异常与超时。完整后端191项通过，CI要求两组报告存在且零失败、零错误、零跳过。CI不访问付费模型；真实同步复测因Arrearage停止，真实流式待账户恢复，详见AGENT_MULTITURN_EVALS.md。

### 2026-10-08 真实评测与CI边界更新

AgentAnswerChecksTest新增中文内部场次编号检查，最低数量改为12，完整后端192项通过。真实模型评测已有同步和流式失败报告；它们不被CI自动测试替代，也不因CI绿色而改记成功。详情见AGENT_MULTITURN_EVALS.md“账户恢复后的实测与撤回”。

## 2026-10-09：Python 评测服务与演示产物

当前工作流共8个任务：后端、四个框架示例、前端，以及Linux与Windows的Python任务。Java主项目280项、前端50项、浏览器8项、框架示例5/7/12/9项；Python16项单独统计。Python报告门禁要求至少16项且零失败、错误和跳过，不把提醒信息屏蔽。

Python任务安装固定依赖，检查任务队列、幂等、失败及恢复，再离线重算已有模型记录、运行真实本机HTTP脚本。两个系统各上传测试与指标报告，不调用云模型或业务数据库。前端任务额外录制明确标注受控HTTP范围的演示，视频和清单位于frontend-test-reports/demo。

发布要求同一提交main CI成功，重新构建前端和JAR，核对包内容，并验证可选Python模块及原始记录可独立运行；界面视频作为独立Release附件。源码、原始测量、CI结果和发布包分别复核，记录的云模型结果不由普通CI重新采样。


## 2026-10-09 中文意图实验

新增Linux、Windows两个独立Python任务，当前共10个CI任务。ml-intent固定依赖与180条合成数据从零训练；18项检查核对分组隔离、训练词表、验证集选择、回退、模型重载和原始存档指标重算。实验结果没有最低准确率门槛，质量不达标如实留档，不以训练成功冒充业务改善。

每系统上传pytest、完整JSON指标、逐条预测、Markdown和独立HTML报告，不上传模型文件。当前主路由保持原实现；本次CI不调用云模型或读写业务数据库。

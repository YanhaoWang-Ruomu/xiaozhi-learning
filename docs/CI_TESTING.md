# 第11项：自动化测试与CI

## 当前结论

2026-10-06，Windows后端完整验证：79项，0失败、0错误、0跳过，`mvn verify`和打包成功。包括33项原有离线测试、27项主项目流式测试、2项离线/网站安全配置测试、1项真实Mongo账号隔离测试、16项真实MySQL预约测试。

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

只运行不依赖数据库的62项检查：

```powershell
cd F:\xiaozhi-learning\backend
$env:JAVA_HOME = 'F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1'
& 'F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd' test
```

默认禁用账号和MySQL集成测试。报告中的跳过不代表它们通过。

完整79项使用临时MySQL脚本；Mongo必须已经运行在127.0.0.1:27017：

```powershell
cd F:\xiaozhi-learning
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-mysql.ps1 -JavaHome "F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1" -MavenCommand "F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd"
```

预期79项、0失败、0错误、0跳过、BUILD SUCCESS以及MYSQL_TEST_RUNNER_EXIT=0。脚本不启动业务后端，不需要云模型Key。隔离方式、测试边界和故障排查见[MYSQL_TESTING.md](MYSQL_TESTING.md)。

## CI运行方式

`.github/workflows/ci.yml`的Project checks在推送main、面向main的PR或手动触发时运行：

- 后端：Java17、Mongo8.0和MySQL8.4临时服务；MySQL映射13307，执行两个集成开关均开启的mvn verify。
- 报告门禁：检查账号至少1项、MySQL至少16项、主项目流式至少27项、安全配置至少2项，失败、错误、跳过均为0，防止未执行被误认成功。
- 三个独立示例：分别mvn verify。
- Vue：Node24、npm ci、npm run test:ci、npm audit --audit-level=low、npm run build；四组测试至少17/8/10/6项，全通过且不得跳过；审计涵盖开发依赖，JSON随前端报告上传。
- 共5个任务，Java四份报告加前端frontend-test-reports共5份，使用upload-artifact@v6保留7天。无真实云端密钥、业务数据库密码或部署步骤；仓库权限只读。

推送后打开Actions → Project checks → 本次提交，检查5个任务及Artifacts。只有本次云端执行全部绿色才表示本次CI通过。本机结果不替代云端结果。分支保护及必需检查尚未配置。

## 后续

第11项第三阶段主项目流式与完整后端已完成本机及云端验收，见[STREAM_TESTING.md](STREAM_TESTING.md)。第四阶段前端41项本机及云端已验收，运行步骤与边界见[FRONTEND_TESTING.md](FRONTEND_TESTING.md)。依赖审计13项已修复为0，回归通过，CI审计门禁已通过提交cbe9c7c的云端验收，详见[DEPENDENCY_AUDIT.md](DEPENDENCY_AUDIT.md)。浏览器账号、预约、历史及停止接收已分步骤复核，详见[BROWSER_ACCEPTANCE.md](BROWSER_ACCEPTANCE.md)。本轮新增安全配置修复及79项后端结果待对应提交的云端验收；模型耗时/ApiException波动及真实部分输出后的停止仍待补充。第11项尚未整体完成，第12项打包部署尚未开始。

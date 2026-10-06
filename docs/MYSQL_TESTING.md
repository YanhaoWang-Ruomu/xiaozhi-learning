# 真实 MySQL 预约测试

2026-10-06 Windows 实测：新增16项预约数据库测试通过；连同33项原有离线测试、27项主项目流式测试和1项账号隔离测试，当前后端77项全部通过，0失败、0错误、0跳过，`mvn verify` 和打包成功。

## 本机运行

先确保 MongoDB 在 `127.0.0.1:27017` 运行。无需启动业务后端或网页，无需模型 API Key、业务 MySQL 密码。MySQL 8.4 安装文件需要存在，业务 MySQL84 服务可以保持原状态。

在 PowerShell 粘贴：

```powershell
cd F:\xiaozhi-learning
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-mysql.ps1 -JavaHome "F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1" -MavenCommand "F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd"
```

脚本默认使用 `C:\Program Files\MySQL\MySQL Server 8.4`，其他安装位置通过 `-MySqlHome` 指定。它创建临时实例，只监听本机13307；如果端口已被占用，直接报错，不停止或复用未知实例。

预期看到：

```text
Tests run: 77, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
TEST_LOGS=...
MYSQL_TEST_RUNNER_EXIT=0
```

脚本在项目旁边的 `xiaozhi-learning-backups` 下生成唯一工作目录，避免 Windows 中文用户名临时路径影响 MySQL。可通过 `-TestWorkRoot "F:\xiaozhi-learning-backups"` 指定其他英文路径。测试完成后关闭临时实例，删除本次临时数据目录，保留日志。ExecutionPolicy 参数只作用于本次 PowerShell 进程。

## 隔离与真实组件

- 固定连接 `127.0.0.1:13307`，不读取业务数据库 URL 或密码，不连接3306。临时实例使用仅用于本机测试的空密码 root。
- 每次创建随机 `xiaozhi_booking_test_<UUID>` MySQL 数据库和同名 MongoDB 数据库，只清理本次创建的库；账号测试另用自己的随机库。
- 执行仓库 `sql/002` 至 `sql/007` 的实际建表脚本，仅移除已知的业务库 `USE` 语句。Maven 将这些文件复制到测试资源，不放入业务运行资源。
- 使用真实 MySQL 8.4、MyBatis-Plus Mapper、JDBC、连接池和业务事务；Mongo 草稿也是真实数据库记录。
- 仅替换业务库初始化/旧数据迁移入口 `AppointmentStorageInitializer`，避免测试启动时操作业务库。迁移流程不属于本测试覆盖范围。

## 16项覆盖

| 范围 | 测试内容 |
|---|---|
| 重复与状态 | 重复确认只有一条预约；相同请求键不能切换日期/场次；未明确确认不能取消；重复取消保留同一时间；取消后旧键不恢复预约，新键可以使用释放的容量 |
| 拒绝结果 | 容量拒绝被保存；释放容量后旧键仍保持拒绝；医生信息变化拒绝；未放号日期不留下部分记录 |
| 并发 | 8个并发请求竞争场次容量、跨场次每日容量、同一请求键；8个并发取消只形成一次状态变化 |
| 回滚 | 用 MySQL 触发器模拟快照插入失败，确认预约、请求目标、尝试记录、快照均回滚；故障解除后同一键可重试 |
| Mongo/MySQL衔接 | 已确认草稿对应预约取消后不可再确认；草稿取消幂等；SQL已提交但Mongo未确认时重试找回同一预约；持久化拒绝关闭确认中的草稿 |

固定业务时间和预置 CONFIRMING 草稿用于消除系统日期影响。这不覆盖 PENDING 到 CONFIRMING 的首次原子抢占、真实网络中断、全浏览器预约流程或多节点压力性能。8线程测试证明本次受控竞争下的结果，不等于任意负载保证。

## CI和失败排查

GitHub 后端任务创建 Mongo 8.0 和 MySQL 8.4 临时服务，显式启用两个集成测试开关，并检查报告中账号1项、MySQL16项和主项目流式27项均未跳过。容器密码 `ci-test-only` 仅用于本轮临时服务，不是业务凭据。

MySQL阶段提交975642f的5个云端任务已由截图验收（运行37450526301，4份报告，1分24秒）。加入27项流式测试后，本地完整77项已通过；本次新增门禁的云端结果待推送后确认。

失败时查看 `TEST_LOGS` 指向目录中的 `maven.log`、`initialize.*.log`、`server.stderr.log` 和 `shutdown.*.log`。Mongo未运行、13307已被占用或依赖缺失应报错，不能把跳过当成功。强制关机/终止进程可能留下临时文件或数据库；先核对本次日志中的完整路径和进程，勿按库名前缀批量删除，也勿停止业务 MySQL84。

# 双库备份与恢复演练（第12项第二阶段）

2026-10-07（本机时间）完成隔离合成数据演练。业务MySQL3306和Mongo27017未用于本次导出、恢复或写入；本次成功不代表已备份现有业务数据，也不代表服务器部署完成。

## 工具与运行

- MySQL Server/client/dump 8.4.11，MongoDB Server 8.0.6。
- MongoDB Database Tools 100.19.1，Windows x86_64 ZIP。
- 官方下载：[版本归档](https://www.mongodb.com/try/download/database-tools/releases/archive)、[发布元数据](https://downloads.mongodb.org/tools/db/release.json)。
- ZIP SHA-256：527738A0F9AB2D80EA40CB8FC7A68F8E28664A2FD4E3B63626C2D4629AEB7F37，与官方元数据相同版本、平台和架构的archive.sha256核对一致。
- 本机工具目录：F:\xiaozhi-medical\tools\mongodb-database-tools-100.19.1\mongodb-database-tools-windows-x86_64-100.19.1\bin。没有修改系统PATH。校验依据是官方HTTPS发布哈希，不是Windows签名；.sha256旁文件返回403时改用官方发布元数据。

在新的PowerShell中执行：

~~~powershell
cd F:\xiaozhi-learning
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-backup-restore.ps1
~~~

脚本默认使用本机已有Java17、Maven、Node和数据库工具路径；换机器可用JavaHome、MavenCommand、NodeCommand、MySqlHome、MongoServer、MongoTools、WorkRoot参数指定。依赖下载需要网络，但演练不调用模型或Pinecone，云密钥在子进程内替换为测试占位值。请勿与test-mysql.ps1同时运行，两者都使用13307。

| 用途 | MySQL | MongoDB | 后端HTTP |
|---|---|---|---|
| 临时源实例 | 13307 | 27317 | 18081 |
| 临时恢复实例 | 13308 | 27318 | 18082 |

所有实例只绑定127.0.0.1；任一端口占用即退出，不终止占用者。每次创建GUID目录，MySQL空密码root与Mongo无认证仅限这些短期本机测试实例，不是业务账号配置。脚本不是业务备份命令，不要改成3306/27017运行。

## 实际验证内容

1. 编译当前后端，初始化独立空数据库，执行SQL002—007及迁移标记；通过真实HTTP接口创建两个账号、两个归属会话和三种草稿状态。
2. 确认两笔预约并取消其中一笔，验证净占用一个号源；停止源应用后，通过真实历史/记忆存储类写入固定中文问答和来源，不发模型请求。
3. 记录源快照，mysqldump与mongodump导出到同一目录，记录字节数及SHA-256；导出后源快照必须不变。应用停写保证本次双库配对，不把两次导出误称为跨库原子事务。
4. 新建独立目标实例，检查备份哈希，再导入SQL与Mongo archive；Mongo不使用--drop。目标应用启动前比较恢复快照。
5. 使用恢复后的真实后端HTTP接口完成下面六组断言，再停止临时服务、删除四个临时数据库目录和包含随机测试密码的fixture.json。保留日志、合成数据备份和哈希证据。

恢复快照比较范围：

- MySQL全部9张表、28行：所有列值（保留null）、行数、建表约束/索引和逐列类型、默认值、字符集、排序规则等元数据。
- MongoDB全部5个非系统集合、9份文档：完整Extended JSON数据、集合选项和索引定义；复合索引键顺序保留。
- 行/对象字段顺序规范化以避免无意义差异；MySQL恢复会将部分继承字符集写成显式CHARACTER SET utf8mb4，仅归一化它与COLLATE utf8mb4_0900_bin的这一等价组合，同时逐列元数据仍独立比较。原始SHOW CREATE TABLE保存在*.ddl.json供复核。

六组恢复后业务断言：

| 检查 | 本机结果 |
|---|---|
| 原密码哈希可登录，账号ID不变 | 通过 |
| 会话列表、中文历史、来源和处理状态可读取 | 通过 |
| Mongo草稿与MySQL预约编号/状态、取消时间及余量一致 | 通过 |
| 第二账号不能读取第一账号的会话、草稿或预约 | 通过 |
| 恢复后的待确认草稿可确认，重复确认不重复扣号 | 通过 |
| 恢复后的有效预约可取消，重复取消只释放一个号源 | 通过 |

## 结果与证据

最终成功目录：
F:\xiaozhi-learning-backups\backup-drill-92d3f5ec784b4a51bd37d163df6af71b

- result.json：passed=true、cleanupOK=true。
- source.json、source-after-dump.json、restored.json：规范化快照SHA-256一致。
- backup-manifest.json、mysql.sql、mongo.archive.gz：配对备份与哈希。
- verify-api.out.log：RESTORED_BUSINESS_OK checks=6。
- 总运行日志：F:\xiaozhi-learning-backups\backup-drill-20261007\runner-fourth.log，包含EXACT_RESTORE_SNAPSHOT_OK与BACKUP_RESTORE_DRILL_OK。
- 六个隔离端口已释放，四个数据库目录及fixture.json已删除；原业务数据库进程保持运行。

前几次演练失败保留为排查证据：先修复Map.of输出顺序导致的快照误判及PowerShell原生命令stderr打断清理，再核对显式字符集产生的DDL文本差异。未跳过结构校验，也未将失败运行记为成功。

脚本构建使用-DskipTests，不能据此新增或重算JUnit通过数量。后端完整88项、前端41项、框架示例5/7/12项维持原统计。当前CI仅新增演练脚本语法检查；双库恢复是Windows本机专项演练，不冒充GitHub已执行恢复。对应提交仍需推送后验收CI。

## 边界与下一步

- 本次只含合成演示数据，未导出当前业务账号/病情描述/聊天或预约，未执行覆盖业务库的恢复。
- 未覆盖大数据量、跨版本、用户/角色/授权、存储过程/事件、加密离线副本或定时备份；应用业务库以外的MySQL/Mongo系统数据不在此次范围。
- 没有验证在线持续写入条件的配对备份。正式备份必须先停止所有应用写入，见[BACKUP_RESTORE.md](BACKUP_RESTORE.md)。
- Pinecone与知识资料需单独保留和规划重建；本演练不删除或重建云端索引。
- 下一步明确目标服务器、开放范围和域名，评估HTTPS/代理/SSE、注册访问策略及后端依赖；实际迁移前再安排业务数据成对备份与独立恢复验收。

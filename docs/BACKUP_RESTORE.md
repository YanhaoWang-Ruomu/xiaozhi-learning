# 双数据库备份与恢复

当前MySQL保存预约/排班/迁移记录；MongoDB保存账号、归属、聊天历史/记忆和草稿。必须成对备份，单独恢复一个可能破坏关联。Git和发布ZIP都不包含这些数据。

## 前提与边界

- 先停止所有访问这对数据库的应用实例及排班定时任务，确认没有聊天、预约、同步或其他写入；数据库服务本身保持运行。单机Mongo不是副本集，不能用--oplog假装跨库一致快照。
- 用mysqldump和MongoDB Database Tools的mongodump导出。MySQL工具已在本机安装；本轮路径检查未发现mongodump/mongorestore，需安装官方Database Tools后再执行Mongo步骤。
- 官方说明：https://www.mongodb.com/docs/database-tools/installation/installation-windows/ 和 https://www.mongodb.com/docs/database-tools/mongodump/ 。
- 以下是待演练的操作说明，本轮没有导出或覆盖业务库。工具退出0和文件存在只是导出完成，还需在独立实例恢复、核对数量/关联及登录和预约流程才能标记备份可用。
- 备份包含账号密码哈希与聊天内容，应保存在私有目录及另一份受控存储，不上传GitHub。

## 导出（停写期间）

在新的PowerShell中创建唯一目录：

~~~powershell
$backupDir = "F:\xiaozhi-learning-data\backups\$(Get-Date -Format yyyyMMdd-HHmmss)"
New-Item -ItemType Directory -Path $backupDir
~~~

MySQL：使用有导出权限的账号，-p会交互提示，不把密码放在命令参数里。--result-file避免Windows PowerShell把SQL转成UTF-16。

~~~powershell
& 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqldump.exe' --host=127.0.0.1 --port=3306 --user=xiaozhi_app -p --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 "--result-file=$backupDir\mysql.sql" --databases xiaozhi_learning
if ($LASTEXITCODE -ne 0) { throw 'MySQL export failed; do not mark this backup complete' }
~~~

Mongo：将下面路径换成实际安装的Database Tools目录。命令适用于当前本机未启用认证的连接；不要将带密码的URI粘贴到聊天或提交到仓库。

~~~powershell
$mongoTools = 'C:\Program Files\MongoDB\Tools\100\bin'
& "$mongoTools\mongodump.exe" --uri='mongodb://127.0.0.1:27017' --db=xiaozhi_learning "--archive=$backupDir\mongo.archive.gz" --gzip
if ($LASTEXITCODE -ne 0) { throw 'Mongo export failed; paired backup is incomplete' }
Get-FileHash "$backupDir\mysql.sql","$backupDir\mongo.archive.gz" -Algorithm SHA256 | Format-List
~~~

记录导出时间、应用提交/发布版本、数据库版本、两个文件哈希和是否全程停写；两边成功才恢复应用。Pinecone是外部知识向量库，不在双库备份内。保留对应知识文件、分段版本和namespace；当前普通账号不能重建管理员专用索引，不要删除云端索引。

## 恢复演练（只用新建隔离实例）

先准备独立MySQL和Mongo实例，使用不同端口和全新数据目录，不能指向3306/27017业务服务。检查SQL含USE/CREATE DATABASE xiaozhi_learning，必须依靠隔离实例避免覆盖业务库。以下假设临时端口为13308/27318；确认无其他服务占用后再配置实例。

在mysql客户端连接临时13308端口后使用source读取文件，保持UTF-8：

~~~powershell
& 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe' --host=127.0.0.1 --port=13308 --user=root -p --default-character-set=utf8mb4
~~~

在mysql提示符中执行（换成实际备份路径，用正斜杠）：

~~~sql
source F:/xiaozhi-learning-data/backups/具体时间/mysql.sql;
~~~

Mongo恢复到全新27318实例，不使用--drop覆盖已有数据：

~~~powershell
& "$mongoTools\mongorestore.exe" --uri='mongodb://127.0.0.1:27318' "--archive=$backupDir\mongo.archive.gz" --gzip '--nsInclude=xiaozhi_learning.*' --stopOnError
if ($LASTEXITCODE -ne 0) { throw 'Mongo restore failed' }
~~~

随后将发布包的独立测试配置指向临时端口，核对迁移状态、表/集合数量、账号登录、会话归属、历史、草稿与预约编号关联、取消后的号源。全过程使用自己的演示数据，避免发送不必要的模型请求。演练通过后记录证据；业务恢复需另行安排停机窗口，不能把这里的演练命令直接改端口用于覆盖生产。

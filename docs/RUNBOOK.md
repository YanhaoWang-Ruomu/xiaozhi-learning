# 小智项目：当前启动、关闭与排查

适用：Windows本机、单实例学习演示。开发仓库F:\xiaozhi-learning。发布包见[RELEASE.md](RELEASE.md)。

## 开发模式启动顺序

### 1. 确认数据库

MySQL84通常开机自动运行。查询服务：

~~~powershell
Get-Service MySQL84
~~~

若确实Stopped，用管理员PowerShell运行Start-Service MySQL84。关闭mysql客户端窗口不会停止MySQL服务。连接业务库的命令（密码按提示输入）：

~~~powershell
& 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe' --host=127.0.0.1 --port=3306 --user=xiaozhi_app -p --default-character-set=utf8mb4 xiaozhi_learning
~~~

MongoDB未运行时，在另一个窗口启动原数据目录，不要创建新空目录代替旧库：

~~~powershell
& 'F:\xiaozhi-medical\tools\mongodb\mongodb-win32-x86_64-windows-8.0.6\bin\mongod.exe' --dbpath 'F:\xiaozhi-learning-data\mongodb' --bind_ip 127.0.0.1 --port 27017
~~~

确认监听（只代表端口可连接，不证明数据完整）：

~~~powershell
Test-NetConnection 127.0.0.1 -Port 3306
Test-NetConnection 127.0.0.1 -Port 27017
~~~

### 2. 确认环境变量

后端需要DASHSCOPE_API_KEY、MYSQL_PASSWORD；知识检索需要PINECONE_API_KEY、PINECONE_INDEX_HOST。MYSQL_USERNAME默认xiaozhi_app。不显示内容的检查：

~~~powershell
'DASHSCOPE_API_KEY','PINECONE_API_KEY','PINECONE_INDEX_HOST','MYSQL_PASSWORD' | ForEach-Object {
    [PSCustomObject]@{Name=$_;Configured=-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_,'User'))}
}
~~~

该检查仅看Windows用户变量，不等于IDEA进程配置。修改Windows变量后重开IDEA；仅配在IDEA运行配置中的变量不会自动用于独立发布包。发布启动脚本可交互读取MySQL密码。

### 3. 启动后端与前端

IDEA运行backend/src/main/java/com/ruomu/xiaozhi/XiaozhiApplication.java，确认Tomcat8081、MYSQL_CONNECTION_OK、MYSQL_APPOINTMENT_STORAGE_READY。正常启动不要带--spring.main.web-application-type=none或--xiaozhi.appointment.import-from-mongo=true。

前端在新PowerShell启动：

~~~powershell
cd F:\xiaozhi-learning\xiaozhi-ui
& 'F:\Node.js\npm.cmd' run dev
~~~

打开 http://127.0.0.1:5173/ ，注册/登录后操作。首次安装或package-lock变化时先npm ci。发布包不需要这一步，而是直接打开8081根页面。

## 当前行为

- 服务端MongoDB保存完整历史，模型记忆窗口最多20条；它们不是同一用途。重新登录后通过会话列表找回账号自己的历史。
- 新会话不删除旧数据。退出清空页面并销毁登录会话，但已接受的后台模型调用可能继续。
- 创建草稿不占号；人工最终确认后占用号源；取消已确认演示预约释放号源并保留记录。
- 草稿取消后不可恢复；已确认预约取消后需要另建草稿，不复用旧幂等请求键。
- 预约按上海时区及演示规则开放；以页面实时排班/余量为准，不使用旧截图日期。
- 停止接收不是云端生成、工具或计费取消。中断后同步历史与草稿，避免不明结果下重复提交。
- 所有业务API要求登录，写请求要求CSRF；旧/demo.html和GET /api/chat已禁用。
- Pinecone同步限ADMIN，当前注册入口只创建USER。不要使用旧手册中的匿名同步命令，也不要为了同步关闭权限。

## 关闭

等待聊天和预约结束，核对草稿/历史；保存代码；停止IDEA后端，前端窗口Ctrl+C。发布模式在start.ps1窗口Ctrl+C并等待关闭日志。最后才在手动Mongo窗口Ctrl+C。MySQL服务可以保持运行；不删除数据库目录。

Git只保存已提交文件，双库备份另见[BACKUP_RESTORE.md](BACKUP_RESTORE.md)。

## 常见问题

| 现象 | 检查 |
|---|---|
| 四个奇怪红色文件/标签 | 本轮发现的Git输出已移到项目外备份；关闭旧标签或刷新IDEA项目树，它们不是Java编译错误 |
| 端口占用 | 核对已有IDEA/发布实例，不终止未知PID |
| MySQL连接失败 | MySQL84服务、用户名、MYSQL_PASSWORD、连接地址与TLS配置 |
| Mongo连接失败 | 原数据库目录、27017进程、URI是否一致 |
| 未迁移/表不存在 | 核对现有库和SQL迁移记录，不自动删表、重建或重复导入 |
| 401/403 | 先区分应用登录/CSRF与云服务错误；重新登录获取新CSRF，不关闭安全过滤器 |
| 409/429 | 当前会话或全局并发占用，等待后台终态 |
| 模型慢或ApiException | 记录callId、耗时和安全错误字段，见MODEL_DIAGNOSTICS.md；不粘贴完整凭据 |
| 页面旧/空白 | 开发访问5173；发布访问JAR根页面；确认使用新包并刷新 |
| 登录后历史不同 | 核对账号、数据库和站点来源；旧浏览器密钥不跨端口自动转移 |

更多依据：[CI_TESTING.md](CI_TESTING.md)、[ACCOUNT_AUTH.md](ACCOUNT_AUTH.md)、[BROWSER_ACCEPTANCE.md](BROWSER_ACCEPTANCE.md)。旧ACCEPTANCE.md保留历史阶段记录，不作为当前启动指南。

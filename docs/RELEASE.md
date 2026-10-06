# 本机发布包（第12项第一阶段）

该包将Vue编译页面放进Spring Boot JAR。同源访问页面和/api，不运行Vite，不修改开发模式5173代理。只面向Windows本机单实例；本轮没有向公网发布。

## 从源码打包

需要Java17、Maven、Node24、npm及Git。构建脚本会npm ci、41项前端测试、依赖审计、Vue构建，以及后端release-ui profile的clean verify。默认后端执行71项非数据库测试，17项数据库测试跳过；完整88项由test-mysql.ps1或对应提交CI负责，不能把跳过算通过。

在项目根目录执行：

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-release.ps1 -JavaHome "F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1" -MavenCommand "F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd" -NpmCommand "F:\Node.js\npm.cmd"
~~~

成功打印RELEASE_READY和RELEASE_ZIP。默认输出到F:\xiaozhi-learning-releases，按时间和源提交区分目录，不覆盖旧包。ZIP内含xiaozhi.jar、start.ps1、config、SQL002—007、说明、release.json和manifest.json；没有业务数据库、密钥、node_modules或Git记录。源码存在未提交修改时release.json明确记录workingTreeDirty=true；提交验收后可重新构建标识清晰的包。

## 本机启动

1. 解压ZIP到新的独立目录，保留ZIP及其.sha256文件。
2. 保持原MySQL和MongoDB运行，确认使用原来的xiaozhi_learning数据库。不要重新建空库或重复迁移。
3. 核对config/application.properties中的地址及MySQL用户名，密码使用MYSQL_PASSWORD环境变量。DASHSCOPE_API_KEY、PINECONE_API_KEY、PINECONE_INDEX_HOST需已配置；脚本优先读取当前进程，没有时读取Windows用户变量。
4. 在解压目录打开PowerShell，运行：

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1 -JavaHome "F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1"
~~~

若MYSQL_PASSWORD未设置，脚本以隐藏输入询问MySQL应用账号密码，只传给当前启动进程，不写入文件。不要在命令中直接写密码。IDEA专用环境变量不会自动成为Windows用户变量。

看到Tomcat与MYSQL_CONNECTION_OK成功日志后，打开 http://127.0.0.1:8081/ 。脚本固定本机绑定及HTTP Cookie模式，禁止自动迁移，不适用于公网或HTTPS部署。8081已占用时先核对并停止IDEA中自己的后端，或传-Port 18081；不会终止其他进程。

若之前在5173使用页面，账号和服务端历史仍在原数据库；浏览器本地存储按来源隔离，未归属的旧浏览器会话密钥不会自动从5173搬到8081。不要清空旧站点存储；旧会话导入在原站点处理。

## 停止与日志

先等待聊天和预约结束，核对不明确的草稿，再在启动窗口按Ctrl+C，等待关闭日志。配置graceful shutdown和30秒关闭阶段等待；不承诺强制关机、任务管理器终止或云模型调用会被优雅取消。不要在预约写入期间直接关机。

停止应用不会停止数据库。日志在解压目录logs/xiaozhi.log，单文件10MB，最多7天、总上限100MB；日志可能包含数据库主机和业务标识，不应公开上传整份日志。

## 校验与回退

下载/复制后先核对ZIP SHA256：

~~~powershell
Get-FileHash .\xiaozhi-具体版本.zip -Algorithm SHA256
Get-Content .\xiaozhi-具体版本.zip.sha256
~~~

manifest.json列出包内原始文件哈希；本机修改config后该文件与原始哈希不同是预期，但JAR和脚本不应变化。回退前停止新应用，使用旧发布目录及对应配置启动；本阶段无新增SQL迁移。若未来发布改变数据库结构，不能仅换旧JAR，需要经过备份/恢复兼容性核对。

## 新机器与剩余工作

包内SQL供明确初始化使用，启动脚本不会自动执行。新库须建用户、建xiaozhi_learning库、按002—007顺序建表，并在隔离核对后执行一次非Web旧Mongo预约导入（空源库也需要完成迁移标记）。不要对现有业务库照抄初始化命令。

备份和恢复流程见BACKUP_RESTORE.md。MongoDB Database Tools已准备，双库隔离合成数据恢复及六组业务断言已通过，见BACKUP_DRILL.md；尚未导出业务数据。第一阶段提交75174a6已验收5个CI任务及5份报告成功。下一阶段明确目标服务器、HTTPS/代理/SSE、账号开放范围及依赖安全评估，迁移前安排业务备份；本地启动和隔离恢复成功不代表公网部署完成。

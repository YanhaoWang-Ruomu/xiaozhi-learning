# 本机发布包

发布包将 Vue 编译页面放进 Spring Boot JAR，同源访问页面和 /api，无需运行 Vite。当前范围是 Windows 本机单实例；GitHub Release 不是公网网站。

## 选择正确入口与版本

- 开发模式：启动 IDEA 后端与 Vue，打开 http://127.0.0.1:5173/。5173 将 /api 转发到8081。
- 发布模式：运行解压包中的 start.ps1，打开 http://127.0.0.1:8081/。
- IDEA 的 backend/target/classes/static 可能遗留旧前端，重启 Java 不会重新构建 Vue。以发布包内的前端为准。
- main、版本标签和运行中的进程不是同一个概念。已下载的 ZIP 不会随 Git 推送更新。v0.3.0 将新版工作台和六个 Agent 方向的实现一起打包，能力边界见 AGENT_DIRECTIONS.md。

## 从源码打包

需要 Java17、Maven、Node24、npm、Git及Python3.14。在项目根目录执行：

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-release.ps1 -JavaHome "F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1" -MavenCommand "F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd" -NpmCommand "F:\Node.js\npm.cmd"
~~~

脚本执行 npm ci、前端测试、依赖审计、Vue构建和后端 release-ui profile 的 clean verify。数据库测试默认可能跳过；完整数据库验收由 test-mysql.ps1 或相同提交的 CI 负责，跳过不能算通过。测试数量以该次报告为准。

成功打印 RELEASE_READY 和 RELEASE_ZIP，默认输出到 F:\xiaozhi-learning-releases，按时间和提交区分目录，不覆盖旧包。本机脚本生成 release.json 和 manifest.json，记录源码提交、工作区是否存在未提交修改及文件校验值。

GitHub标签发布则生成 COMMIT.txt 与 SHA256SUMS；要求相同提交的 main CI 已通过，再构建前端和JAR。发布检查逐字节比较JAR内页面与本次dist，拒绝遗留assets，并确认新增后端类、启动脚本与 config/application.properties。ZIP不包含业务数据库或密钥。v0.3.2还收录可选Python服务、意图实验源码/合成数据/报告、相关操作脚本与所需原始评测。独立框架示例及其他历史评测请查看相应源码版本。

本机与GitHub共用stage-release-extras.py，仅复制已跟踪的模块资料，并通过EXTRAS.json逐文件核对来源与字节。GitHub在待发布目录重跑Python16项、真实HTTP及意图18项，训练报告留在evals/ml-intent/release-validation；运行缓存和模型不进入包。

## 本机启动

1. 将 ZIP 解压到新的独立目录，保留 ZIP 及其 .sha256 文件。
2. 保持原 MySQL 和 MongoDB 运行，使用原来的 xiaozhi_learning 数据库。不要重新建空库或重复迁移。
3. 核对 config/application.properties 中的地址和 MySQL 用户名。密码由 MYSQL_PASSWORD 环境变量提供；同时需要 DASHSCOPE_API_KEY、PINECONE_API_KEY、PINECONE_INDEX_HOST。
4. 停止占用8081的 IDEA 后端，待已有聊天与预约操作结束，再在解压目录运行：

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1 -JavaHome "F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1"
~~~

脚本优先读取当前进程环境变量，再读取Windows用户变量。若缺少MYSQL_PASSWORD，会隐藏输入询问应用账号密码，只传给本次进程。IDEA运行配置里的变量不会自动成为Windows用户变量。

看到Tomcat与MYSQL_CONNECTION_OK成功日志后，打开 http://127.0.0.1:8081/，必要时Ctrl+F5刷新。脚本固定本机绑定及HTTP Cookie模式，并禁止自动导入，不适用于公网部署。端口占用时会报错，不会终止其他进程；可用-Port指定另一端口，但日常使用建议只运行一个业务后端。

账号和服务端历史在原数据库；浏览器本地存储按来源隔离，5173里的旧会话密钥不会自动搬到8081。不要清空旧站点存储，旧会话导入在原站点处理。

## 停止、校验与回退

等待操作结束，在启动窗口按Ctrl+C，等待关闭日志。优雅关闭最多等待30秒，不保证强制终止或云模型调用一定被取消。停止应用不会停止数据库。

日志在解压目录logs/xiaozhi.log，单文件10MB，最多7天，总上限100MB。日志可能包含业务标识，不应直接公开整份日志。

~~~powershell
Get-FileHash .\xiaozhi-v0.3.0.zip -Algorithm SHA256
Get-Content .\xiaozhi-v0.3.0.zip.sha256
~~~

修改外部配置后，其文件哈希与原始包不同是预期；JAR和启动脚本不应变化。回退前停止新应用，再启动保留的旧发布目录。v0.3.0无新增SQL结构迁移；未来若结构改变，必须先核对备份和回退兼容性。

## 新机器与部署范围

SQL文件仅供明确初始化时使用，启动脚本不会自动执行。新库需建应用账号、xiaozhi_learning库，按002—007顺序建表，并在隔离核对后完成一次旧Mongo预约导入标记；不要对已有业务库照抄初始化流程。备份、恢复和演练见BACKUP_RESTORE.md与BACKUP_DRILL.md。

公网服务器、HTTPS、代理/SSE和开放范围仍需独立部署验收；本机启动成功不代表这些工作完成。

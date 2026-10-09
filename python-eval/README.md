# Python 评测任务服务

读取小智已有的评测记录，计算指标并保存报告。Java 继续负责聊天和预约；这个独立服务展示 Python 接口、异步进程、SQLite 持久化及任务恢复，不执行云模型或业务库操作。

## 安装与运行

需要 Python 3.14。PowerShell 中从仓库根目录执行：

```powershell
cd F:\xiaozhi-learning
py -3.14 -m venv .venv
& .\.venv\Scripts\python.exe -m pip install -r python-eval/requirements.txt
& .\.venv\Scripts\python.exe -m pip check
$env:EVAL_API_TOKEN = & .\.venv\Scripts\python.exe -c "import secrets; print(secrets.token_urlsafe(32))"
$env:EVAL_RUNTIME = "F:\xiaozhi-eval-runtime"
cd python-eval
$evalServer = Start-Process -FilePath "$PWD\..\.venv\Scripts\python.exe" -WorkingDirectory $PWD -ArgumentList @("-m","uvicorn","xiaozhi_eval.service:create_app","--factory","--host","127.0.0.1","--port","8012","--workers","1") -PassThru
```

新打开的服务窗口保持运行，在该窗口 Ctrl+C 停止；原窗口继续执行下方接口命令。服务使用本机访问令牌；不要把终端中的令牌、SQLite 库或任务日志提交。测试与报告重算不需要启动 MongoDB、MySQL、Java 或 Vue。

若使用发布 ZIP，将根目录替换为解压目录；先创建该目录下的 `.venv`。发布包包含所需的四份原始记录。运行目录放在发布目录之外。

## 提交和查看任务

在原 PowerShell 中执行下面的命令，沿用已生成的令牌。若另开终端，需要自行设置同一个 EVAL_API_TOKEN；不要把令牌发到聊天或提交仓库。浏览器接口说明：http://127.0.0.1:8012/docs 。

```powershell
$headers = @{
    Authorization = "Bearer $env:EVAL_API_TOKEN"
    "Idempotency-Key" = [guid]::NewGuid().ToString()
}
$job = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8012/jobs" -Headers $headers -ContentType "application/json" -Body '{"kind":"project_metrics"}'
Invoke-RestMethod -Uri "http://127.0.0.1:8012/jobs/$($job.id)" -Headers $headers
# 状态 SUCCEEDED 后查看报告
Invoke-RestMethod -Uri "http://127.0.0.1:8012/jobs/$($job.id)/result" -Headers $headers
Invoke-RestMethod -Uri "http://127.0.0.1:8012/jobs?limit=20" -Headers $headers
```

同一个 Idempotency-Key 重复提交会返回同一任务。新的评测使用新的 UUID。API 只接受固定 `project_metrics` 类型，不接收脚本、命令或文件路径。

## 任务执行与恢复

- 接口提交返回 202；SQLite 中先记录 QUEUED，工作进程领取后标记 RUNNING。
- 报告计算由独立 Python 子进程执行，SQLite 操作放在线程执行，HTTP 接口保持可响应。
- 单个运行目录只允许一个工作进程，最多积压16个未完成任务；队列满返回429。
- 成功保存 SUCCEEDED 与 JSON 结果；失败保存 FAILED 与固定错误码。客户端看不到内部路径和异常内容。
- 重启恢复 QUEUED；此前 RUNNING 标记 WORKER_INTERRUPTED，不自动重复执行。120秒执行上限，停止时清理子进程。
- 健康检查公开；任务列表、状态和结果均需要 Bearer 令牌。本地单操作员模式，不承担主项目账号隔离。
- `EVAL_REPO_ROOT` 可指定评测仓库；默认使用此模块所在仓库。数据保存于 `EVAL_RUNTIME`，默认在仓库同级的 `xiaozhi-eval-runtime`。

## 验证与直接生成报告

```powershell
cd F:\xiaozhi-learning\python-eval
& ..\.venv\Scripts\python.exe -m pytest -q
& ..\.venv\Scripts\python.exe -m xiaozhi_eval.metrics --output "F:\xiaozhi-eval-output"
& ..\.venv\Scripts\python.exe smoke.py "F:\xiaozhi-eval-http-check"
```

16项检查覆盖鉴权、参数范围、并发幂等、排队、失败、结果持久化、重启和进程锁，以及报告口径。HTTP脚本会启动和清理自己的临时 Uvicorn，在实际本机 HTTP 下核验任务及重复提交，不启动业务数据库。

依赖用 requirements.txt 固定版本，CI 同时运行 Linux 与 Windows。当前 Starlette 对 httpx 测试客户端有迁移提醒；这不是测试失败，未屏蔽该提醒。机器学习包不在服务依赖中。

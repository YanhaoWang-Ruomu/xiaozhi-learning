# 小智项目：启动、关闭与排查

本文对应当前本地学习演示版本。项目目录为 `F:\xiaozhi-learning`，后端目录为 `backend`。预约数据为本地演示数据，不代表真实医院挂号成功。

## 1. 当前运行环境

| 项目 | 当前配置 |
| --- | --- |
| Java | 17 |
| Spring Boot | 3.2.6 |
| LangChain4j / DashScope 集成 | 1.0.0-beta3 |
| MongoDB | 8.0.6，`127.0.0.1:27017` |
| MongoDB 数据库 | `xiaozhi_learning` |
| MongoDB 数据目录 | `F:\xiaozhi-learning-data\mongodb` |
| 后端端口 | `8081` |
| 聊天模型 | `qwen-plus`，输出上限 512 tokens |
| 向量模型 | `text-embedding-v3`，1024 维 |
| Pinecone 索引 | `xiaozhi-knowledge`，1024 维，cosine |
| 演示网页 | <http://localhost:8081/demo.html> |

网页由 Spring Boot 提供，不需要另外启动 npm 前端服务。

## 2. 每次继续开发时怎么启动

### 第一步：启动 MongoDB

如果 MongoDB 已运行，保留原来的窗口，不要重复启动。

如果尚未运行，在 PowerShell 中执行：

```powershell
& "F:\xiaozhi-medical\tools\mongodb\mongodb-win32-x86_64-windows-8.0.6\bin\mongod.exe" --dbpath "F:\xiaozhi-learning-data\mongodb" --bind_ip 127.0.0.1 --port 27017
```

保持这个窗口打开。日志应显示服务正在监听并等待连接。

需要确认端口时，在另一个 PowerShell 窗口执行：

```powershell
Test-NetConnection 127.0.0.1 -Port 27017
```

`TcpTestSucceeded: True` 只说明端口可连接，数据库读写还需要由应用验证。如果提示数据目录不存在，先核对原有数据目录，避免误用一个新空目录而看不到旧记录。

### 第二步：确认环境变量

当前需要以下三个变量，已配置完成后不必每天重新设置：

- `DASHSCOPE_API_KEY`
- `PINECONE_API_KEY`
- `PINECONE_INDEX_HOST`：Pinecone 控制台中该索引的 Host。

可以在 PowerShell 中检查 Windows 用户级变量是否存在，命令只显示是否已设置，不输出变量内容：

```powershell
'DASHSCOPE_API_KEY', 'PINECONE_API_KEY', 'PINECONE_INDEX_HOST' | ForEach-Object {
    $configuredValue = [Environment]::GetEnvironmentVariable($_, 'User')
    [PSCustomObject]@{
        Name = $_
        Configured = -not [string]::IsNullOrWhiteSpace($configuredValue)
    }
}
```

以上命令不检查 IDEA 运行配置中的专用变量或系统级变量。应用读取的是启动进程实际获得的环境。如果修改了 Windows 环境变量，应完全退出并重新打开 IDEA，再启动后端。

### 第三步：在 IDEA 启动后端

1. 打开 `F:\xiaozhi-learning`。
2. 找到 `backend/src/main/java/com/ruomu/xiaozhi/XiaozhiApplication.java`。
3. 运行 `XiaozhiApplication` 的 `main` 方法。
4. 等待日志出现 `Tomcat started on port 8081` 和 `Started XiaozhiApplication`。

启动成功说明后端进程已启动，不代表 MongoDB、模型和知识库的全部功能都已验证。

### 第四步：检查知识库

浏览器打开：

<http://localhost:8081/api/knowledge/pinecone/status>

检查返回信息：

- `status` 为 `CONNECTED`。
- 索引维度为 `1024`。
- `knowledge.documentsPresent` 为 `true`。
- `knowledge.storedChunks` 与 `knowledge.expectedChunks` 一致。

当前演示资料预期是 3 个片段。以后修改资料或分段规则后，以接口返回的 `expectedChunks` 为准。

**资料未变化、记录已存在时，不需要每天重新同步。** 搜索仍然会调用向量模型，把本次问题转换成向量。

只有首次导入、资料版本变化或当前版本缺少记录时，才在 PowerShell 执行同步：

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/knowledge/pinecone/sync'
```

返回 `SYNCED` 表示同步检查已完成；返回 `INDEXING` 时稍后重新访问状态接口并尝试查询，不要连续点击同步。Pinecone 写入后，查询可见性可能稍有延迟。

相同版本再次同步应复用已有记录。资料版本变化会使用新的 namespace，旧 namespace 暂不自动删除，因此索引总记录数不一定等于当前资料片段数。

### 第五步：打开演示网页

访问：

<http://localhost:8081/demo.html>

更新过 `demo.html` 时，先确认后端使用了新资源，再按 `Ctrl + F5` 刷新。发送普通问候检查模型调用，发送资料相关问题检查 RAG；具体步骤见 [演示验收清单](ACCEPTANCE.md)。

## 3. 会话与预约操作说明

- 当前标签页使用 `sessionStorage` 保存最近最多 100 条页面消息、资料来源及草稿关联信息；普通刷新可恢复页面状态。关闭标签页或浏览器后，不保证保留这些页面记录。
- MongoDB 中的模型会话记忆是最多 20 条消息的窗口，不是完整聊天档案。当前没有服务端完整历史浏览页面。
- “新会话”会生成新的会话编号，不会删除 MongoDB 中已有的数据。会话编号不是登录凭证。
- “同步当前会话草稿”用于查询和找回草稿，不会替用户提交新的预约请求。提示“找回 0 份”可能只是没有发现尚未显示的新草稿。
- 聊天中断后，后台模型或工具仍可能继续执行。先同步草稿核实结果，不要直接重复发送预约请求。

| 草稿状态 | 含义与可执行操作 |
| --- | --- |
| `PENDING_CONFIRMATION` | 待确认，可确认或取消草稿 |
| `CONFIRMING` | 确认处理中；先查询状态，必要时重试确认同一草稿，不能取消 |
| `CONFIRMED` | 已创建本地演示预约，可查看预约编号；当前不能取消已创建的预约 |
| `CANCELLED` | 草稿已取消，记录保留，不能恢复或再次确认；需要时另建草稿 |

当前支持演示医院 `DEMO001`、`内科`，预约日期按 `Asia/Shanghai` 计算，允许明天至今天之后第 3 天，包含两端。使用页面“填入演示预约请求”生成日期，然后核对再发送。不要把旧截图中的日期当作今天的有效日期。

聊天中的旧回复记录当时的情况；草稿后来取消或确认后，以业务面板查询到的当前状态为准。

## 4. 如何正常关闭

1. 等待正在进行的聊天和预约操作结束；若结果不明确，先查询草稿状态。
2. 保存 IDEA 中修改的文件。需要提交时，检查差异并只暂存本次文件。
3. 点击 IDEA 的停止按钮，停止 Spring Boot 后端。
4. 在手动启动 MongoDB 的 PowerShell 窗口按 `Ctrl + C`，等待正常关闭日志后再关窗口。
5. 关闭其他软件。不要删除 MongoDB 数据目录。

Git 推送保存代码和已提交的文档，不会自动备份本地 MongoDB 数据或 Pinecone 数据。再次启动时继续使用原来的数据目录和云端索引。

## 5. 常见问题

| 现象 | 排查方式 |
| --- | --- |
| MongoDB 连接失败 | 确认 MongoDB 窗口仍在运行、端口为 27017，且应用连接配置一致 |
| 8081 已被占用 | 检查是否已有后端实例；在 IDEA 停止重复实例，不要随意结束未知进程 |
| 提示缺少 API Key | 检查变量名称和 IDEA 实际运行环境；修改用户变量后完全重启 IDEA |
| 云端返回 401 或 403 | 根据后端日志确认是模型还是 Pinecone 请求失败，检查相应凭据和权限 |
| 当前知识库未同步 | 检查状态接口，必要时执行一次显式同步 |
| 刚同步后搜索返回 503 | 查看错误正文；若提示索引尚未就绪，稍后查询状态并重试搜索 |
| 聊天返回 409 | 同一会话仍有请求处理中，等待完成；中断后先查询草稿 |
| 聊天返回 429 | 当前并发请求已达上限，等待已有请求结束 |
| 流式回复中断或提示未完整取得 | 查看后端日志，先同步草稿；不要把半段回复当作操作完成证明 |
| 回复被截断 | 当前输出上限为 512 tokens，过长回复可能被标记为未完整完成 |
| 已取消后按钮不能点 | 正常终态，需要预约时另建草稿 |
| 已确认后不能取消 | 当前只支持取消待确认草稿，尚无取消已创建预约功能 |
| 页面还是旧版本 | 确认新静态资源已进入运行中的后端，再使用 `Ctrl + F5` |

## 6. 当前相关接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/demo.html` | 演示页面 |
| GET | `/api/knowledge/chunks` | 查看资料分段 |
| GET | `/api/knowledge/pinecone/status` | 查看云端索引和当前资料状态 |
| POST | `/api/knowledge/pinecone/sync` | 显式同步当前资料向量 |
| GET | `/api/knowledge/search?query=问题` | 检索资料，会调用问题向量模型 |
| POST | `/api/chat` | 普通聊天，JSON 请求与响应 |
| POST | `/api/chat/stream` | 流式聊天，JSON 请求、SSE 响应 |
| GET | `/api/chat?conversationId=编号&message=问题` | 兼容旧版文本聊天，会实际调用模型 |
| POST | `/api/appointments/drafts` | 创建演示草稿 |
| GET | `/api/appointments/drafts?conversationId=编号` | 查询当前会话最近最多 100 份草稿 |
| GET | `/api/appointments/drafts/{draftId}` | 查询指定草稿 |
| POST | `/api/appointments/drafts/{draftId}/confirm` | 确认指定草稿 |
| POST | `/api/appointments/drafts/{draftId}/cancel` | 取消待确认草稿 |
| GET | `/api/appointments/{appointmentId}` | 查询演示预约 |

聊天接口不是无副作用的健康检查：它会调用模型，预约请求还可能触发工具创建草稿。上述业务接口当前没有用户登录与归属权限校验，仅按本地学习演示使用。

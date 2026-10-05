# 已确认演示预约取消

本次在现有预约草稿、确认、MongoDB 持久化和演示页面上增加“取消已确认的演示预约”。取消后保留记录和原编号，重复请求不改写首次取消时间，原草稿不能再次确认恢复预约。仅操作本地演示数据。

## 1. 完整文件放在哪里

项目根目录：`F:\xiaozhi-learning`。先停止 IDEA 中的 `XiaozhiApplication`，再替换以下文件。下载的文件都是完整文件，不需要解压，也不要只粘贴到旧文件末尾。

| 操作 | 项目内路径 |
| --- | --- |
| 替换 | `backend/src/main/java/com/ruomu/xiaozhi/service/AppointmentService.java` |
| 替换 | `backend/src/main/java/com/ruomu/xiaozhi/service/AppointmentDraftService.java` |
| 替换 | `backend/src/main/java/com/ruomu/xiaozhi/service/ChatAssistant.java` |
| 替换 | `backend/src/main/java/com/ruomu/xiaozhi/controller/AppointmentController.java` |
| 替换 | `backend/src/main/java/com/ruomu/xiaozhi/dto/AppointmentResponse.java` |
| 新建 | `backend/src/main/java/com/ruomu/xiaozhi/dto/CancelAppointmentRequest.java` |
| 替换 | `backend/src/main/resources/static/demo.html` |
| 替换 | `backend/src/main/resources/knowledge/appointment-guide.txt` |
| 新建 | `docs/CONFIRMED_APPOINTMENT_CANCELLATION.md`（本文） |

新 DTO 在 IDEA 左侧 `backend → src → main → java → com.ruomu.xiaozhi → dto` 中创建，文件名必须为 `CancelAppointmentRequest.java`。`dto` 是已有包，不是在项目根目录创建文件夹。

本次不需要增加 Maven 依赖。保留已有 `pom.xml`、AI 配置、PDF 解析代码和其他知识文件。`appointment-guide.txt` 已在知识清单中，无需重复添加。

## 2. 状态如何变化

| 情况 | 草稿数据库状态 | 预约数据库状态 | 草稿接口与页面状态 |
| --- | --- | --- | --- |
| 等待用户确认 | `PENDING_CONFIRMATION` | 尚未创建 | `PENDING_CONFIRMATION` |
| 取消待确认草稿 | `CANCELLED` | 尚未创建 | `CANCELLED`，草稿已取消 |
| 已确认创建预约 | `CONFIRMED` | `DEMO_CREATED` | `CONFIRMED` |
| 取消已确认预约 | `CONFIRMED` | `DEMO_CANCELLED` | `APPOINTMENT_CANCELLED`，预约已取消 |

草稿中的 `CONFIRMED` 表示历史上完成过确认，并不表示预约永远有效。页面查询时读取关联预约，推导显示状态。取消只写预约集合中的状态和 `cancelledAt`，避免同时修改两个集合导致状态不同步；不删除草稿或预约。

新接口：

```http
POST /api/appointments/{appointmentId}/cancel
Content-Type: application/json

{"confirmed": true}
```

- 首次取消：200，返回 `DEMO_CANCELLED` 和 `cancelledAt`。
- 重复取消：200，返回已有取消结果，首次取消时间保持不变。
- 请求中的 `confirmed` 缺失或为 false：400，不执行取消。
- 预约不存在：404，不创建新记录。
- 其他不允许取消的预约状态：409。
- 取消后再次确认原草稿：409，不恢复预约。

`cancelledAt` 为 UTC ISO-8601 字符串；尚未取消的旧记录返回 null。取消不是恢复操作，如需再次预约，应明确提出新的预约需求，创建新的草稿。

## 3. 启动和更新知识库

保持 MongoDB 运行，然后在 IDEA 运行 `XiaozhiApplication`。看到 `Started XiaozhiApplication` 后，浏览器打开：

`http://localhost:8081/demo.html`

按 `Ctrl+F5` 强制刷新，选中草稿后应该能看到第三个按钮“取消已确认的演示预约”。只有关联预约仍有效时该按钮才可用。

本次也更新了预约流程知识文件。先打开：

`http://localhost:8081/api/knowledge/chunks`

确认预览中出现“取消已确认的演示预约”。在 PowerShell 执行一次同步：

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/knowledge/pinecone/sync' | ConvertTo-Json -Depth 10
```

再查看：

`http://localhost:8081/api/knowledge/pinecone/status`

核对当前版本的 `expectedChunks` 与 `storedChunks` 一致，并且 `documentsPresent=true`。若尚在索引处理中，稍后再查询。以本次预览和状态为准，不沿用上一步固定的 6 个片段或 PDF 起始片段编号。资料内容变化会产生新版本，首次同步可能上传整个当前知识库。不要清空旧索引。

同步完成后再用新会话验证聊天流程说明。页面取消接口本身不依赖知识库同步，但模型需要使用更新后的资料。

## 4. 页面验证顺序

1. 新建会话，使用页面原有示例按钮填入预约需求；核对为 DEMO001、内科和允许的具体日期后发送。示例日期按上海时区计算，避免使用过期日期。
2. 生成草稿后，核对信息并点击“确认创建演示预约”。确认展示预约编号，记下完整 `DRAFT-...` 和 `DEMO-...` 编号。
3. 点击“取消已确认的演示预约”，先在弹窗里选择取消或关闭。预约应仍为已确认，不应发送取消请求。
4. 再次点击该按钮，核对弹窗中的预约编号，然后确定。页面应显示“预约已取消”，原预约编号仍在，三个业务操作按钮均不可用。
5. 刷新页面并同步当前会话，状态仍为“预约已取消”。
6. 重启后端，再加载同一草稿，确认取消状态仍然保留。
7. 新建另一份草稿，测试原来的“取消当前草稿”：显示“草稿已取消”，不产生预约，也不能再次确认。
8. 新会话询问“如何取消已确认的演示预约？”模型应解释页面操作，不声称已经替用户取消，也不自动另建草稿。

如果出现网络错误，先加载同一草稿或同步查询。页面不会自动重新发送取消 POST。只有查询确认当前仍可取消并经用户重新确认，才重试同一预约。

## 5. 接口核对（页面测试之后）

下面只针对第 4 步中已经取消的演示预约。将两个占位符改成自己的完整编号，保留引号。在 PowerShell 执行：

```powershell
$appointmentId = '这里替换为已取消预约的完整 DEMO-编号'
$draftId = '这里替换为该预约关联的完整 DRAFT-编号'

$before = Invoke-RestMethod -Uri "http://localhost:8081/api/appointments/$appointmentId"
$before | ConvertTo-Json -Depth 10

$again = Invoke-RestMethod -Method Post -Uri "http://localhost:8081/api/appointments/$appointmentId/cancel" -ContentType 'application/json' -Body '{"confirmed":true}'
$again | ConvertTo-Json -Depth 10

$before.cancelledAt -eq $again.cancelledAt
```

两次状态均应为 `DEMO_CANCELLED`，取消时间非空，最后输出 `True`。然后验证原草稿不能恢复预约：

```powershell
try {
    Invoke-RestMethod -Method Post -Uri "http://localhost:8081/api/appointments/drafts/$draftId/confirm"
    Write-Host '结果不符合预期：应返回 409，请保留输出排查。'
} catch {
    if ($null -ne $_.Exception.Response) {
        Write-Host ('HTTP 状态码：' + [int]$_.Exception.Response.StatusCode)
    } else {
        Write-Host $_.Exception.Message
    }
}

Invoke-RestMethod -Uri "http://localhost:8081/api/appointments/drafts/$draftId" | ConvertTo-Json -Depth 10
Invoke-RestMethod -Uri "http://localhost:8081/api/appointments/$appointmentId" | ConvertTo-Json -Depth 10
```

预期确认请求返回 409；草稿接口返回 `APPOINTMENT_CANCELLED`，预约接口仍为 `DEMO_CANCELLED`。连接失败等其他错误不能算验证通过。

## 6. 已做的实现检查与范围

- 使用 Java 17 和对应依赖编译修改的 Java 类；未运行用户完整项目的 Maven 构建。
- 用实际 Mongo 驱动、MongoTemplate 连接 Mongo 协议内存测试服务器，检查重复取消、16 个并发取消、首次取消时间不变、保留记录、不恢复旧预约、旧草稿异常中断后的恢复路径，以及重新创建服务实例后的读取。该测试服务器不是真实 MongoDB。
- 用 jsdom 执行完整页面脚本，检查弹窗拒绝时不发送请求、准确预约编号、取消后刷新、丢失 POST 响应后的 GET 核实、GET 同时失败时禁用操作、刷新恢复、待确认与确认处理中按钮行为。未完成真实浏览器渲染验证。
- 用户本机真实 MongoDB、完整 Spring Boot HTTP 链路、模型和 Pinecone 联调，以前述实际操作结果为准。

取消接口没有注册成模型工具。`confirmed=true` 是请求确认标记，不是身份认证或权限校验。当前仍是本地演示系统；账号登录和预约归属校验属于后续功能，不能将此接口直接当作真实医院业务接口。

## 7. 验证后追加学习日志

将下面内容追加到现有 `docs/learning-log.md`，不要覆盖历史，并按实际结果填写：

```markdown
## 2026-10-05：已确认演示预约取消

- 新增独立的已确认预约取消接口与页面二次确认。
- 区分取消待确认草稿与取消已确认预约，保留原编号和记录。
- 使用条件更新记录首次取消时间，重复取消不重复改写。
- 草稿保留确认历史，展示状态根据关联预约实时推导。
- 已取消预约不能通过再次确认原草稿恢复。
- 更新预约知识资料并同步当前版本。
- 本机验证：填写页面取消、刷新/重启、重复取消时间、原草稿再次确认 409 的实际结果。
- 当前仍未接入真实医院退号、退款、号源和账号归属校验。
```

如果实际验证日期已变化，请修改日志日期。未完成的项目写“待验证”，不要记录为成功。

## 8. 验证成功后提交推送

确认已新增本文，并已填写本机验证结果后执行：

```powershell
Set-Location F:\xiaozhi-learning

git status --short

git add -- backend/src/main/java/com/ruomu/xiaozhi/service/AppointmentService.java backend/src/main/java/com/ruomu/xiaozhi/service/AppointmentDraftService.java backend/src/main/java/com/ruomu/xiaozhi/service/ChatAssistant.java backend/src/main/java/com/ruomu/xiaozhi/controller/AppointmentController.java backend/src/main/java/com/ruomu/xiaozhi/dto/AppointmentResponse.java backend/src/main/java/com/ruomu/xiaozhi/dto/CancelAppointmentRequest.java backend/src/main/resources/static/demo.html backend/src/main/resources/knowledge/appointment-guide.txt docs/CONFIRMED_APPOINTMENT_CANCELLATION.md docs/learning-log.md

git diff --cached --stat
git diff --cached --check

git commit -m "feat: cancel confirmed demo appointments safely"
git push
```

检查暂存列表只包含本次准备提交的文件。不要使用 `git add .` 把已有的 `backup/` 或无关改动一起提交。

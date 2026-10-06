# 账号登录与数据归属（第9项）

状态：后端双账号集成检查、前端构建及交互回归通过；2026-10-06本轮人工验收通过。本阶段面向单实例本地学习演示。

人工证据：账号xiaozhi成功导入2个旧会话；账号xiaoxiao只显示自己的新会话，再次导入为0。预约DEMO-01046eb8-6c05-3707-b22f-2922a4ed1043从已确认变为预约已取消，编号和记录保留。刷新后2026-10-07演示医生甲上午场次剩余5、当天共享剩余20。另一份待确认草稿保持原状态。截图未覆盖的直接越权接口操作依据自动集成测试，不计为人工测试。

## 使用方式

1. 确保 MongoDB、MySQL 正在运行，IDEA 重新加载 backend/pom.xml 的 Maven 依赖并重启 XiaozhiApplication。
2. 打开 http://127.0.0.1:5173/ 并强制刷新。首次使用点击“创建账号”。
3. 用户名使用3到32位英文字母、数字或下划线，不区分大小写。密码至少12个字符，UTF-8编码长度最多72字节。注册后自行输入密码登录。
4. 旧浏览器会话不会自动分给任何账号。登录后，只有仍持有原浏览器密钥时才显示“导入本浏览器旧会话”。明确确认后导入，第二个账号不能再次认领。
5. 退出会清空当前页面的数据视图并销毁服务器登录会话。已被后端接受的聊天请求可能继续完成；重新登录后同步查询，不自动重发。

请固定使用原浏览器和原站点地址进行旧记录导入；localhost 与127.0.0.1的本地存储不同。没有密钥或会话归属链的旧记录仍保留在数据库中，不向新注册账号开放，也不自动删除。

## 后端边界

- Spring Security 使用服务器 HttpSession/JSESSIONID；MongoDB auth_users 保存用户ID、规范化用户名、BCrypt密码哈希及创建时间。
- 所有业务 /api/** 请求要求登录，登录、注册和获取CSRF令牌除外。所有写请求包括登录、注册、退出均校验CSRF令牌。
- 前端先 GET /api/auth/csrf，再通过返回的headerName携带token；登录/退出后重新获取。没有自动重试写操作。
- POST /api/auth/register、POST /api/auth/login、GET /api/auth/me；POST /api/auth/logout由安全过滤器处理。
- 登录更换会话ID，服务端空闲会话30分钟过期；Cookie设置HttpOnly、SameSite=Lax。本地HTTP下secure=false，未来HTTPS部署需设置APP_COOKIE_SECURE=true。
- chat_conversations.userId 是会话归属依据。历史列表、读取和聊天写入均校验账号，不能通过提交别人的conversationId认领会话。
- 草稿沿“draft.conversationId → conversation.userId”校验；预约沿“draft.appointmentId → draft.conversationId → conversation.userId”校验。校验在业务读写之前执行。
- 聊天工具使用AI Services注入的ToolMemoryId定位已验证会话，不依赖异步线程继承登录信息。人工确认和取消流程保持不变。
- 跨账号访问已有记录返回404；未登录返回401，缺少CSRF或不允许的接口返回403。
- 旧 GET /api/chat 和 /demo.html 停用。预约操作统一使用登录后的Vue界面。
- 共享知识库同步 POST /api/knowledge/pinecone/sync 限管理员权限。当前注册账号仅有USER权限，本阶段不提供管理员创建入口，因此普通账号无法执行同步。知识查询仍可在登录后使用。

## 自动检查

AccountIsolationTest 使用真实本机MongoDB随机临时库，在结束时删除该临时库。预约业务服务和模型使用mock，不调用模型、不操作实际MySQL预约或号源。运行前启动MongoDB，在 backend 目录执行：

```powershell
& 'F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd' '-Dtest=AccountIsolationTest' '-Dxiaozhi.auth.integration=true' test
```

检查涵盖：匿名拒绝、缺少CSRF拒绝、登录会话ID轮换、登录前CSRF失效、用户名唯一性、密码哈希、两账号会话读写隔离、草稿和预约读取/确认/取消隔离、被拒绝请求不触发业务操作、异步工具归属、旧数据一次认领、孤立旧记忆禁止认领、退出销毁会话。

默认 mvn test 不会访问本地数据库，此集成测试会跳过；需要显式打开上述系统属性。

前端已进行构建与模拟交互回归：注册登录、CSRF刷新、退出清空、跨标签页切换账号、旧会话不自动导入、会话历史恢复/分页、SSE断线恢复、预约人工确认和取消、重复点击防护、失败不自动重试。

## 人工验收

- 注册账号A并登录，新建会话、聊天，刷新后仍能查看。
- 在原浏览器明确导入旧会话，检查既有会话及关联草稿。
- 退出A，注册并登录B：不能看到A的会话、草稿或预约。
- 再次登录A：原会话仍在。创建一份演示草稿，人工确认再取消，核实记录状态和余量。
- 关闭页面重开仍可使用未过期会话；后端重启后需要重新登录，数据库中的账号与历史保留。

本阶段不包含找回密码、邮箱验证、管理员控制台、分布式登录会话或公网部署验收。部署阶段需统一升级并复核依赖、HTTPS、注册开放范围和网络边界。无需向他人提供密码、Cookie或API Key。

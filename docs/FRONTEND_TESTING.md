# 前端关键交互回归

2026-10-06，第11项第四阶段本机验证：4个测试文件、41项全部通过，0失败、0跳过。
重新安装锁定依赖（npm ci）、测试报告门禁、Vue生产构建和actionlint均通过。提交f40aee7已由截图验收：5个任务全部成功、5份报告、1分5秒。后续依赖修复的云端结果另行验收。

## 如何运行

使用Node 24，在新PowerShell中执行；无需启动MongoDB、MySQL、业务后端或大模型：

```powershell
cd F:\xiaozhi-learning\xiaozhi-ui
& 'F:\Node.js\npm.cmd' ci
& 'F:\Node.js\npm.cmd' run test:ci
& 'F:\Node.js\npm.cmd' run build
```

每条命令成功后再执行下一条。预期41 passed和FRONTEND_REPORT_OK tests=41 suites=4。
日常只运行测试可用npm.cmd test；test:ci额外检查四组场景确实执行且没有跳过。
报告为test-results/frontend.xml和frontend.json，已忽略，不加入Git。

## 覆盖

| 文件 | 数量 | 验证行为 |
|---|---:|---|
| chat-stream.test.js | 17 | 真实SSE解析器；按字节拆分中文/表情、CRLF、多行data、未知事件、来源、done；异常数据、缺done、HTTP失败、主动停止和超时；清理reader且不自动重发 |
| ChatWindow.test.js | 8 | 真实Vue组件及Element Plus输入/按钮；按账号恢复会话、文本转义和来源显示、重复提交、输入法和Shift+Enter、后台处理中轮询、历史失败锁定、取消切换、卸载中止接收 |
| AppointmentPanel.test.js | 10 | 确认/取消草稿/取消预约均先回查详情再人工确认；重复点击一次POST、返回不提交、过时状态拒绝、断线待核实记录跨重挂载保留、显式解除锁定、存储失败不提交、排班过期/满额及取消后按钮消失 |
| App.test.js | 6 | 登录重复提交、用户名规范化、失败清空密码、注册密码不一致拦截、401移除旧聊天、跨标签账号切换、退出确认 |

三种预约动作展开为三个测试；41是场景总数，不是41个独立业务功能。

## 工具和隔离

- Vitest 5.0.3、Vue Test Utils 2.4.6、jsdom 26.1.0。
- 为匹配受支持的测试工具并避开初选旧版本审计问题，构建工具调整为Vite 6.4.3、plugin-vue 5.2.4，package-lock.json一起更新。Node24与现有CI一致。
- 测试配置合并现有Vite配置，沿用Vue编译及@路径别名。
- 组件使用真实模板和DOM事件，在API模块边界使用替身；SSE测试使用真实解析器、TextDecoder、AbortController，在HTTP reader边界提供可控字节。
- 默认fetch被拦截，遗漏模拟会失败，不会连接业务数据库、后端或云模型。
- 模拟时间用于超时/轮询，不实际等待200秒；每项测试清空浏览器存储，卸载组件并恢复时钟。
- 这不是Chromium端到端测试，不覆盖真实TCP断网、代理缓冲、Cookie/CSRF完整链路、浏览器布局、真实输入法设备或云模型取消。原有后端77项仍分别承担服务端验证。
- 顺便修正ChatWindow中“正式登录尚未接入”的过时说明；本轮未修改业务接口、预约规则和数据库。

官方说明：[Vitest](https://vitest.dev/guide/)、[Vue Test Utils异步测试](https://test-utils.vuejs.org/guide/advanced/async-suspense.html)。

## CI与后续

前端任务改名Vue tests and production build：npm ci → npm run test:ci → npm audit → npm run build。
check-report.mjs要求四组分别至少17/8/10/6项且全部passed，缺报告/缺场景/跳过均失败。
工作流仍为5个任务；新增frontend-test-reports后应有5份报告，保留7天。
本机完整日志位于F:\xiaozhi-learning-backups\frontend-phase4-20261006\final-check.log，原文件备份也在该目录。

第四阶段留下的13项依赖告警已在后续修复中清零；重新npm ci后仍0项，41项测试与构建再次通过。
CI新增包含开发依赖的审计检查并上传JSON。依赖修复提交cbe9c7c已完成云端验收，详见[DEPENDENCY_AUDIT.md](DEPENDENCY_AUDIT.md)。
生产构建仍有大chunk提示；浏览器验收与新发现的离线迁移修复见[BROWSER_ACCEPTANCE.md](BROWSER_ACCEPTANCE.md)。账号、预约、刷新历史、等待阶段停止及可控部分流停止已复核。云模型存在耗时和ApiException波动，需继续处理；本轮修复提交后再验收CI，之后推进打包部署。

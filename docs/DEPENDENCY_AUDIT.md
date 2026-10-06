# 前端依赖审计与修复

## 2026-10-06 验证结果

修复前npm audit：13项（3 moderate、9 high、1 critical）。
修复后及npm ci重新安装后：0项，AUDIT_EXIT=0。
41项前端回归全部通过，无失败/跳过；生产构建、UUID v4调用检查、actionlint和git diff --check通过。
本次依赖与审计门禁尚未提交，云端结果待推送后验收。前一提交f40aee7的5个CI任务、5份报告及1分5秒耗时已由用户截图验收。

## 根因和处理

13是npm汇总的受影响依赖项数，不等于13条可利用攻击路径。

| 依赖 | 修复前实际版本 | 处理后 |
|---|---|---|
| axios | 1.8.4 | 当前前端已统一使用fetch，源码无引用，移除闲置依赖 |
| follow-redirects | 1.15.9 | 随axios依赖链移除 |
| form-data | 4.0.2 | 随axios依赖链移除；npm ls确认不再安装 |
| vue / @vue/server-renderer | 3.5.13 | 3.5.43 |
| element-plus | 2.9.8 | 2.14.7 |
| uuid | 10.0.0 | 11.1.1 |
| lodash / lodash-es | 4.17.21 | 4.18.1 |
| postcss | 8.5.3 | 8.5.29 |
| nanoid | 3.3.11 | 3.3.20 |
| rollup | 4.40.0 | 4.64.0 |
| source-map-js | 1.2.1 | 1.2.2 |

- critical来自旧form-data使用不安全随机数选择multipart边界。当前应用没有使用axios调用链；依赖扫描发现受影响包，不证明业务已被利用。
- Vue服务端渲染器告警通过更新Vue家族修复；本项目目前是浏览器端SPA，没有新增服务端渲染。
- uuid公告涉及带缓冲区参数的v3/v5/v6，本项目只调用v4()，没有直接使用该公告的触发路径。仍更新到修复版本11.1.1，保留现有导入与调用，并验证输出为合法v4。
- Element Plus升级后，真实ElInput/ElButton的重复发送、输入法事件和禁用状态由原有组件回归检查。视觉布局和真实输入法设备仍留待浏览器验证。
- 先移除闲置axios，再明确更新三个直接依赖；剩余传递依赖使用不带--force的npm audit fix在允许范围内修复。没有忽略告警或覆盖不兼容依赖约束。
- package.json固定上述三个直接依赖，package-lock.json保留实际完整版本。Vite6.4.3、Vitest5.0.3、plugin-vue5.2.4等测试工具配置不变。

官方依据：
- [form-data安全公告](https://github.com/form-data/form-data/security/advisories/GHSA-fjxv-7rqg-78g4)
- [uuid安全公告](https://github.com/uuidjs/uuid/security/advisories/GHSA-w5hq-g745-h8pq)
- [Vue 3.5.43](https://github.com/vuejs/core/releases/tag/v3.5.43)
- [Element Plus 2.14.7](https://github.com/element-plus/element-plus/releases/tag/2.14.7)

## 重现验证

Node24，无需业务后端、数据库或云服务Key。在PowerShell逐条执行，确认成功后再下一条：

```powershell
cd F:\xiaozhi-learning\xiaozhi-ui
& 'F:\Node.js\npm.cmd' ci
& 'F:\Node.js\npm.cmd' run test:ci
& 'F:\Node.js\npm.cmd' audit --audit-level=low
& 'F:\Node.js\npm.cmd' run build
```

日志和修复前文件：F:\xiaozhi-learning-backups\dependency-audit-20261006。
audit-before.json与audit-after.json为本次审计证据，verification.log为重新安装及回归结果。
没有重新运行后端77项；本轮未修改后端代码，已有云端回归证据保留。

## CI与边界

Vue任务在测试后执行npm audit --json --audit-level=low，包含运行及开发依赖。
报告写入test-results/npm-audit.json，随frontend-test-reports上传；任务仍5个、报告包仍5份。
任何low及以上已知告警会阻止本轮前端任务通过；registry访问失败也应报错，不能作为0告警。
新公告可能让未变更的锁文件将来失败，届时重新核对公告、更新受影响依赖并回归，勿直接降低门槛。

0项只表示当前npm公告数据库和依赖树未报告已知问题，不代表整套应用已完成安全审计。
生产构建仍有大chunk提示，本次JS包约1,064.57 kB（gzip约349.68 kB），后续可优化加载体积。
提交cbe9c7c的Actions运行37456983741已核对成功：5个任务及5份报告，安装、前端测试、审计、生产构建均通过。后续真实浏览器验收另见BROWSER_ACCEPTANCE.md；第12项打包部署尚未开始。

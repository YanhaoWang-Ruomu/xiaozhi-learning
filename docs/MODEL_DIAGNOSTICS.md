# 模型网络超时与错误诊断

日期：2026-10-07（Australia/Sydney）。基线提交 bf3cee0 的 5 个 CI 任务、5 份报告已由用户截图验收（1 分 16 秒）。以下新改动已在本机验证，待提交及云端验收。

## 观察与结论

此前真实模型调用既有约 9 秒、158 秒的成功，也有约 132 秒、269 秒后 ApiException。旧日志只有异常类名，不能确定网络、服务端、SDK 或其他原因。

核对项目实际安装的 DashScope SDK 2.18.5 和 LangChain4j community beta3 源码：SDK 默认连接 120 秒、写入 60 秒、读取 300 秒，而控制器 SSE 等待窗口为 180 秒。SDK 等待可能超过页面连接生命周期，这是已确认的配置缺口；它不证明历史慢请求的根因。

本轮两次独立 qwen-plus 小请求的首事件分别约 2202/694 毫秒，完成约 3432/2021 毫秒。完整主项目一次真实流式调用的模型耗时 4817 毫秒，输入 12797、输出 130 tokens。小请求与主项目上下文不同，不能直接做性能对照；本次没有重现旧 ApiException，不宣称波动已彻底修复。

## 配置

DashScopeNetworkConfig 在 Spring BeanFactoryPostProcessor 阶段设置 SDK 全局 ConnectionConfigurations，早于模型和嵌入客户端初始化。

| application.properties 配置 | 默认秒数 | 允许范围 |
|---|---:|---:|
| xiaozhi.ai.network.connect-timeout-seconds | 10 | 1—30 |
| xiaozhi.ai.network.read-timeout-seconds | 60 | 1—120 |
| xiaozhi.ai.network.write-timeout-seconds | 20 | 1—60 |

读取超时指等待下一次网络读取的空闲时间，**不是整个请求的总时限**。连续片段、多个模型回合及工具调用的总时间仍可能超过 60 秒。缩短等待也可能使较慢的正常请求更早失败，不会让模型本身生成更快。

策略作用于此 JVM 的 DashScope SDK 单例，包括聊天及嵌入请求；修改后需要重启后端。项目显式配置优先，不依赖 SDK 的 DASHSCOPE_* 默认超时变量。可以使用 Spring 命令行参数覆盖上述完整属性名。

启动日志应有：
```text
DASHSCOPE_NETWORK connectSeconds=10 readIdleSeconds=60 writeSeconds=20
```

## 错误日志

TokenUsageListener 保留 callId、mode、model、durationMs、errorType，增加受限字段：
- httpStatus：有效 HTTP 状态或 SDK 的 -1，否则 UNKNOWN。
- serviceCode：已知错误代码白名单；未知为 OTHER/UNKNOWN。
- requestId：仅接受 32 位十六进制或 UUID 格式；其余隐藏为 UNKNOWN。
- causeType：最多沿 16 层不同异常取类型名称，避免循环原因链。

不输出异常 message、响应正文、用户请求或密钥；不把完整异常对象传给日志。

HTTP 200 只说明响应头成功，不能证明流完成。实际 SDK 本机测试中，HTTP 200 后停止输出会产生 response_error，httpStatus 仍为 200。SDK 某些包装路径不保留底层异常，因此不能仅凭 response_error 就认定具体网络故障。后续复现时保留本轮 callId、时间、耗时和上述字段，再与提供商请求记录对照；本轮未新增自动重试。

## 自动验证

新增 9 项测试：
- DashScopeNetworkConfigTest：3 项，默认值、覆盖值、无效范围拒绝。
- ModelErrorSummaryTest：5 项，结构化状态、网络异常、敏感字段隐藏、空值和循环原因链。
- DashScopeIdleTimeoutTest：1 项，实际 SDK 连接本机 HTTP SSE，收到片段后服务端暂停，以 1 秒读取超时验证失败回调；无需云端密钥，不访问外部网络。

完整后端：88 项，0 失败、0 错误、0 跳过，mvn verify/打包成功，MYSQL_TEST_RUNNER_EXIT=0。实际日志目录：
F:\xiaozhi-learning-backups\xiaozhi-mysql-test-72ae27fecd2e4b71ab02fec05e93b296。

CI 加入三组最低 3/5/1 项报告门禁且不得跳过，actionlint 通过。前端代码未修改，沿用上一阶段 41 项及已通过的 CI 证据，本轮未重复运行前端套件。

## 真实浏览器部分输出停止

在独立 MySQL13307、Mongo27317、后端18081、Vue15173 上，通过真实 Edge 注册临时用户并发送一次真实主项目请求：
- 页面约 6796 毫秒显示首段 2 个字符，6829 毫秒点击停止接收。
- 约 9745 毫秒会话恢复可用，后端 processing=false。
- 服务端只有一问一答，两条状态均 complete；刷新后仍为两条，POST 请求计数仍为 1，无 pageerror。
- 此次后台继续完成并保存了完整回复。验证的是客户端停止接收与后端收尾、历史恢复，**不是远端模型、工具执行或计费取消**。

证据：F:\xiaozhi-learning-backups\model-diagnostics-20261007 下的 probe.log、full-tests.log，以及 live/browser.log、live/results.json、live/partial-stop.png。临时进程和数据目录已清理，业务 MySQL3306、Mongo27017 保持运行。

物理断网、生产反向代理缓冲、HTTPS、多节点压力及提供商级取消仍没有相应验收证据。本轮只补足已观察到的超时配置和诊断信息，不把所有网络稳定性问题标记为已解决。

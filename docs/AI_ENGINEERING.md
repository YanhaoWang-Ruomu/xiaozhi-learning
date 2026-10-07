# AI 工程演示

本轮继续使用 LangChain4j AI Services、Qwen、Pinecone、MongoDB 与 MySQL。不是替换整个框架，也不办理真实医疗预约。

## 六项能力

| 能力 | 实现 | 验证与边界 |
|---|---|---|
| Evals | 冻结24题检索对照、抽样生成原始回答、真实模型工具选择与SQL最终状态 | exact-quote依据评分不是医疗准确率；生成回答单独人工复核，失败运行保留 |
| 混合检索与重排 | 中文二元词BM25 + dense候选，RRF融合，特征重排或Qwen相关性重排 | 特征分数不冒充概率；LLM失败明确标记本地回退；默认hybrid，可设置XIAOZHI_RAG_MODE=vector回退，实验页面可对照 |
| 上下文 | 按完整轮次裁剪、64KB UTF-8序列化记忆预算、指代追问展开、数据库结构化需求 | 字节预算是保守代理，不是Qwen精确tokenizer；工具定义另占上下文；保留最新轮和系统提示 |
| OpenTelemetry | 检索、模型各轮、工具、历史保存关联traceId，流式回调显式恢复Context | 默认仅输出无正文的span日志；OTLP可选，未配置Langfuse云项目 |
| MCP | 官方TypeScript SDK，stdio，知识搜索/排班查询 | 使用项目账号与CSRF登录，固定本机服务；没有创建/确认/取消工具 |
| 有状态流程 | 需求→查询→补充→草稿→明确确认，Mongo检查点、版本CAS、稳定草稿编号 | 显式实验流程，不冒充自主多Agent；恢复读取不自动执行预约 |

## 打开实验页面

登录现有Vue页面，点“AI 工程实验室”。检索可切换vector、hybrid、llm。LLM重排会增加模型调用和延迟。

工作流：新建→保存医院科室→查询排班→填写查询结果中的日期和场次编号→保存完整需求→生成草稿→核对并确认。确认与取消均有独立浏览器确认框。浏览器记住当前账号最后一个流程编号；刷新后从服务器恢复。另一账号无法读取同一编号。中断于草稿写入时显示RECOVERY_REQUIRED，可以显式恢复，重用同一个草稿编号。

## 评测命令（PowerShell，项目根目录）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-ai-engineering.ps1 -JavaHome $env:JAVA_HOME -MavenCommand mvn.cmd
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-mysql.ps1 -LiveAi -JavaHome $env:JAVA_HOME -MavenCommand mvn.cmd
```

第一条会真实调用embedding、Pinecone和Qwen；读取现有密钥但不输出密钥，不同步Pinecone。第二条先执行完整测试，再用13307临时MySQL和Mongo随机库运行真实工具评测，完成后清理。不得将测试端口改为业务3306。没有`-LiveAi`的CI不需要任何云模型密钥。

报告位于`evals/results/`。每次新建目录，不覆盖旧报告。检索分母包含成功运行的正例/负例，错误单独列出；比较时必须核对错误数和回退数。题集很小且是回归题，不是盲测。抽样回答保存完整文本，不能把关键词匹配包装成自动裁判准确率。

## OpenTelemetry

每次请求的检索、生成、工具与保存可用相同`traceId`定位。日志仅包含操作名、状态、耗时、token用量和错误类型，不写问题、病情、密码或工具参数。

可选环境变量`XIAOZHI_OTEL_ENDPOINT=http://127.0.0.1:4318/v1/traces`连接本地OTLP HTTP collector。远程地址要求HTTPS。外部采集平台的鉴权、保留期和Langfuse项目仍需按实际部署配置，默认没有外传追踪。

## MCP

```powershell
cd mcp
npm ci --ignore-scripts
$env:XIAOZHI_MCP_URL='http://127.0.0.1:8081'
$env:XIAOZHI_MCP_USERNAME='自己的演示账号'
# 用密码管理器或客户端安全环境设置 XIAOZHI_MCP_PASSWORD，不写入仓库。
npm start
```

MCP客户端使用`node`命令，参数为`F:\xiaozhi-learning\mcp\server.mjs`，通过客户端的安全环境设置账号密码。stdio窗口不是聊天窗口；协议输出不能混入调试日志。只公开`search_knowledge`与`query_appointment_sessions`。返回资料仍是不可信数据，不是系统指令。

## 发布

打`v*`标签触发release工作流。发布前必须存在同一提交SHA在main分支成功的完整CI；随后构建前端、检查依赖和MCP、打包JAR、生成文件清单及ZIP SHA256，并发布GitHub Release。发布包监听本机；GitHub发布不等于公网网站已部署。服务器、域名/HTTPS与入口代理需要确定目标后再验收。

旧版test-ai-retrieval.ps1固定验证vector管线，保留作历史对照；默认主聊天使用hybrid，当前方案比较应运行test-ai-engineering.ps1。


## 导诊工作台更新

2026-10-07补齐浏览器网络中断恢复和OTLP实际导出检查。OTLP支持通过XIAOZHI_OTEL_AUTHORIZATION设置鉴权头，云项目仍需配置并单独验收。新版界面、测试命令和边界见[UI_ACCEPTANCE.md](UI_ACCEPTANCE.md)。

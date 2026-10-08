# 专用相关性模型重排

已接入 DashScope gte-rerank-v2 专用排序模型。它接收 BM25 与向量检索经 RRF 融合后的候选，对候选重新评分；这不同于原来的本地特征公式或让聊天模型输出相关性分数。

默认仍为 hybrid。工程实验页新增“专用相关性模型重排”，明确点击后才调用外部服务；也可以请求 /api/knowledge/compare?query=...&mode=dedicated。聊天只有显式设置 xiaozhi.rag.mode=dedicated 才使用专用排序。

## 保留与改变

候选身份、来源、向量分数、BM25 和 RRF 分数保留。排序服务返回的 index 是输入数组位置，必须映射回原始片段编号；重复、越界、缺失、非数值和异常分数拒绝。服务异常直接报告失败，不把本地回退伪装成专用模型结果。

原来的候选接纳规则保留，只改变合格候选的顺序。排序分数不能解释为答案正确概率；当前没有给跨问题分数设定新的统一置信阈值。这也意味着低于原接纳门槛的候选不会因神经排序而被救回。

## 实测

报告：../evals/results/dedicated-rerank-20261008-183756/summary.json

- 原24题，20条有依据、4条无关，首轮候选共享。
- 平均证据召回：原方案97.5%，专用排序100%。
- 无关问题误接纳：均0/4；接口错误0。
- 专用排序平均约1367毫秒；这是排序调用增量，不包含原检索耗时。
- 小型既有题集的单次对照；不是医疗准确率，不证明独立数据上的稳定提升。

## 复现

PowerShell，从仓库根目录：

    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-dedicated-rerank.ps1 -JavaHome "F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1" -MavenCommand "F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd"

加 -Live 才运行付费在线对照，需要现有 DASHSCOPE_API_KEY、PINECONE_API_KEY、PINECONE_INDEX_HOST。测试只读知识索引，不同步资料，不读业务库或个人对话。evals/dedicated-rerank-payload.json 记录逐份审阅过的虚构教学资料和固定问题集，执行前核对文件哈希；资料改变后应重新审阅。当前哈希按实际文件字节计算，换行变化也会被拒绝。

应用配置：
- xiaozhi.rag.reranker.model 默认 gte-rerank-v2。
- xiaozhi.rag.reranker.endpoint 默认 https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank，本次已实测。
- 客户端也支持 qwen3-rerank 的扁平请求/响应格式；该模型和地区端点未实测，不能把 gte 的报告算作 qwen3 报告。
- 仅允许官方 DashScope HTTPS 域名，禁止自动重定向。连接超时10秒、请求超时25秒，无自动重试。
- 端点和模型应与账户地区及官方当前可用配置匹配。

官方协议参考：https://www.alibabacloud.com/help/en/model-studio/text-rerank-api

# AI 评测：第一阶段——检索基线

本阶段为简历演示项目建立可重复的真实检索评测。主项目继续使用 LangChain4j；自定义的是 Pinecone REST 通信层，并非整个 AI 框架。

## 已完成与边界

- 数据集：`evals/retrieval-v1.jsonl`，20 个有依据的问题、4 个明确无关的问题。
- 覆盖四份实际知识资料，包括文本型 PDF；包含两道需要两处依据的问题。
- 每条依据记录文件来源和原文，不依赖可能随重新切分变化的片段编号。
- JUnit 直接加载当前知识资料，验证原文存在于实际解析片段；10 项测试检查标注、评分、阈值、排序和错误分母。
- 真实运行复用 KnowledgeDocumentService、KnowledgeEmbeddingConfig、KnowledgeSearchService 和 PineconeClient。
- 不启动 Spring 应用，不连接 MySQL/MongoDB，不调用聊天模型，不创建或取消预约，不调用 Pinecone sync/upsert。
- 仅检索基线已完成；回答依据、工具选择、最终数据库状态、多轮上下文和提示注入评测尚未完成。
- 这些是公开可见的小规模回归题，未设置隐藏测试集；不可据此宣称泛化能力或医疗准确率。

## 指标口径

| 指标 | 计算方式 | 分母 |
|---|---|---|
| macroEvidenceRecallAt3 | 每题被前三项候选覆盖的标注依据数 / 该题标注依据数，再对题目取平均 | 成功执行的正例 |
| mrrAt3 | 第一个包含任一标注依据的候选排名的倒数；未命中为 0，再取平均 | 成功执行的正例 |
| macroAcceptedEvidenceRecallAt2 | 按当前聊天规则筛选后，计算同样的依据覆盖率 | 成功执行的正例 |
| negativeFalseAcceptanceRate | 明确无关的问题中，仍有候选通过聊天筛选的比例 | 成功执行的负例 |

“覆盖”采用同一来源文件、原文包含关系，比较时忽略空白。它是严格的标注原文覆盖率，不是模型语义裁判。来自其他段落的等价解释可能未被标注，因此低分必须人工核对，不能直接判定最终回答错误。

聊天筛选复现当前主项目：候选 topK=3，归一化分数至少 0.80，最多采用 2 项。分数由 cosine 经 (cosine+1)/2 换算，0.80 不等于原始 cosine 0.80，更不等于 80% 的正确概率。离线检查防止聊天阈值变更后评测器仍沿用旧参数。

错误记为 ERROR，达到两次错误后剩余题记为 NOT_RUN；两者不当作“无匹配”或正确拒答，不进入质量分母。报告同时显示 planned/completed/errors/notRun，必须一起阅读。无样本的指标输出 null，不能当作 0% 或 100%。

没有设定自动质量及格线。进程成功退出只表示完成采集，不表示 AI 质量达标。

## 如何运行

在 PowerShell 执行默认离线检查，不需要数据库、API Key 或正在运行的后端：

```powershell
cd F:\xiaozhi-learning
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-ai-retrieval.ps1 -JavaHome "F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1" -MavenCommand "F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd"
```

本次进程使用 Bypass 执行项目脚本，不修改 Windows 持久执行策略。

显式加 `-Live` 才会运行 24 个问题的真实 embedding 和 Pinecone 查询，产生对应服务用量。要求当前终端存在 DASHSCOPE_API_KEY、PINECONE_API_KEY、PINECONE_INDEX_HOST；环境变量设置后需重新打开终端。不要把密钥填入代码或提交记录。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-ai-retrieval.ps1 -Live -JavaHome "F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1" -MavenCommand "F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd"
```

当前知识版本必须已通过原有管理员同步流程进入 Pinecone。评测工具不会为消除报错自动同步或绕过权限。

每次结果位于 `backend/target/eval-runs/<时间-随机编号>/`：
- `summary.json`：执行状态、分母、指标、模型名、Git HEAD、工作区变更标记、数据集和解析结果 SHA-256。
- `trials.jsonl`：每题状态、耗时、候选原文、来源、分数和评分。
- 不保存密钥或异常响应正文；异常只记录类型。
- 手动中断可能只有部分 trials，没有完整 summary，不得当作完成结果。

target 是临时输出。需要保存的基线应人工检查后复制进 evals/baselines，再随代码提交。比较两个方案时应保留同一题集和知识版本、分别记录参数，不应为提高分数删掉失败题。

## 2026-10-06 UTC 真实基线

Windows 本地时间 2026-10-07 04:06；UTC 为 2026-10-06 17:06。存档位于：
`evals/baselines/vector-v1-20261006/`。

执行时 HEAD=4d27433，工作区包含本轮未提交评测文件（trackedDirty=true）。生产检索代码和参数没有修改。报告的 dirty 标记包含未跟踪文件，不代表所有变更都已纳入该提交。

| 项目 | 结果 |
|---|---|
| 完成 / 计划 | 24 / 24 |
| ERROR / NOT_RUN | 0 / 0 |
| 正例 / 负例 | 20 / 4 |
| 前三项标注依据平均覆盖率 | 100% |
| MRR@3 | 0.9417 |
| 聊天采用片段的标注依据平均覆盖率 | 90% |
| 无关问题的误采用比例 | 0 / 4 |

只有一次运行，知识库仅 4 份资料、6 个片段。没有测量回答正确率、token 花费、预约成功率或临床质量；不能写成“AI 准确率 100%”。

人工复核的两个案例：
1. R02：蓝色风车问题的正确片段排第 2，归一化分数约 0.7719；第一项 PDF 约 0.7870，均低于 0.80。当前筛选没有采用资料。这是可用于后续关键词检索/排序对照的实际案例。
2. R14：以业务查询接口为准的标注片段排第 3，约 0.8027；前两项预约指南被采用。它们也包含查询、加载和状态核对建议，所以这是“指定标注片段未进入上下文”，不能直接判定为语义检索失败或错误回答。后续需要人工补充可接受的替代依据标注，并另立数据集版本，保留 v1 原始结果。

不得只看这两个案例就调低阈值或增加 topK 后宣布改进；需要同时观察负例、重复运行及后续新增难题。

## 后续推进顺序

1. Evals 第二阶段：复核替代依据标注，加入回答与引用人工评分；隔离数据库中的工具调用轨迹、人工确认前后状态评测；网络错误与业务失败分开统计。
2. 混合检索与重排序：以本基线为对照，比较关键词+向量候选融合和 reranker。增加难题及独立保留题，防止只拟合这 24 题。
3. 上下文工程：token 预算、追问改写、结构化已确认需求；使用多轮题验证成本和正确性。
4. OpenTelemetry / Langfuse：关联请求、检索、模型、工具、保存与用量；先处理内容脱敏与采样。
5. MCP：优先暴露只读排班查询和知识搜索，保持现有账号权限和人工确认边界。
6. 有状态 Agent：在独立实验中验证恢复点和人工确认，再决定是否替换主流程。

框架依据：
- LangChain4j Pinecone 官方适配器：https://docs.langchain4j.dev/integrations/embedding-stores/pinecone/
- Agent 评测方法（任务、轨迹、评分与环境最终状态）：https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents

主项目保持已有 beta3 依赖，本轮没有按最新文档直接升级版本。

# 第10项之二：Pinecone 集成的离线契约对照

独立模块：examples/pinecone-comparison。固定Java17、Spring Boot3.2.6依赖管理、LangChain4j1.0.0-beta3，Pinecone SDK由该集成引入3.1.0。

本例对照主项目PineconeClient和KnowledgeSearchService的元数据/结果约定，与LangChain4j的真实PineconeEmbeddingStore。它没有替换主项目的RAG实现。

## 如何运行

在IDEA的PowerShell终端执行：

```powershell
cd F:\xiaozhi-learning\examples\pinecone-comparison
$env:JAVA_HOME = 'F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1'
& 'F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd' test
```

预期结果：

```text
PINECONE_CONTRACT_COMPARISON_OK scores=0.9,0.7 sources=2
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

成功标记来自其中一项测试，因此也要核对最后的7项测试汇总和BUILD SUCCESS。本模块以测试作为入口，不运行spring-boot:run；没有Web服务，完成后退出是正常行为。

不需要设置密钥、启动数据库或启动主后端。首次下载Maven依赖仍需联网。测试不读取PINECONE_API_KEY等环境变量，不调用模型、不连接Pinecone云端、不创建/写入/删除云索引。

## 真正验证了哪一层

测试调用发布包中的真实PineconeEmbeddingStore，并由它完成：

- 将TextSegment、Metadata、向量ID转成SDK的写入对象。
- 生成revision过滤条件并传入namespace。
- 读取SDK响应，恢复正文及来源元数据。
- 使用返回向量计算cosine，再转换成0～1分数、排序和筛选。

Mockito仅替换Pinecone客户端的构造和Index调用边界。SDK返回的是预设向量和元数据，不是云端实际搜索结果。因此它验证“请求是否正确传递、适配器如何处理响应”，不证明云端过滤隔离、网络权限、索引存在、真实检索排序或同步最终一致性。

1024维测试向量由公式生成，fixture-embedding只是测试模型标识，不具备语义检索能力。

## 已核对的差异

| 项目 | 当前主项目 | LangChain4j beta3集成 | 对照处理 |
|---|---|---|---|
| 连接位置 | PINECONE_INDEX_HOST，直连数据接口 | index参数使用索引名称，交由SDK连接 | 不能把原Host直接当index名称 |
| 正文键 | metadata.text | 默认text_segment | 明确metadataTextKey("text") |
| 命名空间 | demo-加文档版本摘要 | 默认字符串default | 必须传入完整现有namespace；测试使用lab-only-v1 |
| 版本 | revision过滤，加本地清单再次核对 | 可传MetadataFilter，适配器信任SDK结果 | 保留revision过滤和独立结果核验 |
| 分数 | REST原始cosine转换为(cosine+1)/2 | 从返回向量重新计算cosine，再归一化 | 两边0.8原始cosine都变成0.9；不要归一化两次 |
| 查询返回向量 | includeValues=false | 此版本需要返回向量，查询使用true | 不能沿用不返回values的响应假设 |
| 片段编号 | 检查JSON数字是否与本地index完全相等 | 数字元数据恢复为Double | 先检查有限性和完整相等，再取本地int，不能直接强转Integer或截断 |
| ID | chunk-加稳定编号 | 不传ID的便捷方法生成随机ID | 使用addAll(ids, embeddings, segments)传稳定ID |
| 写入/核验 | 分批32条、核对计数、fetch复查和版本状态 | addAll交给SDK，未复刻主项目同步状态流程 | 不把调用成功直接视为现有同步业务已等价 |

当前聊天层的0.80阈值已经针对归一化分数。主项目先验证完整topK结果，再由聊天层筛选；示例查询minScore=0.0，避免提前筛选改变完整性检查语义。另有测试单独演示minScore=0.8如何筛掉归一化0.7的结果。

## 文件阅读顺序

1. PineconeStoreFactory.java：显式配置索引名、namespace和正文键。不配置createIndex。
2. KnowledgeContract.java：提炼REST响应解析、框架响应转换、来源/版本核验和向量格式检查。
3. PineconeComparisonTest.java：看真实适配器收到什么、输出什么，以及SDK边界如何被替换。

KnowledgeContract不是生产迁移代码：它没有实现主项目的文档版本生成、fetch完整性检查、索引统计校验、写入分批和同步状态管理。

## 七项测试

1. 同一批预设数据经REST契约与真实框架适配器处理后，ID、来源、片段编号、正文和归一化分数一致。
2. namespace、revision过滤及返回values参数正确传给SDK；不请求创建或删除索引。
3. minScore按归一化分数筛选；缺少向量不能当作等价REST结果使用。
4. 使用默认text_segment键会丢失现有text正文；工厂配置修正该差异。
5. 错误revision、重复片段ID被独立契约校验拒绝，不能仅依赖框架结果。
6. 数字元数据实际为Double；错误维度、零向量、NaN和空namespace被拒绝。
7. 写入SDK边界时保持稳定ID、1024维、text/source/index/revision字段。

## 本轮结论与后续

本轮完成离线接口契约对照，主项目继续使用现有客户端。框架适配器提供统一EmbeddingStore接口，但还需要保留现有来源校验、版本管理、幂等同步和错误处理；只替换几行builder不能代表整体迁移完成。

尚未执行真实云端端到端验证。未来若评估迁移，应先选独立实验namespace，核实实际索引名、1024维和cosine度量，使用同一嵌入模型、相同文档/查询验证检索，再核对RAG来源和同步行为。本例没有提供默认会访问云端的运行入口。

第10项还剩Flux流式输出对照。云端迁移验收与本轮离线学习验证应分别记录。

## 核对来源

- 本仓库backend/src/main/java/com/ruomu/xiaozhi/service/PineconeClient.java及KnowledgeSearchService.java，基线提交f4ab2b4。
- beta3发布源码：https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-pinecone/1.0.0-beta3/langchain4j-pinecone-1.0.0-beta3-sources.jar
- 官方集成文档：https://docs.langchain4j.dev/integrations/embedding-stores/pinecone/ （持续更新，当前实验以beta3源码为准）

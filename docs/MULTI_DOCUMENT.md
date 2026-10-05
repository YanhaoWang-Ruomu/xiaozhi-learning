# 第 1 步：多文档知识库

对应课程第 53 节文档加载和第 64 节多份资料入库。本次继续使用现有模型、MongoDB、Pinecone 和网页。

## 文件放置

把压缩包中的 backend 和 docs 合并复制到 `F:\xiaozhi-learning`，不要删除或替换整个文件夹。

替换这两个现有文件：

- `backend/src/main/java/com/ruomu/xiaozhi/service/KnowledgeDocumentService.java`
- `backend/src/main/java/com/ruomu/xiaozhi/dto/KnowledgePreviewResponse.java`

新增这四个资源文件：

- `backend/src/main/resources/knowledge/catalog.txt`
- `backend/src/main/resources/knowledge/hospital-info.txt`
- `backend/src/main/resources/knowledge/appointment-guide.txt`
- `backend/src/main/resources/knowledge/knowledge-scope.txt`

`demo-guide.txt` 可以保留；它不在新清单内，所以不会再被加载。无需修改 pom.xml、API Key、Pinecone 索引、控制器或 demo.html。

## 工作方式

`catalog.txt` 每行列一个 knowledge 目录中的 UTF-8 TXT / MD 文件名；空行和以 # 开头的整行注释会跳过。目前只支持该目录的直接子文件，不支持子目录、PDF 或在线 URL。

例如：

```text
hospital-info.txt
appointment-guide.txt
knowledge-scope.txt
```

程序读取清单，按文件名排序，逐文件解析和切分。每份文档单独切分，每段最多 300 字符、重叠参数为 40 字符。片段 index 在整个知识库中统一从 0 递增，不能把每份文档都从 0 开始计数，否则现有 Pinecone 记录编号会冲突。

GET `/api/knowledge/chunks` 保留原有 chunks 字段，并增加：

- `documentCount`：文档数量。
- `documents`：每份文档的 source、characterCount、firstChunkIndex 和 chunkCount。
- 顶层 `source`：资料清单路径；每个 `chunks[].source` 才是片段实际所属文件。

文件名、内容或切分配置变化后，现有 KnowledgeSearchService 会根据预览内容计算新的资料版本，使用新的 namespace。调整清单行顺序或 Windows/Linux 换行不会单独触发版本变化。

相同版本重复同步复用已有记录；不同资料版本目前作为一个整体隔离，因此修改一个文件后，新版本会重新导入所有当前片段，不是跨版本只更新单个文件。旧 namespace 暂不删除。

文件缺失、空文件、无效 UTF-8、清单重复和不支持的格式会明确阻止启动，不静默跳过。单个文件上限为 1 MiB。当前仍是小规模教学知识库，不是大批量文档管理系统。

## 启动与检查

### 1. MongoDB 与后端

如果是休息后重新继续，先确认 MongoDB 已运行。尚未启动时，在 PowerShell 执行并保留窗口：

```powershell
& "F:\xiaozhi-medical\tools\mongodb\mongodb-win32-x86_64-windows-8.0.6\bin\mongod.exe" --dbpath "F:\xiaozhi-learning-data\mongodb" --bind_ip 127.0.0.1 --port 27017
```

不要重复启动已有的 MongoDB 实例。停止 IDEA 中的 Java 后端，保存替换文件，再运行 XiaozhiApplication。环境变量没有变化时不用重新配置。

### 2. 检查本地分段

浏览器访问：

<http://localhost:8081/api/knowledge/chunks>

本包原样运行的预期为 `documentCount: 3`、`chunkCount: 4`。documents 显示三份资源文件：appointment-guide.txt 为 2 段，hospital-info.txt 和 knowledge-scope.txt 各 1 段。每个 chunk 都有准确的 source 和唯一的 index。以后修改内容时，片段数以实际响应为准，不再固定要求旧版本的 3 段。

如果返回的还是旧结构，确认文件复制到了 `src/main`，并且 IDEA 已重新编译和启动后端。不要修改 target/classes 中的生成文件。

### 3. 同步新资料版本

在 PowerShell 中执行一次：

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/knowledge/pinecone/sync'
```

新 namespace 初次同步通常需要上传全部当前片段。如果你已同步过相同版本，则会复用。若返回 INDEXING，稍后查看状态，不要连续重复提交同步。

查看：

<http://localhost:8081/api/knowledge/pinecone/status>

预期 `knowledge.documentsPresent` 为 true，storedChunks 等于 expectedChunks。索引总记录数 totalVectorCount 可能包含旧 namespace 的记录，因此不必等于 expectedChunks。

状态就绪后再执行一次相同同步，预期 uploadedChunks 为 0、reusedChunks 等于 expectedChunks。

### 4. 检查聊天来源

打开 <http://localhost:8081/demo.html>，点击“新会话”，依次发送：

```text
演示医院服务台在哪里，有什么标志？
```

预期回答星桥楼一层、蓝色风车，相关来源包含 `knowledge/hospital-info.txt`。

```text
待确认的预约草稿取消后还能再次确认吗？
```

预期说明不能再次确认，需要重新创建草稿；相关来源包含 `knowledge/appointment-guide.txt`。

```text
知识文档能告诉我某次预约是否成功以及预约编号吗？
```

预期说明需要查询实际业务记录，不能从知识文档推断；相关来源包含 `knowledge/knowledge-scope.txt`。

展开每轮的“本轮检索参考资料”检查来源和正文。模型检索可能同时返回另一份相关文件，不要求每轮恰好只有一个片段。若答案或来源不符合预期，先记录实际检索结果，不要直接标记验收通过。

本轮这些问题只是在询问规则，不需要创建或取消任何草稿。

## 以后如何增加文档

1. 在 `backend/src/main/resources/knowledge` 中新增一个 UTF-8 `.txt` 或 `.md` 文件。
2. 在 catalog.txt 新增一行文件名。
3. 重启后端，使程序重新加载文档。
4. 查看 chunks 接口确认文件及片段来源。
5. 显式同步新版本，再测试查询。

仅把文件放入目录不会自动导入。同步接口使用后端启动时加载的资料快照，因此修改文件后要先重启后端。

## 本次交付验证

已用 Java 17、LangChain4j 1.0.0-beta3 和 Spring 6.1.8 对替换类及现有检索/同步类进行编译检查。使用真实文档解析与分段组件、模拟向量模型和 Pinecone 客户端验证了：3 份文档共 4 段；全局编号和来源正文对应；未列入清单的旧文件被排除；首次同步上传 4 段、再次同步上传 0 段并复用 4 段；重建服务后不重新生成文档向量；清单换序和 UTF-8 BOM/CRLF 不改变 namespace；文件内容变化隔离到新 namespace；从 JAR 中加载成功；缺失、重复、空白、无效编码及不支持格式明确报错。

这不是完整 Spring Boot 项目的启动验收，也没有使用你的密钥调用真实云服务。真实模型答案和用户云端 Pinecone 的读写仍需按上述步骤完成；不要把本地模拟结果写成云端验收通过。

## learning-log 建议追加内容

完成本机编译运行后，可在现有 learning-log.md 末尾追加，并按实际结果填写：

```markdown
### 2026-10-05：扩展多文档知识库

- 通过 catalog.txt 明确指定要加载的 UTF-8 TXT / MD 文件。
- 将演示资料拆分为医院介绍、预约流程和资料范围三份文档。
- 每份文档独立分段，使用全局片段编号，保留真实文件来源。
- chunks 接口增加文档数量和每份文档摘要。
- 保留现有资料版本隔离及相同版本重复同步复用机制。
- 本机编译与启动：待填写。
- 三份文档预览、新版本云端同步：待填写。
- 重复同步 uploadedChunks=0：待填写。
- 三类问题的答案与资料来源：待填写。
```

## 验收后提交

在 PowerShell 中：

```powershell
cd F:\xiaozhi-learning

git status

git add backend/src/main/java/com/ruomu/xiaozhi/service/KnowledgeDocumentService.java backend/src/main/java/com/ruomu/xiaozhi/dto/KnowledgePreviewResponse.java

git add backend/src/main/resources/knowledge/catalog.txt backend/src/main/resources/knowledge/hospital-info.txt backend/src/main/resources/knowledge/appointment-guide.txt backend/src/main/resources/knowledge/knowledge-scope.txt

git add docs/MULTI_DOCUMENT.md
```

如果学习日志位于 docs/learning-log.md，再执行 `git add docs/learning-log.md`；如果位于项目根目录，则执行 `git add learning-log.md`。只选择实际路径。

然后：

```powershell
git diff --cached --stat

git commit -m "feat: 支持多文档知识库并保留检索来源"

git push origin main
```

不要使用 git add . 把无关文件或 backup/ 一并提交。

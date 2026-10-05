# 文本型 PDF 接入知识库

## 一、本次功能

在现有多文档知识库的基础上，增加文本型 PDF 解析。

当前支持：

- UTF-8 编码的 TXT 文件。
- UTF-8 编码的 Markdown 文件。
- 具有可提取文本层的 PDF 文件。

知识文件统一通过 `knowledge/catalog.txt` 管理。
只有清单中的文件才会加载。

每份文档独立切分，保留来源文件名。
整个知识库的片段编号从 0 开始连续递增。

切分参数：

- 单个片段最大 300 字符。
- 片段重叠上限 40 字符。
- 这里的单位是字符，不是 token。

## 二、涉及文件

| 文件 | 作用 |
| --- | --- |
| backend/pom.xml | 添加 PDF 解析依赖 |
| backend/src/main/java/com/ruomu/xiaozhi/service/KnowledgeDocumentService.java | 根据文件类型读取和切分知识 |
| backend/src/main/resources/knowledge/catalog.txt | 指定参与检索的文件 |
| backend/src/main/resources/knowledge/visit-handbook.pdf | 用于验证的虚构便民服务手册 |
| docs/PDF_KNOWLEDGE.md | 本次功能说明 |

原有三个 TXT 知识文件继续保留。

## 三、Maven 依赖

在 `backend/pom.xml` 的 `<dependencies>` 中添加：

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-document-parser-apache-pdfbox</artifactId>
    <version>1.0.0-beta3</version>
</dependency>
```

版本与当前项目保持一致。

添加完成后，在 IDEA 的 Maven 面板执行：
Reload All Maven Projects（重新加载所有 Maven 项目）。

`dependency.xml` 仅用于保存上述依赖片段。
Maven 不会自动加载该文件，依赖必须加入实际的 `backend/pom.xml`。

## 四、知识清单

`backend/src/main/resources/knowledge/catalog.txt` 内容：

```text
# 每行一个 knowledge 目录内的文件名。
# TXT 和 MD 使用 UTF-8 编码；PDF 必须具有文本层。
# 未列入清单的文件不会加载。

hospital-info.txt
appointment-guide.txt
knowledge-scope.txt
visit-handbook.pdf
```

PDF 必须是真实 PDF 文件，不能将普通文本文件直接改后缀。

本次演示 PDF 中的楼名、服务台和位置均为虚构，
仅用于学习知识检索，不可作为真实医院指引。

## 五、启动与验证

### 1. 启动后端

保持 MongoDB 运行。

完成 Maven 依赖加载后，运行 `XiaozhiApplication`。

知识文件在后端启动时加载。
修改文件或清单后，需要重新启动后端。

### 2. 检查知识预览

浏览器访问：

http://localhost:8081/api/knowledge/chunks

使用本次提供的默认文件，预期：

- documentCount：4
- chunkCount：6

各文件的片段分布：

| 来源 | 起始编号 | 片段数 |
| --- | ---: | ---: |
| knowledge/appointment-guide.txt | 0 | 2 |
| knowledge/hospital-info.txt | 2 | 1 |
| knowledge/knowledge-scope.txt | 3 | 1 |
| knowledge/visit-handbook.pdf | 4 | 2 |

PDF 片段应包含可读中文，例如“青竹服务台”和“橙帆服务台”。

如果自行修改了文件内容，片段数量可能变化，以实际预览为准。

### 3. 同步到 Pinecone

在 PowerShell 执行：

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8081/api/knowledge/pinecone/sync" |
    ConvertTo-Json -Depth 10
```

添加 PDF 会产生新的知识版本。

当前按整个知识库版本隔离，因此新版本首次同步可能上传全部
6 个片段，而不是只上传 PDF 新增的两个片段。

旧版本保留，不需要清空整个 Pinecone 索引。

查看状态：

http://localhost:8081/api/knowledge/pinecone/status

当前默认版本预期：

- expectedChunks：6
- storedChunks：6
- documentsPresent：true

全索引的 totalVectorCount 包含旧版本，不必等于 6。

如果同步返回 INDEXING，稍后再检查状态。

### 4. 验证重复同步

完成首次同步后，再执行一次相同的同步命令。

预期：

- uploadedChunks：0
- reusedChunks：6
- storedChunks：6

这表示当前版本的已有片段被复用。

### 5. 验证聊天检索

打开：

http://localhost:8081/demo.html

新建会话，输入：

```text
根据演示手册，轮椅借用点在哪里？识别标志是什么？
```

预期依据 PDF 回答：

- 位于星桥楼一层西侧的青竹服务台。
- 识别标志是绿色叶子图案。
- 这些属于演示设定。

展开“本轮检索参考资料”，检查实际来源包含：

```text
knowledge/visit-handbook.pdf
```

只回答出预期地点，不能单独证明本轮检索成功。
还需要检查实际检索来源。

## 六、当前限制

- 尚未接入 OCR，不识别扫描图片中的文字。
- 混合型 PDF 只能提取已有文本层，图片中的文字可能遗漏。
- 来源展示精确到文件，暂不支持 PDF 页码定位。
- 多栏和复杂表格的文字顺序可能不理想，需要检查片段预览。
- 单个 TXT、MD 文件上限为 1 MiB。
- 单个 PDF 文件上限为 10 MiB。
- PDF 提取后的文字上限为 100 万字符。
- 文字长度限制在提取后检查，不代表解析过程的内存配额。
- 文件缺失、损坏、需要密码或没有可提取文字时，会明确报错。
- 清单中有问题文件时，知识库初始化会失败，不会静默跳过。
- 修改资料后，需要重启后端，再显式同步当前知识版本。

## 七、验证记录

代码准备阶段已经完成：

- Java 17 编译检查。
- 真实 PDFBox 解析器的中文提取检查。
- TXT 文件兼容检查。
- 文件来源与片段内容绑定检查。
- JAR 内资源读取检查。
- 损坏、空白、需要密码、过大及缺失 PDF 的拒绝检查。
- 使用模拟模型和模拟 Pinecone 检查首次同步、重复复用及版本隔离。

上述检查不代表本机真实云端验收已经完成。

实际运行后填写：

- 知识预览结果：待验证。
- 首次 Pinecone 同步结果：待验证。
- 重复同步结果：待验证。
- 聊天回答与 PDF 来源展示：待验证。

验证完成后，将实际结果追加到 `docs/learning-log.md`。
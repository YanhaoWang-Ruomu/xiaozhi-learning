# 小智中文请求意图分类实验

使用 scikit-learn 完成与导诊请求有关的轻量分类实验：问候与能力、医院资料、预约需求、上下文不足、健康相关表达和其他问题。它只识别请求类型；诊断、紧急程度判断和自动预约不属于训练目标。

## 安装与复现

在新的 PowerShell 中执行：

~~~powershell
# 在源码根目录或发布包解压目录中打开 PowerShell
cd .\ml-intent
py -3.14 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m pip check
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe experiment.py --output runs\local
Start-Process .\runs\local\report.html
~~~

独立依赖不加入 Java 后端或 python-eval。没有模型收费、网络请求或业务数据库读写。

输入一条表达，查看模型提示与回退：

~~~powershell
.\.venv\Scripts\python.exe experiment.py --model runs\local\intent-model.joblib --text "帮我看看哪天能约到医生"
.\.venv\Scripts\python.exe experiment.py --model runs\local\intent-model.joblib --text "胸口疼，想预约明天"
~~~

模型文件由上述训练命令产生；只加载本实验自己生成的文件。输出 action_authorized 始终为 false。

## 实验设计

- 180条自编合成语句，36个语义/改写分组；没有真实病历、账号数据或独立人工标注背书。
- 分组和划分在训练前固定：120训练、30验证、30测试；同组不跨集合，规范化重复文本拒绝加载。
- 字符2至4元 TF-IDF 与分类器组成 Pipeline，特征词表仅在训练集合拟合。
- 多数类和关键词作为基线；比较逻辑回归与多项式朴素贝叶斯。参数与回退阈值只按验证集选择，不用测试结果调参。
- 保存准确率、宏F1、各类精确率/召回率、混淆矩阵、学习曲线、误判原句、原始预测与CPU耗时。
- 低分、未知字符、空白/超长和健康关键词可以回退。分数未经概率校准；不能当成真实正确概率。

这里的意图标签与 Java 的最终执行路由不同。例如“想预约”表示需求，但缺少医院、日期、场次时，业务仍应先澄清；模型分类不会绕过 BookingTurn 核验、权限或独立确认按钮。关键词基线不是 AdaptiveAgentRouter 的复制，不能据此宣称主路由被改善。

## 结果与展示

[第一次完整实验](../evals/ml-intent/20261009/REPORT.md) / [原始指标](../evals/ml-intent/20261009/metrics.json)。

report.html 是可直接打开的独立报告：效果对照、训练/验证差距、混淆矩阵、逐条误判和回退挑战。CLI展示同一句话的分类提示。CI在Linux、Windows重新训练、测试并保存报告；本机存档的耗时只代表当时环境。

本次模型未胜过关键词基线，尚不接入主项目。当前已经完成一轮机器学习实验；生产泛化、独立人工复核和主系统影子比较仍需后续验收。新增表达或改标注后应发布数据新版本，不能用原测试题反复优化再称其为未见测试。

## 发布包中的运行

v0.3.2将本模块、data/intent-v1.jsonl及evals/ml-intent/20261009报告放在ZIP中；解压后按上面的依赖/训练命令操作。GitHub发布时从这个目录结构重跑18项检查和训练，另将云端报告保存在evals/ml-intent/release-validation。Java启动不会自动运行本模块，原业务数据库不受影响。

## 原始语句标注原则

医院流程/设施/资料介绍归 HOSPITAL_INFO；请求查询、创建、取消或修改演示预约归 BOOKING；缺少指代对象的表达归 CONTEXT_DEPENDENT；身体不适、药物或处方相关表达归 MEDICAL_MESSAGE。这是演示意图定义，不是诊疗分级。复合、否定、多轮改口和范围外请求需要单独挑战集，目前六条挑战仅展示提示与回退，不计为外部准确率。

## 参考

- [scikit-learn文本分类示例](https://scikit-learn.org/stable/auto_examples/text/plot_document_classification_20newsgroups.html)
- [训练划分与数据泄漏](https://scikit-learn.org/stable/common_pitfalls.html)
- [概率校准](https://scikit-learn.org/stable/modules/calibration.html)

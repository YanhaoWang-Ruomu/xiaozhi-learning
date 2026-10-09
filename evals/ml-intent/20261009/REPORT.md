# 小智中文请求意图分类实验

180条自编合成演示语句，尚未经过独立人工标注复核。识别请求类型，不诊断疾病，不触发预约。

训练120、验证30、测试30条；36个语义/改写分组固定划分。一次划分实测如下。

|方案|测试准确率|测试宏F1|单条P50(ms)|P95(ms)|
|---|---:|---:|---:|---:|
|logistic|0.533|0.520|0.464|0.529|
|naive_bayes|0.400|0.401|0.459|0.534|
|majority|0.167|0.048|0.029|0.040|
|keyword|0.900|0.897|0.002|0.003|

验证集选择logistic，参数0.5；训练宏F1=1.000，验证=0.476，测试=0.520。
规则为独立关键词基线，未复现Java会话状态；不能写成主项目路由改进率。

## 误判案例

- GREETING-6-1：你是谁；标注GREETING，预测MEDICAL_MESSAGE。
- GREETING-6-2：介绍一下你自己；标注GREETING，预测OTHER。
- GREETING-6-5：你有什么能力；标注GREETING，预测HOSPITAL_INFO。
- HOSPITAL_INFO-6-2：应当从哪个官方渠道核实开放时间；标注HOSPITAL_INFO，预测CONTEXT_DEPENDENT。
- BOOKING-6-3：星期三下午想去门诊；标注BOOKING，预测HOSPITAL_INFO。
- BOOKING-6-5：帮我看看哪天能约到医生；标注BOOKING，预测OTHER。
- CONTEXT_DEPENDENT-6-1：继续；标注CONTEXT_DEPENDENT，预测MEDICAL_MESSAGE。
- CONTEXT_DEPENDENT-6-2：下一步呢；标注CONTEXT_DEPENDENT，预测MEDICAL_MESSAGE。
- CONTEXT_DEPENDENT-6-3：该怎么办；标注CONTEXT_DEPENDENT，预测HOSPITAL_INFO。
- CONTEXT_DEPENDENT-6-5：然后呢；标注CONTEXT_DEPENDENT，预测MEDICAL_MESSAGE。
- MEDICAL_MESSAGE-6-5：有皮疹应该找医生吗；标注MEDICAL_MESSAGE，预测BOOKING。
- OTHER-6-2：旅行有哪些建议；标注OTHER，预测HOSPITAL_INFO。
- OTHER-6-4：介绍一个城市；标注OTHER，预测CONTEXT_DEPENDENT。
- OTHER-6-5：周末想去散步；标注OTHER，预测MEDICAL_MESSAGE。

## 学习曲线与过拟合

|训练条数|训练宏F1|验证宏F1|
|---|---:|---:|
|30|1.000|0.448|
|60|1.000|0.431|
|90|1.000|0.473|
|120|1.000|0.476|

训练与验证差距反映合成表达覆盖不足；相似改写不能替代新的语义分组。

## 混淆矩阵

行是真实标签，列是预测标签。

|真实/预测|GREETING|HOSPITAL_INFO|BOOKING|CONTEXT_DEPENDENT|MEDICAL_MESSAGE|OTHER|
|---|---:|---:|---:|---:|---:|---:|
|GREETING|2|1|0|0|1|1|
|HOSPITAL_INFO|0|4|0|1|0|0|
|BOOKING|0|1|3|0|0|1|
|CONTEXT_DEPENDENT|0|1|0|1|3|0|
|MEDICAL_MESSAGE|0|0|1|0|4|0|
|OTHER|0|1|0|1|1|2|

## 提示与回退

验证集分数阈值0.25；测试非回退0/30，覆盖率0.000。接受部分准确率None。分数未校准，不能解释为正确概率。
健康关键词、低分、未知字符、空白或超长文本可以回退；action_authorized始终false。

## 复现与限制

运行 python experiment.py --output runs/reproduce。候选参数、分类指标、原始预测、挑战案例都保留。
单条CPU预测每方案300次，预热后顺序测量，不代表并发服务或云模型延迟。
数据SHA256：fdc02b61e88c9f0726b5dcb03bb95ca7c3c871e5ea8985c179e15686f1d43127。环境Python3.14.7 / sklearn1.9.1。
模型为运行产物，不入源码库。主项目接入前需独立标注复核、外部挑战、重复分组评估和业务兼容性验收。

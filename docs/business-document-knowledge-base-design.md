# 规章制度、讲话、统计报告与 Excel 数据的轻标签型 AI 知识库设计

## 1. 文档目标

本文档针对以下类型的数据资产，给出一套以轻量标签和结构化知识单元为基础的 AI 知识库设计方案。该基础层不要求知识图谱即可运行；当需要跨文档实体融合、状态演化、修订替代、责任链路等能力时，应叠加知识图谱增强层，参见 `docs/knowledge-graph-evolution-upgrade-plan.md`。

1. 规章制度
2. 讲话材料
3. 通知、纪要、报告
4. 统计公报、统计分析报告
5. Excel 数据表

该方案的核心思想是：

**文档底座 + 知识单元抽取 + 轻量标签体系 + 混合检索 + 结构化问答**

适合文档目录结构不稳定、篇幅差异大、业务问题导向明显的场景。

---

## 2. 问题特征

这类文档与学术论文、技术长文档不同，主要特征如下：

1. 目录结构不严格，不适合过度依赖章节树
2. 文档长短差异大，从一页通知到几十页报告都有
3. 用户关注点更偏业务规则、政策要求、统计口径和数据指标
4. Excel 本质上是结构化数据，不适合当普通文本处理
5. 文档常常存在版本变化、生效失效、口径调整、修订替代等问题

因此，这类知识库的基础目标不是一开始构建复杂图谱，而是先保证：

1. 保证原文可追溯
2. 把规则、事实、观点、数据抽取成稳定知识单元
3. 通过轻量标签和结构化字段提高检索和问答准确率
4. 为后续可选的实体关系、状态演化和规则链路图谱提供干净事实输入

---

## 3. 总体定位

基础系统定义为：

**Lightweight Tagged Knowledge Base for Business Documents**

系统设计原则：

1. 原始文档永远保留，作为最终证据源
2. 业务知识单元是核心检索对象，不是原始 chunk
3. 标签体系只做过滤、归类、聚合，不替代图谱推理
4. 问答优先走结构化知识单元，其次再回溯原文
5. 当启用知识图谱增强层时，知识单元应作为事实候选和证据来源，而不是直接把文件名、标题或标签提升为业务实体

---

## 4. 总体架构

系统采用四层结构：

1. 原始文档层
2. 知识单元层
3. 轻标签层
4. 检索与问答层

### 4.1 原始文档层

保存原始文件及其基础解析结果。

保存内容包括：

1. 原始文件
2. 抽取文本
3. 基础 chunk
4. 文档元数据

该层负责：

1. 提供可追溯证据
2. 作为后续抽取的输入

### 4.2 知识单元层

这是系统的核心层，将不同类型文档抽象为统一的最小可回答单元。

建议知识单元类型包括：

1. `rule`
2. `statement`
3. `fact`
4. `table_record`
5. `summary`

### 4.3 轻标签层

标签层只做轻量分类和过滤，不直接表达实体演化。需要表达实体关系、状态变化、修订替代和责任链路时，应进入图谱增强层。

标签建议包括：

1. 文种标签
2. 主题标签
3. 部门标签
4. 地区标签
5. 时间标签
6. 对象标签
7. 指标标签
8. 有效性标签

### 4.4 检索与问答层

由以下能力组成：

1. 全文检索
2. 向量检索
3. 结构化检索
4. 标签过滤
5. 答案生成与证据回溯

---

## 5. 技术选型

### 5.1 编程语言

首选：`Python`

原因：

1. 文档解析、信息抽取、Excel 处理和 RAG 编排生态成熟
2. 对接 `Docling`、`pandas`、`FastAPI`、`Elasticsearch` 成本低

### 5.2 推荐技术栈

1. API：`FastAPI`
2. 主数据库：`PostgreSQL`
3. 检索引擎：`Elasticsearch`
4. 对象存储：`MinIO`
5. 缓存与任务：`Redis`
6. 异步任务：`Celery` 或 `Arq`

### 5.3 文档解析工具

1. `Docling`：通用文档入口
2. `MinerU`：复杂 PDF 增强解析
3. `pandas/openpyxl`：Excel 读取与结构化处理

### 5.4 模型选型

1. Embedding：`BAAI/bge-m3`
2. Reranker：`BAAI/bge-reranker-v2-m3`
3. 抽取与归一化：`Qwen2.5-Instruct`
4. 图像理解：`Qwen2.5-VL`

云上替代可选：

1. `text-embedding-3-large`
2. `GPT-4.1 mini`

---

## 6. 核心设计思路

### 6.1 不以章节为核心，而以知识单元为核心

这类文档不适合只靠标题层级切块来回答问题，应该把文档抽取成稳定的知识单元。

### 6.2 标签是过滤器，不是推理引擎

标签层只负责：

1. 分类
2. 聚合
3. 过滤
4. 辅助召回

不承担复杂语义推理。

### 6.3 规则和数据优先结构化

规章制度和统计数据如果只保留原文，会导致大模型在回答时频繁“临场解释”，准确率不稳定。  
因此应优先把规则、条件、数值、时间、维度抽成结构化字段。

### 6.4 Excel 视为数据源，不视为普通文档

Excel 的重点不是文本块，而是：

1. 表结构
2. 维度字段
3. 指标字段
4. 时间字段
5. 口径说明

---

## 7. 文档类型处理策略

## 7.1 规章制度

重点抽取条款规则，不依赖全文语义猜测。

建议抽取字段：

1. 主题
2. 适用对象
3. 行为
4. 条件
5. 限制
6. 例外
7. 审批要求
8. 时限
9. 生效日期
10. 废止日期

示例：

```json
{
  "unit_type": "rule",
  "subject": "差旅报销",
  "action": "申请",
  "conditions": ["出差审批通过"],
  "constraints": ["住宿标准不超过500元/晚"],
  "exception": "特殊情况须经批准",
  "effective_date": "2025-01-01",
  "source_doc": "差旅管理办法"
}
```

## 7.2 讲话材料

重点抽取观点、要求、任务和方向。

建议抽取字段：

1. 讲话主题
2. 核心观点
3. 明确要求
4. 行动任务
5. 讲话人
6. 时间
7. 场景

示例：

```json
{
  "unit_type": "statement",
  "topic": "科技创新",
  "stance": "强化企业主体地位",
  "speaker": "某领导",
  "time": "2025-03-12",
  "source_doc": "在科技工作会议上的讲话"
}
```

## 7.3 统计报告

重点抽取指标、数值、维度和变化关系。

建议抽取字段：

1. 指标名
2. 数值
3. 单位
4. 时间
5. 维度
6. 同比
7. 环比
8. 结论性说明

示例：

```json
{
  "unit_type": "fact",
  "indicator": "地区生产总值",
  "value": 12830.5,
  "unit": "亿元",
  "time": "2024年",
  "dimension": {
    "region": "某市"
  },
  "source_doc": "2024统计公报"
}
```

## 7.4 Excel 数据

重点抽取表结构、维度、指标和口径。

处理步骤：

1. 按 sheet 建立对象
2. 识别表头和字段含义
3. 区分维度列和指标列
4. 建立时间字段
5. 生成行级或聚合级数据单元

示例：

```json
{
  "unit_type": "table_record",
  "table_name": "重点企业营收",
  "dimensions": {
    "region": "浦东新区",
    "industry": "制造业",
    "month": "2025-02"
  },
  "metrics": {
    "revenue": 32500000,
    "yoy": 0.12
  }
}
```

---

## 8. 知识单元设计

建议统一设计 `knowledge_units`，作为系统核心事实对象。

### 8.1 单元类型

1. `rule`
2. `statement`
3. `fact`
4. `table_record`
5. `summary`

### 8.2 通用字段

1. `unit_id`
2. `doc_id`
3. `unit_type`
4. `title`
5. `content`
6. `source_span`
7. `source_page`
8. `created_at`
9. `updated_at`

### 8.3 业务字段

按场景扩展：

1. `subject`
2. `action`
3. `organization`
4. `person`
5. `time_expr`
6. `region`
7. `indicator`
8. `value`
9. `unit`
10. `effective_date`
11. `expiry_date`
12. `status`
13. `tags`

---

## 9. 轻标签体系设计

标签体系必须简单、稳定、低维护。

### 9.1 一级标签建议

1. 文种标签
2. 主题标签
3. 部门标签
4. 地区标签
5. 时间标签
6. 对象标签
7. 指标标签
8. 有效性标签

### 9.2 具体示例

#### 文种标签

1. `规章制度`
2. `讲话`
3. `通知`
4. `统计报告`
5. `Excel数据`

#### 主题标签

1. `财务管理`
2. `人事管理`
3. `科技创新`
4. `产业发展`
5. `经济运行`

#### 有效性标签

1. `现行有效`
2. `已修订`
3. `已废止`
4. `历史参考`

### 9.3 标签来源

标签建议通过三种方式生成：

1. 规则抽取
2. LLM 归类
3. 人工维护少量词表

### 9.4 标签职责

标签只承担：

1. 快速过滤
2. 检索缩圈
3. 聚合展示
4. 提升召回精度

---

## 10. 数据存储设计

### 10.1 PostgreSQL 表设计

#### `documents`

存原始文档元数据：

```sql
id, title, doc_type, source_file, source_org, publish_date, effective_date,
expiry_date, status, metadata_json, created_at
```

#### `chunks`

存基础文本块：

```sql
id, doc_id, chunk_type, content, page_no, start_offset, end_offset, metadata_json
```

#### `knowledge_units`

存知识单元：

```sql
id, doc_id, unit_type, title, content, subject, action, organization, person,
time_expr, region, indicator, value_num, value_text, unit_name,
effective_date, expiry_date, status, source_span, source_page, tags_json
```

#### `table_sheets`

存 Excel sheet 元数据：

```sql
id, doc_id, sheet_name, description, time_field, dimension_fields, metric_fields
```

#### `table_records`

存行级或聚合级表数据：

```sql
id, doc_id, sheet_id, record_key, dimensions_json, metrics_json, source_row_no
```

#### `tag_definitions`

存标签定义：

```sql
id, tag_type, tag_name, description
```

#### `unit_tags`

存知识单元与标签关系：

```sql
unit_id, tag_id
```

### 10.2 Elasticsearch 索引设计

建议建立以下索引：

1. `document_chunks`
2. `knowledge_units`
3. `table_records`

索引字段建议包括：

1. `content`
2. `title`
3. `doc_type`
4. `tags`
5. `publish_date`
6. `effective_date`
7. `status`
8. `organization`
9. `region`
10. `indicator`
11. `dense_vector`

---

## 11. 检索设计

该类知识库推荐采用四路检索组合：

1. 全文检索
2. 向量检索
3. 结构化检索
4. 标签过滤

系统定义为：

**Hybrid Retrieval = BM25 + Dense Retrieval + Structured Query + Metadata Filter**

### 11.1 全文检索

适合处理：

1. 原文措辞查询
2. 条款定位
3. 讲话原话定位
4. 专有表达匹配

### 11.2 向量检索

适合处理：

1. 语义相近问法
2. 模糊概念查询
3. 综述式问题

### 11.3 结构化检索

适合处理：

1. 指标查询
2. 金额阈值查询
3. 时间范围查询
4. 地区和部门过滤
5. 生效失效判断

### 11.4 标签过滤

适合处理：

1. 仅看某部门制度
2. 仅看现行有效文件
3. 仅看统计报告
4. 仅看某地区数据

---

## 12. 问答模式设计

不同问题应走不同回答模式。

### 12.1 规则问答

问题示例：

“差旅住宿标准是多少？”

处理流程：

1. 命中 `rule` 单元
2. 提取约束、条件、例外
3. 返回结构化答案
4. 附原文出处

### 12.2 讲话总结

问题示例：

“某次会议讲话重点强调了什么？”

处理流程：

1. 命中 `statement` 单元
2. 聚合主题、观点、任务
3. 返回总结
4. 附讲话来源

### 12.3 数据问答

问题示例：

“2024年工业增加值同比多少？”

处理流程：

1. 先查 `fact` 或 `table_record`
2. 返回数值与维度
3. 必要时补充统计口径
4. 附来源报告

### 12.4 综合分析

问题示例：

“近三年本市创新政策和产业数据变化有什么关系？”

处理流程：

1. 检索政策类 `rule/statement`
2. 检索统计类 `fact/table_record`
3. 按时间聚合
4. 由 LLM 做跨来源归纳
5. 输出引用依据

---

## 13. 服务拆分设计

建议拆分为以下服务：

### 13.1 `ingest-service`

职责：

1. 文件上传
2. 文件分类
3. 创建解析任务

### 13.2 `parser-service`

职责：

1. 调用 `Docling`
2. 调用 `MinerU`
3. 调用 `pandas/openpyxl`
4. 生成统一中间表示

### 13.3 `extract-service`

职责：

1. 抽取 `rule / statement / fact / table_record`
2. 生成结构化字段
3. 生成标签

### 13.4 `index-service`

职责：

1. 生成 chunk
2. 生成 embedding
3. 写入 Elasticsearch
4. 维护 metadata filter 字段

### 13.5 `qa-service`

职责：

1. 问题分类
2. 检索路由
3. 结构化答案生成
4. 引用回溯

---

## 14. API 设计

### 14.1 上传文档

```http
POST /api/v1/documents/upload
```

### 14.2 查询文档

```http
GET /api/v1/documents/{doc_id}
```

### 14.3 查询知识单元

```http
GET /api/v1/knowledge-units
```

支持过滤条件：

1. `unit_type`
2. `doc_type`
3. `tags`
4. `organization`
5. `region`
6. `status`
7. `date_from`
8. `date_to`

### 14.4 问答接口

```http
POST /api/v1/qa/query
```

请求示例：

```json
{
  "query": "差旅住宿标准是多少？",
  "top_k": 10,
  "doc_types": ["规章制度"],
  "status": "现行有效"
}
```

响应示例：

```json
{
  "answer": "制度规定员工出差住宿费原则上不高于500元/晚，特殊情况须经批准。",
  "answer_type": "rule",
  "citations": [
    {
      "doc_id": "doc-1",
      "unit_id": "unit-3",
      "title": "差旅管理办法"
    }
  ]
}
```

---

## 15. 质量控制与评测

### 15.1 离线评测集

建议按四类问题构建评测集：

1. 规则问答
2. 讲话总结
3. 数据问答
4. 综合分析

### 15.2 核心指标

1. `Recall@K`
2. `MRR`
3. 引文准确率
4. 规则抽取准确率
5. 指标抽取准确率
6. 标签准确率
7. 幻觉率

### 15.3 抽检重点

1. 制度是否抽错生效失效状态
2. 条件和例外是否混淆
3. 数据单位是否抽错
4. Excel 维度字段是否错列
5. 讲话总结是否脱离原文

---

## 16. 实施路线图

### V1：可用版

目标：先完成稳定入库、基础抽取和三类核心问答

范围：

1. 文档统一接入
2. 规章、讲话、统计、Excel 四类文种分类
3. `knowledge_units` 抽取
4. 标签生成
5. BM25 + 向量检索
6. 规则问答、数据问答、讲话总结

### V2：增强版

目标：提升结构化检索和版本管理能力

范围：

1. 生效失效判断
2. 修订替代关系
3. 时间和地区过滤增强
4. 表格指标口径统一

### V3：分析版

目标：支持跨文档综合分析

范围：

1. 多文档时间线分析
2. 政策与数据联动分析
3. 统计趋势自动摘要
4. 专题简报自动生成

---

## 17. 推荐项目目录

```text
backend/
  app/
    api/
    core/
    models/
    schemas/
    services/
      ingest/
      parser/
      extract/
      index/
      qa/
    workers/
  scripts/
  tests/
deploy/
docs/
```

---

## 18. 最终推荐方案

如果对象是规章制度、讲话、统计报告和 Excel 数据，推荐第一版直接采用以下方案：

1. `Python + FastAPI`
2. `PostgreSQL + Elasticsearch + Redis + MinIO`
3. `Docling` 作为统一入口
4. `MinerU` 作为复杂 PDF 补充
5. `pandas/openpyxl` 专门处理 Excel
6. `BGE-M3 + bge-reranker-v2-m3 + Qwen2.5-Instruct`

核心方法：

1. 保留原文
2. 抽取知识单元
3. 建轻量标签
4. 混合检索
5. 结构化问答

一句话概括：

**这类知识库的核心不是图谱推理，而是把业务规则、事实数据和讲话观点稳定地抽成可检索、可过滤、可追溯的知识单元。**

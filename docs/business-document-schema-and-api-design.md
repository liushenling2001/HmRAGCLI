# 规章制度与统计类知识库的数据模型、抽取 Schema 与 API 细化设计

## 1. 文档目标

本文档在 `business-document-knowledge-base-design.md` 的基础上，继续细化以下内容：

1. PostgreSQL 数据表设计
2. Elasticsearch 索引字段设计
3. 知识单元抽取 Schema
4. 问答路由设计
5. API 请求与响应对象设计

目标是把“轻标签型业务知识库”推进到可直接开发的层面。

---

## 2. 设计边界

本设计适用于：

1. 规章制度
2. 讲话、通知、纪要
3. 统计公报、统计分析报告
4. Excel 数据表

本设计不包含：

1. 图数据库
2. 重图谱推理
3. 学术论文公式推理

---

## 3. 统一对象模型

系统内建议统一使用以下 5 类核心对象：

1. `Document`
2. `Chunk`
3. `KnowledgeUnit`
4. `Tag`
5. `TableRecord`

### 3.1 Document

表示原始文档及其元数据。

### 3.2 Chunk

表示原始文档解析后生成的基础文本块，用于原文检索与证据回溯。

### 3.3 KnowledgeUnit

表示从文档中抽取出的业务知识单元，是问答主对象。

### 3.4 Tag

表示轻量标签定义和映射关系。

### 3.5 TableRecord

表示 Excel 或表格数据中的结构化记录。

---

## 4. PostgreSQL 详细表结构

## 4.1 `documents`

存储原始文档信息。

建议字段：

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| title | varchar(500) | 文档标题 |
| doc_type | varchar(50) | 文档类型：rule/speech/report/excel/notice/minutes |
| source_file | varchar(1000) | 原始文件路径或对象存储 URI |
| source_filename | varchar(500) | 原始文件名 |
| source_org | varchar(255) | 来源单位 |
| author | varchar(255) | 作者或讲话人 |
| publish_date | date | 发布日期 |
| effective_date | date | 生效日期 |
| expiry_date | date | 失效日期 |
| status | varchar(50) | draft/effective/revised/expired/reference |
| language | varchar(20) | 语言 |
| file_hash | varchar(128) | 去重校验 |
| parse_status | varchar(50) | pending/processing/success/failed |
| metadata_json | jsonb | 其他元数据 |
| created_at | timestamp | 创建时间 |
| updated_at | timestamp | 更新时间 |

建议索引：

1. `idx_documents_doc_type`
2. `idx_documents_publish_date`
3. `idx_documents_status`
4. `idx_documents_source_org`
5. `uniq_documents_file_hash`

## 4.2 `chunks`

存储基础文本块。

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| doc_id | uuid | 文档 ID |
| chunk_no | int | 顺序号 |
| chunk_type | varchar(50) | text/title/table_caption/figure_caption/footer |
| title | varchar(500) | 所属标题 |
| content | text | 块内容 |
| page_no | int | 页码 |
| start_offset | int | 起始偏移 |
| end_offset | int | 结束偏移 |
| token_count | int | token 数 |
| metadata_json | jsonb | 其他定位信息 |
| created_at | timestamp | 创建时间 |

建议索引：

1. `idx_chunks_doc_id`
2. `idx_chunks_chunk_type`

## 4.3 `knowledge_units`

系统最核心的事实表。

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| doc_id | uuid | 文档 ID |
| chunk_id | uuid | 来源 chunk ID |
| unit_type | varchar(50) | rule/statement/fact/table_record/summary |
| title | varchar(500) | 单元标题 |
| content | text | 单元原始内容 |
| normalized_text | text | 归一化描述 |
| subject | varchar(255) | 主题 |
| action | varchar(255) | 行为或动作 |
| organization | varchar(255) | 机构 |
| person | varchar(255) | 人物 |
| region | varchar(255) | 地区 |
| time_expr | varchar(255) | 原始时间表达 |
| event_date | date | 归一化日期 |
| indicator | varchar(255) | 指标名 |
| value_num | numeric(20,6) | 数值型值 |
| value_text | varchar(255) | 文本型值 |
| unit_name | varchar(100) | 单位 |
| effective_date | date | 生效日期 |
| expiry_date | date | 失效日期 |
| status | varchar(50) | effective/revised/expired/reference |
| priority | int | 重要级 |
| confidence | numeric(4,3) | 抽取置信度 |
| source_span | varchar(255) | 原文定位 |
| source_page | int | 来源页码 |
| fields_json | jsonb | 扩展业务字段 |
| created_at | timestamp | 创建时间 |
| updated_at | timestamp | 更新时间 |

建议索引：

1. `idx_ku_doc_id`
2. `idx_ku_unit_type`
3. `idx_ku_subject`
4. `idx_ku_indicator`
5. `idx_ku_region`
6. `idx_ku_event_date`
7. `idx_ku_status`

## 4.4 `knowledge_unit_relations`

用于维护轻量关系，而非图谱。

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| from_unit_id | uuid | 起点单元 |
| to_unit_id | uuid | 终点单元 |
| relation_type | varchar(50) | revised_from/replaces/refers_to/same_topic/same_indicator |
| relation_note | varchar(500) | 说明 |
| confidence | numeric(4,3) | 置信度 |
| created_at | timestamp | 创建时间 |

## 4.5 `tag_definitions`

标签定义表。

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| tag_type | varchar(50) | doc_type/topic/org/region/time/object/indicator/status |
| tag_name | varchar(100) | 标签名 |
| tag_code | varchar(100) | 标签编码 |
| description | varchar(500) | 描述 |
| is_active | boolean | 是否启用 |
| created_at | timestamp | 创建时间 |

## 4.6 `document_tags`

文档标签映射。

| 字段名 | 类型 | 说明 |
|---|---|---|
| doc_id | uuid | 文档 ID |
| tag_id | uuid | 标签 ID |

## 4.7 `knowledge_unit_tags`

知识单元标签映射。

| 字段名 | 类型 | 说明 |
|---|---|---|
| unit_id | uuid | 单元 ID |
| tag_id | uuid | 标签 ID |

## 4.8 `table_sheets`

Excel sheet 描述表。

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| doc_id | uuid | 文档 ID |
| sheet_name | varchar(255) | Sheet 名称 |
| description | text | Sheet 描述 |
| header_row_no | int | 表头行号 |
| time_field | varchar(255) | 时间字段 |
| dimension_fields | jsonb | 维度字段列表 |
| metric_fields | jsonb | 指标字段列表 |
| metadata_json | jsonb | 扩展信息 |
| created_at | timestamp | 创建时间 |

## 4.9 `table_records`

结构化数据记录表。

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| doc_id | uuid | 文档 ID |
| sheet_id | uuid | sheet ID |
| record_key | varchar(255) | 业务主键 |
| time_value | varchar(100) | 时间值 |
| region | varchar(255) | 地区 |
| organization | varchar(255) | 机构 |
| dimensions_json | jsonb | 维度字段 |
| metrics_json | jsonb | 指标字段 |
| source_row_no | int | 原始行号 |
| confidence | numeric(4,3) | 抽取置信度 |
| created_at | timestamp | 创建时间 |

建议索引：

1. `idx_table_records_doc_id`
2. `idx_table_records_sheet_id`
3. `idx_table_records_region`
4. `idx_table_records_time_value`

## 4.10 `ingest_jobs`

用于追踪入库流程。

| 字段名 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键 |
| doc_id | uuid | 文档 ID |
| job_type | varchar(50) | parse/extract/index |
| status | varchar(50) | pending/running/success/failed |
| error_message | text | 错误信息 |
| started_at | timestamp | 开始时间 |
| finished_at | timestamp | 完成时间 |

---

## 5. Elasticsearch 索引设计

建议建立 3 个主要索引：

1. `document_chunks`
2. `knowledge_units`
3. `table_records`

## 5.1 `document_chunks` 索引字段

| 字段名 | 类型 |
|---|---|
| id | keyword |
| doc_id | keyword |
| doc_type | keyword |
| title | text + keyword |
| content | text |
| source_org | keyword |
| publish_date | date |
| status | keyword |
| tags | keyword |
| dense_vector | dense_vector |

## 5.2 `knowledge_units` 索引字段

| 字段名 | 类型 |
|---|---|
| id | keyword |
| doc_id | keyword |
| unit_type | keyword |
| title | text + keyword |
| content | text |
| normalized_text | text |
| subject | keyword |
| action | keyword |
| organization | keyword |
| person | keyword |
| region | keyword |
| indicator | keyword |
| value_num | double |
| unit_name | keyword |
| event_date | date |
| effective_date | date |
| expiry_date | date |
| status | keyword |
| tags | keyword |
| dense_vector | dense_vector |

## 5.3 `table_records` 索引字段

| 字段名 | 类型 |
|---|---|
| id | keyword |
| doc_id | keyword |
| sheet_id | keyword |
| record_key | keyword |
| time_value | keyword |
| region | keyword |
| organization | keyword |
| dimensions_json | flattened |
| metrics_json | flattened |
| tags | keyword |

---

## 6. 知识单元抽取 Schema

抽取阶段建议先做文种分类，再分类型抽取。

## 6.1 文档分类 Schema

```json
{
  "doc_type": "rule|speech|report|excel|notice|minutes",
  "title": "文档标题",
  "source_org": "来源单位",
  "publish_date": "2025-03-01",
  "effective_date": "2025-04-01",
  "expiry_date": null,
  "status": "effective",
  "topics": ["财务管理", "差旅报销"],
  "regions": ["上海"],
  "organizations": ["某单位"]
}
```

## 6.2 规则类 `rule` 抽取 Schema

```json
{
  "unit_type": "rule",
  "title": "住宿费标准",
  "content": "员工出差住宿费原则上不高于500元/晚，特殊情况须经批准。",
  "normalized_text": "出差住宿标准一般不超过500元/晚，特殊情况需审批。",
  "subject": "差旅报销",
  "action": "住宿报销",
  "organization": "某单位",
  "person": null,
  "region": null,
  "time_expr": null,
  "event_date": null,
  "indicator": null,
  "value_num": 500,
  "value_text": null,
  "unit_name": "元/晚",
  "effective_date": "2025-01-01",
  "expiry_date": null,
  "status": "effective",
  "fields_json": {
    "conditions": ["出差审批通过"],
    "constraints": ["住宿标准不超过500元/晚"],
    "exceptions": ["特殊情况须经批准"],
    "approval_required": true,
    "applicable_objects": ["员工"]
  },
  "tags": ["规章制度", "财务管理", "现行有效"]
}
```

## 6.3 讲话类 `statement` 抽取 Schema

```json
{
  "unit_type": "statement",
  "title": "强化企业主体地位",
  "content": "要强化企业在科技创新中的主体地位。",
  "normalized_text": "讲话强调要强化企业主体地位，推动科技创新。",
  "subject": "科技创新",
  "action": "强调",
  "organization": "某部门",
  "person": "某领导",
  "region": null,
  "time_expr": "2025年3月12日",
  "event_date": "2025-03-12",
  "status": "reference",
  "fields_json": {
    "stance": "强化企业主体地位",
    "tasks": ["推动科技成果转化", "提升企业创新能力"],
    "speech_scene": "科技工作会议"
  },
  "tags": ["讲话", "科技创新"]
}
```

## 6.4 统计事实类 `fact` 抽取 Schema

```json
{
  "unit_type": "fact",
  "title": "2024年地区生产总值",
  "content": "2024年全市地区生产总值达到12830.5亿元，同比增长5.6%。",
  "normalized_text": "2024年全市GDP为12830.5亿元，同比增长5.6%。",
  "subject": "经济运行",
  "indicator": "地区生产总值",
  "value_num": 12830.5,
  "unit_name": "亿元",
  "time_expr": "2024年",
  "event_date": "2024-12-31",
  "region": "某市",
  "fields_json": {
    "yoy": 5.6,
    "yoy_unit": "%",
    "mom": null,
    "dimensions": {
      "region": "某市"
    },
    "statistical_scope": "全市"
  },
  "tags": ["统计报告", "经济运行", "某市", "2024年"]
}
```

## 6.5 Excel 行记录 `table_record` 抽取 Schema

```json
{
  "unit_type": "table_record",
  "title": "重点企业营收记录",
  "content": "浦东新区 制造业 2025-02 营收3250万元 同比12%",
  "normalized_text": "2025年2月浦东新区制造业重点企业营收3250万元，同比增长12%。",
  "subject": "企业营收",
  "indicator": "营收",
  "value_num": 3250,
  "unit_name": "万元",
  "region": "浦东新区",
  "time_expr": "2025-02",
  "fields_json": {
    "dimensions": {
      "region": "浦东新区",
      "industry": "制造业",
      "month": "2025-02"
    },
    "metrics": {
      "revenue": 3250,
      "yoy": 12
    }
  },
  "tags": ["Excel数据", "营收", "浦东新区", "制造业"]
}
```

## 6.6 抽取原则

1. 同一段文本可以抽取多个知识单元
2. 原文事实和模型归纳要分开存
3. 所有抽取结果必须保留 `source_span`
4. 对数值、单位、时间必须做归一化
5. 对低置信度结果允许进入待复核队列

---

## 7. 标签生成规则

标签生成建议采用“规则优先，模型补充”的策略。

## 7.1 规则优先标签

适合通过字段直接确定：

1. 文种标签
2. 时间标签
3. 地区标签
4. 有效性标签
5. 指标标签

## 7.2 模型补充标签

适合通过 LLM 分类：

1. 主题标签
2. 对象标签
3. 讲话风格或任务类型标签

## 7.3 标签控制策略

1. 一级标签数量控制在 8 类以内
2. 每个单元标签数建议不超过 10 个
3. 同义标签必须做规范化
4. 禁止自由扩散出大量近义重复标签

---

## 8. 问答路由设计

系统在问答前先做问题分类，决定检索路径。

## 8.1 问题分类

建议分为以下类型：

1. `rule_qa`
2. `speech_summary`
3. `data_qa`
4. `document_lookup`
5. `comparative_analysis`

## 8.2 路由规则

### `rule_qa`

触发词示例：

1. “规定”
2. “要求”
3. “标准”
4. “可以吗”
5. “怎么办”
6. “条件是什么”

检索优先级：

1. `knowledge_units(unit_type=rule)`
2. 标签过滤
3. 原文 chunk 回溯

### `speech_summary`

触发词示例：

1. “强调了什么”
2. “讲话重点”
3. “主要要求”
4. “提出了哪些任务”

检索优先级：

1. `knowledge_units(unit_type=statement)`
2. 讲话类文档过滤
3. 原文聚合摘要

### `data_qa`

触发词示例：

1. “多少”
2. “同比”
3. “环比”
4. “增长率”
5. “某年某地数据”

检索优先级：

1. `table_records`
2. `knowledge_units(unit_type=fact)`
3. 原始统计报告

### `document_lookup`

触发词示例：

1. “哪个文件提到”
2. “原文怎么说”
3. “出处是什么”

检索优先级：

1. `document_chunks`
2. `knowledge_units`

### `comparative_analysis`

触发词示例：

1. “近几年”
2. “变化”
3. “对比”
4. “趋势”
5. “关系”

检索优先级：

1. `knowledge_units`
2. `table_records`
3. 多时间点聚合
4. LLM 归纳总结

---

## 9. 回答生成对象设计

建议统一生成 `AnswerContext`：

```json
{
  "query_type": "rule_qa",
  "question": "差旅住宿标准是多少？",
  "hits": [
    {
      "unit_id": "u1",
      "unit_type": "rule",
      "title": "住宿费标准",
      "normalized_text": "出差住宿标准一般不超过500元/晚，特殊情况需审批。",
      "score": 0.92
    }
  ],
  "citations": [
    {
      "doc_id": "d1",
      "chunk_id": "c1",
      "source_span": "第12条",
      "page_no": 3
    }
  ]
}
```

生成规则：

1. 规则问答返回“规则 + 条件 + 例外 + 来源”
2. 数据问答返回“指标 + 数值 + 时间 + 维度 + 来源”
3. 讲话总结返回“主题 + 观点 + 任务 + 来源”
4. 综合分析返回“时间线归纳 + 证据列表”

---

## 10. API 对象设计

## 10.1 上传文档

### 请求

`POST /api/v1/documents/upload`

表单字段：

1. `file`
2. `doc_type` 可选
3. `source_org` 可选
4. `publish_date` 可选

### 响应

```json
{
  "doc_id": "uuid",
  "status": "pending"
}
```

## 10.2 查询知识单元

### 请求

`GET /api/v1/knowledge-units`

支持参数：

1. `unit_type`
2. `doc_type`
3. `keyword`
4. `tags`
5. `organization`
6. `region`
7. `indicator`
8. `status`
9. `date_from`
10. `date_to`
11. `page`
12. `page_size`

### 响应

```json
{
  "items": [
    {
      "id": "u1",
      "unit_type": "rule",
      "title": "住宿费标准",
      "normalized_text": "出差住宿标准一般不超过500元/晚，特殊情况需审批。",
      "tags": ["规章制度", "财务管理", "现行有效"]
    }
  ],
  "total": 1
}
```

## 10.3 查询 Excel 结构化数据

### 请求

`GET /api/v1/table-records`

支持参数：

1. `sheet_name`
2. `region`
3. `organization`
4. `time_value`
5. `indicator`
6. `page`
7. `page_size`

### 响应

```json
{
  "items": [
    {
      "id": "r1",
      "record_key": "浦东新区|制造业|2025-02",
      "dimensions": {
        "region": "浦东新区",
        "industry": "制造业",
        "month": "2025-02"
      },
      "metrics": {
        "revenue": 3250,
        "yoy": 12
      }
    }
  ],
  "total": 1
}
```

## 10.4 问答接口

### 请求

`POST /api/v1/qa/query`

```json
{
  "query": "差旅住宿标准是多少？",
  "doc_types": ["rule"],
  "status": "effective",
  "top_k": 10
}
```

### 响应

```json
{
  "query_type": "rule_qa",
  "answer": "制度规定员工出差住宿费原则上不高于500元/晚，特殊情况须经批准。",
  "structured_answer": {
    "subject": "差旅报销",
    "constraint": "不高于500元/晚",
    "exception": "特殊情况须经批准"
  },
  "citations": [
    {
      "doc_id": "d1",
      "unit_id": "u1",
      "title": "差旅管理办法",
      "source_span": "第12条"
    }
  ]
}
```

---

## 11. 开发优先级建议

推荐按以下顺序开发：

### 第一阶段

1. 文档上传与解析
2. 基础文本 chunk
3. 文种识别
4. `knowledge_units` 抽取
5. Elasticsearch 检索
6. 规则问答、数据问答、讲话总结

### 第二阶段

1. 标签体系完善
2. Excel 结构化增强
3. 生效失效状态识别
4. 修订替代关系维护

### 第三阶段

1. 多文档综合分析
2. 趋势归纳
3. 专题报告生成

---

## 12. 实施建议

如果立即启动开发，建议先只做以下 3 个高价值闭环：

1. 规章制度规则问答
2. 统计数据查询问答
3. 讲话材料摘要问答

原因：

1. 这三类最容易形成稳定的知识单元
2. 最能体现结构化抽取的价值
3. 最适合尽快形成可验证效果

一句话概括：

**这套系统的关键，不是把所有文档变成“大模型上下文”，而是把业务规则、观点和数据沉淀为稳定、可过滤、可追溯的知识单元。**

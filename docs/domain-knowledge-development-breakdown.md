# 领域知识编译系统开发任务拆解清单

## 1. 文档目标

本文档用于把领域知识编译系统拆解成实际开发任务，方便按阶段排期与分工。

任务拆解按以下工作流分组：

1. 数据库与对象层
2. 后端接口层
3. 编译执行层
4. 自动维护层
5. 智能体消费层
6. 前端页面层

---

## 2. 第一阶段：领域定义基础能力

### 2.1 数据库任务

1. 新增 `domain_definitions`
2. 新增 `topic_definitions`
3. 增加基础索引
4. 编写对应 Flyway migration

### 2.2 后端任务

1. 新增 `DomainDefinition` 实体
2. 新增 `TopicDefinition` 实体
3. 新增对应 Repository
4. 新增 `DomainDefinitionService`
5. 新增 `TopicDefinitionService`
6. 新增 DTO：
   `CreateDomainDefinitionRequest`
   `UpdateDomainDefinitionRequest`
   `CreateTopicDefinitionRequest`
   `UpdateTopicDefinitionRequest`
7. 新增 Controller：
   `DomainDefinitionController`
   `TopicDefinitionController`

### 2.3 前端任务

1. 新增领域列表页
2. 新增领域创建/编辑表单
3. 新增专题树页面
4. 新增专题创建/编辑交互

### 2.4 验收标准

1. 用户可在前端创建领域
2. 用户可维护专题树
3. 后端可持久化这些对象

---

## 3. 第二阶段：人工编译任务基础能力

### 3.1 数据库任务

1. 新增 `domain_refine_jobs`
2. 新增基础状态与索引

### 3.2 后端任务

1. 新增 `DomainRefineJob` 实体
2. 新增 `DomainRefineJobRepository`
3. 新增 `DomainRefineJobService`
4. 新增 DTO：
   `StartDomainRefineRequest`
   `StartTopicRefineRequest`
   `DomainRefineJobDto`
5. 新增 `DomainRefineController`
6. 支持手工创建任务
7. 支持任务状态查询
8. 支持任务取消

### 3.3 前端任务

1. 在领域详情页增加 `立即编译领域`
2. 在专题页增加 `立即编译专题`
3. 新增编译任务列表页
4. 新增编译任务详情页

### 3.4 验收标准

1. 用户可从前端手工发起领域或专题编译任务
2. 任务状态可被查询和展示

---

## 4. 第三阶段：人工编译产物落库

### 4.1 数据库任务

1. 新增 `evidence_packs`
2. 新增 `domain_briefs`
3. 新增 `topic_dossiers`

### 4.2 后端任务

1. 新增对应实体与 Repository
2. 新增 `DomainCompilationService`
3. 实现基础编译流程：
   - 解析领域/专题范围
   - 收集证据
   - 生成领域总览
   - 生成专题知识页
   - 绑定证据包
4. 新增读取服务：
   `DomainKnowledgeReadService`
5. 新增查询接口：
   `GET /domains/{id}/brief`
   `GET /domains/{id}/memory-pack`
   `GET /topics/{id}/dossier`

### 4.3 前端任务

1. 新增知识查看页
2. 展示领域总览
3. 展示专题知识页
4. 展示证据包入口

### 4.4 验收标准

1. 编译任务成功后可生成领域总览和专题页
2. 用户能在前端查看结果

---

## 5. 第四阶段：证据回查能力

### 5.1 数据模型任务

1. 定义统一 `EvidenceRef`
2. 定义 `quote / context / section` 三种展开模式

### 5.2 后端任务

1. 新增 `EvidenceExpansionService`
2. 新增 `POST /api/v1/evidence/expand`
3. 支持从 `documents / chunks / knowledge_units` 拉取正文
4. 支持上下文窗口展开
5. 支持章节级展开

### 5.3 前端任务

1. 在知识查看页中为每条结论增加：
   - `查看引用`
   - `展开上下文`
   - `展开章节`
2. 新增侧边抽屉或弹窗展示正文

### 5.4 验收标准

1. 领域知识页中的结论可回查原始正文
2. 用户或智能体可拿到写作所需上下文

---

## 6. 第五阶段：结构化结论与冲突对象

### 6.1 数据库任务

1. 新增 `claim_sets`
2. 新增 `conflict_sets`

### 6.2 后端任务

1. 新增对应实体与 Repository
2. 在 `DomainCompilationService` 中增加：
   - 结论结构化产出
   - 冲突集合产出
3. 新增读取接口：
   - `GET /topics/{id}/claims`
   - `GET /topics/{id}/conflicts`

### 6.3 前端任务

1. 知识查看页支持结构化视图
2. 展示结论列表
3. 展示冲突项列表

### 6.4 验收标准

1. 专题页不再只是摘要文本
2. 智能体可读取结构化结论和冲突信息

---

## 7. 第六阶段：智能体消费优化

### 7.1 后端任务

1. 固化 `DomainMemoryPack` 结构
2. 增加 `retrieval_hooks`
3. 增加给智能体的 `llm_context_summary`
4. 优化 memory pack 体积

### 7.2 智能体侧任务

1. 设计新的知识消费 skill
2. 先读取领域知识包
3. 再按需回查证据
4. 再做写作或分析

### 7.3 验收标准

1. 智能体不再主要依赖原始碎片检索
2. 智能体可用领域知识包完成分析与写作

---

## 8. 第七阶段：自动维护任务

### 8.1 数据库与任务任务

1. 复用 `domain_refine_jobs`
2. 标记自动任务类型：
   `auto_incremental`
   `auto_full`

### 8.2 后端任务

1. 新增 `DomainAutoMaintenanceService`
2. 新增定时任务入口
3. 自动识别受影响领域
4. 自动识别受影响专题
5. 自动创建维护任务

### 8.3 前端任务

1. 领域详情页支持自动维护配置
2. 编译任务页区分人工和自动任务
3. 展示最近一次自动维护结果

### 8.4 验收标准

1. 已定义领域可以被自动维护
2. 自动维护结果不会覆盖人工高质量成果

---

## 9. 技术风险与控制点

## 9.1 风险：编译任务过重

控制建议：

1. 初期只做人工任务
2. 先服务少量高价值领域
3. 编译对象按领域和专题拆分

## 9.2 风险：证据收集质量不足

控制建议：

1. 初期先复用现有检索
2. 对重要领域增加种子检索词和范围规则
3. 后续再优化专用证据收集策略

## 9.3 风险：自动维护造成知识漂移

控制建议：

1. 自动任务只服务已定义领域
2. 自动结果默认不是 authoritative
3. 重要结果保留人工复核入口

## 9.4 风险：前端复杂度过高

控制建议：

1. 先实现领域与专题的基础管理
2. 再接任务
3. 最后接知识查看与证据回查

---

## 10. 推荐开发顺序

综合建议的落地顺序：

1. 领域定义与专题树
2. 人工编译任务对象
3. 人工编译产物对象
4. 证据回查
5. 结构化结论与冲突
6. 智能体消费优化
7. 自动维护

---

## 11. 可按角色分工的任务包

### 11.1 后端基础包

1. DDL migration
2. Entity / Repository
3. DTO / Controller
4. 基础 CRUD

### 11.2 编译引擎包

1. 编译任务执行器
2. 证据收集器
3. 领域总览生成器
4. 专题页生成器
5. 证据绑定器

### 11.3 前端包

1. 领域管理页面
2. 专题树页面
3. 编译任务页面
4. 知识查看页面
5. 正文回查交互

### 11.4 智能体集成包

1. memory pack 消费协议
2. evidence expand 消费协议
3. 知识型 skill 设计

---

## 12. 最终结论

这套系统建议以“人工定义 + 人工编译优先 + 证据回查 + 智能体消费 + 自动维护补充”的顺序落地。

这样做的好处是：

1. 风险可控
2. 可逐步验证价值
3. 不会破坏当前主流程
4. 能为智能体建立真正可用的领域知识层


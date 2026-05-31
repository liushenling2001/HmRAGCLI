-- HmRAGCLI knowledge graph PostgreSQL one-shot upgrade script.
-- Use this in offline environments when Flyway migrations V17-V21 are not run automatically.
-- Safe to run more than once: DDL uses IF NOT EXISTS and templates use ON CONFLICT DO UPDATE.

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS graph_build_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_file_id UUID NOT NULL REFERENCES source_files(id) ON DELETE CASCADE,
    doc_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'queued',
    stage VARCHAR(80) NOT NULL DEFAULT 'queued',
    trigger_source VARCHAR(50) NOT NULL DEFAULT 'ingest',
    graph_store VARCHAR(50) NOT NULL DEFAULT 'neo4j-http',
    graph_version VARCHAR(100),
    extract_model VARCHAR(200),
    fusion_model VARCHAR(200),
    local_graph_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    heartbeat_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE graph_build_jobs
    ADD COLUMN IF NOT EXISTS graph_batch_id UUID;

CREATE INDEX IF NOT EXISTS idx_graph_build_jobs_status_created_at
    ON graph_build_jobs(status, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_graph_build_jobs_source_file_id
    ON graph_build_jobs(source_file_id);
CREATE INDEX IF NOT EXISTS idx_graph_build_jobs_doc_id
    ON graph_build_jobs(doc_id);
CREATE INDEX IF NOT EXISTS idx_graph_build_jobs_graph_batch_id
    ON graph_build_jobs(graph_batch_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_graph_build_jobs_source_file_active
    ON graph_build_jobs(source_file_id)
    WHERE status IN ('queued', 'running');

CREATE TABLE IF NOT EXISTS graph_sync_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_file_id UUID NOT NULL UNIQUE REFERENCES source_files(id) ON DELETE CASCADE,
    doc_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    graph_store VARCHAR(50) NOT NULL DEFAULT 'neo4j-http',
    graph_version VARCHAR(100),
    graph_document_key VARCHAR(200),
    content_fingerprint VARCHAR(128),
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    last_build_job_id UUID REFERENCES graph_build_jobs(id) ON DELETE SET NULL,
    last_built_at TIMESTAMPTZ,
    error_message TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_graph_sync_state_doc_id
    ON graph_sync_state(doc_id);
CREATE INDEX IF NOT EXISTS idx_graph_sync_state_status
    ON graph_sync_state(status);

CREATE TABLE IF NOT EXISTS graph_fusion_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    graph_batch_id UUID,
    status VARCHAR(50) NOT NULL DEFAULT 'queued',
    stage VARCHAR(80) NOT NULL DEFAULT 'queued',
    trigger_source VARCHAR(50) NOT NULL DEFAULT 'manual',
    fusion_mode VARCHAR(80) NOT NULL DEFAULT 'deterministic',
    output_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_graph_fusion_jobs_status_created_at
    ON graph_fusion_jobs(status, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_graph_fusion_jobs_graph_batch_id
    ON graph_fusion_jobs(graph_batch_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_graph_fusion_jobs_batch_active
    ON graph_fusion_jobs(graph_batch_id)
    WHERE graph_batch_id IS NOT NULL AND status IN ('queued', 'running');

CREATE TABLE IF NOT EXISTS graph_extraction_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    source_file_id UUID NOT NULL REFERENCES source_files(id) ON DELETE CASCADE,
    graph_version VARCHAR(100) NOT NULL,
    extraction_profile VARCHAR(80) NOT NULL DEFAULT 'default',
    batch_no INT NOT NULL,
    batch_count INT NOT NULL DEFAULT 0,
    batch_key VARCHAR(128) NOT NULL UNIQUE,
    input_hash VARCHAR(128) NOT NULL,
    chunk_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    attempt_count INT NOT NULL DEFAULT 0,
    entity_mentions INT NOT NULL DEFAULT 0,
    facts INT NOT NULL DEFAULT 0,
    relations INT NOT NULL DEFAULT 0,
    lease_token VARCHAR(120),
    lease_until TIMESTAMPTZ,
    error_message TEXT,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_graph_extraction_batches_doc_status
    ON graph_extraction_batches(doc_id, status, batch_no);
CREATE INDEX IF NOT EXISTS idx_graph_extraction_batches_source_file
    ON graph_extraction_batches(source_file_id, batch_no);

CREATE TABLE IF NOT EXISTS graph_extraction_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES graph_extraction_batches(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'running',
    raw_request TEXT,
    raw_response TEXT,
    parsed_output_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_graph_extraction_attempts_batch_created
    ON graph_extraction_attempts(batch_id, created_at DESC);

CREATE TABLE IF NOT EXISTS graph_candidate_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES graph_extraction_batches(id) ON DELETE CASCADE,
    attempt_id UUID REFERENCES graph_extraction_attempts(id) ON DELETE SET NULL,
    candidate_key VARCHAR(260) NOT NULL UNIQUE,
    candidate_type VARCHAR(40) NOT NULL,
    stable_local_id VARCHAR(500),
    payload_hash VARCHAR(128) NOT NULL,
    payload_json JSONB NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_graph_candidate_records_batch_type
    ON graph_candidate_records(batch_id, candidate_type, status);

ALTER TABLE graph_build_jobs
    ADD COLUMN IF NOT EXISTS extraction_depth VARCHAR(40) NOT NULL DEFAULT 'skeleton',
    ADD COLUMN IF NOT EXISTS scope_type VARCHAR(40) NOT NULL DEFAULT 'document',
    ADD COLUMN IF NOT EXISTS scope_key VARCHAR(260);

ALTER TABLE graph_extraction_batches
    ADD COLUMN IF NOT EXISTS extraction_depth VARCHAR(40) NOT NULL DEFAULT 'skeleton',
    ADD COLUMN IF NOT EXISTS scope_type VARCHAR(40) NOT NULL DEFAULT 'document',
    ADD COLUMN IF NOT EXISTS scope_key VARCHAR(260);

ALTER TABLE graph_candidate_records
    ADD COLUMN IF NOT EXISTS extraction_depth VARCHAR(40) NOT NULL DEFAULT 'skeleton',
    ADD COLUMN IF NOT EXISTS scope_type VARCHAR(40) NOT NULL DEFAULT 'document',
    ADD COLUMN IF NOT EXISTS scope_key VARCHAR(260);

CREATE INDEX IF NOT EXISTS idx_graph_build_jobs_depth_scope
    ON graph_build_jobs(extraction_depth, scope_type, scope_key);
CREATE INDEX IF NOT EXISTS idx_graph_extraction_batches_depth_scope
    ON graph_extraction_batches(doc_id, extraction_depth, scope_type, scope_key, status);
CREATE INDEX IF NOT EXISTS idx_graph_candidate_records_depth_scope
    ON graph_candidate_records(extraction_depth, scope_type, scope_key, status);

CREATE TABLE IF NOT EXISTS graph_runtime_settings (
    setting_key VARCHAR(120) PRIMARY KEY,
    setting_value VARCHAR(200) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO graph_runtime_settings (setting_key, setting_value, description)
VALUES (
    'auto_build_after_index',
    'false',
    'Whether graph skeleton build jobs are enqueued automatically after indexing finishes.'
)
ON CONFLICT (setting_key) DO NOTHING;

CREATE TABLE IF NOT EXISTS graph_entity_type_templates (
    code VARCHAR(80) PRIMARY KEY,
    label VARCHAR(120) NOT NULL,
    description TEXT,
    aliases_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    attribute_hints_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    relation_hints_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    state_attribute_hints_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    transition_hints_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    anti_patterns_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO graph_entity_type_templates (
    code, label, description, aliases_json, attribute_hints_json, relation_hints_json,
    state_attribute_hints_json, transition_hints_json, anti_patterns_json, enabled, sort_order
) VALUES
    ('Person', '人物', '自然人、作者、负责人、专家、学生、用户等。', '["人员","老师","负责人","专家","学生","用户","讲话人","作者"]'::jsonb, '["姓名","职务","职称","角色","所属单位","联系方式"]'::jsonb, '["任职于","负责","参与","指导","提出","发表","管理"]'::jsonb, '["任职阶段","角色变化","职责变化"]'::jsonb, '["调任","任免","更名","角色调整"]'::jsonb, '[]'::jsonb, TRUE, 10),
    ('Organization', '组织/单位', '高校、学院、部门、企业、政府机构、团队等。', '["单位","机构","部门","高校","学院","团队","企业","处室","中心"]'::jsonb, '["名称","简称","类型","所在地","职责","级别"]'::jsonb, '["上级单位","下属单位","负责","参与","发布","建设","管理","服务"]'::jsonb, '["职责范围","隶属关系","名称","组织结构"]'::jsonb, '["合并","拆分","更名","调整","撤销","设立"]'::jsonb, '[]'::jsonb, TRUE, 20),
    ('Location', '地点/区域', '国家、地区、城市、校区、场所等空间对象。', '["地区","区域","地点","城市","校区","场所"]'::jsonb, '["名称","类型","行政级别","地址","范围"]'::jsonb, '["位于","覆盖","隶属于","服务于"]'::jsonb, '["范围变化","名称变化"]'::jsonb, '["调整","迁移","更名"]'::jsonb, '[]'::jsonb, TRUE, 30),
    ('Time', '时间/时期', '年份、日期、阶段、周期等时间表达。通常作为属性值，只有明确作为业务阶段对象时才建实体。', '["时间","日期","年份","阶段","周期","时期"]'::jsonb, '["起始时间","结束时间","持续时间","周期"]'::jsonb, '["发生于","生效于","截至"]'::jsonb, '["有效期","阶段"]'::jsonb, '["延期","提前","结束"]'::jsonb, '["孤立日期","页眉日期"]'::jsonb, TRUE, 40),
    ('Event', '事件/活动', '会议、发布、评审、建设活动、培训、竞赛、事故等。', '["事件","活动","会议","培训","评审","发布","竞赛"]'::jsonb, '["名称","时间","地点","主题","结果"]'::jsonb, '["主办单位","参与人","涉及对象","产生结果"]'::jsonb, '["进展","结果变化"]'::jsonb, '["启动","完成","延期","取消"]'::jsonb, '[]'::jsonb, TRUE, 50),
    ('Document', '文档/文件', '正式文件、报告、论文、制度文本、通知等文档对象。来源文件名默认不是业务实体。', '["文件","文档","报告","通知","论文","材料"]'::jsonb, '["标题","文号","作者","发布日期","版本","摘要"]'::jsonb, '["发布单位","引用","依据","说明对象"]'::jsonb, '["版本","修订日期","有效性"]'::jsonb, '["修订","废止","替代","重新发布"]'::jsonb, '["导入文件名","扫描编号","路径"]'::jsonb, TRUE, 60),
    ('Policy', '规章制度', '政策、制度、办法、标准、规范、条款等规则性对象。', '["制度","办法","规定","规程","条例","细则","规范","政策"]'::jsonb, '["名称","文号","发布日期","生效日期","修订日期","适用范围","状态"]'::jsonb, '["发布单位","适用对象","约束事项","依据","替代制度"]'::jsonb, '["版本","有效期","条款变化","适用范围"]'::jsonb, '["修订","废止","替代","重新发布","解释"]'::jsonb, '[]'::jsonb, TRUE, 70),
    ('Standard', '标准/规范', '技术标准、业务规范、指标标准、评价标准等。', '["标准","规范","准则","指南"]'::jsonb, '["编号","名称","版本","发布日期","适用范围"]'::jsonb, '["规定","约束","引用","适用于"]'::jsonb, '["版本","有效期","条款变化"]'::jsonb, '["修订","替代","废止"]'::jsonb, '[]'::jsonb, TRUE, 80),
    ('Clause', '条款', '制度、标准、合同、方案中的具体条款或要求。', '["条款","章节","第几条","要求项"]'::jsonb, '["编号","内容","适用范围","约束级别"]'::jsonb, '["属于制度","约束对象","要求","引用"]'::jsonb, '["内容变化","有效性"]'::jsonb, '["修订","废止","新增","删除"]'::jsonb, '[]'::jsonb, TRUE, 90),
    ('Project', '项目', '建设项目、科研项目、工程项目、课题、专项等。', '["项目","课题","工程","专项","建设任务","科研项目"]'::jsonb, '["名称","编号","立项时间","建设主题","周期","状态","经费"]'::jsonb, '["承担单位","参与单位","负责人","建设系统","支撑对象","产生成果"]'::jsonb, '["阶段","周期","建设主题","目标变化","应用范围"]'::jsonb, '["升级","延期","结题","变更","转化"]'::jsonb, '["文件名","导入编号"]'::jsonb, TRUE, 100),
    ('System', '系统/平台', '业务系统、信息系统、平台、工具、软件等。', '["系统","平台","信息系统","管理系统","软件","工具"]'::jsonb, '["名称","简称","版本","建设时间","功能","状态","架构"]'::jsonb, '["建设单位","应用单位","支撑业务","依赖技术","属于项目","服务用户"]'::jsonb, '["版本","阶段","有效时间","应用范围","能力变化"]'::jsonb, '["升级","改版","替代","迁移","更名","扩展"]'::jsonb, '["文件名","导入编号","扫描编号"]'::jsonb, TRUE, 110),
    ('Product', '产品/成果物', '产品、工具包、平台成果、交付物等。', '["产品","成果物","交付物","工具包"]'::jsonb, '["名称","版本","形态","发布日期","功能"]'::jsonb, '["来源项目","应用于","交付给","依赖技术"]'::jsonb, '["版本","能力变化","适用范围"]'::jsonb, '["升级","发布","替代","下线"]'::jsonb, '[]'::jsonb, TRUE, 120),
    ('Algorithm', '算法/模型', '算法、模型、方法、技术路线。', '["算法","模型","方法","技术路线","识别模型"]'::jsonb, '["名称","版本","输入","输出","性能指标","参数"]'::jsonb, '["应用于","依赖数据","支撑系统","解决问题","产出结果"]'::jsonb, '["版本","适用范围","性能变化"]'::jsonb, '["升级","替换","优化","迭代"]'::jsonb, '[]'::jsonb, TRUE, 130),
    ('Technology', '技术/方法', '技术框架、技术能力、方法论、工具技术。', '["技术","方法","框架","能力","技术方案"]'::jsonb, '["名称","类型","特点","适用场景"]'::jsonb, '["支撑","依赖","应用于","解决"]'::jsonb, '["版本","能力变化"]'::jsonb, '["升级","替代","优化"]'::jsonb, '[]'::jsonb, TRUE, 140),
    ('Dataset', '数据集/数据资源', '数据集、数据库、数据资源、样本集、指标库。', '["数据集","数据库","数据资源","样本","指标库"]'::jsonb, '["名称","规模","来源","时间范围","字段","质量"]'::jsonb, '["来源于","用于训练","支撑系统","包含数据项"]'::jsonb, '["版本","规模变化","字段变化"]'::jsonb, '["更新","扩充","迁移","归档"]'::jsonb, '[]'::jsonb, TRUE, 150),
    ('DataElement', '数据项/字段', '字段、数据元素、表单项、元数据项。', '["字段","数据项","数据元素","表单项","元数据"]'::jsonb, '["名称","类型","口径","取值范围","来源"]'::jsonb, '["属于数据集","用于指标","映射到"]'::jsonb, '["口径变化","字段变化"]'::jsonb, '["新增","删除","重命名","调整"]'::jsonb, '[]'::jsonb, TRUE, 160),
    ('Indicator', '指标', '评价指标、统计指标、监测指标、KPI等。', '["指标","评价指标","统计指标","KPI","监测指标"]'::jsonb, '["名称","口径","单位","计算方法","阈值"]'::jsonb, '["衡量对象","来源数据","用于评价","影响"]'::jsonb, '["口径变化","阈值变化"]'::jsonb, '["调整","替代","废止"]'::jsonb, '[]'::jsonb, TRUE, 170),
    ('MetricValue', '指标值/数值结果', '具体数值、比例、得分、金额、数量。通常作为属性值，只有需要追踪序列时建实体。', '["数值","指标值","得分","金额","比例","数量"]'::jsonb, '["值","单位","时间","口径","来源"]'::jsonb, '["属于指标","度量对象","发生于"]'::jsonb, '["值变化","口径变化"]'::jsonb, '["上升","下降","调整"]'::jsonb, '["孤立数字","页码"]'::jsonb, TRUE, 180),
    ('Process', '流程/环节', '业务流程、审批流程、工作环节、处理步骤。', '["流程","环节","步骤","程序","审批流程"]'::jsonb, '["名称","顺序","输入","输出","时限"]'::jsonb, '["前置环节","后置环节","负责单位","产生材料"]'::jsonb, '["流程版本","职责变化"]'::jsonb, '["优化","调整","合并","取消"]'::jsonb, '[]'::jsonb, TRUE, 190),
    ('Workflow', '工作流', '系统或业务中的自动/人工工作流。', '["工作流","流程编排","办理流"]'::jsonb, '["名称","触发条件","节点","状态","时限"]'::jsonb, '["包含环节","触发任务","依赖系统"]'::jsonb, '["节点变化","规则变化"]'::jsonb, '["调整","优化","重构"]'::jsonb, '[]'::jsonb, TRUE, 200),
    ('Task', '任务/工作', '工作任务、建设任务、行动项、待办事项。', '["任务","工作","行动项","建设任务","重点工作"]'::jsonb, '["名称","目标","期限","状态","优先级"]'::jsonb, '["负责人","责任单位","支撑项目","产出成果"]'::jsonb, '["状态","进度","目标变化"]'::jsonb, '["启动","完成","延期","取消","调整"]'::jsonb, '[]'::jsonb, TRUE, 210),
    ('Requirement', '要求/约束', '政策要求、业务要求、技术约束、合规约束。', '["要求","约束","条件","规范要求","合规要求"]'::jsonb, '["内容","强制性","适用范围","期限"]'::jsonb, '["约束对象","来源制度","由谁负责","满足条件"]'::jsonb, '["要求变化","适用范围变化"]'::jsonb, '["新增","修订","废止"]'::jsonb, '[]'::jsonb, TRUE, 220),
    ('Resource', '资源/材料', '材料、经费、设备、人力、课程资源、数据资源等。', '["资源","材料","经费","设备","人力","课程资源"]'::jsonb, '["名称","类型","数量","规格","来源"]'::jsonb, '["属于项目","用于任务","提供单位","消耗于"]'::jsonb, '["数量变化","状态变化"]'::jsonb, '["调拨","归还","更新","报废"]'::jsonb, '[]'::jsonb, TRUE, 230),
    ('Role', '角色/岗位', '业务角色、系统角色、岗位职责。', '["角色","岗位","职责","用户角色"]'::jsonb, '["名称","权限","职责","范围"]'::jsonb, '["由人员担任","属于单位","负责流程","拥有权限"]'::jsonb, '["权限变化","职责变化"]'::jsonb, '["调整","新增","取消"]'::jsonb, '[]'::jsonb, TRUE, 240),
    ('Application', '应用场景/应用对象', '应用领域、业务场景、服务对象、使用场景。', '["应用场景","应用对象","业务场景","服务对象","使用场景"]'::jsonb, '["名称","范围","对象","目标"]'::jsonb, '["应用系统","使用算法","服务单位","支撑业务"]'::jsonb, '["范围变化","对象变化"]'::jsonb, '["扩展","迁移","替代"]'::jsonb, '[]'::jsonb, TRUE, 250),
    ('Risk', '风险/问题', '风险、问题、缺陷、痛点、挑战。', '["风险","问题","缺陷","痛点","挑战"]'::jsonb, '["名称","等级","原因","影响","状态"]'::jsonb, '["影响对象","产生原因","应对措施","责任单位"]'::jsonb, '["等级变化","处理状态"]'::jsonb, '["缓解","解决","升级","关闭"]'::jsonb, '[]'::jsonb, TRUE, 260),
    ('Outcome', '成果/结果', '建设成果、研究成果、评价结果、产出物。', '["成果","结果","产出","成效","建设成果","研究成果"]'::jsonb, '["名称","类型","完成时间","等级","数量"]'::jsonb, '["来源项目","完成人","应用于","支撑对象"]'::jsonb, '["应用范围","转化状态"]'::jsonb, '["转化","推广","升级","归档"]'::jsonb, '[]'::jsonb, TRUE, 270),
    ('Achievement', '科研/教学成果', '论文、专利、奖项、软件著作权、教学成果等。', '["成果","论文","专利","奖项","软著","教学成果"]'::jsonb, '["名称","类型","获奖等级","发表时间","授权号"]'::jsonb, '["完成人","所属单位","来源项目","应用领域"]'::jsonb, '["转化状态","应用范围"]'::jsonb, '["获奖","授权","转化","推广"]'::jsonb, '[]'::jsonb, TRUE, 280),
    ('Thesis', '学位论文', '学位论文、毕业论文、论文成果。', '["学位论文","毕业论文","论文"]'::jsonb, '["题名","作者","导师","学位类型","答辩时间","关键词"]'::jsonb, '["作者","导师","所属学科","来源项目","关联成果"]'::jsonb, '["评审状态","归档状态"]'::jsonb, '["提交","评审","答辩","归档"]'::jsonb, '["文件名编号"]'::jsonb, TRUE, 290),
    ('DegreeProgram', '学位点/培养项目', '学科、专业、学位点、培养项目。', '["学位点","专业","学科","培养项目"]'::jsonb, '["名称","层次","代码","负责人","建设周期"]'::jsonb, '["所属学院","支撑课程","关联论文","培养学生"]'::jsonb, '["建设阶段","评估状态"]'::jsonb, '["新增","撤销","调整","升级"]'::jsonb, '[]'::jsonb, TRUE, 300),
    ('Service', '服务/能力', '系统能力、业务服务、公共服务、接口服务。', '["服务","能力","接口服务","功能服务"]'::jsonb, '["名称","功能","对象","可用状态","接口"]'::jsonb, '["由系统提供","服务对象","依赖数据","支撑流程"]'::jsonb, '["能力变化","服务范围"]'::jsonb, '["上线","下线","升级","迁移"]'::jsonb, '[]'::jsonb, TRUE, 310),
    ('Interface', '接口/API', '系统接口、数据接口、服务API。', '["接口","API","数据接口","服务接口"]'::jsonb, '["名称","协议","地址","参数","返回值","权限"]'::jsonb, '["属于系统","调用系统","提供数据","依赖服务"]'::jsonb, '["版本","参数变化","权限变化"]'::jsonb, '["新增","废弃","升级","迁移"]'::jsonb, '[]'::jsonb, TRUE, 320),
    ('Concept', '概念/主题', '主题、术语、领域概念、抽象对象。', '["概念","主题","术语","方向","领域"]'::jsonb, '["名称","定义","范围","分类"]'::jsonb, '["属于领域","包含","关联","支撑"]'::jsonb, '["定义变化","范围变化"]'::jsonb, '["扩展","细化","替代"]'::jsonb, '["泛泛标题词"]'::jsonb, TRUE, 330),
    ('Other', '其他', '暂时无法稳定归类的业务实体。', '["其他","未分类"]'::jsonb, '["名称","描述"]'::jsonb, '["相关"]'::jsonb, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, TRUE, 999)
ON CONFLICT (code) DO UPDATE SET
    label = EXCLUDED.label,
    description = EXCLUDED.description,
    aliases_json = EXCLUDED.aliases_json,
    attribute_hints_json = EXCLUDED.attribute_hints_json,
    relation_hints_json = EXCLUDED.relation_hints_json,
    state_attribute_hints_json = EXCLUDED.state_attribute_hints_json,
    transition_hints_json = EXCLUDED.transition_hints_json,
    anti_patterns_json = EXCLUDED.anti_patterns_json,
    enabled = EXCLUDED.enabled,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

COMMIT;

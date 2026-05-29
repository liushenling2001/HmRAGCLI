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
    state_attribute_hints_json, transition_hints_json, anti_patterns_json, sort_order
) VALUES
    ('Person', '人物', '自然人、作者、负责人、专家等。', '["人员","老师","负责人","专家"]'::jsonb,
     '["姓名","职务","职称","角色"]'::jsonb, '["任职于","负责","参与","指导"]'::jsonb,
     '["任职阶段","角色变化"]'::jsonb, '["调任","更名"]'::jsonb, '[]'::jsonb, 10),
    ('Organization', '组织/单位', '高校、学院、部门、企业、政府机构等。', '["单位","机构","部门","高校","学院"]'::jsonb,
     '["名称","简称","类型","所在地"]'::jsonb, '["上级单位","下属单位","负责","参与","发布"]'::jsonb,
     '["职责范围","隶属关系","名称"]'::jsonb, '["合并","拆分","更名","调整"]'::jsonb, '[]'::jsonb, 20),
    ('System', '系统/平台', '业务系统、信息系统、平台、工具。', '["平台","信息系统","管理系统","工具"]'::jsonb,
     '["名称","简称","版本","建设时间","功能","状态"]'::jsonb, '["建设单位","应用单位","支撑业务","依赖技术","属于项目"]'::jsonb,
     '["版本","阶段","有效时间","应用范围","能力变化"]'::jsonb, '["升级","改版","替代","迁移","更名"]'::jsonb,
     '["文件名","导入编号","扫描编号"]'::jsonb, 30),
    ('Project', '项目', '建设项目、科研项目、工程项目、课题等。', '["课题","工程","建设项目","科研项目"]'::jsonb,
     '["名称","编号","立项时间","建设主题","周期","状态"]'::jsonb, '["承担单位","参与单位","负责人","建设系统","支撑对象"]'::jsonb,
     '["阶段","周期","建设主题","目标变化"]'::jsonb, '["升级","延期","结题","变更"]'::jsonb,
     '["文件名","导入编号"]'::jsonb, 40),
    ('Policy', '规章制度', '政策、制度、办法、标准、条款等。', '["制度","办法","规定","标准","条款"]'::jsonb,
     '["名称","文号","发布日期","生效日期","修订日期","适用范围"]'::jsonb, '["发布单位","适用对象","约束事项","替代制度"]'::jsonb,
     '["版本","有效期","条款变化"]'::jsonb, '["修订","废止","替代","重新发布"]'::jsonb, '[]'::jsonb, 50),
    ('Algorithm', '算法/模型', '算法、模型、方法、技术路线。', '["模型","方法","技术路线"]'::jsonb,
     '["名称","版本","输入","输出","性能指标"]'::jsonb, '["应用于","依赖数据","支撑系统","解决问题"]'::jsonb,
     '["版本","适用范围","性能变化"]'::jsonb, '["升级","替换","优化"]'::jsonb, '[]'::jsonb, 60)
ON CONFLICT (code) DO NOTHING;

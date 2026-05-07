# 运维面板信息架构

## 1. 页面目标

运维面板用于回答四类问题：

1. 系统当前是否健康
2. 数据源是否正常扫描和入库
3. 最近任务执行情况如何
4. 是否存在失败文件、转换失败或重复处理问题

## 2. 页面结构

建议按单页 Dashboard 设计，顶部到下方分为 5 个区域：

1. 全局状态条
2. KPI 指标区
3. 数据源卡片区
4. 最近任务区
5. 失败文件区

---

## 3. 区域设计

## 3.1 全局状态条

位置：页面顶部

用途：

1. 展示系统整体运行状态
2. 展示当前环境模式
3. 提供常用运维入口

建议信息：

1. 系统名称：`HmRAGCLI Ops`
2. 环境状态：`Healthy / Warning / Failed`
3. 解析模式：`light / auto / enhanced`
4. 增强后端：`none / docling`
5. 关键组件状态：
   - database
   - ai
   - embedding
   - libreoffice
   - parser

建议动作：

1. 查看系统健康检查
2. 触发数据源扫描
3. 触发批量入库
4. 查看失败文件

对应接口：

1. `GET /api/v1/system/health`

---

## 3.2 KPI 指标区

位置：顶部状态条下方

建议使用 8 到 10 个卡片，按优先级分两排。

### 第一排

1. 数据源总数
2. 文件总数
3. 成功文件数
4. 失败文件数
5. 待处理文件数

### 第二排

1. 文档总数
2. 业务文档数
3. 开发文档数
4. Chunk 总数
5. Knowledge Unit 总数

可选卡片：

1. 精确重复文件数
2. 疑似重复文件数

对应字段：

1. `overview.total_data_sources`
2. `overview.total_files`
3. `overview.success_files`
4. `overview.failed_files`
5. `overview.pending_files`
6. `overview.total_documents`
7. `overview.business_documents`
8. `overview.development_documents`
9. `overview.total_chunks`
10. `overview.total_knowledge_units`
11. `overview.exact_duplicate_files`
12. `overview.possible_duplicate_files`

对应接口：

1. `GET /api/v1/operations/dashboard`

---

## 3.3 数据源卡片区

位置：KPI 下方

用途：

1. 快速了解每个数据源的运行情况
2. 识别哪个目录积压最多
3. 识别哪个数据源失败最多

建议每个数据源卡片显示：

1. 数据源名称
2. 数据源类型
3. 根目录路径
4. 总文件数
5. 待处理文件数
6. 处理中数
7. 成功文件数
8. 失败文件数
9. 精确重复文件数
10. 最近扫描时间
11. 最近入库时间

卡片动作：

1. 查看该数据源文件列表
2. 触发扫描
3. 触发入库
4. 查看失败文件

对应字段：

1. `data_sources[].id`
2. `data_sources[].source_name`
3. `data_sources[].source_type`
4. `data_sources[].root_path`
5. `data_sources[].status`
6. `data_sources[].total_files`
7. `data_sources[].pending_files`
8. `data_sources[].processing_files`
9. `data_sources[].success_files`
10. `data_sources[].failed_files`
11. `data_sources[].exact_duplicate_files`
12. `data_sources[].last_scan_at`
13. `data_sources[].last_ingest_at`

对应接口：

1. `GET /api/v1/operations/dashboard`

---

## 3.4 最近任务区

位置：数据源卡片区下方，建议左侧

用途：

1. 展示最近扫描和入库的时间线
2. 快速定位哪个任务失败或卡住
3. 支持按任务类型筛选

建议表格列：

1. 任务类型
2. 数据源名称
3. 状态
4. 总文件数
5. 成功数
6. 失败数
7. 新文件数
8. 变化文件数
9. 开始时间
10. 结束时间

筛选建议：

1. `job_kind = scan / ingest`
2. `status = pending / running / success / partial_failed / failed`
3. `data_source_id`

对应字段：

1. `recent_jobs[]`
2. 或 `GET /api/v1/operations/jobs`

对应接口：

1. `GET /api/v1/operations/dashboard`
2. `GET /api/v1/operations/jobs`

---

## 3.5 失败文件区

位置：数据源卡片区下方，建议右侧或下方整宽

用途：

1. 看当前失败文件
2. 区分是 `.doc` 转换失败、解析失败还是抽取失败
3. 提供单文件修复入口

建议表格列：

1. 文件名
2. 数据源名称
3. 文件类型
4. 当前阶段
5. `parse_status`
6. `extract_status`
7. `retry_count`
8. 错误信息摘要
9. 更新时间

建议筛选：

1. `data_source_id`
2. `file_ext`
3. `parse_status`

重点状态标识：

1. `conversion_failed`
2. `enhanced_parser_not_ready`
3. `failed`

可支持的操作按钮：

1. 重新入库
2. 重新抽取
3. 重新向量化
4. 查看文档详情

对应字段：

1. `recent_failures[]`
2. 或 `GET /api/v1/operations/failures`

对应接口：

1. `GET /api/v1/operations/dashboard`
2. `GET /api/v1/operations/failures`
3. `POST /api/v1/data-sources/files/{source_file_id}/reingest`
4. `POST /api/v1/data-sources/files/{source_file_id}/reextract`
5. `POST /api/v1/data-sources/files/{source_file_id}/reembed`

---

## 4. 推荐页面布局

## 4.1 桌面端

建议布局：

1. 顶部：全局状态条
2. 第二行：5 个 KPI 卡片
3. 第三行：5 个 KPI 卡片
4. 第四行：数据源卡片横向列表或两列网格
5. 第五行：左侧最近任务，右侧失败文件

## 4.2 移动端

建议布局：

1. 顶部状态条折叠
2. KPI 改为两列卡片
3. 数据源卡片整列显示
4. 最近任务与失败文件改为 Tab 切换

---

## 5. API 映射

## 5.1 页面初始化

建议首屏调用：

1. `GET /api/v1/system/health`
2. `GET /api/v1/operations/dashboard`

## 5.2 查看全部任务

1. `GET /api/v1/operations/jobs?page=1&page_size=20`
2. `GET /api/v1/operations/jobs?job_kind=ingest&status=success`

## 5.3 查看失败文件

1. `GET /api/v1/operations/failures?page=1&page_size=20`
2. `GET /api/v1/operations/failures?file_ext=.doc&parse_status=conversion_failed`

## 5.4 查看某数据源文件

1. `GET /api/v1/data-sources/{data_source_id}/files`
2. `GET /api/v1/data-sources/{data_source_id}/files?parse_status=conversion_failed`
3. `GET /api/v1/data-sources/{data_source_id}/files?ingest_status=success&file_ext=.doc`

---

## 6. Figma 页面建议

如果进入 Figma 设计阶段，建议至少做 3 个 Frame：

1. `Ops Dashboard / Desktop`
2. `Ops Dashboard / Mobile`
3. `Ops Dashboard / Failure Detail`

建议组件：

1. KPI Card
2. Status Pill
3. Data Source Card
4. Job Table
5. Failure Table
6. Filter Bar
7. Empty State

---

## 7. 视觉建议

运维面板不建议做得过于花哨，重点是：

1. 高密度信息
2. 清晰状态色
3. 快速筛选
4. 明确的失败原因

颜色建议：

1. 正常：绿色
2. 处理中：蓝色
3. 警告：橙色
4. 失败：红色
5. 开发文档域：中性色


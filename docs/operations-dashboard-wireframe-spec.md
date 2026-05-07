# 运维面板线框规格

## 1. 页面名称

`HmRAGCLI Operations Dashboard`

## 2. 页面目标

面向系统管理员与运维人员，提供：

1. 系统运行健康状态
2. 数据源接入状态
3. 扫描与入库任务状态
4. 失败文件处置入口

## 3. 页面层级

建议拆成 4 个主视图：

1. Dashboard 首页
2. Jobs 全量任务页
3. Failures 失败文件页
4. Data Source Detail 数据源详情页

---

## 4. Dashboard 首页线框

## 4.1 顶部导航

包含：

1. 产品名 `HmRAGCLI Ops`
2. 当前环境标签
3. 最后刷新时间
4. 全局操作按钮

按钮建议：

1. `刷新状态`
2. `环境检查`
3. `开始扫描`
4. `开始入库`

## 4.2 全局状态带

用 5 个状态 Pill 展示：

1. Database
2. AI
3. Embedding
4. LibreOffice
5. Parser

每个状态 Pill 显示：

1. 名称
2. 当前状态
3. 简短明细

## 4.3 KPI 卡片区

建议 10 个卡片，两行布局。

第一行：

1. 数据源总数
2. 文件总数
3. 成功文件
4. 失败文件
5. 待处理文件

第二行：

1. 文档总数
2. 业务文档数
3. 开发文档数
4. Chunk 总数
5. Knowledge Unit 总数

## 4.4 数据源卡片区

建议卡片布局为 2 列或 3 列。

每个卡片包含：

1. 数据源名称
2. 根目录
3. 状态标签
4. 总文件数
5. 待处理数
6. 成功数
7. 失败数
8. 重复文件数
9. 最近扫描时间
10. 最近入库时间

卡片操作：

1. 查看文件
2. 扫描
3. 入库
4. 查看失败

## 4.5 最近任务区

建议位于首页中下方左侧。

列建议：

1. 任务类型
2. 数据源
3. 状态
4. 总文件
5. 成功/失败
6. 开始时间
7. 结束时间

顶部筛选：

1. 任务类型
2. 状态
3. 数据源

## 4.6 最近失败文件区

建议位于首页中下方右侧。

列建议：

1. 文件名
2. 数据源
3. 文件类型
4. 阶段
5. parse_status
6. retry_count
7. 更新时间

行内操作：

1. 重入库
2. 重抽取
3. 重建向量
4. 查看详情

---

## 5. Jobs 页线框

## 5.1 顶部筛选栏

1. Job Kind
2. Status
3. Data Source
4. 时间范围

## 5.2 任务表格

列建议：

1. 任务 ID
2. 类型
3. 数据源
4. 状态
5. 总文件
6. 新文件
7. 变化文件
8. 成功文件
9. 失败文件
10. 开始时间
11. 完成时间

## 5.3 右侧抽屉

点击任务后显示：

1. 任务摘要
2. 状态时间线
3. 可跳转的数据源
4. 可跳转的失败文件列表

---

## 6. Failures 页线框

## 6.1 筛选栏

1. 数据源
2. 文件扩展名
3. parse_status
4. ingest_status
5. 更新时间范围

## 6.2 失败文件表格

列建议：

1. 文件名
2. 数据源
3. 文件扩展名
4. 当前阶段
5. parse_status
6. extract_status
7. index_status
8. retry_count
9. 错误摘要
10. 更新时间

## 6.3 底部批量操作区

建议支持：

1. 仅重试选中文件
2. 导出失败清单
3. 按失败类型分组

---

## 7. Data Source Detail 页线框

## 7.1 数据源头部

显示：

1. 数据源名称
2. 根目录
3. include_patterns
4. exclude_patterns
5. 最近扫描时间
6. 最近入库时间

## 7.2 文件状态统计条

1. Total
2. Pending
3. Processing
4. Success
5. Failed
6. Exact Duplicate

## 7.3 文件列表

列建议：

1. 文件名
2. 扩展名
3. ingest_status
4. processing_stage
5. parse_status
6. extract_status
7. retry_count
8. 更新时间

支持筛选：

1. parse_status
2. ingest_status
3. file_ext
4. is_exact_duplicate

---

## 8. 组件建议

建议在 Figma 中先定义这几类组件：

1. `StatusPill`
2. `KpiCard`
3. `DataSourceCard`
4. `OpsTable`
5. `FilterBar`
6. `ActionButton`
7. `EmptyState`
8. `ErrorBadge`

---

## 9. 视觉风格建议

风格建议偏：

1. 工具化
2. 信息密度高
3. 轻量但专业

颜色策略：

1. 正常：绿色
2. 处理中：蓝色
3. 待处理：灰蓝
4. 警告：橙色
5. 失败：红色

## 10. 前端建议的数据装配顺序

首页初始化建议：

1. `GET /api/v1/system/health`
2. `GET /api/v1/operations/dashboard`

二级页建议：

1. Jobs 页：
   - `GET /api/v1/operations/jobs`
2. Failures 页：
   - `GET /api/v1/operations/failures`
3. Data Source Detail：
   - `GET /api/v1/data-sources/{id}`
   - `GET /api/v1/data-sources/{id}/files`


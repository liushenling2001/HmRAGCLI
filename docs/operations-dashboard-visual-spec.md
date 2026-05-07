# 运维面板视觉规范建议

## 1. 设计目标

运维面板的视觉目标不是品牌展示，而是：

1. 高信息密度
2. 状态清晰
3. 操作路径短
4. 错误易识别

整体建议偏：

```text
企业控制台 / 运维中台 / 数据平台
```

避免：

1. 过强装饰
2. 大面积情绪化渐变
3. 过多无意义阴影
4. 高彩度背景

---

## 2. 视觉方向

建议视觉方向：

1. 浅色底
2. 中性色主体
3. 状态色明确
4. 表格和卡片边界清晰

关键词：

```text
Precise / Dense / Operational / Structured
```

---

## 3. 色彩系统

## 3.1 中性色

建议基础色：

```text
Background: #F5F7FA
Panel: #FFFFFF
Panel Alt: #FAFBFC
Border: #D9E0E7
Text Primary: #18212B
Text Secondary: #5C6B7A
Text Tertiary: #8A98A8
```

## 3.2 状态色

### Success

```text
Base: #1F8F5F
Bg: #EAF7F0
Border: #BFE8D0
```

### Processing

```text
Base: #1D6FD6
Bg: #EAF2FF
Border: #BED7FF
```

### Pending

```text
Base: #7A8695
Bg: #F2F4F7
Border: #D8DEE6
```

### Warning

```text
Base: #C98316
Bg: #FFF5E7
Border: #F3D6A5
```

### Error

```text
Base: #D64545
Bg: #FDECEC
Border: #F5C2C2
```

---

## 4. 字体系统

建议使用：

```text
中文：思源黑体 / Noto Sans SC
英文与数字：IBM Plex Sans / Inter / Source Sans
```

如果必须统一：

```text
Noto Sans SC
```

层级建议：

### Page Title

```text
28 / 36 / Semibold
```

### Section Title

```text
18 / 26 / Semibold
```

### Card Title

```text
14 / 20 / Medium
```

### KPI Value

```text
30 / 36 / Semibold
```

### Body

```text
14 / 22 / Regular
```

### Caption

```text
12 / 18 / Regular
```

---

## 5. 间距系统

建议采用 4 的倍数。

基础单位：

```text
4 / 8 / 12 / 16 / 24 / 32
```

建议使用：

1. 卡片内边距：`16`
2. 卡片间距：`16`
3. 大区块间距：`24`
4. 页面边距：`32`

---

## 6. 圆角与边框

建议：

1. 大卡片圆角：`12`
2. 小组件圆角：`8`
3. 状态标签圆角：`999`
4. 边框：`1px solid #D9E0E7`

阴影建议轻量：

```text
0 1 2 rgba(16, 24, 40, 0.04)
0 4 12 rgba(16, 24, 40, 0.06)
```

---

## 7. 组件视觉建议

## 7.1 KPI Card

结构：

1. 小标题
2. 主数字
3. 底部辅助说明

建议：

1. 主数字左对齐
2. 可加极小状态标记
3. 不要加多余图表

## 7.2 Status Pill

结构：

1. 状态点
2. 标签文字

示例：

```text
Database / OK
Parser / Warning
LibreOffice / OK
```

## 7.3 Data Source Card

建议突出：

1. 数据源名
2. 状态统计条
3. 快捷操作

弱化：

1. 根路径长文本
2. 次要说明

## 7.4 Table

建议：

1. 表头固定浅灰底
2. 行 hover 高亮
3. 状态列使用 tag
4. 错误列支持截断和 tooltip

---

## 8. 图标建议

建议图标语义：

1. 数据源：folder / database
2. 扫描任务：scan / refresh
3. 入库任务：upload / layers
4. 失败文件：triangle-alert
5. 重试：rotate-cw
6. 详情：panel-right-open

图标风格建议统一线性、16 或 18 像素。

---

## 9. 状态展示规则

### 顶部全局健康

1. 全部 OK：绿色
2. 任一失败：红色
3. 无失败但有待处理或未就绪：橙色

### 文件级状态

建议优先显示：

1. `conversion_failed`
2. `enhanced_parser_not_ready`
3. `failed`
4. `processing`
5. `success`

### 文档域标签

1. `business`：中性蓝灰
2. `development`：灰色

---

## 10. Figma 组件 Token 建议

建议在 Figma 中先建立这些变量或样式：

### Colors

1. `bg/base`
2. `bg/panel`
3. `border/base`
4. `text/primary`
5. `text/secondary`
6. `status/success`
7. `status/warning`
8. `status/error`
9. `status/info`
10. `status/pending`

### Radius

1. `radius/sm = 8`
2. `radius/md = 12`
3. `radius/pill = 999`

### Spacing

1. `space/4`
2. `space/8`
3. `space/12`
4. `space/16`
5. `space/24`
6. `space/32`

### Typography

1. `type/page-title`
2. `type/section-title`
3. `type/card-title`
4. `type/kpi-value`
5. `type/body`
6. `type/caption`

---

## 11. 推荐的首版视觉优先级

如果只先做一版 MVP 视觉，优先完成：

1. 顶部状态条
2. KPI 卡片
3. 数据源卡片
4. 任务表格
5. 失败文件表格

其余复杂图表先不做。


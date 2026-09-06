---
name: excalidraw
description: 生成 Excalidraw 手绘风格图表。支持流程图、思维导图、层级图、关系图、对比图、时间线、矩阵图。触发词:Excalidraw、画图、流程图、思维导图、手绘图、diagram。
---

# Excalidraw 图表生成器

从文本内容生成 Excalidraw 手绘风格图表，支持两种输出模式。

## 输出模式

| 触发词 | 模式 | 文件格式 | 用途 |
|--------|------|----------|------|
| `Excalidraw`、`画图`、`流程图`、`思维导图`、`手绘图` | **Standard**（默认） | `.excalidraw` | 在 excalidraw.com 打开/编辑/分享 |
| `Excalidraw动画`、`动画图`、`animate` | **Animated** | `.excalidraw` | 拖到 excalidraw-animate 生成动画 |

## 工作流程

1. **分析内容** — 识别概念、关系、层级
2. **选择图表类型** — 参考下方图表类型表
3. **生成 Excalidraw JSON** — Animated 模式需添加 `customData.animate` 字段
4. **用 write_file 保存文件** — 文件名格式: `[主题].[类型].excalidraw`
5. **通知用户** — 文件路径 + 使用说明

## 图表类型选择

| 类型 | 使用场景 | 做法 |
|------|---------|------|
| **流程图** | 步骤说明、工作流程、任务顺序 | 用箭头连接各步骤 |
| **思维导图** | 概念发散、主题分类、灵感捕捉 | 以中心为核心向外发散 |
| **层级图** | 组织结构、内容分级、系统拆解 | 自上而下构建层级节点 |
| **关系图** | 要素间的影响、依赖、互动 | 图形间用连线表示关联 |
| **对比图** | 方案或观点的对照分析 | 左右两栏，标明比较维度 |
| **时间线图** | 事件发展、项目进度 | 以时间为轴标出关键节点 |
| **矩阵图** | 双维度分类、任务优先级 | 建立 X 与 Y 两个维度 |
| **自由布局** | 内容零散、灵感记录 | 自由放置图块与箭头 |

## 设计规则

### 文本格式
- 所有文本元素使用 `fontFamily: 5`（Excalifont 手写字体）
- 文本中的双引号 `"` 替换为 `『』`
- 文本中的圆括号 `()` 替换为 `「」`
- 字体大小: 标题 20-28px，副标题 18-20px，正文 16-18px，**绝对禁止低于 14px**
- 行高: `lineHeight: 1.25`

### 文字居中估算
- 估算文字宽度: `estimatedWidth = text.length * fontSize * 0.5`（CJK 字符用 `* 1.0`）
- 居中公式: `x = centerX - estimatedWidth / 2`

### 布局设计
- 画布范围: 所有元素在 0-1200 x 0-800 区域内
- 最小形状尺寸: 带文字的矩形/椭圆不小于 120x60px
- 元素间距: 最小 20-30px，防止重叠
- **禁止 Emoji**: 不要在图表文本中使用任何 Emoji

### 调色板

**文字颜色:**

| 用途 | 色值 |
|------|------|
| 标题 | `#1e40af` |
| 副标题/连接线 | `#3b82f6` |
| 正文文字 | `#374151` |
| 强调 | `#f59e0b` |

**形状填充色:**

| 色值 | 语义 |
|------|------|
| `#a5d8ff` | 浅蓝 — 输入、数据源 |
| `#b2f2bb` | 浅绿 — 成功、输出 |
| `#ffd8a8` | 浅橙 — 警告、待处理 |
| `#d0bfff` | 浅紫 — 处理中、中间件 |
| `#ffc9c9` | 浅红 — 错误、关键 |
| `#fff3bf` | 浅黄 — 备注、决策 |
| `#c3fae8` | 浅青 — 存储、数据 |
| `#eebefa` | 浅粉 — 分析、指标 |

## JSON 结构

### Standard 模式
```json
{
  "type": "excalidraw",
  "version": 2,
  "source": "https://excalidraw.com",
  "elements": [...],
  "appState": { "gridSize": null, "viewBackgroundColor": "#ffffff" },
  "files": {}
}
```

### Animated 模式
与 Standard 相同，但每个元素添加 `customData.animate`:
```json
{
  "id": "element-1",
  "type": "rectangle",
  "customData": { "animate": { "order": 1, "duration": 500 } },
  ...其他标准字段
}
```

## 元素模板

基础元素（不要添加 `frameId`、`index`、`versionNonce`、`rawText` 等多余字段）:
```json
{
  "id": "unique-id",
  "type": "rectangle",
  "x": 100, "y": 100,
  "width": 200, "height": 50,
  "angle": 0,
  "strokeColor": "#1e1e1e",
  "backgroundColor": "transparent",
  "fillStyle": "solid",
  "strokeWidth": 2,
  "strokeStyle": "solid",
  "roughness": 1,
  "opacity": 100,
  "groupIds": [],
  "roundness": {"type": 3},
  "seed": 123456789,
  "version": 1,
  "isDeleted": false,
  "boundElements": null,
  "updated": 1,
  "link": null,
  "locked": false
}
```

文本元素额外属性:
```json
{
  "text": "显示文本",
  "fontSize": 20,
  "fontFamily": 5,
  "textAlign": "center",
  "verticalAlign": "middle",
  "containerId": null,
  "originalText": "显示文本",
  "autoResize": true,
  "lineHeight": 1.25
}
```

## 常见错误

- **文字偏移** — 独立 text 元素的 `x` 是左边缘，不是中心，必须用居中公式
- **元素重叠** — 放置新元素前检查与周围元素是否有至少 20px 间距
- **画布留白不足** — 内容四周留 50-80px padding
- **对比度不够** — 白底上文字颜色不低于 `#757575`
- **字号太小** — 低于 14px 在正常缩放下不可读

## 参考

详细 JSON schema 见 `references/excalidraw-schema.md`。

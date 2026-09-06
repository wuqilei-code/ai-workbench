---
name: mermaid
description: 将文本内容转换为专业 Mermaid 图表。支持流程图、思维导图、序列图、状态图、对比图。触发词:Mermaid、可视化、流程图、序列图、状态图、思维导图。
---

# Mermaid 可视化

将文本内容转换为专业 Mermaid 图表，优化用于展示和文档。内置语法错误预防。

## 快速开始

1. **分析内容** — 识别关键概念、关系、流程
2. **选择图表类型** — 参考下方类型表
3. **生成 Mermaid 代码** — 严格遵循语法规则
4. **用 write_file 保存** — 文件名格式: `[主题].[类型].md`，内容为 mermaid 代码块

## 图表类型

### 1. 流程图 (graph TB/LR)
**适用:** 工作流程、决策树、顺序过程、AI agent 架构

```
graph TB
    A[开始] --> B{判断}
    B -->|是| C[执行]
    B -->|否| D[结束]
```

### 2. 思维导图 (mindmap)
**适用:** 层级概念、知识组织、主题分解

```
mindmap
  root((中心))
    分支1
      叶子1
      叶子2
    分支2
      叶子3
```

### 3. 序列图 (sequenceDiagram)
**适用:** 组件交互、API 调用、消息流

```
sequenceDiagram
    participant A as 用户
    participant B as 服务器
    A->>B: 请求
    B-->>A: 响应
```

### 4. 状态图 (stateDiagram-v2)
**适用:** 系统状态、状态转换、生命周期

```
stateDiagram-v2
    [*] --> 空闲
    空闲 --> 处理中: 开始
    处理中 --> 完成: 结束
    完成 --> [*]
```

### 5. 对比图 (graph TB with parallel paths)
**适用:** A vs B 分析、传统 vs 现代

```
graph TB
    subgraph 方案A["方案 A"]
        A1[特点1]
        A2[特点2]
    end
    subgraph 方案B["方案 B"]
        B1[特点1]
        B2[特点2]
    end
```

### 6. 循环流程图 (graph TD with circular layout)
**适用:** 迭代过程、反馈循环、持续改进

```
graph TD
    A[计划] --> B[执行]
    B --> C[检查]
    C --> A
```

## 关键语法规则

### 规则1: 避免列表语法冲突
```
❌ 错误: [1. Perception]       → 触发 "Unsupported markdown: list"
✅ 正确: [1.Perception]         → 去掉句号后的空格
✅ 正确: [① Perception]         → 使用带圈数字
✅ 正确: [(1) Perception]       → 使用括号
```

### 规则2: 子图命名
```
❌ 错误: subgraph AI Agent Core  → 名称含空格
✅ 正确: subgraph agent["AI Agent Core"]  → 使用 ID + 显示名
✅ 正确: subgraph agent          → 仅使用简单 ID
```

### 规则3: 节点引用
```
❌ 错误: Title --> AI Agent Core  → 直接引用显示名
✅ 正确: Title --> agent          → 引用子图 ID
```

### 规则4: 特殊字符
```
✅ 空格文本用引号: ["Text with spaces"]
✅ 中文引号替换: " → 『, ( → 『
✅ 换行仅在圆形节点: ((Text<br/>Break))
```

### 规则5: 箭头类型
- `-->` 实线箭头
- `-.->` 虚线箭头（辅助系统、可选路径）
- `==>` 粗箭头（强调）
- `~~~` 不可见链接（仅布局）

## 配置选项

**布局:**
- `direction`: "vertical" (TB), "horizontal" (LR)

**详细程度:**
- `simple`: 仅核心元素
- `standard`: 平衡细节（默认）
- `detailed`: 完整注释

**样式:**
- `minimal`: 单色，简洁线条
- `professional`: 语义颜色，清晰层级（默认）
- `colorful`: 鲜艳颜色，高对比度

## 配色方案

| 颜色 | 用途 |
|------|------|
| 绿色 `#d3f9d8`/`#2f9e44` | 输入、感知、开始 |
| 红色 `#ffe3e3`/`#c92a2a` | 计划、决策点 |
| 紫色 `#e5dbff`/`#5f3dc4` | 处理、推理 |
| 橙色 `#ffe8cc`/`#d9480f` | 动作、工具使用 |
| 青色 `#c5f6fa`/`#0c8599` | 输出、执行、结果 |
| 黄色 `#fff4e6`/`#e67700` | 存储、记忆、数据 |

## 质量检查

输出前验证:
- [ ] 无 "数字. 空格" 模式
- [ ] 所有子图使用正确 ID 语法
- [ ] 所有箭头语法正确
- [ ] 颜色应用一致
- [ ] 布局方向已指定
- [ ] **无 Emoji** — 用文本标签或颜色区分

## 保存格式

用 `write_file` 保存为 `.md` 文件:

```markdown
# [图表标题]

[简要说明图表结构]

```mermaid
[Mermaid 代码]
```

> 兼容 Obsidian、GitHub 等 Mermaid 渲染器
```

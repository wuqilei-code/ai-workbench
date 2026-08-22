---
name: browser-operate
description: 用户要求打开网页/浏览/点击/输入/验证页面效果时使用。驱动的浏览器与用户实时面板共享同一个上下文。
---

# 浏览器操作

任务是帮用户浏览网页、验证页面效果、抓取页面信息。浏览器与用户实时面板是**同一个浏览器**,
你的每一步都会实时显示在用户的浏览器面板里。

## 规则
- 所有操作必须经过 `browser_navigate` 打开一个 http(s) 页面后,再执行后续点击/输入/滚动。
- `browser_navigate` 每次都会请求用户允许;被拒绝就不要再重试同一个页面,向用户说明。
- **默认不截图**:只有用户明确要求截图/查看网页图片时,才调用 `browser_screenshot`
  (或 `browser_navigate` 的 screenshot=true)。截图结果必须用 `[结果文件] <路径>` 标记交付。

## 标准流程
1. `browser_navigate` 打开目标网址,拿到标题。
2. `browser_get_text` 读取页面文字,判断页面结构与可操作元素(按钮文案/输入框/链接)。
3. 按需操作:
   - 点按钮/链接:优先 `browser_click_text`(按文字,如"登录"、"下载"),多个匹配时精确到选择器用 `browser_click`。
   - 输入:`browser_type(selector, text)` 填表单(CSS 选择器,如 `#q`、`[name=email]`)。
   - 滚动:`browser_scroll(y=…)`,向下为正。
   - 回退:`browser_go_back`。
4. 需要向用户展示结果/页面要求截图时,再 `browser_screenshot`。

## 注意事项
- 页面文字只从 `browser_get_text` 获取(白屏/登录墙等先说明,不要硬点)。
- 每步操作都要对照页面内容,不要盲点坐标。
- 操作失败时如实报告 `[工具名]返回的错误`,不要编造成功。

## 参考
- 工具清单见当前模型可用工具描述;流程之上按此技能执行。
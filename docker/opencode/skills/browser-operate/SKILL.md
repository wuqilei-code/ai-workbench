---
name: browser-operate
description: 当任务需要操作网页、浏览网站、搜索资料、点击链接、填写表单、抓取页面内容,或用户要求「用浏览器打开/访问/查一下某个网址」时使用。通过 `browser` MCP(Playwright,经 CDP 连接)操作**与用户共享的同一无头 Chromium**——用户在浏览器面板能看到实时画面。固化操作步骤、验证规范与共享浏览器使用守则,确保操作可复现、不破坏用户正在看的页面。
license: MIT
compatibility: opencode
metadata:
  audience: all-users
  workflow: browser-automation
---

# 浏览器操作

通过 `browser` MCP 操作与用户共享的无头 Chromium。**你在网页上的每一次动作,用户都能在浏览器面板实时看到**——这是共享浏览器,不是你的私有沙箱。

## 先搞清环境(重要)

- 浏览器是 **AI 与用户共用**:`playwright-mcp --cdp-endpoint` 连到网关无人值守起的同一个 Chromium(视口固定 1280×720,多标签页,登录态/会话共享)。
- 用户侧浏览器面板的标签栏、实时画面与你的操作同步;你新建/关闭标签会在用户面板标签栏出现。
- 因此:操作要**透明、可验证、可回退**,不做用户不知道的破坏性动作。

## 可用工具(browser MCP,按用途优先选)

| 用途 | 首选工具 |
|---|---|
| 导航 | `browser_navigate`(URL)、`browser_navigate_back` |
| 看页面结构 | `browser_snapshot`(无障碍树,本质是标记文本,`ref` 用于点击/输入)**第一优先** |
| 点击/悬停/输入 | `browser_click`/`browser_hover`/`browser_type`(用 snapshot 里的 `ref`) |
| 键 | `browser_press_key`(Enter/Tab/Backspace/Arrow* 等) |
| 表单 | `browser_fill_form`(多字段一次填)、`browser_select_option`(下拉) |
| 拖拽 | `browser_drag`(元素 drag and drop) |
| 坐标级兜底 | `browser_mouse_move_xy`/`browser_mouse_click_xy`/`browser_mouse_drag_xy`/`browser_mouse_wheel`(见下方注意) |
| 标签管理 | `browser_tabs`(列出/新建/选择/关闭) |
| 等待 | `browser_wait_for`(等文本出现/消失或等指定时间) |
| 提取 | `browser_find`(在无障碍快照里按文字/正则搜)、`browser_snapshot`、`browser_evaluate`(JS) |
| 证据 | `browser_take_screenshot`(当前页截图) |
| 排障 | `browser_console_messages`、`browser_network_requests`、`browser_handle_dialog`(弹窗) |
| 上传 | `browser_file_upload` |

**禁用或慎用**:
- `browser_run_code_unsafe`:等于在 Playwright 服务进程跑任意 JS,RCE 级能力。**默认不要用**;非用不可时向用户说明并获得同意。
- 不要用 `browser_evaluate` 做破坏性操作(`localStorage.clear()`、删 cookie、强改登录态等)。

## 操作流程

1. **理解任务**:要访问什么站、拿到什么信息、完成什么动作。
2. **规划步骤**:列出页面与动作序列;需要多个来源时规划好先访问谁。
3. **动作但行**:
   - 导航 `browser_navigate <url>`。
   - 每次 `browser_snapshot` 看结构,用 `ref` 定位元素,逐个执行 `browser_click`/`browser_type`。
   - 页面交互后,等渲染:`browser_wait_for` 等关键文本/元素出现,再继续。
4. **验证**:不要只看截图就下结论——
   - 用 `browser_snapshot`/`browser_find` 确认页面状态与目标内容;
   - 核对关键 DOM/文本(如搜索结果条数、表单提交后的提示、URL 是否变化);
   - 提取的数据要能给出来源(URL、选择器/路径,必要时附截图)。
5. **汇报**:说明访问了哪些 URL、做了哪些动作、最终停在哪个页面/标签。这样用户在面板里看到的就是你说的那个页面。

## 操作规范

- **先用 `browser_navigate` 导航,再 `browser_snapshot`** —— 导航后首屏可能未稳定,先 `browser_wait_for` 等加载。
- **元素定位优先用快照的 `ref`,不要盲敲坐标**。快照是滚动后可见区,长页面要先滚到目标附近。
- **坐标工具只在页面是 canvas/iframe 渲染、快照拿不到元素时才用**。此时视口固定 1280×720,坐标即画面像素。
- **填写输入框前先 `browser_click` 聚焦,再 `browser_type`**;需要清空时用 `browser_press_key` 的 Ctrl+A 后替换(x 提示:Playwright 的 fill 类工具可传空串清空)。
- **弹窗/对话框**(confirm/alert/prompt):先读 `browser_handle_dialog` 内容再决定接受/取消;不要无脑接受。
- **一次只做一件事**,每步后看结果再下一步;报错了先看 `browser_console_messages` 或截图,不要盲目重试。

## 共享浏览器守则

- **不要**擅自登出账号、清 cookie/storage、提交不可逆操作(下单/付款/删除/发送)。确需时先征得用户同意。
- **标签**:默认在用户当前标签继续操作;确需新页时 `browser_tabs` 新建并在汇报里说明,别偷偷开一堆标签。
- **不要**关闭用户正在看的标签页;要关也先说明。
- 站点有验证码/登录墙/反爬时:**如实告知**用户哪些步骤被拦、给了什么提示,不要编造成功。
- 拿到的敏感信息(密码、令牌、个人信息)只在本次任务内使用,不写进交付文件。

## 常见小抄

- 打开 URL:`browser_navigate` `url: "https://..."`(务必带协议)。
- 搜索:打开搜索引擎/站内搜索框 → `browser_type` → `browser_press_key` Enter → `browser_wait_for` 结果 → `browser_snapshot` 提取。
- 登录/表单:逐字段 snapshot→find 定位 → `browser_click` 聚焦 → `browser_type`,提交后验证跳转。
- 抽取列表数据:snapshot 里按 `browser_find` 正则抓取,组织成表格/清单给用户,附每条的来源。
- 结果交给用户:文字结论 + 关键数据 + 访问过的 URL 列表;需要图片证据时 `browser_take_screenshot`。
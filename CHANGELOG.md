# 更新日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 安全
- 移除仓库内泄漏的模型 API Key 及内部测试脚本(`docker/.smoke`)。
- 以开源字体 **Noto Sans SC**(SIL OFL 1.1)替换内置的专有字体 SimHei。

### 新增
- 开源配套:`LICENSE`(Apache-2.0)、`CONTRIBUTING.md`、`SECURITY.md`、
  `CODE_OF_CONDUCT.md`、`THIRD_PARTY_NOTICES.md`、Issue/PR 模板。

## [0.1.0] - 2026-10-13

首个公开版本。多用户通用 AI 工作台:对话 + 智能体编排 + 浏览器接管 +
动态 UI 可视化 + 技能/模板体系一体化。

### 新增
- **对话**:SSE 流式直通、会话/消息持久化、多用户隔离、附件上传、会话导出(MD/JSON/Word/PDF)。
- **智能体**:基于 deepagents(LangGraph)的执行轨;chat / task 两种模式;
  HITL 危险操作人工确认;用户记忆;入站来源白名单与出站 SSRF 防护。
- **沙箱**:每用户独立 AIO 沙箱容器(Chromium + VNC + code-server + shell),
  前端 iframe 内嵌 VNC 实时画面与接管;单端口代理 + JWT 鉴权。
- **技能体系**:内置技能(docx/pdf/pptx/xlsx/浏览器操作/动态 UI 等)、技能市场、
  多文件技能编辑器、按会话勾选加载。
- **模板体系**:模板库、模板市场、收藏、上传 HTML、一键「做同款」。
- **工具库**:MCP / OpenAPI / 手动接口工具,统一管理、卡片化表单与在线调试。
- **自动化**:定时任务(6 段 cron)、执行历史、深色终端日志、结果文件预览与下载。
- **前端**:Vue 3 + Vite + TS + Naive UI,全站国际化、统一交互组件与分页。

[Unreleased]: https://github.com/wuqilei-code/ai-workbench/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/wuqilei-code/ai-workbench/releases/tag/v0.1.0

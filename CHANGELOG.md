# 更新日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.1.0] - 2026-10-13

首个公开版本。多用户通用 AI 工作台:对话 + 智能体编排 + 浏览器接管 +
动态 UI 可视化 + 技能/模板体系一体化。

### 新增
- **对话**:SSE 流式直通、会话/消息持久化、多用户隔离、附件上传、会话导出(MD/JSON/Word/PDF)。
- **智能体**:基于 deepagents(LangGraph)的执行轨;chat / task 两种模式;
  HITL 危险操作人工确认;用户记忆;入站来源白名单与出站 SSRF 防护。
- **沙箱**:每用户独立 AIO 沙箱容器(Chromium + VNC + code-server + shell),
  前端 iframe 内嵌 VNC 实时画面与接管;单端口代理 + JWT 鉴权。
- **导入登录态**:沙箱浏览器面板提供「导入登录态」——把本机导出的 cookie(JSON)经 CDP
  注入沙箱 Chromium,免登录访问目标站点(兼容 Cookie-Editor / EditThisCookie /
  Playwright storageState 等格式)。
- **技能体系**:内置技能(docx/pdf/pptx/xlsx/浏览器操作/动态 UI 等)、技能市场、
  多文件技能编辑器、按会话勾选加载。
- **模板体系**:模板库、模板市场、收藏、上传 HTML、一键「做同款」。
- **工具库**:MCP / OpenAPI / 手动接口工具,统一管理、卡片化表单与在线调试。
- **自动化**:定时任务(6 段 cron)、执行历史、深色终端日志、结果文件预览与下载。
- **模型配置**:内置常用厂商预设(DeepSeek / OpenAI / 通义千问 / 智谱 / Kimi / 火山方舟 /
  千帆 / 混元 / 硅基流动 / MiniMax / 阶跃 / 百川 / 零一万物 / 星火 / OpenRouter / Groq /
  Gemini / Ollama / 自定义),选中即带出 Base URL 等默认配置,通常只需填 API Key;
  Base URL 可改为内网 / 自建网关地址。对话与定时任务实际调用即使用所选 provider 的
  Base URL +(加密存储、解密后下发的)API Key + 模型 ID;未配置任何 provider 时回退
  `.env` 的 `AGENT_MODEL*`。
- **前端**:Vue 3 + Vite + TS + Naive UI,全站国际化、统一交互组件与分页。
- **文档**:README 界面预览截图,以及 `CONTRIBUTING` / `SECURITY` / `CODE_OF_CONDUCT` /
  `THIRD_PARTY_NOTICES`、Issue/PR 模板。

### 安全
- 移除仓库内泄漏的模型 API Key 及内部测试脚本(`docker/.smoke`)。
- 以开源字体 **Noto Sans SC**(SIL OFL 1.1)替换内置的专有字体 SimHei。

### 工程
- 采用 **Apache-2.0** 许可证。
- 修复 agent-service 的 pytest 无法导入 `app`(新增根级 `conftest.py`)。
- 应用 `npm audit` 非破坏性依赖修复(markdown-it / nanoid / source-map-js 等)。
- `docker compose` 默认自带 MySQL 容器并自动建表;补充沙箱镜像拉取与密钥生成说明
  (README / `.env.example`)。
- 新增 `docker/gen-env.sh` / `docker/gen-env.ps1`:一键生成带随机密钥的 `.env`。
- 固定 compose 项目名为 `ai-workbench`:容器/卷/网络统一 `ai-workbench-*` 前缀,便于辨识。
- 默认开启注册(`APP_REGISTRATION_ENABLED=true`),服务启动即可建号。

### 修复
- 注册页「邮箱 / 密码」标签与输入框错位。
- 「模型配置」此前不生效:实际调用固定使用 `.env` 的 `AGENT_MODEL*`,UI 里配的 key 被忽略
  (表现为 401)。现已改为按会话所选 provider 下发模型配置。

[0.1.0]: https://github.com/wuqilei-code/ai-workbench/releases/tag/v0.1.0

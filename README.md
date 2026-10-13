# AI Workbench

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![CI](https://github.com/wuqilei-code/ai-workbench/actions/workflows/ci.yml/badge.svg)](https://github.com/wuqilei-code/ai-workbench/actions/workflows/ci.yml)

多用户通用 AI 工作台(类 Trae):**对话 + 智能体编排 + 浏览器接管 + 动态 UI 可视化 + 技能/模板体系**一体化。
服务端部署,浏览器访问;多用户注册与会话隔离;每个用户独立工作区与沙箱容器;面向内网私有化部署。

## 界面预览

### 智能对话与动态 UI 可视化
对话即工作台:流式回答、工具调用卡片,以及由 AI 现场生成的交互式图表/组件。

![对话与动态 UI 可视化](docs/screenshots/chat-dynamic-ui.png)

### 技能市场
浏览、安装、分享技能(`SKILL.md` 指令 + 可选脚本/参考/模板),一键装进工作台,按会话勾选加载。

![技能市场](docs/screenshots/skills.png)

| 模板库 | 工具库(MCP / 接口内联) |
|---|---|
| ![模板库](docs/screenshots/templates.png) | ![工具库](docs/screenshots/tools.png) |

| 定时自动化 | 个人驾驶舱 |
|---|---|
| ![自动化](docs/screenshots/automation.png) | ![驾驶舱](docs/screenshots/dashboard.png) |

<details>
<summary>登录页</summary>

![登录](docs/screenshots/login.png)

</details>


## 架构

```
浏览器 ──80──▶ nginx (Vue SPA + /api 反代) ──▶ Spring Boot 网关 (8080)
                                              │  ├─ MySQL 8     用户/会话/消息/模板/技能/任务/通知(含 Flyway 迁移)
                                              │  ├─ SSE         /api/chat/sessions/{id}/events(流式直通)
                                              │  └─ WebClient + X-Internal-Token
                                              ▼
                                        agent-service (FastAPI, 8000)
                                              │  ├─ deepagents(LangGraph)执行轨
                                              │  └─ Postgres   线程 checkpoint / 跨线程 store
                                              ▼
                                        每用户 AIO 沙箱容器(Chromium + VNC + code-server + shell)
```

- **deepagents 单轨**:对话与定时任务都由 agent-service 执行;网关只做鉴权、持久化与 SSE 中转,
  事件(deepagents 原生 `start/delta/tool/tool_result/permission/done` …)原样转发给前端。
- **隔离**:每用户工作区 `/workspace/users/<uid>/project`,被执行沙箱与文件树共享。
- **沙箱**:`execute`/文件/浏览器操作全部在 per-user AIO 沙箱容器内;前端 iframe 内嵌 VNC 画面。
- **跨副本**:定时任务用 DB 分布式锁互斥;会话事件经 `session_event` 出站表按 `seq` 增量中继,
  多副本可并列运行(单机亦可)。

## 技术栈

| 层 | 选型 |
|---|---|
| 前端 | Vue 3 + Vite + TypeScript + Pinia + Naive UI(+ vue-i18n) |
| 网关 | Java 21 + Spring Boot 3.3 + MyBatis-Plus + Spring Security(JWT) + Flyway |
| 智能体 | Python + FastAPI + deepagents(LangGraph) |
| 数据库 | MySQL 8(业务) + Postgres 16(agent checkpoint) |
| 部署 | Docker multi-stage + supervisord + nginx |

## 快速开始

```bash
cd docker
# 首次:复制并按需修改 .env(见 docker/.env;密钥务必替换为随机值)
docker compose up --build
```

- 浏览器打开 `http://localhost:8080/`
- **首个注册用户自动成为管理员**
- 管理员在「管理 → 模型配置」添加 provider/model 并 Apply 后即可对话

## 本地开发

```bash
# 后端(需 JDK 21+、Maven 3.6+)
cd backend
mvn clean package -DskipTests     # 产物 target/*.jar

# 前端(需 Node 18+)
cd frontend
npm install
npm run dev                       # Vite 代理 /api → localhost:8080
```

> 提示:若本机 Maven 全局 `settings.xml` 使用华为云镜像(`repo.huaweicloud.com`),个别 artifact 可能返回 HTML 错误页导致 `Non-parseable POM`,改用阿里云镜像(`https://maven.aliyun.com/repository/public`)即可。

## 测试与检查(CI 门禁)

```bash
cd backend  && mvn test            # JUnit(令牌/变量替换/内部鉴权/SSE 中继)
cd frontend && npm run typecheck && npm run test && npm run build
cd agent-service && pytest -q      # 沙箱工作区 / 出站 SSRF / 入站来源白名单
```

CI 见 `.github/workflows/ci.yml`(后端 package、前端 typecheck+test+build、agent-service pytest)。

## API 文档

- OpenAPI JSON:`GET /v3/api-docs`
- Swagger UI:`/swagger-ui/index.html`
- 默认开启;如需收敛为"需登录",设 `OPENAPI_ENABLED=false`。
- 除 `登录/注册/健康检查/内部接口` 外,均需 `Authorization: Bearer <JWT>`。

## 配置(环境变量,见 `docker/docker-compose.yml` 与 `docker/.env`)

| 变量 | 说明 |
|---|---|
| `JWT_SECRET` | JWT 签名密钥(`openssl rand -hex 32`;缺失启动失败) |
| `APP_AES_KEY` | AES-256 密钥(Base64 的 32 字节;加密存储 provider key 等) |
| `APP_REGISTRATION_ENABLED` | 是否开放注册(生产默认 `false`) |
| `INTERNAL_TOKEN` | app ⇄ agent-service 内部令牌(两服务一致;`openssl rand -hex 32`) |
| `INTERNAL_TRUSTED_CIDRS` | 内部接口可选来源白名单(IPv4/CIDR;空=仅校验令牌) |
| `SSE_RELAY_ENABLED` / `SSE_RELAY_POLL_MS` | 会话事件跨副本中继开关/轮询间隔 |
| `SPRING_DATASOURCE_*` / `MYSQL_HOST` | 业务库连接(dev 默认宿主机 MySQL,经 `host.docker.internal`) |
| `POSTGRES_PASSWORD` | agent checkpoint 库密码 |
| `AGENT_MODEL` / `AGENT_MODEL_BASE_URL` / `AGENT_MODEL_API_KEY` | 模型与密钥 |
| `AGENT_HITL` | 危险操作是否需人工确认(默认 `true`) |
| `AGENT_WORKERS` / `AGENT_MAX_TURNS` | agent 并发 worker / 单请求步数上限 |
| `AGENT_SANDBOX_*` | 沙箱镜像、网络、内存/CPU、空闲回收等 |
| `AGENT_JOBS_*` | 定时任务并发、超时、重试、Webhook、全局暂停 |
| `AGENT_OUTBOUND_ALLOWLIST` / `AGENT_ALLOW_PRIVATE_OUTBOUND` | 出站 SSRF 策略 |

## 目录

```
ai-workbench/
├── docker/         Dockerfile + compose(dev/prod) + supervisord + nginx + prometheus 示例
├── backend/        Spring Boot 3 网关(Maven;db/migration 为 Flyway 迁移)
├── frontend/       Vue 3 + Vite + TS
├── agent-service/  Python FastAPI + deepagents
└── scripts/        备份脚本(MySQL/Postgres/工作区卷)
```

整改与待办清单见 [`TASKS.md`](TASKS.md)。

## 贡献

欢迎提交 Issue 与 PR。开始前请阅读:

- [`CONTRIBUTING.md`](CONTRIBUTING.md) — 开发环境、CI 门禁与提交规范
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — 社区行为准则
- [`SECURITY.md`](SECURITY.md) — 漏洞披露方式与部署安全须知

## 许可证

本项目以 **Apache License 2.0** 发布,见 [`LICENSE`](LICENSE)。
内置的开源字体 Noto Sans SC 等第三方资产的许可见
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

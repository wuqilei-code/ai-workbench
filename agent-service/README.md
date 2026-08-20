# agent-service(deepagents 轨道)

基于 LangChain `deepagents`(0.7.x)的多租户 agent 微服务,替代 opencode 每条对话的进程/容器开销:

- 每用户独立工作区 `<AGENT_WS_ROOT>/<user_id>/workspace`、技能 `<AGENT_WS_ROOT>/<user_id>/skills`(只读)、
  记忆 `<AGENT_WS_ROOT>/<user_id>/.memory/AGENTS.md`;
- 一个进程承载所有用户,按 `thread_id` + `user_id` 隔离 checkpoint(Postgres 持久化);
- 工具就在本服务内执行(文件读写/脚本运行经 FilesystemPermission 白名单),不依赖外部 opencode 实例。

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `AGENT_HOST` / `AGENT_PORT` | `0.0.0.0` / `8000` | HTTP 监听 |
| `AGENT_LOG_LEVEL` | `INFO` | 日志级别 |
| `AGENT_MODEL` | `openai:gpt-4o-mini` | 模型标识;给了 `AGENT_MODEL_BASE_URL` 则按 OpenAI 兼容协议构造 |
| `AGENT_MODEL_BASE_URL` | — | 如 `https://api.deepseek.com/v1`(DeepSeek 必填) |
| `AGENT_MODEL_API_KEY` | — | API key(DeepSeek 必填) |
| `AGENT_MODEL_TEMPERATURE` | `0.0` | 采样温度 |
| `AGENT_WS_ROOT` | `/data/users` | 每用户工作区根目录 |
| `AGENT_PG_DSN` | — | 如 `postgresql://user:pass@host:5432/agent_workbench`;留空则内存 checkpoint(重启丢失) |
| `AGENT_HITL` | `false` | 是否启用 human-in-the-loop(write/edit/execute 需审批) |
| `AGENT_MAX_TURNS` | `50` | 单轮最多执行步数 |

## 接口

- `GET /health` — 存活 + deepagents 版本。
- `POST /api/users/{user_id}/invoke` — 同步执行,返回最后一条消息文本(冒烟/简单集成用)。
- `POST /api/users/{user_id}/stream` — SSE 流式,Spring 网关桥接的消费端:

  body:
  ```json
  { "thread_id": "da-xxx", "messages": [ { "role": "user", "content": "..." } ], "system_prompt": null }
  ```

  事件(自定义命名事件,data 为 JSON):
  - `start` — `{thread_id}`
  - `delta` — `{content}` 文本增量(每 chunk 一条)
  - `tool` — `{name, args, id}` 工具调用开始
  - `tool_result` — `{name, content}` 工具结果(content 截断到 2000 字符)
  - `done` — `{status:"ok"}` 本轮结束
  - `error` — `{message}` 异常终止

## 本地开发

```bash
python -m venv .venv
.venv\Scripts\activate            # Windows
pip install -r requirements.txt   # 用 TUNA 镜像更快: -i https://pypi.tuna.tsinghua.edu.cn/simple
# 导出模型/存储环境变量后:
python -m app.main
```

文本/工具流式冒烟(不需要 Postgres):

```bash
python smoke_stream.py "ping a few file commands and report"
```

## Docker 编排

`../docker/docker-compose.yml` 定义 `agent-service`(镜像 `ai-workbench-agent-service`)+ `postgres`
(checkpoint/store 持久化),网关容器内以 `http://agent-service:8000` 访问,宿主机可用
`http://localhost:8000`(请确认 `${AGENT_SERVICE_PORT:-8000}` 已被映射)。

```bash
docker compose build agent-service
docker compose up -d postgres agent-service
curl http://localhost:8000/health
```
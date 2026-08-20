# dsh 运行时(DeepSeek Harness)接入说明

本部署默认用 opencode `serve` 作为 agent 运行时。本文描述备选运行时 **dsh**
(DeepSeek Harness 官方单文件运行时),它以 opencode 兼容的 HTTP+SSE API 接入现有
后端与前端,无需改动 `OpencodeClient` / `OpencodeInstanceEventConsumer` / `ChatMessageService`。

## 运行时选择

全局开关(compose 环境变量):

```yaml
OPENCODE_AGENT_RUNTIME: "dsh"     # 可选 "opencode"(默认)
```

后端绑定见 `OpencodeProperties.agentRuntime`(`backend/.../config/OpencodeProperties.java`)。
dsh 仅支持容器模式(`OPENCODE_CONTAINER_MODE=true`);若进程模式选择了 dsh,后端会
告警并回退到 opencode serve。

### 按用户覆盖

`users.agent_runtime` 列(可空)可覆盖全局:非空时以用户值优先(`opencode`/`dsh`),空则跟随
全局开关。改动下次该用户 agent 实例启动时生效。管理端接口:

```http
PUT /api/admin/users/{id}/agent-runtime   {"agentRuntime":"dsh"}   # 或 "opencode",null 清除
```

- Spring 启动执行的 `backend/src/main/resources/db/schema.sql` 已含该列的建表与幂等 ALTER。
- 若手动用 `docker/schema.sql` + `run-schema.bat` 建库,需自行补列:
  `ALTER TABLE users ADD COLUMN agent_runtime VARCHAR(16) NULL;`(该文件为未跟踪副本,仓库内不修改)。

## dsh 是什么

官方发布载体是 PyPI wheel `deepseek-harness-runtime-bin`(linux-x64),内含单文件 Node
可执行程序 `dsh-jsonrpc-agent`(pkg 打包,运行时无需 Node/npm 与依赖解析)。agent 容器
内由 `dsh-bridge`(node)以 stdio JSON-RPC 驱动它,并把其会话事件翻译成 opencode 兼容
SSE 事件。容器内部件:

- `/opt/aiworkbench/dsh/bin/dsh-jsonrpc-agent` — 官方单文件运行时
- `/opt/aiworkbench/dsh/cordis.yml` — 从 wheel 原样解出的权威配置
- `/opt/aiworkbench/dsh-bridge/` — 桥(node,零 npm 依赖),监听 4096
- `/opt/aiworkbench/dsh-entrypoint.sh` — 容器入口(Chromium/playwright-mcp 同
  `agent-entrypoint.sh`,再 exec 桥)

## 构建

`docker/Dockerfile` 的 agent-runtime 阶段经 `pip download` 拉取 wheel(走
`PIP_INDEX_URL`,内网/阿里云镜像可达)。构建参数:

```bash
docker compose --profile build build agent-build \
  --build-arg DSH_RUNTIME_VERSION=0.1.0rc7 \
  --build-arg DSH_WHEEL_URL=https://files.pythonhosted.org/.../xxx.whl   # 可选:绕过 index
```

- 若内部 PyPI 镜像未同步该包,用 `DSH_WHEEL_URL` 直接给 wheel 下载地址。
- 仅支持 `amd64`;arm64 构建自动跳过 dsh(回退 opencode)。

## 每用户环境

后端 `OpencodeProcessManager.computeEnv` 在 dsh 模式注入:

| 变量 | 说明 |
| --- | --- |
| `DSH_SESSION_ROOT` | 会话持久化根,落在每用户 `$HOME/.local/share/dsh`(与 opencode 同隔离,回收后按原 sessionId 恢复) |
| `DSH_CWD` | 工作目录,与容器 `-w`(用户项目目录)一致 |
| `DSH_PROVIDER` / `DSH_MODEL` | 默认 `deepseek-official` / `deepseek-chat`(须匹配运行时注册的适配器路由) |
| `DSH_MAX_TOKENS_AS_SUCCESS` | 默认 20000 |
| `DSH_CORDIS_CONFIG` / `DSH_RUNTIME_BIN` | 桥固定路径 |

API key:`DEEPSEEK_API_KEY`(及可选 `DEEPSEEK_BASE_URL`)由用户的 `opencode.env`
经既有解析注入,无需额外配置。会话里的模型固定为容器初始化时的
`DSH_PROVIDER/DSH_MODEL`(dsh 的 `session/prompt` 协议不携带模型参数)。

## 与 opencode 的差异(优雅降级)

用户问答已确认接受以下降级:

- **abort** = 杀并重启运行时子进程(会话持久化在磁盘,可继续),返回 `true`。
- **summarize / command** = `true` / 200 no-op。
- **switchAgent** = 204;`/command`、`/agent` 返回空数组。
- **question/permission 回复** = 200 no-op(dsh 无该交互面)。
- **DELETE 会话** = 200(运行时会话仍留在磁盘)。
- 前端 tool 部件实时流由 `tool/call` + `tool/result` 事件驱动;`todo`、compaction、
  子代理事件不翻译(v1)。

## 桥接口(容器内)

`dsh-bridge` 暴露与 opencode serve 相同子集:`/global/health`、`/global/event`(SSE)、
`/session`(POST/GET)、`/session/{id}/prompt_async`、`abort`、`summarize`、`message`、
`/question|permission` 回复、`/command`、`/agent`、`/api/session/{id}/agent`。无鉴权
(容器网络内,与 opencode Basic auth 同为网关单点)。

## 验证

```bash
docker compose --profile build build agent-build
# .env 设 OPENCODE_AGENT_RUNTIME=dsh 后重建/重启 app
docker compose up -d app
docker logs aiwb-agent-<uid>   # 看 [dsh-bridge] runtime ready / fatal
```

### 本地容器级验证(无需完整 app 栈)

`dsh-validate` 最小镜像(仅 node + dsh 运行时 + 桥)可在本地 Docker 内验证真实 dsh 事件流:

```bash
# 1. 准备构建目录(docker build context 不含仓库根,需先收拢桥文件)
$vb = "$env:TEMP\dsh-valbuild"
New-Item -ItemType Directory -Path $vb | Out-Null
Copy-Item -Recurse docker/opencode/dsh-bridge "$vb\dsh-bridge"
Copy-Item docker/opencode/dsh-entrypoint.sh "$vb\dsh-entrypoint.sh"
Copy-Item docker/.smoke/dsh-validate.Dockerfile "$vb\Dockerfile"
docker build -f "$vb\Dockerfile" -t dsh-validate $vb

# 2. 运行(注入真实 DEEPSEEK_API_KEY;github 在本机不通不影响 dsh 路径)
docker run -d --name dsh-val -p 14096:4096 \
  -e DEEPSEEK_API_KEY=<key> -e DEEPSEEK_BASE_URL=https://api.deepseek.com/v1 \
  -e DSH_PROVIDER=deepseek-official -e DSH_MODEL=deepseek-chat \
  dsh-validate
docker logs dsh-val   # 应看到 [dsh-bridge] dsh runtime ready

# 3. 驱动真实对话(完整断言见 .smoke/container-e2e.mjs)
node docker/.smoke/container-e2e.mjs
#    应看到:真实 token 回填、流式文本、session.idle 恰好一次、后端每轮一次 git 快照
```

生产 agent 镜像构建需要 github 可达(下载 opencode 二进制)或
`--build-arg OPENCODE_DOWNLOAD_URL=<内网镜像>`;dsh 运行时来自 aliyun PyPI 镜像,不受此限。
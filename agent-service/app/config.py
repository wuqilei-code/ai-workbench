"""agent-service 运行配置:全部来自环境变量,零硬编码。"""

from __future__ import annotations

import os
from pathlib import Path


def _bool(name: str, default: bool = False) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, str(default)))
    except ValueError:
        return default


# --- 模型 ---
# LangChain model 标识,如: openai:gpt-4o-mini / anthropic:claude-sonnet-4-6
# 若提供 AGENT_MODEL_BASE_URL,则按 OpenAI 兼容协议构造(可对接 DeepSeek/Ollama/自建网关)。
AGENT_MODEL: str = os.environ.get("AGENT_MODEL", "openai:gpt-4o-mini")
AGENT_MODEL_BASE_URL: str | None = os.environ.get("AGENT_MODEL_BASE_URL") or None
AGENT_MODEL_API_KEY: str | None = os.environ.get("AGENT_MODEL_API_KEY") or None
AGENT_MODEL_TEMPERATURE: float = float(os.environ.get("AGENT_MODEL_TEMPERATURE", "0.0"))
# DeepSeek V4 思维链开关:false 时请求体带 thinking.type=disabled,减少输出长度与延迟/成本
#(部分场景关闭思考可能影响工具选择质量,可先观察再开关)。
AGENT_MODEL_THINKING: bool = _bool("AGENT_MODEL_THINKING", False)
# 推理强度(low/medium/high/max);仅 thinking 开启时生效。
AGENT_MODEL_REASONING_EFFORT: str = os.environ.get("AGENT_MODEL_REASONING_EFFORT", "high")

# --- 工作区 ---
# 所有用户工作区/技能目录的根。多租户主机上按 <root>/<user_id>/ 隔离。
AGENT_WS_ROOT: Path = Path(os.environ.get("AGENT_WS_ROOT", "/data/users"))

# --- 持久化 ---
# AGENT_PG_DSN 形如 postgresql://user:pass@host:5432/db;为空则退化为内存 checkpointer。
AGENT_PG_DSN: str | None = os.environ.get("AGENT_PG_DSN") or None
# 并发加固:checkpointer/store 改用连接池(多线程/多 worker 并发 checkpoint 安全)。
AGENT_PG_POOL_MIN: int = _int("AGENT_PG_POOL_MIN", 2)
AGENT_PG_POOL_MAX: int = _int("AGENT_PG_POOL_MAX", 10)

# --- 安全 ---
# 打开后所有 write/edit/execute 需人工审批(deepagents interrupt_on)。
AGENT_HITL: bool = _bool("AGENT_HITL", False)

# 内部令牌:网关/agent-service 互相调用的共享凭证(X-Internal-Token)。
# 生产必须设置随机值;缺失即启动失败(fail-fast),不落公开默认值,防默认令牌被滥用。
INTERNAL_TOKEN: str = os.environ.get("INTERNAL_TOKEN", "")
if not INTERNAL_TOKEN:
    raise RuntimeError(
        "INTERNAL_TOKEN 未设置。请在环境变量中配置随机令牌,并与后端 app.internal-token 保持一致。"
    )

# --- 执行沙箱 ---
# AGENT_SANDBOX_BACKEND:
#   aio   = AIO Sandbox(agent-infra/sandbox)per-user 容器,自带浏览器/VNC/VSCode/shell(推荐,默认)
#   local = 退回 LocalShellBackend(agent-service 进程内直接跑 shell,仅限本机可信调试)
AGENT_SANDBOX_BACKEND: str = os.environ.get("AGENT_SANDBOX_BACKEND", "aio").strip().lower()
# AIO Sandbox 镜像(内置 Chromium + VNC + code-server + python/node 运行时)
AGENT_SANDBOX_IMAGE: str = os.environ.get("AGENT_SANDBOX_IMAGE", "aio-sandbox:latest")
# 沙箱容器挂入的自定义网络(与网关同网,agent-service 按容器 IP 直连 AIO HTTP API);compose 网络名 aiwb-agents
AGENT_SANDBOX_NETWORK_NAME: str = os.environ.get("AGENT_SANDBOX_NETWORK_NAME", "aiwb-agents")


def _resolve_volume_root() -> str:
    """解析 workspace 卷的真实宿主路径,供沙箱 bind mount 用。

    优先使用环境变量 AGENT_SANDBOX_VOLUME_ROOT(手动指定)。
    未指定时自动查询 Docker daemon 解析 <compose项目>_workspace 卷的宿主挂载点,
    避免默认路径在容器内不存在导致 bind mount 失败。
    """
    env_val = os.environ.get("AGENT_SANDBOX_VOLUME_ROOT", "").strip()
    if env_val:
        return env_val
    try:
        import docker as _docker

        cli = _docker.from_env()
        # compose 卷命名规则: <project>_workspace
        vol_name = os.environ.get("COMPOSE_PROJECT_NAME", "docker") + "_workspace"
        vol = cli.volumes.get(vol_name)
        mount_point = vol.attrs["Mountpoint"]  # 如 /var/lib/docker/volumes/docker_workspace/_data
        return mount_point + "/users"
    except Exception:
        # 回退:常见 Linux Docker 默认路径
        return "/var/lib/docker/volumes/docker_workspace/_data/users"


# 宿主(daemon 视角)workspace 卷下 users 根:用于按用户 bind 子目录到沙箱工作区。
AGENT_SANDBOX_VOLUME_ROOT: str = _resolve_volume_root()
# 沙箱工作区在 AIO 容器内归 gem 用户(uid 1000);agent-service 会 chown 该用户 project 目录。
AGENT_SANDBOX_UID: int = _int("AGENT_SANDBOX_UID", 1000)
# 是否允许沙箱访问网络(合并浏览器后需开网,execute 也随之可联网)
AGENT_SANDBOX_NETWORK: bool = _bool("AGENT_SANDBOX_NETWORK", True)
AGENT_SANDBOX_MEM_MB: int = _int("AGENT_SANDBOX_MEM_MB", 1024)
AGENT_SANDBOX_CPUS: float = float(os.environ.get("AGENT_SANDBOX_CPUS", "1.0"))
AGENT_SANDBOX_PIDS: int = _int("AGENT_SANDBOX_PIDS", 256)
# 空闲沙箱容器回收(秒)
AGENT_SANDBOX_IDLE_TTL: int = _int("AGENT_SANDBOX_IDLE_TTL", 1800)
# 单条命令超时上限(秒),deepagents 传入的 timeout 会被 clamp 到该值
AGENT_SANDBOX_MAX_TIMEOUT: int = _int("AGENT_SANDBOX_MAX_TIMEOUT", 120)

# --- 服务 ---
AGENT_HOST: str = os.environ.get("AGENT_HOST", "0.0.0.0")
AGENT_PORT: int = _int("AGENT_PORT", 8000)
# 并发加固:多个 uvicorn worker 各自处理请求(进程级并行,配合 PG 连接池)。
AGENT_WORKERS: int = max(1, _int("AGENT_WORKERS", 4))
# 单请求步数上限(每次模型调用/工具执行各计一步)。任务规划卡片会让 AI 高频更新
# write_todos(每次更新=1 步),复杂任务(多文件+验证+浏览器)轻松超 50 步,
# 50 会触发 GraphRecursionError 直接掐断输出(表现为规划卡永远停在第一项转圈)。
AGENT_MAX_TURNS: int = _int("AGENT_MAX_TURNS", 150)
AGENT_LOG_LEVEL: str = os.environ.get("AGENT_LOG_LEVEL", "info")

# --- 后端内部接口(create_skill 工具回写技能库;令牌需与后端 app.internal-token 一致) ---
BACKEND_INTERNAL_URL: str = os.environ.get("BACKEND_INTERNAL_URL", "http://app:8080").rstrip("/")
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

# --- 执行沙箱 ---
# AGENT_SANDBOX_BACKEND:
#   docker = execute 与 /workspace 文件工具全部在 per-user 沙箱容器内执行(推荐,默认)
#   local  = 退回 LocalShellBackend(agent-service 进程内直接跑 shell,仅限本机可信调试)
AGENT_SANDBOX_BACKEND: str = os.environ.get("AGENT_SANDBOX_BACKEND", "docker").strip().lower()
# 沙箱镜像(含 python3 + 文档依赖 + node/playwright/chromium + 浏览器入口;见 agent-service/Dockerfile.sandbox)
AGENT_SANDBOX_IMAGE: str = os.environ.get("AGENT_SANDBOX_IMAGE", "ai-workbench-sandbox:latest")
# 沙箱容器挂入的自定义网络(与网关同网,按容器名直连 CDP/MCP);compose 网络名 aiwb-agents
AGENT_SANDBOX_NETWORK_NAME: str = os.environ.get("AGENT_SANDBOX_NETWORK_NAME", "aiwb-agents")
# 宿主(daemon 视角)workspace 卷下 users 根:用于按用户 bind 子目录到沙箱 /workspace。
# compose 默认把共享卷命名为 docker_workspace,对应 daemon 路径如下。
AGENT_SANDBOX_VOLUME_ROOT: str = os.environ.get(
    "AGENT_SANDBOX_VOLUME_ROOT",
    "/var/lib/docker/volumes/docker_workspace/_data/users",
)
# 沙箱内运行 uid(非 root);agent-service 会先把该用户 project 目录 chown 成此 uid。
AGENT_SANDBOX_UID: int = _int("AGENT_SANDBOX_UID", 10001)
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
AGENT_MAX_TURNS: int = _int("AGENT_MAX_TURNS", 50)
AGENT_LOG_LEVEL: str = os.environ.get("AGENT_LOG_LEVEL", "info")

# --- 后端内部接口(create_skill 工具回写技能库;令牌需与后端 app.internal-token 一致) ---
BACKEND_INTERNAL_URL: str = os.environ.get("BACKEND_INTERNAL_URL", "http://app:8080").rstrip("/")
INTERNAL_TOKEN: str = os.environ.get("INTERNAL_TOKEN", "aiwb-internal-dev")
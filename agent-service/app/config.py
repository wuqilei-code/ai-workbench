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

# --- 服务 ---
AGENT_HOST: str = os.environ.get("AGENT_HOST", "0.0.0.0")
AGENT_PORT: int = _int("AGENT_PORT", 8000)
# 并发加固:多个 uvicorn worker 各自处理请求(进程级并行,配合 PG 连接池)。
AGENT_WORKERS: int = max(1, _int("AGENT_WORKERS", 4))
AGENT_MAX_TURNS: int = _int("AGENT_MAX_TURNS", 50)
AGENT_LOG_LEVEL: str = os.environ.get("AGENT_LOG_LEVEL", "info")
"""创建/缓存基于 deepagents 的每用户 agent。

核心:一个进程内多 agent 实例,靠 LangGraph thread_id 区分并发会话;
每个 user_id 拥有独立 /workspace + 只读 /skills + 用户记忆。
"""

from __future__ import annotations

import hashlib
import threading

from deepagents import FilesystemPermission, create_deep_agent
from deepagents.backends import CompositeBackend, FilesystemBackend, LocalShellBackend
from langchain_core.language_models.chat_models import BaseChatModel

from app import config
from app import workspace as ws

_runtime = None
_RUNTIME_LOCK = threading.Lock()


def _get_runtime():
    global _runtime
    if _runtime is None:
        with _RUNTIME_LOCK:
            if _runtime is None:
                from app.persistence import get_runtime

                _runtime = get_runtime()
    return _runtime


def _build_model() -> BaseChatModel:
    if config.AGENT_MODEL_BASE_URL:
        from langchain_openai import ChatOpenAI

        # 支持 deepseek/ollama/自建网关等 OpenAI 兼容接口:AGENT_MODEL 里剥掉 provider: 前缀
        model = config.AGENT_MODEL.split(":", 1)[-1]
        return ChatOpenAI(
            model=model,
            base_url=config.AGENT_MODEL_BASE_URL,
            api_key=config.AGENT_MODEL_API_KEY or "not-needed",
            temperature=config.AGENT_MODEL_TEMPERATURE,
        )
    from langchain.chat_models import init_chat_model

    return init_chat_model(
        config.AGENT_MODEL,
        temperature=config.AGENT_MODEL_TEMPERATURE,
    )


def _build_backend(user_id: str) -> CompositeBackend:
    return CompositeBackend(
        # execute 只委托 default 后端(非路径路由),故 default 必须支持 shell。
        # 单机自部署:文件读写 + execute 都落在用户工作区,直接可用 python3/pip。
        # 生产多租户隔离场景应换 BaseSandbox(见 deepagents.backends.sandbox)。
        default=LocalShellBackend(
            root_dir=str(ws.workspace_root(user_id)), virtual_mode=True
        ),
        routes={
            "/workspace/": LocalShellBackend(
                root_dir=str(ws.workspace_root(user_id)), virtual_mode=True
            ),
            "/skills/": FilesystemBackend(
                root_dir=str(ws.skills_root(user_id)), virtual_mode=True
            ),
        },
    )


def _build_permissions() -> list[FilesystemPermission]:
    return [
        # 技能目录只读:agent 可读 SKILL.md,但不能改(平台统一投放)
        FilesystemPermission(operations=["write"], paths=["/skills/**"], mode="deny"),
    ]


def _fingerprint(user_id: str, skills: list[str], memory: list[str]) -> str:
    parts = [user_id] + skills + memory
    return hashlib.sha256("\x1f".join(parts).encode()).hexdigest()


class AgentManager:
    """每用户编译后的 agent 缓存;技能/记忆文件被平台更新后自动失效重建。"""

    def __init__(self) -> None:
        self._cache: dict[str, object] = {}
        self._lock = threading.Lock()

    def get_or_create(
        self,
        user_id: str,
        *,
        system_prompt: str | None = None,
        extra_tools: list | None = None,
    ):
        ws.ensure_user_dirs(user_id)
        skills = ws.list_skills(user_id) + ws.list_global_skills()
        memory = ws.memory_files(user_id)
        key = _fingerprint(user_id, skills, memory)

        with self._lock:
            cached = self._cache.get(key)
            if cached is not None:
                return cached

        agent = create_deep_agent(
            model=_build_model(),
            tools=list(extra_tools or []),
            system_prompt=system_prompt
            or (
                "你是用户的 AI 工作助手。所有用户文件都放在 /workspace 下,"
                "请在该目录内处理与生成文件,不要写入其他位置。"
                "可用技能介绍见 /skills 目录,需要时才读取对应的 SKILL.md。"
                "你拥有 execute 工具,可以在 shell 里运行 python3 / pip(网络可用,"
                "如 pip install 或直接 import python-docx)。execute 的当前工作目录"
                "就是 /workspace 对应的真实目录,所以在命令行里请用相对路径读写文件"
                "(例如 python3 gen.py 生成 report.docx);write_file/read_file 的 "
                "/workspace/xxx 与命令行相对路径指向同一个位置,不要混用 /workspace/xxx "
                "做 shell 路径。"
            ),
            backend=_build_backend(user_id),
            permissions=_build_permissions(),
            skills=skills,
            memory=memory,
            interrupt_on=(
                {
                    "write_file": True,
                    "edit_file": True,
                    "delete": True,
                    "execute": True,
                }
                if config.AGENT_HITL
                else None
            ),
            checkpointer=_get_runtime().checkpointer,
            store=_get_runtime().store,
        )

        with self._lock:
            self._cache[key] = agent
            # 容量保护:超过 64 个用户的上线缓存直接清空,交由下次按需重建
            if len(self._cache) > 64:
                self._cache.clear()
        return agent


_manager: AgentManager | None = None
_MANAGER_LOCK = threading.Lock()


def get_manager() -> AgentManager:
    global _manager
    if _manager is None:
        with _MANAGER_LOCK:
            if _manager is None:
                _manager = AgentManager()
    return _manager


def config_for(user_id: str, thread_id: str) -> dict:
    return {
        "configurable": {
            "user_id": user_id,
            "thread_id": thread_id,
            "checkpoint_id": None,
        }
    }
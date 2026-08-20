"""每个用户的目录布局与技能/记忆文件管理。

布局(<AGENT_WS_ROOT>/<user_id>/):
  project/    读写的用户工作区 ← 与前端文件树(app /workspace/users/<uid>/project)同一位置
  skills/     只读技能目录(平台/租户管理员投放,agent 不可改)
  .memory/    用户级别长期记忆(AGENTS.md 标准格式)
"""

from __future__ import annotations

from pathlib import Path

from app.config import AGENT_WS_ROOT


def user_root(user_id: str) -> Path:
    if not user_id or not user_id.replace("-", "").replace("_", "").replace(".", "").isalnum():
        raise ValueError(f"非法 user_id: {user_id!r}")
    return AGENT_WS_ROOT / user_id


def workspace_root(user_id: str) -> Path:
    # 与 app 前端文件树同址:app 侧为 /workspace/users/<uid>/project(共享 workspace 卷)。
    # 调整此处即调整后端 /workspace 虚拟根指向;skills/.memory 仍留在用户根下。
    return user_root(user_id) / "project"


def skills_root(user_id: str) -> Path:
    return user_root(user_id) / "skills"


def memory_root(user_id: str) -> Path:
    return user_root(user_id) / ".memory"


def agent_md(user_id: str) -> Path:
    return memory_root(user_id) / "AGENTS.md"


def ensure_user_dirs(user_id: str) -> None:
    for p in (workspace_root(user_id), skills_root(user_id), memory_root(user_id)):
        p.mkdir(parents=True, exist_ok=True)


def list_skills(user_id: str) -> list[str]:
    """返回该用户可用的技能 SKILL.md 绝对路径(不含工作区,防止 agent 误读)。"""
    root = skills_root(user_id)
    return sorted(str(p) for p in root.rglob("SKILL.md"))


def list_global_skills() -> list[str]:
    """平台级内置技能(缺省技能模板等),通过 AGENT_GLOBAL_SKILLS 配置目录注入。"""
    import os

    root = os.environ.get("AGENT_GLOBAL_SKILLS")
    if not root:
        return []
    return sorted(str(p) for p in Path(root).rglob("SKILL.md"))


def memory_files(user_id: str) -> list[str]:
    """该用户可用的 AGENTS.md 记忆文件(用户级优先,平台级兜底)。"""
    files: list[str] = []
    user_md = agent_md(user_id)
    if user_md.exists():
        files.append(str(user_md))
    global_root = __import__("os").environ.get("AGENT_GLOBAL_MEMORY")
    if global_root:
        g = Path(global_root) / "AGENTS.md"
        if g.exists():
            files.append(str(g))
    return files
"""每个用户的目录布局与技能/记忆文件管理。

布局(<AGENT_WS_ROOT>/<user_id>/):
  project/    读写的用户工作区 ← 与前端文件树(app /workspace/users/<uid>/project)同一位置
  skills/     只读技能目录(平台/租户管理员投放,agent 不可改)
  .memory/    用户级别长期记忆(AGENTS.md 标准格式)
"""

from __future__ import annotations

import os
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
    seed_global_skills(user_id)


_GLOBAL_SKILLS_SRC = Path(os.environ.get("AGENT_GLOBAL_SKILLS") or "/app/skills")


def seed_global_skills(user_id: str) -> None:
    """把平台内置技能(镜像 /app/skills)播种进用户 skills 目录(deepagents /skills/ 虚拟根内,agent 加载用)。

    整目录复制:多文件技能(如 dynamic-ui 的 scenes/templates)需要子文件才能工作。
    """
    import shutil

    if not _GLOBAL_SKILLS_SRC.is_dir():
        return
    for d in _GLOBAL_SKILLS_SRC.iterdir():
        src = d / "SKILL.md"
        if not src.is_file():
            continue
        dst = skills_root(user_id) / d.name / "SKILL.md"
        # 只在缺失时播种;绝不覆盖用户目录中已有的同名技能(可能是用户自建/改过的内容)。
        # dirs_exist_ok=True:多 worker 并发播种时目录可能已被对方建出,幂等覆盖空壳即可。
        if dst.exists():
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(d, skills_root(user_id) / d.name, dirs_exist_ok=True)


def list_skills(user_id: str) -> list[str]:
    """返回该用户可用的技能 SKILL.md 绝对路径(不含工作区,防止 agent 误读)。"""
    root = skills_root(user_id)
    return sorted(str(p) for p in root.rglob("SKILL.md"))


def list_global_skills() -> list[str]:
    """平台级内置技能已由 ensure_user_dirs 播种进各用户 skills 目录(list_skills 覆盖);
    此处保持空,避免 deepagents 直接加载 /app 根外路径失败。"""
    return []


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
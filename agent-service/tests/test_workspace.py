"""workspace 目录布局与校验的单元测试(纯函数,无外部依赖)。"""

import pytest

from app import workspace as ws


def test_user_root_layout():
    assert ws.workspace_root("42").as_posix().endswith("users/42/project")
    assert ws.skills_root("42").as_posix().endswith("users/42/skills")
    assert ws.memory_root("42").as_posix().endswith("users/42/.memory")
    assert ws.agent_md("42").as_posix().endswith("users/42/.memory/AGENTS.md")


def test_user_root_rejects_path_traversal():
    for bad in ("../etc", "a/b", "..", ""):
        with pytest.raises(ValueError):
            ws.user_root(bad)


def test_user_root_accepts_safe_ids():
    assert ws.user_root("u_1-2.3").name == "u_1-2.3"

"""审批模式(HITL)开关:manual 保留人工确认,auto 完全放行,task 始终关闭。

这三条是"完全访问"功能的核心不变式:
- manual:危险命令/删除/浏览器/技能写入都走 interrupt_on,ask_user 也可用;
- auto  :interrupt_on 返回 None(工具直接执行),且 get_or_create 不再挂 ask_user;
- task  :无论前端传什么,都保持无中断(无人值守)。
"""

from app import config
from app.agent_factory import _build_interrupt_on


def test_manual_mode_keeps_interrupts(monkeypatch):
    monkeypatch.setattr(config, "AGENT_HITL", True)
    rules = _build_interrupt_on("chat", "manual")
    assert isinstance(rules, dict)
    for name in ("execute", "delete", "browser_navigate", "create_skill", "ask_user"):
        assert name in rules
    assert rules["execute"]["allowed_decisions"] == ["approve", "reject"]


def test_auto_mode_disables_every_interrupt(monkeypatch):
    monkeypatch.setattr(config, "AGENT_HITL", True)
    assert _build_interrupt_on("chat", "auto") is None


def test_task_mode_stays_disabled_even_in_manual(monkeypatch):
    monkeypatch.setattr(config, "AGENT_HITL", True)
    assert _build_interrupt_on("task", "manual") is None
    assert _build_interrupt_on("task", "auto") is None


def test_global_hitl_off_disables_manual(monkeypatch):
    monkeypatch.setattr(config, "AGENT_HITL", False)
    assert _build_interrupt_on("chat", "manual") is None

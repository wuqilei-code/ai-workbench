"""会话压缩摘要的流式输出通道。

摘要模型调用发生在 agent 的同步执行线程里，而 SSE 生成器在事件循环里。
这里用 ContextVar 暴露一个"当前请求的摘要 sink"，摘要模型每产出一段文本就
调用 sink -> call_soon_threadsafe -> asyncio.Queue，主生成器在等待下一步的同时
把摘要 token 作为 summary_delta 事件发给网关/前端。
"""

from __future__ import annotations

from contextvars import ContextVar
from typing import Callable

_summary_sink: ContextVar[Callable[[str], None] | None] = ContextVar(
    "summary_stream_sink", default=None
)


def set_summary_sink(sink: Callable[[str], None] | None):
    """设置当前执行上下文(含 asyncio.to_thread 复制出的工作线程)的摘要 sink。"""
    return _summary_sink.set(sink)


def reset_summary_sink(token) -> None:
    _summary_sink.reset(token)


def emit_summary_piece(text: str) -> None:
    """从摘要模型线程发送一段增量;没有活跃 sink 时静默丢弃。"""
    if not text:
        return
    sink = _summary_sink.get()
    if sink is None:
        return
    try:
        sink(text)
    except Exception:  # noqa: BLE001
        pass

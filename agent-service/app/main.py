"""agent-service HTTP 入口。

- 同步 invoke:冒烟/简单集成
- SSE 流式 /stream:消息增量 + 工具事件,供 Spring 网关中转给前端
"""

from __future__ import annotations

import json
import logging
from typing import Literal, Optional

from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app import __version__, config
from app.agent_factory import config_for, get_manager

logging.basicConfig(level=config.AGENT_LOG_LEVEL.upper())
logger = logging.getLogger(__name__)

app = FastAPI(title="ai-workbench agent-service", version=__version__)


class ChatMessage(BaseModel):
    role: Literal["user", "assistant", "system", "tool"] = "user"
    content: str


class InvokeRequest(BaseModel):
    thread_id: str = Field(..., min_length=1, max_length=128)
    messages: list[ChatMessage] = Field(..., min_length=1)
    system_prompt: Optional[str] = None


class InvokeResponse(BaseModel):
    thread_id: str
    content: str


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "service": "agent-service",
        "version": __version__,
        "deepagents": _deepagents_version(),
    }


def _deepagents_version() -> str:
    try:
        import deepagents

        return deepagents.__version__
    except Exception:
        return "unknown"


@app.post("/api/users/{user_id}/invoke")
def invoke(user_id: str, body: InvokeRequest) -> InvokeResponse:
    logger.info("[%s][%s] invoke: %d msgs", user_id, body.thread_id, len(body.messages))
    agent = get_manager().get_or_create(user_id, system_prompt=body.system_prompt)
    result = agent.invoke(
        {"messages": [m.model_dump() for m in body.messages]},
        config=config_for(user_id, body.thread_id),
    )
    last = result["messages"][-1]
    content = last.content if hasattr(last, "content") else str(last)
    return InvokeResponse(thread_id=body.thread_id, content=content or "")


@app.post("/api/users/{user_id}/stream")
def stream(user_id: str, body: InvokeRequest) -> StreamingResponse:
    logger.info("[%s][%s] stream: %d msgs", user_id, body.thread_id, len(body.messages))
    agent = get_manager().get_or_create(user_id, system_prompt=body.system_prompt)
    cfg = config_for(user_id, body.thread_id)

    def gen():
        yield _sse("start", {"thread_id": body.thread_id})
        try:
            for mode, value in agent.stream(
                {"messages": [m.model_dump() for m in body.messages]},
                config=cfg,
                stream_mode=["messages", "updates"],
                version="v1",
            ):
                if mode == "messages":
                    for item in _iter_stream_messages(value):
                        out = _emit_stream_message(item)
                        if out:
                            yield out
                elif mode == "updates":
                    for out in _emit_update(value):
                        yield out
        except Exception as exc:  # noqa: BLE001
            logger.exception("[%s][%s] stream error", user_id, body.thread_id)
            yield _sse("error", {"message": str(exc)})
            return
        yield _sse("done", {"status": "ok"})

    return StreamingResponse(
        gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


def _iter_stream_messages(value):
    """messages 模式:value 可能是 [(msg_chunk, meta), ...] 或 [msg_chunk, ...]。"""
    for item in value:
        if isinstance(item, tuple) or isinstance(item, list):
            if len(item) == 2 and hasattr(item[0], "content"):
                yield item[0]
            else:
                for sub in item:
                    yield sub
        else:
            yield item


def _emit_stream_message(msg) -> str:
    """messages 模式中的 token 增量(仅文本)。"""
    content = getattr(msg, "content", "") or ""
    if content and getattr(msg, "type", "").endswith("AIMessageChunk"):
        return _sse("delta", {"content": content})
    return ""


def _emit_update(update) -> list[str]:
    """updates 模式:发出完整的工具调用与工具结果。"""
    outs = []
    if not isinstance(update, dict):
        return outs
    for node, upd in update.items():
        if not isinstance(upd, dict):
            continue
        for msg in upd.get("messages", []) or []:
            # 注意用类名判断:langchain-core 里 ToolMessage.type 是 'tool' 而非 'ToolMessage'
            mtype = type(msg).__name__
            if mtype == "ToolMessage":
                outs.append(
                    _sse(
                        "tool_result",
                        {"name": getattr(msg, "name", ""), "content": (getattr(msg, "content", "") or "")[:2000]},
                    )
                )
            elif getattr(msg, "tool_calls", None):
                for call in msg.tool_calls:
                    outs.append(
                        _sse("tool", {"name": call["name"], "args": call.get("args", {}), "id": call.get("id")})
                    )
    return outs


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=config.AGENT_HOST,
        port=config.AGENT_PORT,
        workers=config.AGENT_WORKERS,
        log_level=config.AGENT_LOG_LEVEL,  # type: ignore[arg-type]
    )
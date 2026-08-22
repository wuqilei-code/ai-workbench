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
from langgraph.types import Command
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
    messages: list[ChatMessage] = Field(default_factory=list)
    system_prompt: Optional[str] = None
    # 中断恢复:提供该字段时不再追加消息,而是以 Command(resume=决策) 从 checkpointer 继续。
    # 决策形状为 langchain HITL:{"decisions":[{"type":"approve"|"reject"|"edit"|"respond",...}]}
    resume_decision: Optional[dict] = None
    # 该会话勾选启用的自定义技能名(内置技能默认加载,无需列出)
    skills: list[str] = Field(default_factory=list)


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
    agent = get_manager().get_or_create(user_id, system_prompt=body.system_prompt, skills_override=body.skills)
    result = agent.invoke(
        {"messages": [m.model_dump() for m in body.messages]},
        config=config_for(user_id, body.thread_id),
    )
    last = result["messages"][-1]
    content = last.content if hasattr(last, "content") else str(last)
    return InvokeResponse(thread_id=body.thread_id, content=content or "")


@app.post("/api/users/{user_id}/skills/seed")
def skills_seed(user_id: str) -> dict:
    """确保该用户已播种平台内置技能(仅拷文件,不起容器);技能栏/前端按需调用。"""
    from app import workspace as ws

    ws.ensure_user_dirs(user_id)
    return {"status": "ok"}


@app.post("/api/users/{user_id}/sandbox/ensure")
def sandbox_ensure(user_id: str) -> dict:
    """确保该用户合并沙箱(execute + 浏览器 daemon)容器已创建并运行;网关浏览器面板按需调用。"""
    from app.sandbox import get_docker_sandbox

    sb = get_docker_sandbox(user_id)
    try:
        container = sb.ensure()
        return {"status": "ok", "container": container.name}
    except Exception as exc:  # noqa: BLE001
        logger.warning("[%s] sandbox ensure failed: %s", user_id, exc)
        return {"status": "error", "error": str(exc)}


@app.post("/api/users/{user_id}/stream")
def stream(user_id: str, body: InvokeRequest) -> StreamingResponse:
    logger.info("[%s][%s] stream: resume=%s msgs=%d",
                user_id, body.thread_id, body.resume_decision is not None, len(body.messages))
    agent = get_manager().get_or_create(user_id, system_prompt=body.system_prompt, skills_override=body.skills)
    cfg = config_for(user_id, body.thread_id)

    def gen():
        yield _sse("start", {"thread_id": body.thread_id})
        try:
            if body.resume_decision is not None:
                # 中断恢复:以 Command(resume=决策) 继续现有线程(hitl 审批/拒绝后)
                it = agent.stream(
                    Command(resume=body.resume_decision),
                    config=cfg,
                    stream_mode=["messages", "updates"],
                    version="v1",
                )
            else:
                it = agent.stream(
                    {"messages": [m.model_dump() for m in body.messages]},
                    config=cfg,
                    stream_mode=["messages", "updates"],
                    version="v1",
                )
            for mode, value in it:
                if mode == "messages":
                    for item in _iter_stream_messages(value):
                        out = _emit_stream_message(item)
                        if out:
                            yield out
                elif mode == "updates":
                    # Human-in-the-loop 中断:吐出待审批请求后终止本流(线程已 checkpoint),
                    # 不再发 done;由网关收到用户确认后以 resume_decision 重新起流续跑。
                    if isinstance(value, dict) and "__interrupt__" in value:
                        payload = value["__interrupt__"][0]
                        hreq = getattr(payload, "value", payload)
                        logger.info("[%s][%s] hitl interrupt: %s",
                                    user_id, body.thread_id, str(hreq)[:200])
                        yield _sse("permission", hreq if isinstance(hreq, dict) else {})
                        return
                    for out in _emit_update(value, user_id):
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


def _emit_update(update, user_id: str | None = None) -> list[str]:
    """updates 模式:发出完整的工具调用与工具结果(含结构化输出物路径)。"""
    outs = []
    if not isinstance(update, dict):
        return outs
    msgs = []
    for node, upd in update.items():
        if isinstance(upd, dict):
            msgs += upd.get("messages", []) or []
    # 本轮 update 内的配对:tool_call_id -> (name, args)。
    # deepagents 可能把"AI 发起调用"与"工具结果"拆成两次 update,故用模块级最近调用表兜底。
    calls = dict(_TOOL_CALLS)
    for m in msgs:
        tcs = getattr(m, "tool_calls", None) or []
        for c in tcs:
            cid = c.get("id")
            if cid:
                calls[cid] = (c.get("name", ""), c.get("args", {}) or {})
    for msg in msgs:
        # 注意用类名判断:langchain-core 里 ToolMessage.type 是 'tool' 而非 'ToolMessage'
        mtype = type(msg).__name__
        if mtype == "ToolMessage":
            outputs = []
            tc_id = getattr(msg, "tool_call_id", "") or ""
            call = calls.get(tc_id)
            if call:
                outputs = _outputs_for(call[0], call[1])
                # execute 的 shell 写文件:从沙箱目录 diff 的最近变更获取"输出物"路径
                if call[0] in ("execute", "run") and user_id is not None:
                    sandbox = _current_sandbox(user_id)
                    if sandbox is not None:
                        exec_outputs = sandbox.pop_recent_changes()
                        if exec_outputs:
                            outputs = [{"path": p} for p in exec_outputs]
            payload = {
                "name": getattr(msg, "name", ""),
                "content": (getattr(msg, "content", "") or "")[:2000],
                "id": tc_id,
            }
            if outputs:
                payload["outputs"] = outputs
            outs.append(_sse("tool_result", payload))
        elif getattr(msg, "tool_calls", None):
            for call in msg.tool_calls:
                cid = call.get("id")
                if cid:
                    _TOOL_CALLS[cid] = (call.get("name", ""), call.get("args", {}) or {})
                    if len(_TOOL_CALLS) > 128:
                        # 简单容量保护:保留最近一半
                        for k in list(_TOOL_CALLS)[:64]:
                            _TOOL_CALLS.pop(k, None)
                outs.append(
                    _sse("tool", {"name": call["name"], "args": call.get("args", {}), "id": call.get("id")})
                )
    return outs


# 跨 update 的工具调用配对(模块级,容量有上限;key= tool_call_id)
_TOOL_CALLS: dict[str, tuple[str, dict]] = {}


def _current_sandbox(user_id: str):
    """取该用户当前沙箱后端(可能是 CompositeBackend 包装);无或非 docker 返回 None。"""
    try:
        from app.sandbox import get_docker_sandbox

        return get_docker_sandbox(user_id)
    except Exception:  # noqa: BLE001
        return None


def _outputs_for(tool_name: str, args: dict) -> list:
    """从文件写入类工具参数中提炼"输出物":确定写出的文件路径(相对 /workspace)。

    只对明确会产出/修改单个文件的工具给出 outputs;execute 因可能产出任意多个文件,
    不在此推断(agent 可按 [结果文件] 标记补充或由前端从写操作 args 兜底)。
    """
    name = tool_name or ""
    rel = None
    if not isinstance(args, dict):
        args = {}
    if name in ("write", "write_file", "edit", "edit_file", "append", "append_file", "patch"):
        rel = args.get("file_path") or args.get("path") or args.get("file")
    elif name in ("rename", "move"):
        rel = args.get("new_path") or args.get("destination") or args.get("path")
    if not rel or not isinstance(rel, str) or not rel.strip():
        return []
    rel = rel.strip().replace("\\", "/")
    if rel.startswith("/workspace/"):
        rel = rel[len("/workspace/"):]
    elif rel.startswith("/workspace"):
        rel = rel[len("/workspace"):]
    rel = rel.lstrip("/")
    if not rel:
        return []
    return [{"path": rel}]


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=config.AGENT_HOST,
        port=config.AGENT_PORT,
        workers=config.AGENT_WORKERS,
        log_level=config.AGENT_LOG_LEVEL,  # type: ignore[arg-type]
    )
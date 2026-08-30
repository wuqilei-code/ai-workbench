"""agent-service HTTP 入口。

- 同步 invoke:冒烟/简单集成
- SSE 流式 /stream:消息增量 + 工具事件,供 Spring 网关中转给前端
"""

from __future__ import annotations

import asyncio
import hmac
import json
import logging
import re
from typing import Literal, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse
from langgraph.types import Command
from pydantic import BaseModel, Field

from app import __version__, config
from app.agent_factory import config_for, get_manager

logging.basicConfig(level=config.AGENT_LOG_LEVEL.upper())
logger = logging.getLogger(__name__)

app = FastAPI(title="ai-workbench agent-service", version=__version__)

# 排除鉴权的路径:健康检查(healthcheck/冒烟用)、以及内部工具回写无需二次鉴权。
_PUBLIC_PATHS = {"/health"}


def verify_internal_token(
    request: Request,
    x_internal_token: str | None = Header(default=None, alias="X-Internal-Token"),
) -> None:
    """校验网关/内部调用的共享令牌,拒绝未授权来源。

    agent-service 本身无用户态身份(调用方已按 user_id 维度鉴权),这里只守住
    服务间边界:任何能访问 :8000 的进程必须持有与网关一致的 INTERNAL_TOKEN。
    """
    if request.url.path in _PUBLIC_PATHS:
        return
    expected = (config.INTERNAL_TOKEN or "").strip()
    if not expected:
        raise HTTPException(status_code=500, detail="INTERNAL_TOKEN 未配置，拒绝内部调用")
    provided = (x_internal_token or "").strip()
    # 常量时间比较,避免定时侧信道
    if not hmac.compare_digest(provided.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="无效的内部令牌")


def require_token() -> dict:
    """占位依赖:仅用于对个别端点显式声明需要令牌(语义清晰)。"""
    return {}


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


@app.post("/api/users/{user_id}/invoke", dependencies=[Depends(verify_internal_token)])
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


@app.post("/api/users/{user_id}/skills/seed", dependencies=[Depends(verify_internal_token)])
def skills_seed(user_id: str) -> dict:
    """确保该用户已播种平台内置技能(仅拷文件,不起容器);技能栏/前端按需调用。"""
    from app import workspace as ws

    ws.ensure_user_dirs(user_id)
    return {"status": "ok"}


@app.post("/api/users/{user_id}/sandbox/ensure", dependencies=[Depends(verify_internal_token)])
def sandbox_ensure(user_id: str) -> dict:
    """确保该用户合并沙箱容器已创建运行,并按需拉起浏览器栈(懒启动,已就绪秒回)。

    网关浏览器面板 warmup 调用;AI 浏览器工具走 browser.py 内部 ensure_browser。
    """
    from app.sandbox import get_docker_sandbox

    sb = get_docker_sandbox(user_id)
    try:
        container = sb.ensure()
        sb.ensure_browser()
        return {"status": "ok", "container": container.name}
    except Exception as exc:  # noqa: BLE001
        logger.warning("[%s] sandbox ensure failed: %s", user_id, exc)
        return {"status": "error", "error": str(exc)}


@app.post("/api/users/{user_id}/sandbox/stop", dependencies=[Depends(verify_internal_token)])
def sandbox_stop(user_id: str) -> dict:
    """停止该用户的沙箱容器(浏览器面板「关闭浏览器」):立即释放 CPU/内存。

    容器保留(不删除),下次 ensure / AI 浏览器工具按需重新创建并拉起浏览器栈。
    """
    from app.sandbox import get_docker_sandbox

    sb = get_docker_sandbox(user_id)
    try:
        sb.stop()
        return {"status": "ok"}
    except Exception as exc:  # noqa: BLE001
        logger.warning("[%s] sandbox stop failed: %s", user_id, exc)
        return {"status": "error", "error": str(exc)}


@app.post("/api/users/{user_id}/stream", dependencies=[Depends(verify_internal_token)])
def stream(request: Request, user_id: str, body: InvokeRequest) -> StreamingResponse:
    logger.info("[%s][%s] stream: resume=%s msgs=%d",
                user_id, body.thread_id, body.resume_decision is not None, len(body.messages))
    agent = get_manager().get_or_create(user_id, system_prompt=body.system_prompt, skills_override=body.skills)
    cfg = config_for(user_id, body.thread_id)

    async def gen():
        emitted_tools: set[str] = set()
        tool_calls: dict[str, tuple[str, dict]] = {}
        wc_decoders: dict[str, "_WidgetCodeDecoder"] = {}
        yield _sse("start", {"thread_id": body.thread_id})
        try:
            if body.resume_decision is not None:
                # 中断恢复:以 Command(resume=决策) 继续现有线程(hitl 审批/拒绝后)
                it = agent.stream(
                    Command(resume=_align_decisions(agent, cfg, body.resume_decision)),
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
            # 同步 langgraph 迭代器经线程拉取,避免阻塞事件循环;每步间检查客户端断连。
            while True:
                if await request.is_disconnected():
                    logger.info("[%s][%s] client disconnected, abort stream",
                                user_id, body.thread_id)
                    # 线程已由 langgraph checkpoint;无需回滚,直接终止本流
                    return
                result = await asyncio.to_thread(_next_stream, it)
                if result is None:
                    # 迭代器耗尽,正常结束。不能依赖 StopIteration:经线程池会被
                    # asyncio 转成 RuntimeError,导致正常完成被误判为流错误,
                    # 进而使网关给前端发 error 事件、总结文本随流错误被丢弃。
                    break
                mode, value = result
                if mode == "messages":
                    for item in _iter_stream_messages(value):
                        for out in _emit_stream_message(item, wc_decoders):
                            yield out
                elif mode == "updates":
                    # Human-in-the-loop 中断:吐出待审批请求后终止本流(线程已 checkpoint),
                    # 不再发 done;由网关收到用户确认后以 resume_decision 重新起流续跑。
                    if isinstance(value, dict) and "__interrupt__" in value:
                        payload = value["__interrupt__"][0]
                        hreq = getattr(payload, "value", payload)
                        logger.info("[%s][%s] hitl interrupt: %s",
                                    user_id, body.thread_id, str(hreq)[:200])
                        # 检查是否为 ask_user 工具的提问
                        tool_name = ""
                        tool_args = {}
                        if isinstance(hreq, dict):
                            action_requests = hreq.get("action_requests", [])
                            if action_requests:
                                req = action_requests[0]
                                tool_name = req.get("name", "")
                                tool_args = req.get("args", {})
                        if tool_name == "ask_user":
                            # agent 向用户提问:提取问题内容,发送 question 事件
                            question = tool_args.get("question", "请提供更多信息")
                            options_str = tool_args.get("options", "")
                            multiple = tool_args.get("multiple", "false") == "true"
                            yield _sse("question", {
                                "id": hreq.get("id", "") if isinstance(hreq, dict) else "",
                                "question": question,
                                "tool": "ask_user",
                                "options": [o.strip() for o in options_str.split("|") if o.strip()] if options_str else [],
                                "multiple": multiple,
                            })
                        else:
                            # 普通 HITL 审批
                            yield _sse("permission", hreq if isinstance(hreq, dict) else {})
                        return
                    for out in _emit_update(value, user_id, emitted_tools, tool_calls, wc_decoders):
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


def _next_stream(it):
    """在线程池中推进同步 langgraph 迭代器,返回 (mode, value);耗尽返回 None。

    注意:不能靠抛 StopIteration 表示耗尽——asyncio.to_thread 会把线程里抛出的
    StopIteration 转成 RuntimeError("StopIteration interacts badly with generators"),
    导致正常完成被误判为流错误(网关收到 error 事件,前端总结文本被丢弃)。"""
    try:
        return next(it)
    except StopIteration:
        return None


def _align_decisions(agent, cfg, decision: dict) -> dict:
    """把网关的单条决策对齐到挂起工具调用数量。

    多个工具同时被中断时(如 create_skill + delete 同轮),langgraph 要求
    decisions 数组长度与挂起调用数一致,否则 ValueError;此处按线程状态里的
    action_requests 数量复制首条决策(前端当前只提供全局允许/拒绝)。
    """
    try:
        state = agent.get_state(cfg)
        total = 0
        for itp in (getattr(state, "interrupts", None) or ()):
            val = getattr(itp, "value", itp)
            reqs = val.get("action_requests") if isinstance(val, dict) else None
            total += len(reqs) if reqs else 1
        decisions = decision.get("decisions") if isinstance(decision, dict) else None
        if total > 1 and decisions:
            return {"decisions": [decisions[0]] * total}
    except Exception:  # noqa: BLE001
        logger.warning("align resume decisions failed", exc_info=True)
    return decision


def _emit_stream_message(msg, wc_decoders: dict) -> list[str]:
    """messages 模式中的 token 增量:reasoning_content → reason 事件,正文文本 → delta 事件,
    pure_show_widget 参数流 → widget_delta 事件(逐块流式渲染组件)。
    wc_decoders:本流独有的 widget 参数解码器表(避免跨流/跨用户串扰)。"""
    outs: list[str] = []
    if not getattr(msg, "type", "").endswith("AIMessageChunk"):
        return outs
    ak = getattr(msg, "additional_kwargs", None) or {}
    rc = ak.get("reasoning_content") if isinstance(ak, dict) else None
    if rc:
        outs.append(_sse("reason", {"content": str(rc)}))
    content = getattr(msg, "content", "") or ""
    if content and isinstance(content, str):
        outs.append(_sse("delta", {"content": content}))
    for tcc in getattr(msg, "tool_call_chunks", None) or []:
        # 兼容 dict 与 ToolCallChunk 对象两种形态
        if isinstance(tcc, dict):
            get = tcc.get
        else:
            get = lambda k, d=None: getattr(tcc, k, d)  # noqa: E731
        # 先按 name 注册解码器:首个 chunk 带 name 但 args 为空,
        # 后续片段有 args 却不带 name——顺序反了会永远建不了解码器。
        keys = [str(k) for k in (get("id"), f"idx{get('index')}") if k]
        dec = next((wc_decoders[k] for k in keys if k in wc_decoders), None)
        if dec is None:
            if get("name") != "pure_show_widget":
                continue  # 其他工具的参数流不跟踪(仅首个 chunk 带 name)
            dec = _WidgetCodeDecoder(keys)
            for k in keys:
                wc_decoders[k] = dec
        frag = get("args") or ""
        if not isinstance(frag, str) or not frag:
            continue
        try:
            piece = dec.feed(frag)
        except Exception:  # noqa: BLE001
            for k in dec.keys:
                wc_decoders.pop(k, None)
            continue
        if piece:
            outs.append(_sse("widget_delta", {"id": get("id"), "delta": piece}))
    return outs


class _WidgetCodeDecoder:
    r"""增量解码 pure_show_widget 参数中的 widget_code 字符串(JSON 转义 → 原文)。

    参数是流式到达的 JSON 文本片段;此状态机跳过 `{"widget_code": "` 前缀,
    之后把转义序列(\n \" \\ \uXXXX…)还原为原文并按到达顺序吐出,
    使前端能在模型生成过程中逐块渲染组件。
    """

    _PREFIX = re.compile(r'"widget_code"\s*:\s*"')
    _SIMPLE = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\", "/": "/", "b": "\b", "f": "\f"}

    def __init__(self, keys: list[str]) -> None:
        self.keys = keys    # 该解码器注册的全部键(id + index),清理时一并弹出
        self._buf = ""      # 尚未消费的原始片段
        self._pending = ""  # 跨 chunk 的不完整转义序列
        self._stage = 0     # 0=等前缀, 1=字符串内, 2=已结束

    def feed(self, text: str) -> str:
        if self._stage >= 2:
            return ""
        self._buf += text
        if self._stage == 0:
            m = self._PREFIX.search(self._buf)
            if not m:
                # 前缀未到:保留尾部即可(前缀最长 ~20 字符)
                if len(self._buf) > 128:
                    self._buf = self._buf[-64:]
                return ""
            self._buf = self._buf[m.end():]
            self._stage = 1
        data = self._pending + self._buf
        self._buf = ""
        self._pending = ""
        res: list[str] = []
        i, n = 0, len(data)
        while i < n:
            ch = data[i]
            if ch == '"':
                self._stage = 2  # 字符串闭合,后面没有需要的内容
                break
            if ch == "\\":
                if i + 1 >= n:
                    self._pending = data[i:]  # 等下一个 chunk 补全转义
                    break
                esc = data[i + 1]
                if esc == "u":
                    if i + 6 > n:
                        self._pending = data[i:]
                        break
                    try:
                        res.append(chr(int(data[i + 2:i + 6], 16)))
                    except ValueError:
                        res.append(data[i:i + 6])
                    i += 6
                    continue
                res.append(self._SIMPLE.get(esc, esc))
                i += 2
                continue
            res.append(ch)
            i += 1
        return "".join(res)


# 读 SKILL.md = 加载技能动作(deepagents 渐进式披露:先读说明再照做)。
# 捕获后合成 use_skill 工具调用记录,前端/持久化/溯源均按普通工具调用处理。
_SKILL_MD_RE = re.compile(r"^/?skills/([a-z0-9][a-z0-9-]{0,63})/SKILL\.md$", re.IGNORECASE)


def _skill_from_read(name: str, args: dict) -> str | None:
    if name not in ("read", "read_file"):
        return None
    p = args.get("file_path") or args.get("path") or args.get("file") or ""
    m = _SKILL_MD_RE.match(str(p).strip().replace("\\", "/"))
    return m.group(1).lower() if m else None


def _emit_update(update, user_id: str | None = None, emitted_tools: set | None = None,
                 tool_calls: dict | None = None, wc_decoders: dict | None = None) -> list[str]:
    """updates 模式:发出完整的工具调用与工具结果(含结构化输出物路径)。
    emitted_tools:本轮流内已发过 tool 事件的调用 id 集合——同一调用会在多个节点更新里
    出现,不去重的话网关时间线会出现重复条目(前端渲染成空步骤)。
    tool_calls/wc_decoders:本流独有的配对表与 widget 解码器表(避免跨流串扰)。"""
    outs = []
    if tool_calls is None:
        tool_calls = {}
    if wc_decoders is None:
        wc_decoders = {}
    if not isinstance(update, dict):
        return outs
    msgs = []
    for node, upd in update.items():
        if isinstance(upd, dict):
            msgs += upd.get("messages", []) or []
    # 本轮 update 内的配对:tool_call_id -> (name, args)。
    # deepagents 可能把"AI 发起调用"与"工具结果"拆成两次 update,故用本流最近调用表兜底。
    calls = dict(tool_calls)
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
                # pure_show_widget:路径由工具运行时生成,从返回内容提取
                if call[0] == "pure_show_widget" and not outputs:
                    m = re.search(r"\[WIDGET_FILE\]\s*(\S+)", getattr(msg, "content", "") or "")
                    if m:
                        outputs = [{"path": m.group(1)}]
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
            if tc_id:
                dec = wc_decoders.get(tc_id)
                if dec is not None:
                    for k in dec.keys:
                        wc_decoders.pop(k, None)
            outs.append(_sse("tool_result", payload))
            # 技能加载溯源:读 /skills/<名>/SKILL.md ⇒ 合成 use_skill 记录(带技能名)
            skill = _skill_from_read(call[0], call[1]) if call else None
            if skill:
                sid = f"skill-{tc_id}"
                outs.append(_sse("tool", {"name": "use_skill", "args": {"skill": skill}, "id": sid}))
                outs.append(_sse("tool_result", {
                    "name": "use_skill",
                    "id": sid,
                    "content": f"已加载技能「{skill}」的使用说明",
                }))
        elif getattr(msg, "tool_calls", None):
            for call in msg.tool_calls:
                cid = call.get("id")
                if cid and emitted_tools is not None:
                    if cid in emitted_tools:
                        continue  # 同一调用已在先前节点更新里发过,跳过
                    emitted_tools.add(cid)
                if cid:
                    tool_calls[cid] = (call.get("name", ""), call.get("args", {}) or {})
                    if len(tool_calls) > 128:
                        # 简单容量保护:保留最近一半(本流内也极少超限,防御性处理)
                        for k in list(tool_calls)[:64]:
                            tool_calls.pop(k, None)
                outs.append(
                    _sse("tool", {"name": call["name"], "args": call.get("args", {}), "id": call.get("id")})
                )
    return outs


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
"""一轮对话内每次模型调用的溯源记录。

一个回合里 agent 可能调用模型 N 次(每次工具调用后都要再问一次模型)。网关目前只把
token 累加到 chat_message,查不到"第几次调用、问了什么、回了什么、耗时多久"。

这里用 LangChain callback 捕获每次调用的:
- 请求:messages(含历史/工具结果)、tools schema、模型参数(不含 api_key)
- 响应:文本、tool_calls、finish_reason
- 指标:input/output/reasoning/cache、首 token 延迟、总耗时、错误

payload 以 gzip+base64 随 SSE `llm_call` 事件发出(网关落库),事件体积有上限,
超限时只保留最近若干条消息并标记 truncated。落库前做一次敏感信息脱敏。

设计取舍:
- 不直接写库:agent-service 无 MySQL 依赖,统一走 SSE 由网关落库;
- 不做增量/vs 父请求 diff:P1/P2 先用全量 + gzip,后续再优化;
- 回调在 langgraph 的工作线程里触发,收集结果用队列 + drain 由主生成器发出。
"""

from __future__ import annotations

import base64
import gzip
import json
import logging
import re
import threading
import time
import uuid
from typing import Any

from langchain_core.callbacks import BaseCallbackHandler

logger = logging.getLogger(__name__)

# 单个 payload(gzip 后 base64)上限:超过则只保留最近的消息并标记截断
MAX_PAYLOAD_B64 = 480_000
# 截断时保留的消息条数(通常最后几条是本次新增的工具结果)
TRUNCATE_KEEP_MESSAGES = 8

_SECRET_PATTERNS = [
    re.compile(r"(sk-[A-Za-z0-9_\-]{12,})"),
    re.compile(r"(?i)(authorization\s*[:=]\s*)([^\s\"',}]+)"),
    re.compile(r"(?i)(\"?(?:api[_-]?key|access[_-]?token|secret|password|cookie)\"?\s*[:=]\s*\")([^\"]{6,})(\")"),
]


def redact(text: str) -> str:
    """粗粒度脱敏:API Key / Authorization / cookie / password 等。"""
    if not text:
        return text
    out = text
    out = _SECRET_PATTERNS[0].sub("sk-***", out)
    out = _SECRET_PATTERNS[1].sub(lambda m: m.group(1) + "***", out)
    out = _SECRET_PATTERNS[2].sub(lambda m: m.group(1) + "***" + m.group(3), out)
    return out


def _jsonable(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, dict):
        return {str(k): _jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_jsonable(v) for v in value]
    return str(value)


def _msg_to_dict(m: Any) -> dict:
    """把 BaseMessage 归一成可读 JSON(role/content/tool_calls/tool_call_id)。"""
    mtype = getattr(m, "type", None) or m.__class__.__name__.lower()
    role = {
        "human": "user",
        "ai": "assistant",
        "system": "system",
        "tool": "tool",
        "function": "tool",
    }.get(str(mtype), str(mtype))
    d: dict[str, Any] = {"role": role}
    content = getattr(m, "content", "")
    d["content"] = _jsonable(content)
    tool_calls = getattr(m, "tool_calls", None)
    if tool_calls:
        d["tool_calls"] = _jsonable(tool_calls)
    tool_call_id = getattr(m, "tool_call_id", None)
    if tool_call_id:
        d["tool_call_id"] = tool_call_id
    name = getattr(m, "name", None)
    if name:
        d["name"] = name
    return d


def _tool_names(tools: Any) -> list[str]:
    names: list[str] = []
    if isinstance(tools, list):
        for t in tools:
            if isinstance(t, dict):
                fn = t.get("function") if isinstance(t.get("function"), dict) else None
                n = (fn or {}).get("name") or t.get("name")
                if n:
                    names.append(str(n))
            else:
                n = getattr(t, "name", None)
                if n:
                    names.append(str(n))
    return names


def _gzip_b64(payload: Any) -> tuple[str, int]:
    raw = redact(json.dumps(payload, ensure_ascii=False, default=str)).encode("utf-8")
    gz = gzip.compress(raw, compresslevel=6)
    return base64.b64encode(gz).decode("ascii"), len(gz)


class _TraceHandler(BaseCallbackHandler):
    def __init__(self, collector: "TraceCollector") -> None:
        self._c = collector

    # 每次模型调用开始:记录模型、参数、工具 schema、输入 messages
    def on_chat_model_start(self, serialized, messages, *, run_id, parent_run_id=None,
                            tags=None, metadata=None, **kwargs):
        try:
            params = dict(kwargs.get("invocation_params") or {})
            # 去掉敏感字段
            for k in ("api_key", "openai_api_key", "azure_ad_token"):
                params.pop(k, None)
            model = (
                params.get("model")
                or params.get("model_name")
                or (serialized or {}).get("kwargs", {}).get("model_name")
                or (serialized or {}).get("name")
                or ""
            )
            tools = params.pop("tools", None)
            flat: list[dict] = []
            for batch in messages or []:
                for m in batch:
                    flat.append(_msg_to_dict(m))
            self._c.start_call(
                str(run_id),
                model=str(model or ""),
                params=_jsonable(params),
                tool_names=_tool_names(tools),
                tools_count=len(tools) if isinstance(tools, list) else 0,
                messages=flat,
            )
        except Exception as exc:  # noqa: BLE001
            logger.debug("[trace] on_chat_model_start failed: %s", exc)

    def on_llm_new_token(self, token, *, chunk=None, run_id, parent_run_id=None, tags=None, **kwargs):
        try:
            self._c.first_token(str(run_id))
        except Exception:  # noqa: BLE001
            pass

    def on_llm_end(self, response, *, run_id, parent_run_id=None, tags=None, **kwargs):
        try:
            content = ""
            tool_calls: list = []
            finish_reason = None
            usage: dict = {}
            try:
                gen = (response.generations or [[None]])[0][0]
                msg = getattr(gen, "message", None)
                if msg is not None:
                    content = getattr(msg, "content", "") or ""
                    tool_calls = _jsonable(getattr(msg, "tool_calls", None) or [])
                    meta = getattr(msg, "response_metadata", None) or {}
                    finish_reason = meta.get("finish_reason") or meta.get("stop_reason")
                    um = getattr(msg, "usage_metadata", None)
                    if isinstance(um, dict):
                        usage = um
                else:
                    content = getattr(gen, "text", "") or ""
            except Exception:  # noqa: BLE001
                pass
            llm_output = getattr(response, "llm_output", None) or {}
            tu = llm_output.get("token_usage") or llm_output.get("usage") or {}
            ind = usage.get("input_token_details") or {}
            outd = usage.get("output_token_details") or {}
            self._c.end_call(
                str(run_id),
                response={
                    "content": _jsonable(content),
                    "tool_calls": tool_calls,
                    "finish_reason": finish_reason,
                },
                usage={
                    "input": usage.get("input_tokens", tu.get("prompt_tokens", 0)) or 0,
                    "output": usage.get("output_tokens", tu.get("completion_tokens", 0)) or 0,
                    "reasoning": outd.get("reasoning", 0) or 0,
                    "cacheRead": ind.get("cache_read", 0) or 0,
                    "cacheWrite": ind.get("cache_creation", 0) or 0,
                },
                finish_reason=str(finish_reason or ""),
                tool_calls=len(tool_calls),
            )
        except Exception as exc:  # noqa: BLE001
            logger.debug("[trace] on_llm_end failed: %s", exc)

    def on_llm_error(self, error, *, run_id, parent_run_id=None, tags=None, **kwargs):
        try:
            self._c.fail_call(str(run_id), f"{type(error).__name__}: {error}")
        except Exception:  # noqa: BLE001
            pass


class TraceCollector:
    """线程安全的调用收集器:回调线程写入,生成器线程 drain 后发 SSE。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._pending: dict[str, dict] = {}
        self._done: list[dict] = []
        self._index = 0
        self._truncated = False

    @property
    def truncated(self) -> bool:
        """本轮是否有模型调用因 max_tokens 截断且带工具调用(需中止)。"""
        with self._lock:
            return self._truncated

    def handler(self) -> BaseCallbackHandler:
        return _TraceHandler(self)

    def start_call(self, run_id: str, *, model: str, params: dict, tool_names: list[str],
                   tools_count: int, messages: list[dict]) -> None:
        with self._lock:
            self._pending[run_id] = {
                "model": model,
                "params": params,
                "toolNames": tool_names,
                "toolsCount": tools_count,
                "messages": messages,
                "t0": time.time(),
                "ttft": None,
            }

    def first_token(self, run_id: str) -> None:
        with self._lock:
            rec = self._pending.get(run_id)
            if rec is not None and rec["ttft"] is None:
                rec["ttft"] = time.time()

    def end_call(self, run_id: str, *, response: dict, usage: dict,
                 finish_reason: str, tool_calls: int) -> None:
        with self._lock:
            rec = self._pending.pop(run_id, None)
            if rec is None:
                return
            now = time.time()
            latency = int((now - rec["t0"]) * 1000)
            ttft = int((rec["ttft"] - rec["t0"]) * 1000) if rec["ttft"] else None
            if finish_reason in ("length", "max_tokens") and tool_calls > 0:
                self._truncated = True
            self._index += 1
            req_payload = {
                "model": rec["model"],
                "params": rec["params"],
                "tools": rec["toolNames"],
                "toolCount": rec["toolsCount"],
                "messages": rec["messages"],
            }
            req_b64, req_bytes = _encode(req_payload, key="messages")
            resp_b64, resp_bytes = _encode(response)
            self._done.append({
                "callId": run_id or uuid.uuid4().hex,
                "index": self._index,
                "model": rec["model"],
                "inputTokens": int(usage.get("input") or 0),
                "outputTokens": int(usage.get("output") or 0),
                "reasoningTokens": int(usage.get("reasoning") or 0),
                "cacheReadTokens": int(usage.get("cacheRead") or 0),
                "cacheWriteTokens": int(usage.get("cacheWrite") or 0),
                "latencyMs": latency,
                "ttftMs": ttft,
                "finishReason": finish_reason,
                "status": "ok",
                "error": "",
                "toolCalls": tool_calls,
                "messageCount": len(rec["messages"]),
                "requestBytes": req_bytes,
                "responseBytes": resp_bytes,
                "requestB64": req_b64,
                "responseB64": resp_b64,
            })

    def fail_call(self, run_id: str, error: str) -> None:
        with self._lock:
            rec = self._pending.pop(run_id, None)
            if rec is None:
                return
            self._index += 1
            self._done.append({
                "callId": run_id or uuid.uuid4().hex,
                "index": self._index,
                "model": rec["model"],
                "inputTokens": 0, "outputTokens": 0, "reasoningTokens": 0,
                "cacheReadTokens": 0, "cacheWriteTokens": 0,
                "latencyMs": int((time.time() - rec["t0"]) * 1000), "ttftMs": None,
                "finishReason": "", "status": "error", "error": error[:2000],
                "toolCalls": 0, "messageCount": len(rec["messages"]),
                "requestB64": "", "responseB64": "",
            })

    def drain(self) -> list[dict]:
        with self._lock:
            out = self._done
            self._done = []
            return out

    def discard(self) -> None:
        with self._lock:
            self._pending.clear()
            self._done.clear()


def _encode(payload: dict, key: str | None = None) -> tuple[str, int]:
    """序列化+gzip+base64;超过上限时截断消息列表(保留最近 N 条)。"""
    b64, size = _gzip_b64(payload)
    if len(b64) <= MAX_PAYLOAD_B64 or not key:
        return b64, size
    trimmed = dict(payload)
    msgs = trimmed.get(key) or []
    trimmed["truncated"] = True
    trimmed["omittedMessages"] = max(0, len(msgs) - TRUNCATE_KEEP_MESSAGES)
    trimmed[key] = msgs[-TRUNCATE_KEEP_MESSAGES:]
    return _gzip_b64(trimmed)

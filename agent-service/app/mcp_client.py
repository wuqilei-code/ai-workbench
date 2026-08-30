"""加载用户配置的远程 MCP server 工具(deepagents 兼容)。

deepagents 在本项目以同步 `agent.stream()` 运行,而 langchain-mcp-adapters 生成的
`StructuredTool` 是 async-only(`StructuredTool does not support sync invocation`)。
这里把每个工具包装成同时具备 sync/async 实现的结构化工具,sync 路径经 `asyncio.run`
桥接(构建/调用的 worker 线程没有运行中的事件循环);遇到运行中 event loop 的极端
情况(如需兼容)再退回 run_coroutine_threadsafe。

多 server 逐个加载并隔离失败:单个 MCP server 连不上/握手失败只跳过该 server,
不影响其他 server 与整体 agent 构建(agent 缺工具也应可用,主链路不能崩)。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

logger = logging.getLogger(__name__)


def _to_sync_tool(tool) -> Any:
    """把 async-only 的 MCP StructuredTool 包装成支持同步调用的工具。"""

    async def _arun(**kwargs):
        result = await tool.ainvoke(kwargs)
        if isinstance(result, tuple) and len(result) == 2:
            return result[0]
        return result

    def _run(**kwargs):
        try:
            return asyncio.run(_arun(**kwargs))
        except RuntimeError:
            # 运行时已存在事件循环(如异步上下文里被同步调用):调度到该循环上执行。
            # 注意:在同一线程的 running loop 内直接 run_coroutine_threadsafe 会阻塞
            # 该循环——async 工具在 async 图里走 ainvoke 路径,不会走到这里。
            loop = asyncio.get_running_loop()
            fut = asyncio.run_coroutine_threadsafe(_arun(**kwargs), loop)
            return fut.result(timeout=90)

    from langchain_core.tools import StructuredTool

    return StructuredTool(
        name=tool.name,
        description=tool.description,
        args_schema=tool.args_schema,
        func=_run,
        coroutine=_arun,
    )


async def _load_one(server: dict) -> list:
    """加载单个 MCP server 的工具(隔离失败)。未配置接口地址则返回空。"""
    name = server.get("name") or "server"
    url = server.get("url") or ""
    if not url.startswith(("http://", "https://")):
        logger.warning("[mcp] %s 跳过:URL 非法 %r", name, url)
        return []
    headers = server.get("headers") or {}

    from langchain_mcp_adapters.client import MultiServerMCPClient

    # 按 URL 自动判定传输方式:MCP 官方 /sse 订阅端点须用 SSE transport,其余走 HTTP(streamable)。
    transport = "sse" if _is_sse_url(url) else "http"
    conn: dict = {"transport": transport, "url": url}
    if headers:
        conn["headers"] = headers

    client = MultiServerMCPClient({name: conn}, tool_name_prefix=True)
    tools = await client.get_tools()
    return [_to_sync_tool(t) for t in tools]


def _is_sse_url(url: str) -> bool:
    """是否是 MCP SSE 订阅端点(路径段含 /sse,或 query 里 transport=sse)。"""
    path = url.split("?", 1)[0].rstrip("/")
    if path.endswith("/sse"):
        return True
    q = url.split("?", 1)[1] if "?" in url else ""
    return "transport=sse" in q


def build_mcp_tools(mcp_servers: list[dict]) -> list:
    """同步入口:按 server 逐个加载并包装 MCP 工具;任一失败只跳过该 server。
    type=openapi → 按 OpenAPI/Swagger 文档批量生成;type=manual → 按内联接口清单生成。
    """
    if not mcp_servers:
        return []
    loaded: list = []
    for server in mcp_servers:
        name = server.get("name") or "server"
        stype = (server.get("type") or "remote").lower()
        try:
            if stype == "openapi":
                from app import openapi_tools
                tools = openapi_tools.build_openapi_tools(server)
                logger.info("[openapi] server %s 生成 %d 个接口工具", name, len(tools))
            elif stype == "manual":
                from app import manual_tools
                tools = manual_tools.build_manual_tools(server)
                logger.info("[manual] server %s 生成 %d 个接口工具", name, len(tools))
            else:
                tools = asyncio.run(_load_one(server))
                logger.info("[mcp] server %s 加载 %d 个工具", name, len(tools))
            loaded.extend(tools)
        except Exception as exc:  # noqa: BLE001
            logger.warning("[mcp] server %s 加载失败,已跳过: %s", name, str(exc)[:300])
    return loaded


def mcp_signature(mcp_servers: list[dict]) -> str:
    """MCP/OpenAPI/手动接口配置指纹:name/type/url/headers/config 的稳定摘要,变更时重建 agent 缓存。"""
    if not mcp_servers:
        return ""
    import hashlib
    import json

    rows = sorted(
        (
            json.dumps(
                {
                    "name": s.get("name", ""),
                    "type": s.get("type") or "remote",
                    "url": s.get("url", ""),
                    "headers": s.get("headers") or {},
                    "config": s.get("config") or [],
                },
                sort_keys=True,
                ensure_ascii=False,
            )
            for s in mcp_servers
        )
    )
    return hashlib.sha256(json.dumps(rows, ensure_ascii=False).encode()).hexdigest()[:16]
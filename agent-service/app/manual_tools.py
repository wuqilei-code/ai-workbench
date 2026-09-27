"""手动接口组转为深链(deepagents)可调用的结构化工具。

适用对象:没有 OpenAPI/Swagger 文档的系统。一个条目(一个平台)有自己的公共 base_url 与
公共鉴权 headers,条目内通过内联接口清单(config: list[dict])逐个定义接口。

接口定义字段:
  name        str   接口名(工具名用 {server_name}__{name})
  method      str   GET/POST/PUT/...
  path        str   相对 base_url 的路径,支持 {name} 路径参数模板,如 /orders/{id}
  description str   (可选)接口说明
  params      list  (可选)参数:
                     { name, in: path|query|header, required?, type?, description? }
  body_fields list  (可选)JSON 请求体顶层字段:
                     { name, type?, required?, description? }
  body        str   (可选)直接指定完整 JSON body(字符串),覆盖 body_fields 展开

调用规则:base_url + path 拼 URL,路径参数代入 path 模板,query/header 参数按其 in 归类,
body 由 body_fields 组装(未提供的字段忽略)。条目的公共 headers 始终携带。
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any

import httpx

logger = logging.getLogger(__name__)


def _slugify(s: str) -> str:
    s = re.sub(r"[^0-9A-Za-z]+", "_", s).strip("_").lower()
    return s or "op"


def _build_args_schema(fields: dict[str, dict]) -> type | None:
    from pydantic import BaseModel, Field, create_model

    if not fields:
        return None
    anns: dict[str, Any] = {}
    for name, f in fields.items():
        ann = {"string": str, "integer": int, "number": float, "boolean": bool}.get(
            (f.get("type") or "string"), str)
        desc = f.get("description") or f"参数 {name}"
        required = f.get("_required", False)
        default = f.get("default")
        if required and default is None:
            anns[name] = (ann, Field(..., description=desc))
        else:
            anns[name] = (ann, Field(default=default, description=desc))
    return create_model("ManualArgs", **anns)


def _interface_tool(server: dict, server_name: str, base: str, item: dict) -> Any:
    from langchain_core.tools import StructuredTool

    method = (item.get("method") or "GET").upper()
    path = item.get("path") or "/"
    iname = item.get("name") or (_slugify(f"{method}_{path}"))
    tool_name = f"{server_name}__{_slugify(iname)}"
    description = f"{method} {path}\n{item.get('description') or ''}".strip() or tool_name

    # 收集参数范围
    fields: dict[str, dict] = {}
    path_params: dict[str, str] = {}
    for prm in item.get("params") or []:
        if not isinstance(prm, dict):
            continue
        pname = prm.get("name")
        loc = prm.get("in")
        if not pname:
            continue
        fields[pname] = {
            "type": prm.get("type") or "string",
            "description": prm.get("description"),
            "_required": bool(prm.get("required")) or loc == "path",
            "_loc": loc,  # path/query/header
        }
        if loc == "path":
            path_params[pname] = pname
    for bf in item.get("body_fields") or []:
        if not isinstance(bf, dict) or not bf.get("name"):
            continue
        fields[bf["name"]] = {
            "type": bf.get("type") or "string",
            "description": bf.get("description"),
            "_required": bool(bf.get("required")),
            "_loc": "body",
        }

    args_schema = _build_args_schema(fields)

    def _run(**kwargs):
        # 路径
        url = base.rstrip("/") + path
        for pn in path_params:
            url = url.replace("{" + pn + "}", str(kwargs.get(pn, "")))
        # query / header / body
        query: dict[str, str] = {}
        headers: dict[str, str] = {}
        body_fields: list[tuple[str, Any]] = []
        extra_headers: dict[str, str] = {
            str(k): str(v) for k, v in (server.get("headers") or {}).items()
        }
        for fname, f in fields.items():
            val = kwargs.get(fname)
            if val is None:
                continue
            loc = f.get("_loc")
            if loc == "path":
                continue
            if loc == "header":
                headers[fname] = str(val)
            elif loc == "body":
                body_fields.append((fname, val))
            else:  # query
                query[fname] = val if isinstance(val, str) else json.dumps(val, ensure_ascii=False)

        body = item.get("body")
        if body is None and body_fields:
            body = json.dumps({n: v for n, v in body_fields}, ensure_ascii=False)

        from app import net_guard

        net_guard.assert_url_allowed(url)  # SSRF 防护(每次调用校验)
        with httpx.Client(timeout=60, follow_redirects=True) as client:
            resp = client.request(
                method,
                url,
                params=query or None,
                headers={**extra_headers, **headers},
                content=body,
            )
        txt = resp.text
        if len(txt) > 6000:
            txt = txt[:6000] + "\n...[截断]"
        return f"HTTP {resp.status_code}\n{txt}"

    return StructuredTool(name=tool_name, description=description, args_schema=args_schema, func=_run)


def build_manual_tools(server: dict) -> list:
    """同步入口:按内联接口清单生成一组结构化工具;单个接口失败只跳过该接口。"""
    server_name = server.get("name") or "server"
    base = server.get("url") or ""
    items = server.get("config") or []
    if not isinstance(items, list):
        return []
    tools: list = []
    for item in items:
        if not isinstance(item, dict):
            continue
        try:
            tools.append(_interface_tool(server, server_name, base, item))
        except Exception as exc:  # noqa: BLE001
            logger.warning("[manual] %s 接口 %s 生成失败,已跳过: %s",
                           server_name, item.get("name") or item.get("path"), str(exc)[:200])
    return tools

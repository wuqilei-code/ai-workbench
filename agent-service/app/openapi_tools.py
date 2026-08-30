"""OpenAPI / Swagger 文档批量转为深链(deepagents)可调用的结构化工具。

设计:一个 OpenAPI 条目(一个平台)在「MCP 连接」里以其 name/url/headers 单独配置,
本模块抓取其 OpenAPI/Swagger JSON,把每个接口(operation)暴露为一个结构化工具:
  - 工具名: {server_name}__{operation_id 或 method+path 摘要},带前缀避免跨条目/同名冲突
  - 参数: path/query/header 参数 + JSON 请求体(展开为顶层字段);无 schema 的请求体回落为 request_body
  - 调用: 按 OpenAPI 规则拼 URL/query/header/body,走 httpx 同步发送,返回响应文本

支持 Swagger 2.0(swagger:"2.0")与 OpenAPI 3.x(openapi:3.x)。
单个条目任一操作失败只跳过该操作,不影响其余操作与整体 agent 构建。
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any

import httpx

logger = logging.getLogger(__name__)

_METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")


# ---------- 工具构造 ----------

def _to_structured(name: str, description: str, args_schema: dict, fn) -> Any:
    from langchain_core.tools import StructuredTool

    return StructuredTool(
        name=name,
        description=description,
        args_schema=args_schema,
        func=fn,
    )


def _slugify(s: str) -> str:
    s = re.sub(r"[^0-9A-Za-z]+", "_", s).strip("_").lower()
    return s or "op"


def _desc(obj: dict | None) -> str:
    if not obj:
        return ""
    d = obj.get("description") or obj.get("summary") or ""
    if not isinstance(d, str):
        d = str(d)
    return d.strip()


# ---------- 参数 / schema 展开 ----------

def _primitive_type(schema: dict) -> str:
    """把 OpenAPI/schema 类型映射为 python 简单类型(用于 pydantic 参数)。"""
    t = schema.get("type")
    fmt = schema.get("format")
    if t == "string":
        return "string"
    if t == "integer":
        return "integer"
    if t == "number":
        return "number"
    if t == "boolean":
        return "boolean"
    if t == "array":
        return "string"  # 复杂数组以 JSON 字符串传入,由运行期解析
    if t == "object":
        return "string"  # 嵌套对象以 JSON 字符串传入
    if "enum" in schema:
        return "string"
    if fmt:
        return "string"
    return "string"


def _field_to_python(schema: dict) -> tuple[str, Any]:
    """返回 (python 类型注解, 默认值) 供 pydantic 字段建模。"""
    t = _primitive_type(schema)
    ann = {"string": str, "integer": int, "number": float, "boolean": bool}.get(t, str)
    default = schema.get("default")
    if default is not None or schema.get("required"):
        return ann, default
    return ann, None


def _flatten_body_schema(schema: dict) -> dict[str, dict]:
    """把请求体 JSON schema 展开为顶层字段 {name: schema_fragment};无法展开时返回空。"""
    schema = schema or {}
    if schema.get("type") == "object" and isinstance(schema.get("properties"), dict):
        props = {}
        required = set(schema.get("required") or [])
        for pname, psch in schema["properties"].items():
            f = dict(psch) if isinstance(psch, dict) else {"type": "string"}
            merged = dict(f)
            merged["_required"] = pname in required
            merged["_field"] = pname
            merged["_loc"] = "body"
            props[pname] = merged
        if props:
            return props
    return {}


def _build_args_schema(flat_fields: dict[str, Any]) -> type:
    from pydantic import BaseModel, Field, create_model

    anns: dict[str, Any] = {}
    for name, frag in flat_fields.items():
        ann, default = _field_to_python(frag)
        desc = _desc(frag) or f"参数 {name}"
        required = frag.get("_required", False)
        # 标记:required 且无默认 -> 位置为必填;否则给默认
        if required and default is None:
            anns[name] = (ann, Field(..., description=desc))
        elif default is not None:
            try:
                anns[name] = (ann, Field(default=default, description=desc))
            except Exception:  # noqa: BLE001
                anns[name] = (ann, Field(default=None, description=desc))
        else:
            anns[name] = (ann, Field(default=None, description=desc))
    if not anns:
        return None
    return create_model("OpenApiArgs", **anns)


# ---------- 规格加载与归一化 ----------

def _load_spec(server: dict) -> dict:
    url = server.get("url") or ""
    if not url.startswith(("http://", "https://")):
        raise ValueError(f"openapi url 非法: {url!r}")
    headers = server.get("headers") or {}
    with httpx.Client(timeout=30, follow_redirects=True) as client:
        resp = client.get(url, headers=headers)
        resp.raise_for_status()
        try:
            spec = resp.json()
        except Exception:
            spec = json.loads(resp.text)
    if not isinstance(spec, dict):
        raise ValueError("openapi 文档需为 JSON 对象")
    return spec


def _base_url(spec: dict, spec_url: str) -> str:
    """推导 API 基地址(绝对的 origin+prefix)。

    OpenAPI 3 servers[0].url 可能是相对地址(如 /api/v3),此时以抓取文档的 spec_url 为准
    解析为其源(self-host)绝对地址;Swagger 2.0 用 schemes+host+basePath。
    """
    from urllib.parse import urljoin

    servers = spec.get("servers")
    if isinstance(servers, list) and servers and isinstance(servers[0], dict):
        base = servers[0].get("url")
        if base:
            if base.startswith(("http://", "https://")):
                return base.rstrip("/")
            # 相对 server url → 相对 spec 文档自身源解析
            if spec_url.startswith(("http://", "https://")):
                return urljoin(spec_url, base).rstrip("/")
            return base.rstrip("/")
    # Swagger 2.0: schemes + host + basePath
    host = spec.get("host")
    schemes = spec.get("schemes") or ["https"]
    base_path = (spec.get("basePath") or "").rstrip("/")
    if host:
        scheme = schemes[0] if isinstance(schemes, list) and schemes else "https"
        return f"{scheme}://{host}{base_path}".rstrip("/")
    # 兜底:无 servers → 空前缀(依赖完整 path)
    return ""


# ---------- $ref 解析 ----------

def _deref(spec: dict, node: Any, _seen: set | None = None) -> Any:
    """就地解析文档内 $ref(#/components/schemas/...),最多 40 层防环。"""
    if not isinstance(node, dict):
        return node
    ref = node.get("$ref")
    if not isinstance(ref, str):
        return node
    if not ref.startswith("#/"):
        return node  # 外部/绝对 ref 无法本地解析,原样返回
    if _seen is None:
        _seen = set()
    if ref in _seen:
        return {"type": "string", "description": f"circular {ref}"}
    _seen.add(ref)
    target: Any = spec
    for part in ref[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        if isinstance(target, dict) and part in target:
            target = target[part]
        else:
            return node
    return _deref(spec, target, _seen)


# ---------- 单操作 -> 工具 ----------

def _operation_tool(server: dict, server_name: str, method: str, path: str, op: dict, base: str, spec: dict) -> Any:
    op = op or {}
    op_id = op.get("operationId") or f"{method}_{path}"
    tool_name = f"{server_name}__{_slugify(op_id)}"

    summary = _desc(op)
    tags = op.get("tags") or []
    taglab = (tags[0] + " / ") if tags else ""
    description = f"{taglab}{method.upper()} {path}\n{summary}".strip() or tool_name

    flat_fields: dict[str, Any] = {}
    path_params: dict[str, str] = {}
    for prm in op.get("parameters") or []:
        if not isinstance(prm, dict):
            continue
        pname = prm.get("name")
        loc = prm.get("in")
        if not pname or loc not in ("path", "query", "header"):
            continue
        sch = prm.get("schema") or prm  # swagger2 参数没有 schema 层
        sch = _deref(spec, sch) if isinstance(sch, dict) else sch
        frag = dict(sch) if isinstance(sch, dict) else {"type": "string"}
        frag["_required"] = (loc == "path") or bool(prm.get("required"))
        frag["_field"] = pname
        frag["_loc"] = loc
        flat_fields[pname] = frag
        if loc == "path":
            path_params[pname] = pname

    # 请求体:取第一个 application/json content
    body_schema = None
    rb = op.get("requestBody")
    if isinstance(rb, dict):
        content = rb.get("content") or {}
        cj = content.get("application/json") or content.get("*/*")
        if isinstance(cj, dict):
            body_schema = _deref(spec, cj.get("schema")) if isinstance(cj.get("schema"), dict) else cj.get("schema")
    flat = _flatten_body_schema(body_schema)
    for pname, frag in flat.items():
        flat_fields[pname] = frag
    body_arg = "request_body" if body_schema and not flat else None

    args_schema = _build_args_schema(flat_fields)

    def _run(**kwargs):
        # 路径参数
        url = base + path
        for pn in path_params:
            val = kwargs.get(pn)
            if val is None:
                val = ""
            url = url.replace("{" + pn + "}", str(val))
        # query / header 参数
        query: dict[str, str] = {}
        headers: dict[str, str] = {}
        extra_headers: dict[str, str] = {
            str(k): str(v) for k, v in (server.get("headers") or {}).items()
        }
        body_fields = []
        for fname, frag in flat_fields.items():
            val = kwargs.get(fname)
            if val is None:
                continue
            loc = frag.get("_loc")
            if loc == "path":
                continue
            if loc == "header":
                headers[fname] = str(val)
                continue
            if loc == "body":
                body_fields.append((fname, val))
                continue
            # query(默认)
            if isinstance(val, (dict, list)):
                query[fname] = json.dumps(val, ensure_ascii=False)
            else:
                query[fname] = str(val)
        # 请求体
        body = None
        if body_arg is not None:
            bval = kwargs.get(body_arg)
            if bval is not None:
                body = bval if isinstance(bval, str) else json.dumps(bval, ensure_ascii=False)
        elif body_fields:
            bobj = {fname: val for fname, val in body_fields}
            body = json.dumps(bobj, ensure_ascii=False)

        with httpx.Client(timeout=60, follow_redirects=True) as client:
            resp = client.request(
                method.upper(),
                url,
                params=query,
                headers={**extra_headers, **headers},
                content=body,
            )
        txt = resp.text
        if len(txt) > 6000:
            txt = txt[:6000] + "\n...[截断]"
        return f"HTTP {resp.status_code}\n{txt}"

    return _to_structured(tool_name, description, args_schema, _run)


def build_openapi_tools(server: dict) -> list:
    """同步入口:抓取 OpenAPI 文档并生成一组结构化工具;单个操作失败只跳过该操作。"""
    spec_url = server.get("url") or ""
    spec = _load_spec(server)
    server_name = server.get("name") or "server"
    base = _base_url(spec, spec_url)

    paths = spec.get("paths") or {}
    if not isinstance(paths, dict):
        return []

    tools: list = []
    for path, path_item in paths.items():
        if not isinstance(path_item, dict):
            continue
        for method in _METHODS:
            if method not in path_item:
                continue
            op = path_item[method]
            if not isinstance(op, dict):
                continue
            try:
                tools.append(_operation_tool(server, server_name, method, path, op, base, spec))
            except Exception as exc:  # noqa: BLE001
                logger.warning("[openapi] %s %s %s 生成失败,已跳过: %s",
                               server_name, method.upper(), path, str(exc)[:200])
    return tools

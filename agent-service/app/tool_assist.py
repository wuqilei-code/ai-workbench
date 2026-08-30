"""手动接口的 AI 辅助:把自然语言/URL/curl 示例描述转成(一条)接口定义 JSON。

复用平台统一的 AGENT_MODEL(OpenAI 兼容接口,RaisonChatOpenAI 同源调用),以严格的
JSON 输出约束让模型返回符合 manual_tools 约定的单接口定义:
  {method, path, name, description, params:[{name,in,required,type,description}],
   body_fields:[{name,type,required,description}]}
"""

from __future__ import annotations

import json
import logging
from typing import Any

logger = logging.getLogger(__name__)

_PROMPT = """你是一个 REST 接口定义助手。根据下面的描述,产出一个符合要求的接口定义 JSON。

必须严格返回一个 JSON 对象,不要输出任何其他文字、不要用代码块包裹。JSON 结构:
{
  "method": "GET|POST|PUT|DELETE|PATCH|...",
  "path": "相对 base_url 的路径,路径参数用 {name} 占位,如 /orders/{id}",
  "name": "接口名,英文小写下划线,如 get_order",
  "description": "一句话中文/英文说明,供 agent 判断何时调用",
  "params": [
    {"name": "id", "in": "path|query|header", "required": true, "type": "integer|string|boolean", "description": "说明"}
  ],
  "body_fields": [
    {"name": "name", "type": "string", "required": true, "description": "说明"}
  ]
}

规则:
- params 的 in 只能是 path/query/header;路径里的 {name} 对应一条 in=path 的 param。
- 若请求体是 JSON,拆成 body_fields 顶层字段;若无法确定请求体字段,可留空数组。
- name 用英文,type 用 integer/string/boolean。
- 无法从描述判断的字段尽量留空数组,不要编造。
- 只输出 JSON。

描述:
__DESCRIPTION__
"""

_PARAMS_PROMPT = """你是一个 REST 接口参数推断助手。用户已经填好了接口的基本信息，请你根据这些信息推断出合适的参数列表(params)和请求体字段(body_fields)。

必须严格返回一个 JSON 对象,不要输出任何其他文字、不要用代码块包裹。JSON 结构:
{
  "params": [
    {"name": "id", "in": "path|query|header", "required": true, "type": "integer|string|boolean", "description": "说明"}
  ],
  "body_fields": [
    {"name": "name", "type": "string", "required": true, "description": "说明"}
  ]
}

规则:
- 从路径中提取 {xxx} 占位符,对应一条 in=path 的 param。
- 根据接口名和说明推断可能需要的查询参数(in=query)。
- POST/PUT/PATCH 的请求体字段放 body_fields;GET/DELETE 一般无 body_fields。
- params 的 in 只能是 path/query/header。
- type 只用 integer/string/boolean。
- 只输出 JSON,不要输出其他内容。

接口信息:
方法: __METHOD__
路径: __PATH__
接口名: __NAME__
说明: __DESC__
"""

_PARAMS_METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}
_LOCS = {"path", "query", "header"}
_TYPES = {"string", "integer", "boolean", "number"}


def _model():
    from app.agent_factory import _build_model
    return _build_model()


def generate_interface(description: str, server_name: str = "") -> dict:
    """调用 LLM 把描述转成单接口定义;返回 {ok, interface} 或 {ok:false, error}。

    返回的 interface 仅包含可写字段,与 manual_tools 的 config 元素一致。
    """
    desc = (description or "").strip()
    if not desc:
        return {"ok": False, "error": "描述为空"}
    prompt = _PROMPT.replace("__DESCRIPTION__", desc)
    try:
        model = _model()
        resp = model.invoke([{"role": "system", "content": "你是 JSON 输出助手,只输出干净 JSON。"},
                             {"role": "user", "content": prompt}])
        text = (getattr(resp, "content", None) or "").strip()
        text = _extract_json(text)
        obj = json.loads(text)
        if not isinstance(obj, dict):
            return {"ok": False, "error": "模型输出不是 JSON 对象"}
        interface = _sanitize(obj)
        if not interface.get("path"):
            return {"ok": False, "error": "未能从描述中解析出接口路径"}
        return {"ok": True, "interface": interface}
    except Exception as exc:  # noqa: BLE001
        logger.warning("[assist] generate interface failed: %s", str(exc)[:300])
        return {"ok": False, "error": str(exc)[:300]}


def generate_params_only(method: str, path: str, name: str, description: str) -> dict:
    """根据用户已填的 method/path/name/description,推断 params 和 body_fields。

    只生成参数部分,不覆盖用户已填字段。返回 {ok, params, body_fields} 或 {ok:false, error}。
    """
    method = (method or "GET").upper()
    path = (path or "").strip()
    name = (name or "").strip()
    desc = (description or "").strip()
    if not path:
        return {"ok": False, "error": "路径为空，无法推断参数"}
    prompt = (_PARAMS_PROMPT
              .replace("__METHOD__", method)
              .replace("__PATH__", path)
              .replace("__NAME__", name)
              .replace("__DESC__", desc or "（无说明）"))
    try:
        model = _model()
        resp = model.invoke([{"role": "system", "content": "你是 JSON 输出助手,只输出干净 JSON。"},
                             {"role": "user", "content": prompt}])
        text = (getattr(resp, "content", None) or "").strip()
        text = _extract_json(text)
        obj = json.loads(text)
        if not isinstance(obj, dict):
            return {"ok": False, "error": "模型输出不是 JSON 对象"}
        params = _sanitize_params(obj.get("params"))
        body_fields = _sanitize_body(obj.get("body_fields"))
        return {"ok": True, "params": params, "body_fields": body_fields}
    except Exception as exc:  # noqa: BLE001
        logger.warning("[assist] generate params failed: %s", str(exc)[:300])
        return {"ok": False, "error": str(exc)[:300]}


def _extract_json(text: str) -> str:
    """从模型输出中抽取 JSON 片段(容忍前后缀/代码块)。"""
    s = text.strip()
    if s.startswith("```"):
        # 去掉可能的 ```json ... ``` 围栏
        lines = s.splitlines()
        if lines and lines[0].strip().startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        s = "\n".join(lines).strip()
    start = s.find("{")
    end = s.rfind("}")
    if start != -1 and end != -1 and end > start:
        return s[start:end + 1]
    return s


def _sanitize(obj: dict) -> dict:
    method = str(obj.get("method") or "GET").upper()
    if method not in _METHODS:
        method = "GET"
    path = str(obj.get("path") or "").strip()
    if not path.startswith("/"):
        path = "/" + path
    name = str(obj.get("name") or "").strip()
    if not name or not name.replace("_", "").isalnum():
        name = "iface"
    out: dict[str, Any] = {
        "method": method,
        "path": path,
        "name": name,
    }
    d = obj.get("description")
    if d:
        out["description"] = str(d).strip()
    out["params"] = [p for p in _sanitize_params(obj.get("params"))]
    out["body_fields"] = [b for b in _sanitize_body(obj.get("body_fields"))]
    return out


def _sanitize_params(raw) -> list[dict]:
    if not isinstance(raw, list):
        return []
    out: list[dict] = []
    for p in raw:
        if not isinstance(p, dict) or not p.get("name"):
            continue
        loc = str(p.get("in") or "query")
        if loc not in _LOCS:
            loc = "query"
        t = str(p.get("type") or "string")
        if t not in _TYPES:
            t = "string"
        item: dict = {"name": str(p["name"]), "in": loc, "type": t}
        if p.get("required"):
            item["required"] = True
        if p.get("description"):
            item["description"] = str(p["description"])
        out.append(item)
    return out


def _sanitize_body(raw) -> list[dict]:
    if not isinstance(raw, list):
        return []
    out: list[dict] = []
    for b in raw:
        if not isinstance(b, dict) or not b.get("name"):
            continue
        t = str(b.get("type") or "string")
        if t not in _TYPES:
            t = "string"
        item: dict = {"name": str(b["name"]), "type": t}
        if b.get("required"):
            item["required"] = True
        if b.get("description"):
            item["description"] = str(b["description"])
        out.append(item)
    return out

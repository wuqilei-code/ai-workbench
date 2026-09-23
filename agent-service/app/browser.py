"""浏览器工具集:驱动 AIO Sandbox 内置 Chromium(通过 CDP WebSocket)。

- 与前端 VNC 实时画面**同一浏览器**:agent 打开/点击/输入的页面就是用户看到的画面,
  用户也可以直接在 VNC 里接管操作。
- 全部返回文本(标题/正文/操作结果),不把图片喂给模型(deepseek 不支持图像的坑)。
- 默认不截图;仅当用户明确要求时才保存 PNG 到工作区。
- 交互优先用 Runtime.evaluate 执行 DOM 操作(比坐标点击稳定),滚动可用 JS。

AIO 浏览器地址由 AioSandbox.cdp_url() 提供(容器内 /cdp/json/list 的第一个 page 目标)。
"""

from __future__ import annotations

import base64
import itertools
import json
import logging
import time

from langchain_core.tools import tool

logger = logging.getLogger(__name__)

_CDP_TIMEOUT = 25
_NAV_WAIT = 1.0


def _rel_screenshot_name() -> str:
    return f"browsers/browser-{time.strftime('%Y%m%d-%H%M%S')}.png"


class _Cdp:
    """极简 CDP 客户端(单页面 target,直接对本页发命令)。"""

    def __init__(self, ws_url: str) -> None:
        from websockets.sync.client import connect as _ws_connect

        self._ws = _ws_connect(ws_url, open_timeout=_CDP_TIMEOUT, max_size=None)
        self._counter = itertools.count(1)

    def call(self, method: str, params: dict | None = None) -> dict:
        mid = next(self._counter)
        self._ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        deadline = time.monotonic() + _CDP_TIMEOUT
        while time.monotonic() < deadline:
            raw = self._ws.recv(timeout=_CDP_TIMEOUT)
            msg = json.loads(raw)
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(msg["error"].get("message", str(msg["error"])))
                return msg.get("result", {})
        raise TimeoutError(f"CDP {method} 超时")

    def js(self, expression: str, await_promise: bool = False):
        res = self.call("Runtime.evaluate", {
            "expression": expression,
            "returnByValue": True,
            "awaitPromise": await_promise,
        })
        if res.get("exceptionDetails"):
            raise RuntimeError(res["exceptionDetails"].get("text", "JS 执行异常"))
        return (res.get("result") or {}).get("value")

    def close(self) -> None:
        try:
            self._ws.close()
        except Exception:  # noqa: BLE001
            pass


def _open_cdp(user_id: str) -> _Cdp:
    from app.aio_sandbox import get_aio_sandbox

    sb = get_aio_sandbox(user_id)
    sb.ensure()
    ws_url = sb.cdp_url()
    if not ws_url:
        raise RuntimeError("未找到可用的浏览器页面(CDP 目标)")
    return _Cdp(ws_url)


def build_browser_tools(user_id: str) -> list:
    """构造驱动 AIO 内置浏览器的 langchain 工具集(与前端 VNC 画面同一浏览器)。"""

    @tool("browser_navigate")
    def browser_navigate(url: str, screenshot: bool = False) -> str:
        """打开指定网址,返回页面标题。默认不截图;仅当用户明确要求截图时才设置 screenshot=true。
        每次打开都会请求用户允许,被拒绝后不要重试同一个页面。"""
        url = (url or "").strip()
        if not url.startswith(("http://", "https://")):
            return "URL 必须 http(s) 开头,请修正后再试。"
        if len(url) > 2048:
            return "URL 过长。"
        try:
            cdp = _open_cdp(user_id)
            try:
                cdp.call("Page.enable")
                cdp.call("Page.navigate", {"url": url})
                time.sleep(_NAV_WAIT)
                title = ""
                try:
                    title = (cdp.js("document.title") or "").strip()
                except Exception:  # noqa: BLE001
                    pass
                base = f"已打开 {url}" + (f"(标题:{title})" if title else "") + "。"
                if not screenshot:
                    return base + "用户未要求截图,因此未保存截图。"
                data = cdp.call("Page.captureScreenshot", {"format": "png"}).get("data")
                rel = _save_screenshot(user_id, data)
                return base + f"已截图: {rel}。请用 `[结果文件] {rel}` 标记交付。"
            finally:
                cdp.close()
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_navigate 失败: %s", exc)
            return f"打开网页失败:{exc}"

    @tool("browser_get_text")
    def browser_get_text() -> str:
        """读取当前页面文字内容(标题 + 正文前 2000 字),用于了解页面结构后再操作。"""
        try:
            cdp = _open_cdp(user_id)
            try:
                title = cdp.js("document.title") or ""
                body = cdp.js("document.body ? document.body.innerText : ''") or ""
                body = str(body).strip()
                if len(body) > 2000:
                    body = body[:2000] + "\n…(已截断)"
                return (f"标题: {title}\n" if title else "") + body
            finally:
                cdp.close()
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_get_text 失败: %s", exc)
            return f"读取页面失败:{exc}"

    @tool("browser_click")
    def browser_click(selector: str) -> str:
        """按 CSS 选择器点击页面元素(如 #login、.btn、input[name=q])。"""
        try:
            cdp = _open_cdp(user_id)
            try:
                sel = json.dumps(selector)
                ok = cdp.js(
                    "(function(){var el=document.querySelector(" + sel + ");"
                    "if(!el)return false;el.click();return true;})()"
                )
                if not ok:
                    return f"未找到元素:{selector}"
                return f"已点击 {selector}。"
            finally:
                cdp.close()
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_click 失败: %s", exc)
            return f"点击失败:{exc}"

    @tool("browser_click_text")
    def browser_click_text(text: str) -> str:
        """点击页面上文字匹配的第一个元素(适合按钮/链接,如"登录"、"Download")。"""
        try:
            cdp = _open_cdp(user_id)
            try:
                t = json.dumps(text)
                ok = cdp.js(
                    "(function(){var t=" + t + ";"
                    "var all=document.querySelectorAll('a,button,input,[role=button],span,div');"
                    "for(var i=0;i<all.length;i++){var el=all[i];"
                    "if(el.innerText&&el.innerText.trim().indexOf(t)>=0){el.click();return true;}}"
                    "return false;})()"
                )
                if not ok:
                    return f"未找到包含「{text}」的可点击元素。"
                return f"已点击包含「{text}」的元素。"
            finally:
                cdp.close()
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_click_text 失败: %s", exc)
            return f"点击失败:{exc}"

    @tool("browser_type")
    def browser_type(selector: str, text: str) -> str:
        """在指定输入框(selector)输入文字(如 #q、[name=email])。"""
        try:
            cdp = _open_cdp(user_id)
            try:
                sel = json.dumps(selector)
                val = json.dumps(text)
                ok = cdp.js(
                    "(function(){var el=document.querySelector(" + sel + ");"
                    "if(!el)return false;el.focus();el.value=" + val + ";"
                    "el.dispatchEvent(new Event('input',{bubbles:true}));"
                    "el.dispatchEvent(new Event('change',{bubbles:true}));return true;})()"
                )
                if not ok:
                    return f"未找到输入框:{selector}"
                return f"已在 {selector} 输入内容。"
            finally:
                cdp.close()
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_type 失败: %s", exc)
            return f"输入失败:{exc}"

    @tool("browser_scroll")
    def browser_scroll(y: int = 300) -> str:
        """滚动当前页面(y>0 向下,y<0 向上)。"""
        try:
            cdp = _open_cdp(user_id)
            try:
                cdp.js(f"window.scrollBy(0,{int(y)});")
                return f"已滚动 y={y}。"
            finally:
                cdp.close()
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_scroll 失败: %s", exc)
            return f"滚动失败:{exc}"

    @tool("browser_go_back")
    def browser_go_back() -> str:
        """返回上一页。"""
        try:
            cdp = _open_cdp(user_id)
            try:
                cdp.js("history.back();")
                time.sleep(_NAV_WAIT)
                title = ""
                try:
                    title = (cdp.js("document.title") or "").strip()
                except Exception:  # noqa: BLE001
                    pass
                return f"已返回上一页" + (f"(标题:{title})" if title else "") + "。"
            finally:
                cdp.close()
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_go_back 失败: %s", exc)
            return f"返回失败:{exc}"

    @tool("browser_screenshot")
    def browser_screenshot() -> str:
        """把当前页面截图保存到工作区并返回路径(仅用户要求截图/图片时使用)。"""
        try:
            cdp = _open_cdp(user_id)
            try:
                data = cdp.call("Page.captureScreenshot", {"format": "png"}).get("data")
                rel = _save_screenshot(user_id, data)
                return f"截图已保存为 {rel}。请用 `[结果文件] {rel}` 标记交付。"
            finally:
                cdp.close()
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_screenshot 失败: %s", exc)
            return f"截图失败:{exc}"

    return [
        browser_navigate,
        browser_get_text,
        browser_click,
        browser_click_text,
        browser_type,
        browser_scroll,
        browser_go_back,
        browser_screenshot,
    ]


def _save_screenshot(user_id: str, data_b64: str | None) -> str:
    """把 CDP 返回的 base64 PNG 写入用户工作区 browsers/,返回相对路径。"""
    if not data_b64:
        raise RuntimeError("截图数据为空")
    rel = _rel_screenshot_name()
    content = base64.b64decode(data_b64)
    from app.aio_sandbox import get_aio_sandbox

    get_aio_sandbox(user_id).upload_files([(rel, content)])
    return rel


__all__ = ["build_browser_tools"]

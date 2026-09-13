"""浏览器工具集:驱动"合并沙箱"里的共享 Chromium(connect_over_cdp)。

- 与前端实时面板**同一浏览器上下文**:agent 打开/点击/输入的页面就是面板里看到的画面。
- 全部返回文本(标题/正文/操作结果),不把图片喂给模型(deepseek 不支持图像的坑)。
- 默认不截图;仅当用户明确要求截图时才保存 PNG 到工作区。
"""

from __future__ import annotations

import logging
import time

from langchain_core.tools import tool

from app import workspace as ws

logger = logging.getLogger(__name__)


def _rel_screenshot_name() -> str:
    return f"browsers/browser-{time.strftime('%Y%m%d-%H%M%S')}.png"


def _sandbox_cdp_endpoint(user_id: str) -> str:
    """该用户沙箱的 CDP 端点(http://<沙箱>:9223)。

    docker 版:容器挂 aiwb-agents 网,常驻浏览器入口(node 把 127.0.0.1:9222 转发到
    0.0.0.0:9223),用容器 IP 直连绕开新版 Chromium 的 Host 头校验。
    k8s 版:沙箱 Pod 暴露同名 headless Service/Pod IP,同样返回 CDP 地址。
    统一委托给沙箱实现(DockerSandbox.cdp_endpoint / K8sSandbox.cdp_endpoint)。
    """
    from app.sandbox import get_sandbox
    from app.sandbox_k8s import K8sSandbox

    sb = get_sandbox(user_id)
    if isinstance(sb, K8sSandbox):
        return sb.cdp_endpoint()
    # docker:沿用旧逻辑查容器 IP(绕开 Host 校验)
    import docker

    cli = docker.from_env()
    name = f"aiwb-sandbox-u{user_id}"
    c = cli.containers.get(name)
    nets = (c.attrs.get("NetworkSettings", {}).get("Networks", {}) or {})
    for net_meta in nets.values():
        ip = (net_meta or {}).get("IPAddress")
        if ip:
            return f"http://{ip}:9223"
    raise RuntimeError(f"沙箱容器 {name} 未接入可访问网络,无法获取 CDP 端点")


def _open_shared_page(user_id: str):
    """connect_over_cdp 拿到共享浏览器的第一个页面(面板与 agent 共用)。

    懒启动:连接前先幂等拉起浏览器栈(已就绪秒回,未就绪启动并等待)。
    """
    from playwright.sync_api import sync_playwright

    from app.sandbox import get_sandbox

    get_sandbox(user_id).ensure_browser()
    endpoint = _sandbox_cdp_endpoint(user_id)
    p = sync_playwright().start()
    try:
        browser = p.chromium.connect_over_cdp(endpoint)
    except Exception:
        # connect_over_cdp 失败时若不清 driver,会泄漏 Node 子进程
        try:
            p.stop()
        except Exception:  # noqa: BLE001
            pass
        raise
    ctx = browser.contexts[0] if browser.contexts else browser.new_context()
    page = ctx.pages[0] if ctx.pages else ctx.new_page()
    return p, browser, ctx, page


def _close(p, browser):
    try:
        browser.close()
    except Exception:  # noqa: BLE001
        pass
    try:
        p.stop()
    except Exception:  # noqa: BLE001
        pass


def _title_of(page) -> str:
    try:
        t = (page.title() or "").strip()
        return f"(标题:{t})" if t else ""
    except Exception:  # noqa: BLE001
        return ""


def build_browser_tools(user_id: str) -> list:
    """构造驱动共享浏览器容器的 langchain 工具集(与前端实时面板同一浏览器上下文)。"""

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
            p, browser, ctx, page = _open_shared_page(user_id)
            try:
                page.goto(url, wait_until="load", timeout=30_000)
                page.wait_for_timeout(800)
                base = f"已打开 {url}{_title_of(page)}。"
                if not screenshot:
                    return base + "用户未要求截图,因此未保存截图。"
                rel = _rel_screenshot_name()
                out = ws.workspace_root(user_id) / rel
                out.parent.mkdir(parents=True, exist_ok=True)
                page.screenshot(path=str(out))
                return base + f"已截图: {rel}。请用 `[结果文件] {rel}` 标记交付。"
            finally:
                _close(p, browser)
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_navigate 失败: %s", exc)
            return f"打开网页失败:{exc}"

    @tool("browser_get_text")
    def browser_get_text() -> str:
        """读取当前页面文字内容(标题 + 正文前 2000 字),用于了解页面结构后再操作。"""
        try:
            p, browser, ctx, page = _open_shared_page(user_id)
            try:
                title = (page.title() or "").strip()
                body = page.evaluate("() => (document.body ? document.body.innerText : '')") or ""
                body = str(body).strip()
                if len(body) > 2000:
                    body = body[:2000] + "\n…(已截断)"
                return (f"标题: {title}\n" if title else "") + body
            finally:
                _close(p, browser)
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_get_text 失败: %s", exc)
            return f"读取页面失败:{exc}"

    @tool("browser_click")
    def browser_click(selector: str) -> str:
        """按 CSS 选择器点击页面元素(如 #login、.btn、input[name=q])。"""
        try:
            p, browser, ctx, page = _open_shared_page(user_id)
            try:
                page.click(selector, timeout=8000)
                return f"已点击 {selector}。"
            finally:
                _close(p, browser)
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_click 失败: %s", exc)
            return f"点击失败:{exc}"

    @tool("browser_click_text")
    def browser_click_text(text: str) -> str:
        """点击页面上文字匹配的第一个元素(适合按钮/链接,如"登录"、"Download")。"""
        try:
            p, browser, ctx, page = _open_shared_page(user_id)
            try:
                page.get_by_text(text, exact=False).first.click(timeout=8000)
                return f"已点击包含「{text}」的元素。"
            finally:
                _close(p, browser)
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_click_text 失败: %s", exc)
            return f"点击失败:{exc}"

    @tool("browser_type")
    def browser_type(selector: str, text: str) -> str:
        """在指定输入框(selector)输入文字(如 #q、[name=email])。"""
        try:
            p, browser, ctx, page = _open_shared_page(user_id)
            try:
                page.fill(selector, text, timeout=8000)
                return f"已在 {selector} 输入内容。"
            finally:
                _close(p, browser)
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_type 失败: %s", exc)
            return f"输入失败:{exc}"

    @tool("browser_scroll")
    def browser_scroll(y: int = 300) -> str:
        """滚动当前页面(y>0 向下,y<0 向上)。"""
        try:
            p, browser, ctx, page = _open_shared_page(user_id)
            try:
                page.mouse.wheel(0, int(y))
                return f"已滚动 y={y}。"
            finally:
                _close(p, browser)
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_scroll 失败: %s", exc)
            return f"滚动失败:{exc}"

    @tool("browser_go_back")
    def browser_go_back() -> str:
        """返回上一页。"""
        try:
            p, browser, ctx, page = _open_shared_page(user_id)
            try:
                page.go_back(timeout=15000)
                return f"已返回上一页{_title_of(page)}。"
            finally:
                _close(p, browser)
        except Exception as exc:  # noqa: BLE001
            logger.warning("browser_go_back 失败: %s", exc)
            return f"返回失败:{exc}"

    @tool("browser_screenshot")
    def browser_screenshot() -> str:
        """把当前页面截图保存到工作区并返回路径(仅用户要求截图/图片时使用)。"""
        try:
            p, browser, ctx, page = _open_shared_page(user_id)
            try:
                rel = _rel_screenshot_name()
                out = ws.workspace_root(user_id) / rel
                out.parent.mkdir(parents=True, exist_ok=True)
                page.screenshot(path=str(out))
                return f"截图已保存为 {rel}。请用 `[结果文件] {rel}` 标记交付。"
            finally:
                _close(p, browser)
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


__all__ = ["build_browser_tools"]
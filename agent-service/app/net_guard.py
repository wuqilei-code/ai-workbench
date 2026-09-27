"""出站请求 SSRF 防护。

默认禁止访问内网/回环/链路本地/保留地址(防 SSRF 探测内网、访问云元数据等)。
放行方式(二选一):
  - 环境变量 AGENT_OUTBOUND_ALLOWLIST=host1.example.com,host2  (按主机名精确放行)
  - 环境变量 AGENT_ALLOW_PRIVATE_OUTBOUND=true                  (内网私有部署时整体放行)

用法:在发起出站请求前调用 assert_url_allowed(url);不合规抛 ValueError。
注意:该检查在请求时按当前 DNS 解析结果判定,能挡住绝大多数内网探测;
对需要更强防护的场景应叠加出站代理/网络策略。
"""

from __future__ import annotations

import ipaddress
import os
import socket
from urllib.parse import urlparse


def _allow_private() -> bool:
    return os.environ.get("AGENT_ALLOW_PRIVATE_OUTBOUND", "").strip().lower() in ("1", "true", "yes", "on")


def _allowlist() -> set[str]:
    raw = os.environ.get("AGENT_OUTBOUND_ALLOWLIST", "")
    return {h.strip().lower() for h in raw.split(",") if h.strip()}


def _is_blocked_ip(ip: str) -> bool:
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    # ipv4-mapped ipv6 也按底层地址判断
    if isinstance(addr, ipaddress.IPv6Address) and addr.ipv4_mapped is not None:
        addr = addr.ipv4_mapped
    return (
        addr.is_private
        or addr.is_loopback
        or addr.is_link_local
        or addr.is_reserved
        or addr.is_multicast
        or addr.is_unspecified
    )


def assert_url_allowed(url: str) -> None:
    """URL 主机解析后若指向受保护地址则拒绝;白名单/全局放行时放行。"""
    if not url:
        raise ValueError("出站 URL 为空")
    if _allow_private():
        return
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise ValueError(f"不支持的出站协议: {parsed.scheme!r}")
    host = parsed.hostname
    if not host:
        raise ValueError(f"出站 URL 缺少主机: {url!r}")
    if host.lower() in _allowlist():
        return

    # 直接是字面 IP
    try:
        ips = [str(ipaddress.ip_address(host))]
    except ValueError:
        try:
            infos = socket.getaddrinfo(host, None)
        except socket.gaierror:
            raise ValueError(f"无法解析出站主机: {host}")
        ips = sorted({i[4][0] for i in infos})

    for ip in ips:
        if _is_blocked_ip(ip):
            raise ValueError(f"出站目标 {host} 解析到受保护地址 {ip},已拒绝(SSRF 防护)")

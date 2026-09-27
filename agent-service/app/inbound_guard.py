"""入站网络层加固:可选的来源 IP 白名单(CIDR)。

配置 ``INTERNAL_TRUSTED_CIDRS``(逗号分隔的 IP 或 CIDR,IPv4/IPv6)后,
除健康检查路径外的所有请求必须来自白名单网段,否则拒绝。
默认空 = 不限制来源(仅依赖 ``INTERNAL_TOKEN`` 鉴权)。

直接读取环境变量(而非 ``app.config``)以便在无 INTERNAL_TOKEN 的测试环境独立使用;
按原始配置字符串缓存解析结果,配置变更时自动重解析。
"""

from __future__ import annotations

import ipaddress
import logging
import os

logger = logging.getLogger(__name__)

_ENV_KEY = "INTERNAL_TRUSTED_CIDRS"
_cache_raw: str | None = None
_cache_nets: list = []


def _networks() -> list:
    global _cache_raw, _cache_nets
    raw = (os.environ.get(_ENV_KEY, "") or "").strip()
    if raw == _cache_raw:
        return _cache_nets
    nets: list = []
    if raw:
        for item in raw.split(","):
            v = item.strip()
            if not v:
                continue
            try:
                nets.append(ipaddress.ip_network(v, strict=False))
            except ValueError:
                logger.warning("invalid %s entry ignored: %s", _ENV_KEY, v)
        if not nets:
            logger.warning("%s set but no valid entry parsed; 来源限制不生效", _ENV_KEY)
    _cache_raw = raw
    _cache_nets = nets
    return nets


def is_trusted_client(host: str | None) -> bool:
    """来源 IP 是否允许。未配置白名单时一律允许。"""
    nets = _networks()
    if not nets:
        return True
    if not host:
        return False
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return False
    return any(ip in net for net in nets)

"""net_guard 出站 SSRF 防护的单元测试。"""

import pytest

from app import net_guard


def test_blocks_loopback():
    with pytest.raises(ValueError):
        net_guard.assert_url_allowed("http://127.0.0.1:8080/api")


def test_blocks_private_and_metadata():
    with pytest.raises(ValueError):
        net_guard.assert_url_allowed("http://192.168.1.1/")
    with pytest.raises(ValueError):
        net_guard.assert_url_allowed("http://169.254.169.254/latest/meta-data")

def test_blocks_non_http_scheme():
    with pytest.raises(ValueError):
        net_guard.assert_url_allowed("file:///etc/passwd")


def test_allow_private_env_bypasses(monkeypatch):
    monkeypatch.setenv("AGENT_ALLOW_PRIVATE_OUTBOUND", "true")
    # 不抛错即视为通过
    net_guard.assert_url_allowed("http://127.0.0.1:8080/")


def test_allowlist_host_passes(monkeypatch):
    monkeypatch.setenv("AGENT_OUTBOUND_ALLOWLIST", "internal.example.com")
    # 白名单主机直接放行,不做 DNS 解析
    net_guard.assert_url_allowed("http://internal.example.com/api")

"""inbound_guard 入站来源白名单的单元测试。"""

from app import inbound_guard


def test_no_config_allows_all(monkeypatch):
    monkeypatch.delenv("INTERNAL_TRUSTED_CIDRS", raising=False)
    assert inbound_guard.is_trusted_client("10.1.2.3")
    assert inbound_guard.is_trusted_client("127.0.0.1")
    assert inbound_guard.is_trusted_client(None)


def test_cidr_and_exact(monkeypatch):
    monkeypatch.setenv("INTERNAL_TRUSTED_CIDRS", "172.18.0.0/16,127.0.0.1")
    assert inbound_guard.is_trusted_client("172.18.5.9")
    assert inbound_guard.is_trusted_client("127.0.0.1")
    assert not inbound_guard.is_trusted_client("10.1.2.3")
    assert not inbound_guard.is_trusted_client(None)
    assert not inbound_guard.is_trusted_client("not-an-ip")


def test_ipv6_loopback(monkeypatch):
    monkeypatch.setenv("INTERNAL_TRUSTED_CIDRS", "::1")
    assert inbound_guard.is_trusted_client("::1")
    assert not inbound_guard.is_trusted_client("172.18.0.1")


def test_invalid_entries_ignored(monkeypatch):
    monkeypatch.setenv("INTERNAL_TRUSTED_CIDRS", "garbage,172.18.0.0/16")
    assert inbound_guard.is_trusted_client("172.18.0.2")
    assert not inbound_guard.is_trusted_client("192.168.1.1")

"""沙箱并发上限保护(void-docker 也可运行):仅限制新建、复用不受限、计数排除自身。"""

import os

# aio_sandbox 会经 config 校验 INTERNAL_TOKEN;测试环境注入占位值
os.environ.setdefault("INTERNAL_TOKEN", "test-token")

import pytest  # noqa: E402

from app import aio_sandbox as aio  # noqa: E402
from app import config  # noqa: E402


def test_cap_disabled_allows(monkeypatch):
    monkeypatch.setattr(config, "AGENT_SANDBOX_MAX_ACTIVE", 0)
    sb = aio.AioSandbox("cap-u1")
    try:
        sb._enforce_active_cap()  # 不抛错
    finally:
        aio.unregister_aio_sandbox(sb)


def test_cap_limits_new(monkeypatch):
    monkeypatch.setattr(config, "AGENT_SANDBOX_MAX_ACTIVE", 1)
    a = aio.AioSandbox("cap-a")
    b = aio.AioSandbox("cap-b")
    try:
        # 排除自身后各看到 1 个对方 >= 上限 1 → 拒绝新建
        with pytest.raises(RuntimeError):
            a._enforce_active_cap()
        with pytest.raises(RuntimeError):
            b._enforce_active_cap()
    finally:
        aio.unregister_aio_sandbox(a)
        aio.unregister_aio_sandbox(b)


def test_count_excludes_self(monkeypatch):
    monkeypatch.setattr(config, "AGENT_SANDBOX_MAX_ACTIVE", 0)
    sb = aio.AioSandbox("cap-c")
    try:
        assert aio.active_sandbox_count(exclude=sb) == 0
        assert aio.active_sandbox_count() >= 1
    finally:
        aio.unregister_aio_sandbox(sb)

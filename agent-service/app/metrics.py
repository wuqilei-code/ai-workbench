"""极简进程内指标(零依赖),以 Prometheus 文本格式在 /metrics 暴露。

统计少量关键计数(counter)与瞬时量(gauge:如活跃沙箱数),便于内网监控与排障;
如需直方图/分位等高级指标,再引入 prometheus_client。
"""

from __future__ import annotations

import threading
import time

_lock = threading.Lock()
_start_ts = time.time()
_counters: dict[str, float] = {}
_gauges: dict[str, float] = {}


def inc(name: str, n: float = 1.0) -> None:
    """计数器 +n(默认 +1)。"""
    with _lock:
        _counters[name] = _counters.get(name, 0.0) + n


def set_gauge(name: str, value: float) -> None:
    """瞬时量设为 value(如活跃沙箱数)。"""
    with _lock:
        _gauges[name] = float(value)


def _fmt(v: float) -> str:
    return str(int(v)) if v == int(v) else str(v)


def render() -> str:
    with _lock:
        counters = dict(_counters)
        gauges = dict(_gauges)
    lines = [
        "# HELP aiwb_uptime_seconds Process uptime in seconds.",
        "# TYPE aiwb_uptime_seconds gauge",
        f"aiwb_uptime_seconds {time.time() - _start_ts:.0f}",
    ]
    for name in sorted(gauges):
        lines.append(f"# TYPE {name} gauge")
        lines.append(f"{name} {_fmt(gauges[name])}")
    for name in sorted(counters):
        lines.append(f"# TYPE {name} counter")
        lines.append(f"{name} {_fmt(counters[name])}")
    return "\n".join(lines) + "\n"

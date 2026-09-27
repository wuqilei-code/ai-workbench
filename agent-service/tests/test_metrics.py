"""metrics 计数/瞬时量渲染的单元测试。"""

from app import metrics


def test_counter_rendered():
    metrics.inc("aiwb_test_counter_total", 2)
    out = metrics.render()
    assert "# TYPE aiwb_test_counter_total counter" in out
    assert "aiwb_test_counter_total 2" in out


def test_gauge_rendered_and_overwritten():
    metrics.set_gauge("aiwb_test_gauge", 3)
    assert "aiwb_test_gauge 3" in metrics.render()
    assert "# TYPE aiwb_test_gauge gauge" in metrics.render()
    metrics.set_gauge("aiwb_test_gauge", 0)
    assert "aiwb_test_gauge 0" in metrics.render()


def test_uptime_present():
    assert "aiwb_uptime_seconds" in metrics.render()

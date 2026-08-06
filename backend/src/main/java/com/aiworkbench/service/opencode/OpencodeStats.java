package com.aiworkbench.service.opencode;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * opencode 事件处理统计(轻量可观测性,无额外依赖)。
 * <p>
 * 由各 {@link OpencodeInstanceEventConsumer} 递增,经 {@code /api/health} 暴露,
 * 用于观察流式事件量、文本落库节流效果与重连频率。
 */
@Component
public class OpencodeStats {

    private final AtomicLong eventsHandled = new AtomicLong();
    private final AtomicLong textPersists = new AtomicLong();
    private final AtomicLong textPersistSkipped = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    private final ConcurrentHashMap<String, Long> eventsByType = new ConcurrentHashMap<>();

    public void onEvent(String type) {
        eventsHandled.incrementAndGet();
        eventsByType.compute(type, (k, v) -> v == null ? 1L : v + 1);
    }

    public void onTextPersist() {
        textPersists.incrementAndGet();
    }

    public void onTextPersistSkipped() {
        textPersistSkipped.incrementAndGet();
    }

    public void onReconnect() {
        reconnects.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventsHandled", eventsHandled.get());
        m.put("textPersists", textPersists.get());
        m.put("textPersistSkipped", textPersistSkipped.get());
        m.put("reconnects", reconnects.get());
        m.put("byType", new LinkedHashMap<>(eventsByType));
        return m;
    }
}

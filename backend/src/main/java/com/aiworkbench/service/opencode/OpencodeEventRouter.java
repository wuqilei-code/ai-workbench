package com.aiworkbench.service.opencode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 事件路由:按 opencode session id 维护前端 SseEmitter 列表,
 * 将 opencode /event 的事件转发给订阅了该会话的前端连接。
 * <p>
 * 订阅时由 Controller 校验会话归属(防越权)后再调用 {@link #subscribe}。
 */
@Slf4j
@Component
public class OpencodeEventRouter {

    /** key = opencodeSessionId */
    private final ConcurrentHashMap<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public void subscribe(String opencodeSessionId, SseEmitter emitter) {
        subscribers.computeIfAbsent(opencodeSessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        log.debug("subscribed emitter to opencode session {} (total={})",
                opencodeSessionId, subscribers.get(opencodeSessionId).size());
    }

    public void unsubscribe(String opencodeSessionId, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(opencodeSessionId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                subscribers.remove(opencodeSessionId, list);
            }
        }
    }

    /**
     * 25s 心跳:给所有订阅发注释行,既防代理/浏览器断连,也清理已断开的死连接。
     * 注意:catch 中只能用 emitter.complete(),不能用 completeWithError(e),
     * 否则 Spring MVC 会触发 GlobalExceptionHandler 试图用 R 对象响应 text/event-stream,
     * 导致 HttpMessageNotWritableException 二次异常,污染日志并可能影响连接清理。
     */
    @Scheduled(fixedRate = 25_000L)
    public void heartbeat() {
        if (subscribers.isEmpty()) return;
        subscribers.forEach((sid, list) -> {
            if (list == null || list.isEmpty()) return;
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (Exception e) {
                    log.debug("heartbeat emitter gone: {}", e.toString());
                    unsubscribe(sid, emitter);
                    try { emitter.complete(); } catch (Exception ignored) {}
                }
            }
        });
    }

    /**
     * 将一个事件转发给该会话的所有前端连接。
     *
     * @param opencodeSessionId 事件归属的 opencode 会话
     * @param type              事件类型(作为 SSE event name)
     * @param dataJson          事件 data(properties 的 JSON 字符串)
     */
    public void dispatch(String opencodeSessionId, String type, String dataJson) {
        List<SseEmitter> list = subscribers.get(opencodeSessionId);
        if (list == null || list.isEmpty()) {
            // 诊断:压缩事件无人订阅也要可观测
            if ("session.compacting".equals(type) || "session.compacted".equals(type)) {
                log.info("dispatch {} to session {}: NO subscribers", type, opencodeSessionId);
            }
            return;
        }
        if ("session.compacting".equals(type) || "session.compacted".equals(type)
                || type.startsWith("message.")) {
            log.info("dispatch {} to session {} ({} subscribers)", type, opencodeSessionId, list.size());
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(type).data(dataJson));
            } catch (Exception e) {
                // 客户端断开等:移除该 emitter,安静地 complete(不用 completeWithError 以免触发异常处理链)
                log.debug("dispatch to emitter failed (likely client gone): {}", e.toString());
                unsubscribe(opencodeSessionId, emitter);
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }
    }
}

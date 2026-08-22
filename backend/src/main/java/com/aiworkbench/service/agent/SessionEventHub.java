package com.aiworkbench.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 会话级 SSE 事件枢纽:订阅/分发/心跳。
 * <p>
 * 纯传输层,不感知事件语义;事件载荷由 AgentStreamBridge 原样转发
 * (deepagents 原生事件:start/delta/tool/tool_result/error/done)。
 */
@Slf4j
@Component
public class SessionEventHub {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public void subscribe(String sessionId, SseEmitter emitter) {
        subscribers.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        log.debug("subscribed emitter to session {} (total={})", sessionId, subscribers.get(sessionId).size());
    }

    public void unsubscribe(String sessionId, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(sessionId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) subscribers.remove(sessionId, list);
        }
    }

    /** 全会话心跳(防代理层空闲断连)。 */
    public void heartbeat() {
        if (subscribers.isEmpty()) return;
        subscribers.forEach((sid, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    list.remove(emitter);
                }
            }
        });
    }

    /** 向指定会话的所有订阅者分发事件(type + data 对象)。 */
    public void dispatch(String sessionId, String type, Object data) {
        List<SseEmitter> list = subscribers.get(sessionId);
        if (list == null || list.isEmpty()) {
            log.debug("dispatch {} to session {}: NO subscribers", type, sessionId);
            return;
        }
        String json;
        try {
            json = data instanceof String s ? s : objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("serialize event {} failed: {}", type, e.toString());
            return;
        }
        log.info("dispatch {} to session {} ({} subscribers)", type, sessionId, list.size());
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(type).data(json));
            } catch (Exception e) {
                unsubscribe(sessionId, emitter);
            }
        }
    }

    public int subscriberCount(String sessionId) {
        List<SseEmitter> l = subscribers.get(sessionId);
        return l == null ? 0 : l.size();
    }

    /** 兼容旧调用签名(String JSON 直发)。 */
    public void dispatchJson(String sessionId, String type, String dataJson) {
        dispatch(sessionId, type, dataJson == null ? Map.of() : raw(dataJson));
    }

    private JsonNode raw(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}

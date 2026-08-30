package com.aiworkbench.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

    /** 最后一个订阅者断开时,取消在途 agent 生成(避免浏览器关页后 agent 仍在烧 token/写盘)。 */
    private final org.springframework.beans.factory.ObjectProvider<AgentStreamBridge> bridgeProvider;

    /**
     * 断连宽限调度:客户端重连(页面刷新、网络抖动、前端 45s 无事件看门狗重连)都会出现
     * "旧连接先关、新连接后订阅"的间隙。最后订阅者一走就立刻 abort 会误杀在途生成
     * (用户症状:输出过程中突然断了)。延迟 10s 执行、期间有新订阅者则取消,
     * 既保留"回收孤儿任务"的初衷,又避开重连竞态。
     */
    private final ScheduledExecutorService abortScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-abort-grace");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingAborts = new ConcurrentHashMap<>();

    public SessionEventHub(@Lazy org.springframework.beans.factory.ObjectProvider<AgentStreamBridge> bridgeProvider) {
        this.bridgeProvider = bridgeProvider;
    }

    public void subscribe(String sessionId, SseEmitter emitter) {
        // 新订阅者到位:撤销待执行的断连 abort(重连场景)
        ScheduledFuture<?> pending = pendingAborts.remove(sessionId);
        if (pending != null) {
            pending.cancel(false);
        }
        subscribers.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        log.debug("subscribed emitter to session {} (total={})", sessionId, subscribers.get(sessionId).size());
    }

    public void unsubscribe(String sessionId, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(sessionId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                subscribers.remove(sessionId, list);
                // 无监听者:宽限期后再终止在途生成;若期间有人重新订阅则取消
                pendingAborts.computeIfAbsent(sessionId, sid -> abortScheduler.schedule(() -> {
                    pendingAborts.remove(sid);
                    List<SseEmitter> cur = subscribers.get(sid);
                    if (cur == null || cur.isEmpty()) {
                        try {
                            bridgeProvider.getIfAvailable().abort(sid);
                        } catch (Exception e) {
                            log.debug("abort on last-subscriber disconnect failed (session={}): {}",
                                    sid, e.toString());
                        }
                    }
                }, 10, TimeUnit.SECONDS));
            }
        }
    }

    /**
     * 全会话心跳(15s 一次):防代理层空闲断连,更重要的是给前端"续活"——
     * 前端流式期间 45s 收不到任何 SSE 事件会判定连接卡死并强制重连,而重连间隙
     * 曾触发旧连接的即时 abort,把在途生成掐断(agent 静默执行长工具/思考时最易踩中)。
     * 必须用命名事件而非注释行(:ping):前端只在真实 message 上刷新活跃时间戳,
     * 注释行不会进入 onmessage。
     */
    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        if (subscribers.isEmpty()) return;
        subscribers.forEach((sid, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().name("ping").data("{}"));
                } catch (Exception e) {
                    unsubscribe(sid, emitter);
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
        log.debug("dispatch {} to session {} ({} subscribers)", type, sessionId, list.size());
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

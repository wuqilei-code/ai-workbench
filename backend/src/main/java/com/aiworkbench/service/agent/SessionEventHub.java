package com.aiworkbench.service.agent;

import com.aiworkbench.entity.SessionEvent;
import com.aiworkbench.mapper.SessionEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 会话级 SSE 事件枢纽:订阅/分发/心跳 + 跨副本广播。
 * <p>
 * 纯传输层,不感知事件语义;事件载荷由 AgentStreamBridge 原样转发
 * (deepagents 原生事件:start/delta/tool/tool_result/error/done)。
 * <p>
 * <b>跨副本</b>:dispatch 除投递本地订阅者外,还写一条 {@code session_event} 出站记录
 * (带 origin=本实例 id)。各实例定时按 seq 增量轮询,把<b>非本实例产生</b>的事件
 * 投递给本地订阅者 —— 从而"执行发生在 A、订阅连到 B"时事件仍可达。
 * 单副本时轮询仅读到自身 origin 的行(直接跳过),行为与纯内存一致,仅多一次轻量查询。
 * 可用 {@code app.sse-relay.enabled=false} 关闭出站与轮询(退回纯内存)。
 */
@Slf4j
@Component
public class SessionEventHub {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    private final SessionEventMapper sessionEventMapper;
    private final boolean relayEnabled;
    private final long retentionSeconds;
    private final String instanceId;

    /** 出站轮询水位(已处理的最大 seq)。 */
    private volatile long lastSeq = 0;

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

    public SessionEventHub(SessionEventMapper sessionEventMapper,
                           @Lazy org.springframework.beans.factory.ObjectProvider<AgentStreamBridge> bridgeProvider,
                           @Value("${app.sse-relay.enabled:true}") boolean relayEnabled,
                           @Value("${app.sse-relay.retention-seconds:300}") long retentionSeconds,
                           @Value("${app.sse-relay.instance-id:}") String configuredInstanceId) {
        this.sessionEventMapper = sessionEventMapper;
        this.bridgeProvider = bridgeProvider;
        this.relayEnabled = relayEnabled;
        this.retentionSeconds = retentionSeconds;
        this.instanceId = (configuredInstanceId == null || configuredInstanceId.isBlank())
                ? "inst-" + UUID.randomUUID() : configuredInstanceId.trim();
    }

    /** 启动时把水位推进到当前最大 seq,避免新实例把历史事件当作"新事件"重放。 */
    @PostConstruct
    public void initRelayWatermark() {
        if (!relayEnabled) {
            return;
        }
        try {
            SessionEvent last = sessionEventMapper.selectOne(
                    new LambdaQueryWrapper<SessionEvent>().orderByDesc(SessionEvent::getSeq).last("limit 1"));
            if (last != null && last.getSeq() != null) {
                lastSeq = last.getSeq();
            }
        } catch (Exception e) {
            log.warn("init sse relay watermark failed: {}", e.toString());
        }
        log.info("sse relay {} (instance={})", relayEnabled ? "enabled" : "disabled", instanceId);
    }

    public String instanceId() {
        return instanceId;
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

    /** 向指定会话的所有订阅者分发事件(type + data 对象),并写出站记录供其它副本中继。 */
    public void dispatch(String sessionId, String type, Object data) {
        String json;
        try {
            json = data instanceof String s ? s : objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("serialize event {} failed: {}", type, e.toString());
            return;
        }
        deliverLocal(sessionId, type, json);
        persistOutbox(sessionId, type, json);
    }

    /** 投递给本地订阅者(无订阅者则静默)。 */
    private void deliverLocal(String sessionId, String type, String json) {
        List<SseEmitter> list = subscribers.get(sessionId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(type).data(json));
            } catch (Exception e) {
                unsubscribe(sessionId, emitter);
            }
        }
    }

    /** 写出站记录(失败仅告警,不影响本地投递)。 */
    private void persistOutbox(String sessionId, String type, String json) {
        if (!relayEnabled) {
            return;
        }
        try {
            SessionEvent e = new SessionEvent();
            e.setSessionId(sessionId);
            e.setOrigin(instanceId);
            e.setType(type);
            e.setData(json);
            sessionEventMapper.insert(e);
        } catch (Exception ex) {
            log.debug("persist session_event failed (session={} type={}): {}",
                    sessionId, type, ex.toString());
        }
    }

    /**
     * 跨副本中继:按 seq 增量读取出站表,把非本实例产生的事件投递给本地订阅者。
     * 未命中本地订阅者的事件直接跳过(客户端刷新后由消息接口重建,不依赖事件回放)。
     */
    @Scheduled(fixedDelayString = "${app.sse-relay.poll-ms:500}")
    public void relay() {
        if (!relayEnabled) {
            return;
        }
        List<SessionEvent> rows;
        try {
            rows = sessionEventMapper.selectList(new LambdaQueryWrapper<SessionEvent>()
                    .gt(SessionEvent::getSeq, lastSeq)
                    .orderByAsc(SessionEvent::getSeq)
                    .last("limit 500"));
        } catch (Exception e) {
            log.debug("sse relay poll failed: {}", e.toString());
            return;
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (SessionEvent e : rows) {
            if (e.getSeq() != null && e.getSeq() > lastSeq) {
                lastSeq = e.getSeq();
            }
            if (instanceId.equals(e.getOrigin())) {
                continue; // 本实例已即时投递
            }
            if (subscribers.containsKey(e.getSessionId())) {
                deliverLocal(e.getSessionId(), e.getType(), e.getData());
            }
        }
    }

    /** 定期清理过期的出站记录,限制表增长。 */
    @Scheduled(fixedDelay = 60_000)
    public void cleanupOutbox() {
        if (!relayEnabled) {
            return;
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(Math.max(60, retentionSeconds));
            sessionEventMapper.delete(new LambdaQueryWrapper<SessionEvent>()
                    .lt(SessionEvent::getCreatedAt, cutoff)
                    .last("limit 5000"));
        } catch (Exception e) {
            log.debug("sse relay cleanup failed: {}", e.toString());
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

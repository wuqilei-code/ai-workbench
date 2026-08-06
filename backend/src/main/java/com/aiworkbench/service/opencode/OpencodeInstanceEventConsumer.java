package com.aiworkbench.service.opencode;

import com.aiworkbench.service.ChatMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 单个 opencode 实例的 SSE 事件消费者(Phase 2:per-user 多实例)。
 * <p>
 * 每个 {@link com.aiworkbench.service.opencode.process.UserProcess} 持有一个本类实例,
 * 订阅该实例(127.0.0.1:&lt;port&gt;)的 /global/event 流,逐事件:
 * <ol>
 *   <li>解析 {type, properties}</li>
 *   <li>message.updated / message.part.updated → 经 {@link ChatMessageService} 持久化到 MySQL(turn-merging 关键路径)</li>
 *   <li>转发给前端:经共享 {@link OpencodeEventRouter} 按 opencodeSessionId 路由(多消费者向同一 router 投递,天然正确)</li>
 * </ol>
 * 连接断开/异常自动 2s 重连。
 * <p>
 * 非单例 bean:由 {@link com.aiworkbench.service.opencode.process.OpencodeProcessManager} 显式 new 出来,
 * 生命周期与所属 UserProcess 绑定(start/stop)。不再用 @Component/@PostConstruct,避免启动时订阅不存在的 4096 流。
 */
@Slf4j
public class OpencodeInstanceEventConsumer {

    private final WebClient instanceClient;
    private final OpencodeEventRouter router;
    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;
    private final int port;

    /** 消费者实例序号(仅用于线程命名;不能用 port,因 scheduler 字段初始化早于构造函数赋值 port) */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger(0);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "opencode-sse-" + SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private volatile reactor.core.Disposable subscription;
    private volatile boolean stopped = false;

    public OpencodeInstanceEventConsumer(WebClient instanceClient, OpencodeEventRouter router,
                                         ChatMessageService chatMessageService, ObjectMapper objectMapper, int port) {
        this.instanceClient = instanceClient;
        this.router = router;
        this.chatMessageService = chatMessageService;
        this.objectMapper = objectMapper;
        this.port = port;
    }

    /** 订阅该实例 /global/event。由 ProcessManager 在进程 READY 后调用。 */
    public void start() {
        connect();
    }

    /** 停止订阅并释放线程池。由 ProcessManager.stop 调用。 */
    public void stop() {
        stopped = true;
        if (subscription != null) {
            subscription.dispose();
        }
        scheduler.shutdownNow();
    }

    private void connect() {
        if (stopped) {
            return;
        }
        log.info("connecting to opencode /global/event stream (port={})", port);
        try {
            subscription = instanceClient.get().uri("/global/event")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .timeout(java.time.Duration.ofSeconds(120), reactor.core.publisher.Flux.empty())
                    .subscribe(this::handle, this::onError, this::onComplete);
        } catch (Exception e) {
            log.warn("failed to subscribe opencode event stream (port={}): {}", port, e.toString());
            scheduleReconnect();
        }
    }

    private void onError(Throwable err) {
        if (stopped) return;
        log.warn("opencode event stream error (port={}), reconnect in 2s: {}", port, err.toString());
        scheduleReconnect();
    }

    private void onComplete() {
        if (stopped) return;
        log.warn("opencode event stream completed (port={}), reconnect in 2s", port);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (stopped) return;
        try {
            scheduler.schedule(this::connect, 2, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private void handle(ServerSentEvent<String> sse) {
        String type = sse.event();
        String data = sse.data();
        if (data == null || data.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(data);
            // /global/event 的 data 形如 {"payload":{"id,type,properties}};unwrap
            if (root.has("payload")) {
                root = root.get("payload");
            }
            String resolvedType = root.has("type") ? root.get("type").asText() : type;
            JsonNode properties = root.has("properties") ? root.get("properties") : root;
            if (resolvedType == null) {
                return;
            }
            String sessionID = properties.has("sessionID") ? properties.get("sessionID").asText() : null;
            if (sessionID == null && properties.has("info")) {
                JsonNode info = properties.get("info");
                if (info.has("sessionID")) sessionID = info.get("sessionID").asText();
            }
            // 持久化:message.updated 写元信息,message.part.updated 写文本内容/parts
            if ("message.updated".equals(resolvedType) && sessionID != null) {
                try {
                    chatMessageService.upsertFromEvent(sessionID, properties);
                } catch (Exception persistErr) {
                    log.warn("persist message.updated failed: {}", persistErr.toString());
                }
            }
            if ("message.part.updated".equals(resolvedType) && sessionID != null) {
                try {
                    chatMessageService.upsertPartFromEvent(sessionID, properties);
                } catch (Exception persistErr) {
                    log.warn("persist message.part.updated failed: {}", persistErr.toString());
                }
            }
            if (("question.asked".equals(resolvedType) || "question.v2.asked".equals(resolvedType)) && sessionID != null) {
                try {
                    chatMessageService.upsertQuestionFromEvent(sessionID, properties);
                } catch (Exception persistErr) {
                    log.warn("persist question.asked failed: {}", persistErr.toString());
                }
            }
            if (("permission.asked".equals(resolvedType) || "permission.v2.asked".equals(resolvedType)) && sessionID != null) {
                try {
                    chatMessageService.upsertPermissionFromEvent(sessionID, properties);
                } catch (Exception persistErr) {
                    log.warn("persist permission.asked failed: {}", persistErr.toString());
                }
            }
            // 已回答/已拒绝 → 从 parts 持久化中移除 pendingQuestion,避免重新加载会话时重复弹框
            if (("question.replied".equals(resolvedType) || "question.rejected".equals(resolvedType)
                    || "question.v2.replied".equals(resolvedType) || "question.v2.rejected".equals(resolvedType))
                    && sessionID != null) {
                try {
                    chatMessageService.clearPendingQuestionFromEvent(sessionID, properties);
                } catch (Exception persistErr) {
                    log.warn("clear pendingQuestion on {} failed: {}", resolvedType, persistErr.toString());
                }
            }
            // 已确认权限 → 从 parts 持久化中移除 pendingPermission
            if (("permission.replied".equals(resolvedType) || "permission.v2.replied".equals(resolvedType))
                    && sessionID != null) {
                try {
                    chatMessageService.clearPendingPermissionFromEvent(sessionID, properties);
                } catch (Exception persistErr) {
                    log.warn("clear pendingPermission on {} failed: {}", resolvedType, persistErr.toString());
                }
            }
            // 转发给前端
            if (sessionID != null) {
                router.dispatch(sessionID, resolvedType, objectMapper.writeValueAsString(properties));
            }
        } catch (Exception e) {
            log.debug("failed to handle opencode event (type={}): {}", type, e.toString());
        }
    }
}

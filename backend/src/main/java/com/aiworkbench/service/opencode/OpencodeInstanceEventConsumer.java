package com.aiworkbench.service.opencode;

import com.aiworkbench.service.ChatMessageService;
import com.aiworkbench.service.ChangeSummaryService;
import com.aiworkbench.service.WorkspaceSnapshotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
 * <p>
 * 性能与健壮性说明:
 * <ul>
 *   <li>事件处理({@code publishOn(boundedElastic)})落在弹性线程池,<b>避免阻塞 JDBC 占用 Netty 事件循环线程</b>。
 *       流式期间每条 part 都涉及 DB 读写,原实现在 reactor 线程上做阻塞调用,会拖累整机所有用户的 HTTP 事件循环。</li>
 *   <li>流式文本落库<b>节流</b>(TEXT_PERSIST_MIN_INTERVAL_MS):同一消息的文本 part 至少间隔该时长才写一次,
 *       避免把整段 MEDIUMTEXT 重写几百次;message.updated(消息定稿)与 stop() 时强制 flush 最终文本。</li>
 *   <li>生成状态经 {@code streamingSink} 上报 ProcessManager,供 applyConfig 跳过正在生成的用户。</li>
 * </ul>
 */
@Slf4j
public class OpencodeInstanceEventConsumer {

    /** 同一消息两次文本落库的最小间隔:节流窗口内的中间态只进内存,不落库 */
    private static final long TEXT_PERSIST_MIN_INTERVAL_MS = 800L;

    private final WebClient instanceClient;
    private final OpencodeEventRouter router;
    private final ChatMessageService chatMessageService;
    private final WorkspaceSnapshotService snapshotService;
    private final ChangeSummaryService changeSummaryService;
    private final ObjectMapper objectMapper;
    private final int port;
    private final long userId;
    private final Consumer<Boolean> streamingSink;
    private final OpencodeStats stats;

    /** 消费者实例序号(仅用于线程命名;不能用 port,因 scheduler 字段初始化早于构造函数赋值 port) */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger(0);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "opencode-sse-" + SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private volatile reactor.core.Disposable subscription;
    private volatile boolean stopped = false;

    /** 流式文本节流缓冲:messageID → {sessionID, 最新文本} */
    private final ConcurrentHashMap<String, TextBuffer> pendingTexts = new ConcurrentHashMap<>();
    /** messageID → 上次落库时间戳(毫秒) */
    private final ConcurrentHashMap<String, Long> lastTextPersistAt = new ConcurrentHashMap<>();

    private record TextBuffer(String sessionID, String text) {}

public OpencodeInstanceEventConsumer(WebClient instanceClient, OpencodeEventRouter router,
                                     ChatMessageService chatMessageService, WorkspaceSnapshotService snapshotService,
                                     ChangeSummaryService changeSummaryService,
                                     ObjectMapper objectMapper, int port,
                                     long userId, Consumer<Boolean> streamingSink, OpencodeStats stats) {
        this.instanceClient = instanceClient;
        this.router = router;
        this.chatMessageService = chatMessageService;
        this.snapshotService = snapshotService;
        this.changeSummaryService = changeSummaryService;
        this.objectMapper = objectMapper;
        this.port = port;
        this.userId = userId;
        this.streamingSink = streamingSink;
        this.stats = stats;
    }

    /** 订阅该实例 /global/event。由 ProcessManager 在进程 READY 后调用。 */
    public void start() {
        connect();
    }

    /** 停止订阅并释放线程池。由 ProcessManager.stop 调用。先 flush 未落库的节流文本。 */
    public void stop() {
        stopped = true;
        flushAllPendingTexts();
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
                    .timeout(java.time.Duration.ofSeconds(120), Flux.empty())
                    // 事件处理含阻塞 JDBC,必须离开 Netty 事件循环线程
                    .publishOn(Schedulers.boundedElastic())
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
        stats.onReconnect();
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
            stats.onEvent(resolvedType);

            String sessionID = extractSessionId(properties);

            // 生成状态标记:applyConfig 借此跳过正在生成的用户(不打断活跃对话)
            markStreaming(resolvedType, properties, sessionID);

            // 持久化:message.updated 写元信息,message.part.updated 写文本内容/parts
            if ("message.updated".equals(resolvedType) && sessionID != null) {
                try {
                    chatMessageService.upsertFromEvent(sessionID, properties);
                    // 消息定稿 → 强制 flush 该消息尚未落库的节流文本(最终文本以 message.updated 为准收口)
                    flushTextForMessage(sessionID, properties);
                } catch (Exception persistErr) {
                    log.warn("persist message.updated failed: {}", persistErr.toString());
                }
            }
            if ("message.part.updated".equals(resolvedType) && sessionID != null) {
                try {
                    JsonNode part = properties.has("part") ? properties.get("part") : null;
                    if (part != null && "text".equals(part.path("type").asText("")) && part.has("messageID")) {
                        // 文本 part:节流落库(中间态进内存,超间隔写一次),而非每条重写整段 content
                        bufferText(sessionID, part.get("messageID").asText(), part.path("text").asText(""));
                    } else {
                        chatMessageService.upsertPartFromEvent(sessionID, properties);
                    }
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

    /** 提取事件归属的 opencode session id(sessionID 或 info.sessionID) */
    private String extractSessionId(JsonNode properties) {
        String sessionID = properties.has("sessionID") ? properties.get("sessionID").asText() : null;
        if (sessionID == null && properties.has("info")) {
            JsonNode info = properties.get("info");
            if (info.has("sessionID")) sessionID = info.get("sessionID").asText();
        }
        return sessionID;
    }

    /**
     * 依据事件类型更新该用户进程的"生成中"标记。
     * <p>
     * 约定:从第一条 part 出现到 session.idle/session.error 之间视为 streaming=true;
     * question/permission 等待用户期间也保持 true(避免 applyConfig 重启打断待答交互)。
     */
    private void markStreaming(String resolvedType, JsonNode properties, String sessionID) {
        switch (resolvedType) {
            case "message.part.updated" -> streamingSink.accept(true);
            case "session.status" -> {
                JsonNode status = properties.has("status") ? properties.get("status") : null;
                boolean busy = status != null && "busy".equals(status.get("type").asText(""));
                streamingSink.accept(busy);
            }
            case "session.idle", "session.error" -> {
                streamingSink.accept(false);
                // 每轮对话结束(正常空闲或异常结束)对用户工作区做一次 git 快照,
                // 把 AI 本轮产生的文件改动固化为一个可回滚的版本;随后对本次改动生成 AI 变更总结。
                String snapshotId = sessionID;
                asyncSnapshot(snapshotId);
            }
            default -> { }
        }
    }

    /** 每轮对话结束:异步快照,成功后对本次快照生成 AI 变更总结;异步且静默,避免阻塞事件流。 */
    private void asyncSnapshot(String sessionID) {
        final long uid = userId;
        final String sid = sessionID;
        CompletableFuture.runAsync(() -> {
            String sha = snapshotService.snapshot(uid, "AI 对话结束");
            if (sha != null) {
                changeSummaryService.summarizeAfterSnapshot(uid, sid, sha);
            }
        }).exceptionally(e -> {
            log.warn("snapshot/summary failed (userId={}): {}", uid, e.toString());
            return null;
        });
    }

    // ============ 文本落库节流 ============

    private void bufferText(String sessionID, String messageID, String text) {
        if (text.isBlank()) {
            return; // 空文本无意义,不占缓冲
        }
        pendingTexts.put(messageID, new TextBuffer(sessionID, text));
        long now = System.currentTimeMillis();
        Long last = lastTextPersistAt.getOrDefault(messageID, 0L);
        if (now - last >= TEXT_PERSIST_MIN_INTERVAL_MS) {
            flushText(messageID);
        } else {
            stats.onTextPersistSkipped();
        }
    }

    private void flushText(String messageID) {
        TextBuffer buf = pendingTexts.remove(messageID);
        if (buf == null) {
            return;
        }
        lastTextPersistAt.put(messageID, System.currentTimeMillis());
        try {
            chatMessageService.upsertStreamingText(buf.sessionID, messageID, buf.text);
            stats.onTextPersist();
        } catch (Exception e) {
            log.warn("persist streamed text failed (userId={} messageID={}): {}", userId, messageID, e.toString());
        }
    }

    /** message.updated 定稿:flush 该消息残留的节流文本 */
    private void flushTextForMessage(String sessionID, JsonNode properties) {
        JsonNode info = properties.has("info") ? properties.get("info") : properties;
        if (info == null || !info.has("id")) {
            return;
        }
        String messageID = info.get("id").asText();
        if (pendingTexts.containsKey(messageID)) {
            flushText(messageID);
        }
    }

    private void flushAllPendingTexts() {
        for (String messageID : new java.util.HashSet<>(pendingTexts.keySet())) {
            try {
                flushText(messageID);
            } catch (Exception e) {
                log.warn("flush pending text failed (userId={} messageID={}): {}", userId, messageID, e.toString());
            }
        }
    }
}

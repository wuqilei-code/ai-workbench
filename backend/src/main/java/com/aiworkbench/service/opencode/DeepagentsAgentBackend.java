package com.aiworkbench.service.opencode;

import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.service.ChatMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * deepagents 轨道的 Spring 网关桥:把每个用户消息转发到 python agent-service 的 SSE 流,
 * 并把 agent-service 的事件翻译成前端/DB 已约定的 opencode 形状:
 * <ul>
 *   <li>delta → message.part.delta(实时流)+ 节流落库(upsertStreamingText)</li>
 *   <li>tool → message.part.updated{tool}(mergeParts 写入 parts.toolCalls)</li>
 *   <li>tool_result → message.part.updated{tool state.completed}</li>
 *   <li>done/error → message.part.updated{text 定稿} + message.updated + session.idle</li>
 * </ul>
 * 落库与前端路由均以 chat_session.opencode_session_id(合成 "da-" 前缀 id)为 key,
 * 与 opencode 轨道共享 {@link ChatMessageService} upsert 方法与 {@link OpencodeEventRouter}。
 * <p>
 * 启动方式:由 ChatMessageService/ChatSessionService 在 switch=deepagents 时调用(两者以 @Lazy 注入本组件)。
 */
@Slf4j
@Component
public class DeepagentsAgentBackend {

    /** 文本节流落库最小间隔(ms),避免流式期间反复重写 MEDIUMTEXT */
    private static final long TEXT_PERSIST_MIN_INTERVAL_MS = 800L;

    private final boolean enabled;
    private final ChatMessageService chatMessageService;
    private final OpencodeEventRouter router;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final ExecutorService executor;

    /** 在途生成状态,key = opencodeSessionId("da-..." 合成 id)。 */
    private final Map<String, TurnStream> streams = new ConcurrentHashMap<>();

    public DeepagentsAgentBackend(
            @Value("${app.agent-backend:opencode}") String agentBackend,
            @Value("${deepagents.base-url:http://localhost:8000}") String baseUrl,
            ChatMessageService chatMessageService,
            OpencodeEventRouter router,
            ObjectMapper objectMapper) {
        this.enabled = "deepagents".equalsIgnoreCase(agentBackend);
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.chatMessageService = chatMessageService;
        this.router = router;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().baseUrl(base).build();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "deepagents-sse");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 是否 deepagents 合成的会话 id(ChatSessionService 建会话时生成 "da-" 前缀)。 */
    public boolean ownsSession(String opencodeSessionId) {
        return opencodeSessionId != null && opencodeSessionId.startsWith("da-");
    }

    /**
     * 发送用户消息(异步 fire-and-forget):订阅 agent-service SSE,翻译后落库 + 推前端。
     * 调用方不阻塞,事件在弹性线程池上处理。
     */
    public void sendMessage(ChatSession session, Long userId, String text) {
        String ocSessionId = session.getOpencodeSessionId();
        if (!ownsSession(ocSessionId)) {
            log.warn("deepagents sendMessage on non-deepagents session {}", ocSessionId);
            return;
        }
        TurnStream ts = new TurnStream(ocSessionId);
        streams.put(ocSessionId, ts);
        // 冷启动占位:agent-service 首个真实事件到达前,前端显示"启动中"。
        dispatch(ocSessionId, "session.starting", "{\"status\":\"starting\"}");
        executor.execute(() -> stream(ts, userId, text));
    }

    /**
     * 终止在途生成:只撤销本地订阅与后续事件处理(agent-service 无 abort 端点)。
     *
     * @return true=确有在途生成被终止(调用方据此补"已中断"标记/清理 streaming 状态)
     */
    public boolean abort(String opencodeSessionId) {
        TurnStream ts = streams.remove(opencodeSessionId);
        if (ts == null) {
            return false;
        }
        ts.cancelled.set(true);
        if (ts.subscription != null) {
            ts.subscription.dispose();
        }
        log.info("deepagents abort: cancelled in-flight stream for session {}", opencodeSessionId);
        return true;
    }

    /** 单轮生成状态:消息 id、累计文本、订阅句柄、最终化守卫。 */
    private static final class TurnStream {
        final String ocSessionId;
        final String assistantMessageId = "da-msg-" + UUID.randomUUID();
        final StringBuilder fullText = new StringBuilder();
        final Map<String, JsonNode> toolArgs = new java.util.LinkedHashMap<>();
        final List<String> toolCallOrder = new java.util.ArrayList<>();
        final AtomicBoolean finalized = new AtomicBoolean(false);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile reactor.core.Disposable subscription;
        volatile long lastPersistAt = System.currentTimeMillis();
        final long startedAt = System.currentTimeMillis();

        TurnStream(String ocSessionId) {
            this.ocSessionId = ocSessionId;
        }
    }

    private void stream(TurnStream ts, Long userId, String text) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("thread_id", ts.ocSessionId);
            ArrayNode msgs = body.putArray("messages");
            ObjectNode m = msgs.addObject();
            m.put("role", "user");
            m.put("content", text);
            Flux<ServerSentEvent<String>> flux = webClient.post()
                    .uri("/api/users/{userId}/stream", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    // 事件处理含阻塞 JDBC,必须离开 Netty 事件循环线程
                    .publishOn(Schedulers.boundedElastic());
            ts.subscription = flux.subscribe(
                    sse -> handle(ts, sse),
                    err -> onStreamError(ts, err),
                    () -> onStreamComplete(ts));
        } catch (Exception e) {
            log.warn("deepagents stream subscribe failed (session={}): {}", ts.ocSessionId, e.toString());
            onStreamError(ts, e);
        }
    }

    private void handle(TurnStream ts, ServerSentEvent<String> sse) {
        if (ts.cancelled.get() || ts.finalized.get()) {
            return;
        }
        String type = sse.event();
        String data = sse.data();
        if (data == null || data.isBlank()) {
            return;
        }
        try {
            JsonNode payload = objectMapper.readTree(data);
            switch (type == null ? "" : type) {
                case "delta" -> {
                    String delta = payload.has("content") ? payload.get("content").asText() : "";
                    if (delta.isEmpty()) {
                        break;
                    }
                    ts.fullText.append(delta);
                    // 前端实时流式增量
                    ObjectNode d = objectMapper.createObjectNode();
                    d.put("messageID", ts.assistantMessageId);
                    d.put("field", "text");
                    d.put("delta", delta);
                    dispatch(ts.ocSessionId, "message.part.delta", d.toString());
                    // 节流落库
                    long now = System.currentTimeMillis();
                    if (now - ts.lastPersistAt >= TEXT_PERSIST_MIN_INTERVAL_MS) {
                        chatMessageService.upsertStreamingText(ts.ocSessionId, ts.assistantMessageId, ts.fullText.toString());
                        ts.lastPersistAt = now;
                    }
                }
                case "tool" -> {
                    String name = payload.path("name").asText("tool");
                    String callId = payload.path("id").asText("");
                    JsonNode args = payload.has("args") ? payload.get("args") : null;
                    if (callId.isBlank()) {
                        callId = "tool-" + ts.toolCallOrder.size();
                    }
                    ts.toolCallOrder.add(callId);
                    ts.toolArgs.put(callId, args);
                    emitToolPart(ts, callId, name, "running", args, null);
                }
                case "tool_result" -> {
                    String name = payload.path("name").asText("tool");
                    String content = payload.path("content").asText("");
                    String callId = ts.toolCallOrder.isEmpty() ? "" : ts.toolCallOrder.remove(ts.toolCallOrder.size() - 1);
                    JsonNode args = callId.isEmpty() ? null : ts.toolArgs.remove(callId);
                    JsonNode output = parseOutput(content);
                    emitToolPart(ts, callId, name, "completed", args, output);
                }
                case "error" -> {
                    dispatch(ts.ocSessionId, "session.error", payload.toString());
                    finalize(ts);
                }
                case "done" -> finalize(ts);
                default -> {
                    // start / 未知事件:忽略
                }
            }
        } catch (Exception e) {
            log.debug("deepagents handle event failed (type={}): {}", type, e.toString());
        }
    }

    private void onStreamError(TurnStream ts, Throwable err) {
        if (ts.cancelled.get() || ts.finalized.get()) {
            return;
        }
        log.warn("deepagents stream error (session={}): {}", ts.ocSessionId, err.toString());
        try {
            ObjectNode e = objectMapper.createObjectNode();
            e.put("message", err.toString());
            dispatch(ts.ocSessionId, "session.error", e.toString());
        } catch (Exception ignore) {
            // 事件已断,dispatcher 可能已无订阅者
        }
        finalize(ts);
    }

    private void onStreamComplete(TurnStream ts) {
        // abort() 已终止该流:direct dispose 会触发 onComplete,不得再定稿覆盖"已中断"状态
        if (ts.cancelled.get() || ts.finalized.get()) {
            return;
        }
        streams.remove(ts.ocSessionId, ts);
        finalize(ts);
    }

    /** 定稿:flush 文本 + message.updated(completed)+ 文本 part 定稿 + 结束信号。 */
    private void finalize(TurnStream ts) {
        if (!ts.finalized.compareAndSet(false, true)) {
            return;
        }
        streams.remove(ts.ocSessionId);
        String ocSessionId = ts.ocSessionId;
        String messageId = ts.assistantMessageId;
        String text = ts.fullText.toString();
        long end = System.currentTimeMillis();
        try {
            chatMessageService.upsertStreamingText(ocSessionId, messageId, text);
        } catch (Exception e) {
            log.warn("deepagents flush text failed: {}", e.toString());
        }
        // 文本 part 定稿(前端 finalizeTextPart:完整文本 + time.end → completed)
        ObjectNode partNode = objectMapper.createObjectNode();
        partNode.put("type", "text");
        partNode.put("messageID", messageId);
        partNode.put("text", text);
        partNode.putObject("time").put("start", ts.startedAt).put("end", end);
        try {
            chatMessageService.upsertPartFromEvent(ocSessionId, wrapPart(partNode));
        } catch (Exception e) {
            log.warn("deepagents upsert text part failed: {}", e.toString());
        }
        dispatch(ocSessionId, "message.part.updated", wrapPart(partNode).toString());
        // message.updated:resolveStatus 依据 time.completed → completed
        ObjectNode info = objectMapper.createObjectNode();
        info.put("id", messageId);
        info.put("role", "assistant");
        info.putObject("time").put("start", ts.startedAt).put("completed", end);
        ObjectNode updated = objectMapper.createObjectNode();
        updated.set("info", info);
        try {
            chatMessageService.upsertFromEvent(ocSessionId, updated);
        } catch (Exception e) {
            log.warn("deepagents upsert message.updated failed: {}", e.toString());
        }
        dispatch(ocSessionId, "message.updated", updated.toString());
        dispatch(ocSessionId, "session.status", "{\"status\":{\"type\":\"idle\"}}");
        dispatch(ocSessionId, "session.idle", "{\"sessionID\":\"" + ocSessionId + "\"}");
    }

    /** 工具 part(tool / tool_result)→ 落库 + 推前端,形状与 opencode tool part 对齐。 */
    private void emitToolPart(TurnStream ts, String callId, String name, String status, JsonNode input, JsonNode output) {
        ObjectNode part = objectMapper.createObjectNode();
        part.put("type", "tool");
        part.put("messageID", ts.assistantMessageId);
        part.put("id", callId);
        part.put("tool", name);
        ObjectNode state = part.putObject("state");
        state.put("status", status);
        if (input != null) {
            state.set("input", input);
        }
        if (output != null) {
            state.set("output", output);
        }
        part.putObject("time").put("start", System.currentTimeMillis());
        ObjectNode wrap = wrapPart(part);
        try {
            chatMessageService.upsertPartFromEvent(ts.ocSessionId, wrap);
        } catch (Exception e) {
            log.warn("deepagents upsert tool part failed ({}): {}", name, e.toString());
        }
        dispatch(ts.ocSessionId, "message.part.updated", wrap.toString());
    }

    /** tool_result content 近似 JSON(以 { 或 [ 开头且可解析)→ 对象/数组,否则按文本。 */
    private JsonNode parseOutput(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return objectMapper.readTree(trimmed);
            } catch (Exception ignore) {
                // 非 JSON 文本,走字符串
            }
        }
        return objectMapper.getNodeFactory().textNode(content);
    }

    private ObjectNode wrapPart(ObjectNode part) {
        ObjectNode wrap = objectMapper.createObjectNode();
        wrap.set("part", part);
        return wrap;
    }

    private void dispatch(String ocSessionId, String type, String dataJson) {
        try {
            router.dispatch(ocSessionId, type, dataJson);
        } catch (Exception e) {
            log.debug("deepagents dispatch {} failed: {}", type, e.toString());
        }
    }
}
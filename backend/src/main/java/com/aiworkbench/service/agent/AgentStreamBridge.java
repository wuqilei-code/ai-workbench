package com.aiworkbench.service.agent;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * deepagents 执行轨道的唯一桥:用户消息 → agent-service SSE,事件<b>原样直通</b>给前端。
 * <p>
 * 事件契约(与 agent-service 对齐,网关只补消息帧):
 * <ul>
 *   <li>{@code start}   {threadId} —— 一轮开始(网关随后补 {@code message.start}{id})</li>
 *   <li>{@code delta}   {id, content} —— 文本增量</li>
 *   <li>{@code tool}    {id, name, args} —— 工具调用开始(running)</li>
 *   <li>{@code tool_result} {id, name, output} —— 工具完成</li>
 *   <li>{@code error}   {message}</li>
 *   <li>{@code done}    {} —— 网关补 {@code message.end}{id, status}</li>
 * </ul>
 * 持久化:每轮一条 assistant 消息,content=累计文本,parts={toolCalls:[{id,name,args,output,status}]}。
 */
@Slf4j
@Component
public class AgentStreamBridge {

    /** 流式期间文本节流落库最小间隔(ms),避免反复重写 MEDIUMTEXT */
    private static final long TEXT_PERSIST_MIN_INTERVAL_MS = 800L;

    private final ChatMessageService chatMessageService;
    private final SessionEventHub hub;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final ExecutorService executor;

    /** 在途生成状态,key = 会话外部 id("da-" 前缀)。 */
    private final Map<String, TurnStream> streams = new ConcurrentHashMap<>();

    public AgentStreamBridge(
            @Value("${deepagents.base-url:http://localhost:8000}") String baseUrl,
            @org.springframework.context.annotation.Lazy ChatMessageService chatMessageService,
            SessionEventHub hub,
            ObjectMapper objectMapper) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.chatMessageService = chatMessageService;
        this.hub = hub;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().baseUrl(base).build();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-stream");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 发送用户消息(异步 fire-and-forget)。调用方已负责先落库 user 消息。
     */
    public void sendMessage(ChatSession session, Long userId, String text) {
        String sessionId = session.getAgentSessionId();
        if (!ownsSession(sessionId)) {
            log.warn("sendMessage on non-agent session {}", sessionId);
            return;
        }
        TurnStream ts = new TurnStream(sessionId);
        ts.userId = userId;
        ts.skills = parseSkillIds(session.getSkills());
        streams.put(sessionId, ts);
        hub.dispatchJson(sessionId, "start", "{\"threadId\":\"" + sessionId + "\"}");
        executor.execute(() -> stream(ts, userId, text));
    }

    /** 是否本轨道的会话 id(合成 "da-" 前缀)。 */
    public boolean ownsSession(String sessionId) {
        return sessionId != null && sessionId.startsWith("da-");
    }

    /** 解析会话勾选的自定义技能名(JSON 数组文本)。 */
    private java.util.List<String> parseSkillIds(String skillsJson) {
        if (skillsJson == null || skillsJson.isBlank() || "[]".equals(skillsJson.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(skillsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 终止在途生成:撤销本地订阅与后续事件处理。若当前暂停在人工确认上,
     * 一并清除已持久化的待确认标记(requestId),避免刷新后残留过期卡片。
     *
     * @return true=确有在途生成被终止
     */
    public boolean abort(String sessionId) {
        TurnStream ts = streams.remove(sessionId);
        if (ts == null) {
            return false;
        }
        ts.cancelled.set(true);
        if (ts.pausedOnPermission && ts.pendingPermissionRequestId != null) {
            try {
                chatMessageService.clearPendingPermission(sessionId, ts.pendingPermissionRequestId);
            } catch (Exception ignored) {}
        }
        if (ts.subscription != null) {
            ts.subscription.dispose();
        }
        log.info("abort: cancelled in-flight stream for session {}", sessionId);
        return true;
    }

    /**
     * 人工确认(HITL)恢复:把用户决策发给 agent-service 继续被中断的工具调用。
     *
     * @param sessionId 会话外部 id
     * @param requestId 待确认请求 id(permission.asked 下发)
     * @param decision  approve | reject | reply
     * @param answer    用户回答内容(reply 时必填)
     * @return 是否是有效的暂停态并被恢复
     */
    public boolean resumePermission(String sessionId, String requestId, String decision, String answer) {
        TurnStream ts = streams.get(sessionId);
        if (ts == null || !ts.pausedOnPermission) {
            return false;
        }
        if (!requestId.equals(ts.pendingPermissionRequestId)) {
            log.warn("resumePermission: requestId mismatch session={} expect={} got={}",
                    sessionId, ts.pendingPermissionRequestId, requestId);
            return false;
        }
        ts.pausedOnPermission = false;
        ts.pendingPermissionRequestId = null;
        try {
            chatMessageService.clearPendingPermission(sessionId, requestId);
        } catch (Exception ignored) {}
        ObjectNode d = objectMapper.createObjectNode();
        if ("approve".equals(decision)) {
            d.put("type", "approve");
        } else if ("reply".equals(decision)) {
            d.put("type", "approve");
            d.put("output", answer != null ? answer : "");
        } else {
            d.put("type", "reject");
            d.put("message", "用户拒绝了该操作");
        }
        ObjectNode resumeDecision = objectMapper.createObjectNode();
        resumeDecision.putArray("decisions").add(d);
        resumeStream(ts, ts.userId, resumeDecision);
        log.info("resumePermission: {} session={} requestId={}", decision, sessionId, requestId);
        return true;
    }

    /** 单轮生成状态。 */
    private static final class TurnStream {
        final String sessionId;
        final String assistantMessageId = "da-msg-" + UUID.randomUUID();
        final StringBuilder fullText = new StringBuilder();
        /** callId → 工具调用节点(含 status),保持插入序 */
        final Map<String, ObjectNode> toolCalls = new LinkedHashMap<>();
        /**
         * 时间线:文本段({k:"text",v})与工具调用({k:"tool",id})按发生顺序交错,
         * 落库后前端可按原顺序重建"解说 + 工具"步骤(思维链/工具结果刷新后不丢)。
         */
        final java.util.List<ObjectNode> timeline = new java.util.ArrayList<>();
        final AtomicBoolean finalized = new AtomicBoolean(false);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile reactor.core.Disposable subscription;
        volatile long lastPersistAt = System.currentTimeMillis();
        final long startedAt = System.currentTimeMillis();
        volatile Long userId;
        volatile java.util.List<String> skills;
        /** HITL 暂停:true=正等待用户确认(消息保持 streaming,不落定稿) */
        volatile boolean pausedOnPermission;
        volatile String pendingPermissionRequestId;

        TurnStream(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    private void stream(TurnStream ts, Long userId, String text) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("thread_id", ts.sessionId);
            ArrayNode msgs = body.putArray("messages");
            ObjectNode m = msgs.addObject();
            m.put("role", "user");
            m.put("content", text);
            if (ts.skills != null && !ts.skills.isEmpty()) {
                ArrayNode skills = body.putArray("skills");
                for (String s : ts.skills) skills.add(s);
            }
            subscribeStream(ts, userId, body);
        } catch (Exception e) {
            log.warn("stream subscribe failed (session={}): {}", ts.sessionId, e.toString());
            onStreamError(ts, e);
        }
    }

    /** HITL 恢复:以 Command(resume=决策) 重新起流,续跑被中断的线程。 */
    private void resumeStream(TurnStream ts, Long userId, JsonNode resumeDecision) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("thread_id", ts.sessionId);
            body.putArray("messages");
            body.set("resume_decision", resumeDecision);
            subscribeStream(ts, userId, body);
        } catch (Exception e) {
            log.warn("resume stream subscribe failed (session={}): {}", ts.sessionId, e.toString());
            onStreamError(ts, e);
        }
    }

    private void subscribeStream(TurnStream ts, Long userId, ObjectNode body) {
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
                case "start" -> {
                    // 消息帧:告知前端本轮 assistant 消息 id
                    ObjectNode ms = objectMapper.createObjectNode();
                    ms.put("id", ts.assistantMessageId);
                    hub.dispatch(ts.sessionId, "message.start", ms);
                    persistTurn(ts, "streaming");
                }
                case "delta" -> {
                    String delta = payload.has("content") ? payload.get("content").asText() : "";
                    if (delta.isEmpty()) {
                        break;
                    }
                    ts.fullText.append(delta);
                    appendTimelineText(ts, delta, "text");
                    ObjectNode d = objectMapper.createObjectNode();
                    d.put("id", ts.assistantMessageId);
                    d.put("content", delta);
                    hub.dispatch(ts.sessionId, "delta", d);
                    long now = System.currentTimeMillis();
                    if (now - ts.lastPersistAt >= TEXT_PERSIST_MIN_INTERVAL_MS) {
                        persistTurn(ts, "streaming");
                        ts.lastPersistAt = now;
                    }
                }
                case "reason" -> {
                    // 模型推理段(reasoning_content):实时转发 + 落时间线,刷新后可回看
                    String rc = payload.has("content") ? payload.get("content").asText() : "";
                    if (!rc.isEmpty()) {
                        appendTimelineText(ts, rc, "reason");
                        hub.dispatch(ts.sessionId, "reason", payload);
                        long now2 = System.currentTimeMillis();
                        if (now2 - ts.lastPersistAt >= TEXT_PERSIST_MIN_INTERVAL_MS) {
                            persistTurn(ts, "streaming");
                            ts.lastPersistAt = now2;
                        }
                    }
                }
                case "widget_delta" -> {
                    // dynamic-ui 组件参数流:纯实时转发供前端逐块渲染;
                    // 不落时间线/不持久化(组件定稿由 tool_result 的 outputs 路径承载)
                    hub.dispatch(ts.sessionId, "widget_delta", payload);
                }
                case "tool" -> {
                    String name = payload.path("name").asText("tool");
                    String callId = payload.path("id").asText("");
                    JsonNode args = payload.has("args") ? payload.get("args") : null;
                    if (callId.isBlank()) {
                        callId = "call-" + ts.toolCalls.size();
                    }
                    ObjectNode tc = objectMapper.createObjectNode();
                    tc.put("id", callId);
                    tc.put("name", name);
                    if (args != null) tc.set("args", args);
                    tc.put("status", "running");
                    ts.toolCalls.put(callId, tc);
                    ObjectNode te = objectMapper.createObjectNode();
                    te.put("k", "tool");
                    te.put("id", callId);
                    ts.timeline.add(te);
                    hub.dispatch(ts.sessionId, "tool", payload);
                    persistTurn(ts, "streaming");
                }
                case "tool_result" -> {
                    String name = payload.path("name").asText("tool");
                    String callId = payload.path("id").asText("");
                    // 工具输出文本:优先 output 字段,退回 content(agent-service 用 content 承载 ToolMessage 文本)
                    JsonNode outputNode = payload.has("output") && !payload.get("output").isNull()
                            ? payload.get("output")
                            : objectMapper.getNodeFactory().textNode(payload.path("content").asText(""));
                    // 兼容未回传 id 的旧版 agent-service:配对最后一个 running 调用
                    if (callId.isBlank()) {
                        callId = lastRunningCallId(ts);
                    }
                    ObjectNode tc = ts.toolCalls.get(callId);
                    if (tc != null) {
                        tc.put("status", "completed");
                        // 结果文本必须落库,否则刷新后工具调用只剩名字没有输出
                        tc.set("output", outputNode);
                        // deepagents 结构化输出物(文件路径列表),落库供前端结果文件块精确渲染
                        if (payload.has("outputs") && payload.get("outputs").isArray()) {
                            tc.set("outputs", payload.get("outputs"));
                        }
                    }
                    ObjectNode out = objectMapper.createObjectNode();
                    out.put("id", callId);
                    out.put("name", name);
                    out.set("output", outputNode);
                    if (payload.has("outputs")) {
                        out.set("outputs", payload.get("outputs"));
                    }
                    hub.dispatch(ts.sessionId, "tool_result", out);
                    persistTurn(ts, "streaming");
                }
                case "error" -> {
                    hub.dispatch(ts.sessionId, "error", payload);
                    finalize(ts, "error");
                }
                case "permission" -> {
                    // HITL 人工确认:agent-service 已中断该轮并终止本流,等待前端决策。
                    // 本地保持 streaming(不落定稿),持续化待确认标记供刷新后恢复。
                    JsonNode actions = payload.path("action_requests");
                    JsonNode first = actions.isArray() && actions.size() > 0 ? actions.get(0) : payload;
                    String toolName = first.path("name").asText("tool");
                    JsonNode args = first.has("args") && !first.get("args").isNull() ? first.get("args") : null;
                    String desc = first.path("description").asText("");
                    String requestId = "perm-" + UUID.randomUUID();
                    ts.pausedOnPermission = true;
                    ts.pendingPermissionRequestId = requestId;
                    try {
                        chatMessageService.upsertPendingPermission(
                                ts.sessionId, ts.assistantMessageId, requestId,
                                toolName,
                                args != null ? args.toString() : "{}",
                                desc,
                                java.util.List.of("approve", "reject"));
                    } catch (Exception e) {
                        log.warn("persist pending permission failed (session={}): {}", ts.sessionId, e.toString());
                    }
                    ObjectNode perm = objectMapper.createObjectNode();
                    perm.put("id", requestId);
                    perm.put("name", toolName);
                    if (args != null) perm.set("args", args);
                    perm.put("description", desc);
                    ArrayNode allowed = perm.putArray("allowed");
                    allowed.add("approve");
                    allowed.add("reject");
                    hub.dispatch(ts.sessionId, "permission.asked", perm);
                    log.info("permission.asked session={} requestId={} tool={}",
                            ts.sessionId, requestId, toolName);
                }
                case "question" -> {
                    // agent 向用户提问:与 permission 类似,但 allowed 只有 reply
                    String question = payload.path("question").asText("请提供更多信息");
                    String requestId = "perm-" + UUID.randomUUID();
                    ts.pausedOnPermission = true;
                    ts.pendingPermissionRequestId = requestId;
                    // 提取 options 和 multiple 字段
                    JsonNode optionsNode = payload.path("options");
                    boolean multiple = payload.path("multiple").asBoolean(false);
                    try {
                        ObjectNode argsNode = objectMapper.createObjectNode();
                        argsNode.put("question", question);
                        argsNode.set("options", optionsNode);
                        argsNode.put("multiple", multiple);
                        chatMessageService.upsertPendingPermission(
                                ts.sessionId, ts.assistantMessageId, requestId,
                                "ask_user",
                                argsNode.toString(),
                                question,
                                java.util.List.of("reply"));
                    } catch (Exception e) {
                        log.warn("persist pending question failed (session={}): {}", ts.sessionId, e.toString());
                    }
                    ObjectNode perm = objectMapper.createObjectNode();
                    perm.put("id", requestId);
                    perm.put("name", "ask_user");
                    ObjectNode qArgs = objectMapper.createObjectNode();
                    qArgs.put("question", question);
                    qArgs.set("options", optionsNode);
                    qArgs.put("multiple", multiple);
                    perm.set("args", qArgs);
                    perm.put("description", question);
                    ArrayNode allowed = perm.putArray("allowed");
                    allowed.add("reply");
                    hub.dispatch(ts.sessionId, "permission.asked", perm);
                    log.info("question session={} requestId={} options={}", ts.sessionId, requestId, optionsNode);
                }
                case "done" -> {
                    hub.dispatch(ts.sessionId, "done", payload);
                    finalize(ts, "completed");
                }
                default -> {
                    // 未知事件忽略
                }
            }
        } catch (Exception e) {
            log.debug("handle event failed (type={}): {}", type, e.toString());
        }
    }

    private String lastRunningCallId(TurnStream ts) {
        for (Map.Entry<String, ObjectNode> e : ts.toolCalls.entrySet()) {
            if ("running".equals(e.getValue().path("status").asText())) {
                return e.getKey();
            }
        }
        return ts.toolCalls.isEmpty() ? "" : ts.toolCalls.keySet().iterator().next();
    }

    /** 追加流式文本到时间线:延续最后一段同类(k)文本,否则新开一段。kind: text|reason */
    private void appendTimelineText(TurnStream ts, String delta, String kind) {
        if (!ts.timeline.isEmpty()) {
            ObjectNode last = ts.timeline.get(ts.timeline.size() - 1);
            if (kind.equals(last.path("k").asText())) {
                last.put("v", last.path("v").asText("") + delta);
                return;
            }
        }
        ObjectNode te = objectMapper.createObjectNode();
        te.put("k", kind);
        te.put("v", delta);
        ts.timeline.add(te);
    }

    private void onStreamError(TurnStream ts, Throwable err) {
        if (ts.cancelled.get() || ts.finalized.get()) {
            return;
        }
        log.warn("stream error (session={}): {}", ts.sessionId, err.toString());
        ObjectNode e = objectMapper.createObjectNode();
        e.put("message", err.toString());
        hub.dispatch(ts.sessionId, "error", e);
        finalize(ts, "error");
    }

    private void onStreamComplete(TurnStream ts) {
        // abort() 已终止该流:dispose 触发的 onComplete 不得覆盖"已中断"状态
        if (ts.cancelled.get() || ts.finalized.get()) {
            return;
        }
        // HITL 暂停:agent-service 在 permission 后正常结束本流(线程已 checkpoint)。
        // 不落定稿、不移出 streams,等待 resumePermission 重新起流续跑。
        if (ts.pausedOnPermission) {
            log.info("stream paused on permission (session={}), awaiting user decision", ts.sessionId);
            return;
        }
        streams.remove(ts.sessionId, ts);
        finalize(ts, "completed");
    }

    /** 定稿:落库最终态 + message.end 帧。 */
    private void finalize(TurnStream ts, String status) {
        if (!ts.finalized.compareAndSet(false, true)) {
            return;
        }
        streams.remove(ts.sessionId);
        persistTurn(ts, status);
        ObjectNode end = objectMapper.createObjectNode();
        end.put("id", ts.assistantMessageId);
        end.put("status", status);
        hub.dispatch(ts.sessionId, "message.end", end);
    }

    /** 把当前轮(文本 + 时间线 + 工具调用)upsert 到 DB。 */
    private void persistTurn(TurnStream ts, String status) {
        try {
            chatMessageService.upsertAssistantTurn(
                    ts.sessionId, ts.assistantMessageId,
                    ts.fullText.toString(), ts.toolCalls, ts.timeline, status);
        } catch (Exception e) {
            log.warn("persist turn failed (session={}): {}", ts.sessionId, e.toString());
        }
    }
}

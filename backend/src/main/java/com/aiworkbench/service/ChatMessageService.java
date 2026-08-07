package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.chat.MessageVo;
import com.aiworkbench.dto.chat.SendMessageRequest;
import com.aiworkbench.entity.ChatMessage;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.mapper.ChatMessageMapper;
import com.aiworkbench.service.opencode.OpencodeClient;
import com.aiworkbench.service.opencode.dto.CreateSessionBody;
import com.aiworkbench.service.opencode.dto.OpencodeSessionDto;
import com.aiworkbench.service.opencode.dto.PromptAsyncBody;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;

/**
 * 聊天消息服务。
 * <p>
 * 持久化策略(单一数据源):发消息时只调 prompt_async,user/assistant 消息均由
 * SSE message.updated 事件 upsert 到 MySQL(经 {@link #upsertFromEvent}),
 * 避免重复与状态不一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionService chatSessionService;
    private final OpencodeClient opencodeClient;
    private final ObjectMapper objectMapper;

    /** 历史从 MySQL 加载(由事件镜像填充) */
    public List<MessageVo> listBySession(Long sessionId, Long userId, int limit) {
        chatSessionService.getOwned(sessionId, userId); // 越权校验
        List<ChatMessage> list = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getId)
                .last("limit " + Math.max(1, Math.min(limit, 500))));
        return list.stream().map(m -> {
            sanitizeStalePending(m);
            return toVo(m);
        }).toList();
    }

    /** 全部消息(导出用,不受 listBySession 500 条上限约束) */
    public List<ChatMessage> allBySession(Long sessionId, Long userId) {
        chatSessionService.getOwned(sessionId, userId); // 越权校验
        return chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getId));
    }

    /** 清空会话消息(仅删本地 MySQL 镜像,不删会话本身) */
    public int clearBySession(Long sessionId, Long userId) {
        chatSessionService.getOwned(sessionId, userId); // 越权校验
        return chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId));
    }

    /** 导出会话为 Markdown(可读的对话记录) */
    public String exportMarkdown(Long sessionId, Long userId, String title) {
        List<ChatMessage> msgs = allBySession(sessionId, userId);
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title == null ? "会话导出" : title).append("\n\n");
        sb.append("- 导出时间: ").append(java.time.LocalDateTime.now()).append("\n");
        sb.append("- 消息数: ").append(msgs.size()).append("\n\n");
        sb.append("---\n\n");
        for (ChatMessage m : msgs) {
            String roleLabel = switch (m.getRole() == null ? "" : m.getRole()) {
                case "user" -> "用户";
                case "assistant" -> "AI";
                case "tool" -> "工具";
                case "system" -> "系统";
                case "summary" -> "AI 变更总结";
                default -> m.getRole();
            };
            sb.append("## ").append(roleLabel);
            if (m.getCreatedAt() != null) {
                sb.append(" · ").append(m.getCreatedAt());
            }
            sb.append("\n\n");
            String content = m.getContent() == null ? "" : m.getContent();
            if (content.isBlank() && m.getParts() != null) {
                sb.append("```json\n").append(m.getParts()).append("\n```\n\n");
            } else {
                sb.append(content).append("\n\n");
            }
            sb.append("---\n\n");
        }
        return sb.toString();
    }

    /** 导出会话为 JSON(机器可读,含 content + parts 完整数据) */
    public String exportJson(Long sessionId, Long userId, String title) {
        List<ChatMessage> msgs = allBySession(sessionId, userId);
        com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
        for (ChatMessage m : msgs) {
            ObjectNode o = arr.addObject();
            o.put("id", m.getId());
            o.put("role", m.getRole());
            o.put("content", m.getContent() == null ? "" : m.getContent());
            o.put("status", m.getStatus());
            if (m.getCreatedAt() != null) o.put("createdAt", m.getCreatedAt().toString());
            if (m.getParts() != null && !m.getParts().isBlank()) {
                try {
                    o.set("parts", objectMapper.readTree(m.getParts()));
                } catch (Exception e) {
                    o.put("parts", m.getParts());
                }
            }
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("sessionTitle", title == null ? "会话导出" : title);
        root.put("exportedAt", java.time.LocalDateTime.now().toString());
        root.set("messages", arr);
        return root.toPrettyString();
    }

    /** 兜底:若 pendingPermission 残留但其关联 toolCall 已 completed(权限已授予),
     *  说明是清除竞态导致的残留,从 parts 移除并更新 DB,避免重新加载会话时重复弹框。 */
    private void sanitizeStalePending(ChatMessage m) {
        if (m.getParts() == null || m.getParts().isBlank()) return;
        try {
            ObjectNode partsNode = (ObjectNode) objectMapper.readTree(m.getParts());
            boolean changed = false;
            if (partsNode.has("pendingPermission") && hasCompletedToolCall(partsNode)) {
                partsNode.remove("pendingPermission");
                changed = true;
            }
            if (changed) {
                String newParts = objectMapper.writeValueAsString(partsNode);
                m.setParts(newParts);
                chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                        .eq(ChatMessage::getId, m.getId())
                        .set(ChatMessage::getParts, newParts));
                log.info("sanitizeStalePending: removed stale pendingPermission, msgId={}", m.getId());
            }
        } catch (Exception e) {
            log.warn("sanitizeStalePending failed (msgId={}): {}", m.getId(), e.toString());
        }
    }

    private boolean hasCompletedToolCall(ObjectNode partsNode) {
        JsonNode toolCalls = partsNode.get("toolCalls");
        if (toolCalls == null || !toolCalls.isArray()) return false;
        for (JsonNode tc : toolCalls) {
            if ("completed".equals(tc.has("status") ? tc.get("status").asText() : "")) return true;
        }
        return false;
    }

    /**
     * 发送用户消息:调 opencode prompt_async,流式回复走 SSE。
     * <p>
     * lazy re-bind:切换到 per-user 进程后,旧 opencode 会话 id 在新进程中不存在(prompt_async 返回 404)。
     * 捕获 404 后建新 opencode 会话、写回 chat_session.opencode_session_id、用新 id 重发,对用户无感。
     */
    public void sendUserMessage(Long sessionId, Long userId, SendMessageRequest req) {
        ChatSession s = chatSessionService.getOwned(sessionId, userId);

        // 模型优先取请求,其次会话默认
        String providerId = req.getProviderId() != null ? req.getProviderId() : s.getProviderId();
        String modelId = req.getModelId() != null ? req.getModelId() : s.getModelId();

        PromptAsyncBody body = PromptAsyncBody.builder()
                .parts(List.of(PromptAsyncBody.Part.builder().type("text").text(req.getText()).build()))
                .agent(s.getAgent())
                .model((providerId != null && modelId != null)
                        ? PromptAsyncBody.ModelRef.builder().providerID(providerId).modelID(modelId).build()
                        : null)
                .build();

        try {
            opencodeClient.promptAsync(userId, s.getOpencodeSessionId(), body);
        } catch (WebClientResponseException.NotFound e) {
            log.info("opencode session {} not found (404), re-binding for session {}",
                    s.getOpencodeSessionId(), sessionId);
            CreateSessionBody reb = CreateSessionBody.builder()
                    .title(s.getTitle())
                    .agent(s.getAgent())
                    .model((providerId != null && modelId != null)
                            ? CreateSessionBody.ModelRef.builder().id(modelId).providerID(providerId).build()
                            : null)
                    .build();
            OpencodeSessionDto oc = opencodeClient.createSession(userId, reb);
            if (oc == null || oc.getId() == null) {
                throw new BusinessException(ResultCode.OPENCODE_UNAVAILABLE);
            }
            chatSessionService.rebindOpencodeSession(sessionId, oc.getId());
            opencodeClient.promptAsync(userId, oc.getId(), body);
        }
    }

    /** 终止当前生成 */
    public boolean abort(Long sessionId, Long userId) {
        ChatSession s = chatSessionService.getOwned(sessionId, userId);
        return opencodeClient.abort(userId, s.getOpencodeSessionId());
    }

    /**
     * 由 SSE 消费者在 message.updated 时调用,upsert 消息到 MySQL。
     *
     * @param opencodeSessionId 事件中的 opencode 会话 id
     * @param properties        事件 properties({info:{id,role,time,...}, parts:[...]})
     */
    public void upsertFromEvent(String opencodeSessionId, JsonNode properties) {
        if (properties == null) {
            return;
        }
        // /global/event 的 message.updated:properties = {info:{...}, parts:[...]}
        JsonNode info = properties.has("info") ? properties.get("info") : properties;
        if (info == null || !info.has("id")) {
            return;
        }
        ChatSession session = chatSessionService.findByOpencodeSessionId(opencodeSessionId);
        if (session == null) {
            return; // 非本实例跟踪的会话(越权/其它部署),忽略
        }
        String opencodeMessageId = info.get("id").asText();
        String role = info.has("role") ? info.get("role").asText() : "assistant";
        JsonNode parts = properties.has("parts") ? properties.get("parts")
                : (info.has("parts") ? info.get("parts") : null);
        String text = extractText(parts);
        String status = resolveStatus(info, role);

        ChatMessage existing = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, session.getId())
                .eq(ChatMessage::getOpencodeMessageId, opencodeMessageId)
                .last("limit 1"));

        if (existing != null) {
            existing.setRole(role);
            // message.updated 不含 parts/文本,不要覆盖 message.part.updated 已写入的 content 与 parts
            if (text != null && !text.isEmpty()) {
                existing.setContent(text);
            }
            existing.setStatus(status);
            chatMessageMapper.updateById(existing);
        } else {
            ChatMessage m = new ChatMessage();
            m.setSessionId(session.getId());
            m.setUserId(session.getUserId());
            m.setOpencodeMessageId(opencodeMessageId);
            m.setRole(role);
            m.setContent(text);
            m.setStatus(status);
            chatMessageMapper.insert(m);
        }
    }

    /**
     * 由 SSE 消费者在 message.part.updated 时调用,把 part 内容写入对应消息。
     * <p>
     * opencode 的事件流中,消息元信息(message.updated)和内容(message.part.updated)
     * 是分离的。message.updated 只带 {id, role, time, ...},不带 parts;
     * 文本通过 message.part.updated 的 properties.part.text 送达;
     * 工具调用(tool)与步骤结束(step-finish)也通过本事件送达,写入 parts JSON 列。
     *
     * @param opencodeSessionId 事件中的 opencode 会话 id
     * @param properties        事件 properties({sessionID, part:{type, messageID, ...}})
     */
    public void upsertPartFromEvent(String opencodeSessionId, JsonNode properties) {
        JsonNode part = properties.has("part") ? properties.get("part") : null;
        if (part == null || !part.has("messageID")) {
            return;
        }
        String partType = part.has("type") ? part.get("type").asText() : "";
        // step-start 等无业务语义的 part 直接忽略
        if ("step-start".equals(partType)) {
            return;
        }
        ChatSession session = chatSessionService.findByOpencodeSessionId(opencodeSessionId);
        if (session == null) {
            return;
        }
        String messageID = part.get("messageID").asText();
        ChatMessage existing = findOrCreateMessage(session, messageID);

        if ("text".equals(partType)) {
            // text part:写 content(用户消息 & 助手消息文本都走这里)
            existing.setContent(part.has("text") ? part.get("text").asText() : "");
        } else {
            // tool / step-finish 等结构化 part:合并到 parts JSON 列
            ObjectNode partsNode = parsePartsObject(existing.getParts());
            mergeParts(partsNode, part);
            try {
                existing.setParts(objectMapper.writeValueAsString(partsNode));
            } catch (Exception e) {
                log.warn("serialize parts failed (messageID={}): {}", messageID, e.toString());
            }
        }
        persistMessage(existing);
    }

    /**
     * 由 SSE 消费者对<b>节流后</b>的流式文本落库(消费者层已把中间态合并进内存)。
     * <p>
     * 与 {@link #upsertPartFromEvent} 的 text 分支等价,但只更新 content,
     * 供 {@link com.aiworkbench.service.opencode.OpencodeInstanceEventConsumer} 以
     * 一定时间间隔调用,避免流式期间把整段 MEDIUMTEXT 反复重写。
     *
     * @param opencodeSessionId 事件中的 opencode 会话 id
     * @param messageID         目标消息的 opencode 消息 id
     * @param text              最新文本内容
     */
    public void upsertStreamingText(String opencodeSessionId, String messageID, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ChatSession session = chatSessionService.findByOpencodeSessionId(opencodeSessionId);
        if (session == null) {
            return;
        }
        ChatMessage existing = findOrCreateMessage(session, messageID);
        existing.setContent(text);
        persistMessage(existing);
    }

    /** 按 (sessionId, opencodeMessageId) 查找消息;不存在则建 assistant 壳(message.updated 前的兜底)。 */
    private ChatMessage findOrCreateMessage(ChatSession session, String messageID) {
        ChatMessage existing = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, session.getId())
                .eq(ChatMessage::getOpencodeMessageId, messageID)
                .last("limit 1"));
        if (existing != null) {
            return existing;
        }
        ChatMessage m = new ChatMessage();
        m.setSessionId(session.getId());
        m.setUserId(session.getUserId());
        m.setOpencodeMessageId(messageID);
        m.setRole("assistant");
        m.setContent("");
        m.setStatus("streaming");
        return m;
    }

    /** 新消息 insert,已有消息 updateById。 */
    private void persistMessage(ChatMessage m) {
        if (m.getId() != null) {
            chatMessageMapper.updateById(m);
        } else {
            chatMessageMapper.insert(m);
        }
    }

    /**
     * 解析 parts JSON 字符串为可变 ObjectNode;null/异常 → 新建空容器
     * {@code {"toolCalls":[],"stepFinish":null}}。
     */
    private ObjectNode parsePartsObject(String partsJson) {
        if (partsJson != null && !partsJson.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(partsJson);
                if (node != null && node.isObject()) {
                    ObjectNode obj = (ObjectNode) node;
                    if (!obj.has("toolCalls")) {
                        obj.putArray("toolCalls");
                    }
                    if (!obj.has("stepFinish")) {
                        obj.putNull("stepFinish");
                    }
                    return obj;
                }
            } catch (Exception e) {
                log.warn("parse parts json failed, reset to empty container: {}", e.toString());
            }
        }
        ObjectNode obj = objectMapper.createObjectNode();
        obj.putArray("toolCalls");
        obj.putNull("stepFinish");
        return obj;
    }

    /**
     * 把单个 part 合并到 partsNode:
     * <ul>
     *   <li>tool → 构造 {partId,tool,status,input,output,time},按 partId 在 toolCalls 去重替换</li>
     *   <li>step-finish → partsNode.stepFinish = {reason}</li>
     *   <li>其他 → 忽略(文本已在 setContent 处理)</li>
     * </ul>
     * tool part 的 pending/running/completed 共用同一 part.id,故按 partId 去重。
     */
    private void mergeParts(ObjectNode partsNode, JsonNode part) {
        String partType = part.has("type") ? part.get("type").asText() : "";
        switch (partType) {
            case "tool" -> {
                ObjectNode tc = objectMapper.createObjectNode();
                if (part.has("id")) {
                    tc.put("partId", part.get("id").asText());
                }
                if (part.has("tool")) {
                    tc.put("tool", part.get("tool").asText());
                }
                JsonNode state = part.has("state") ? part.get("state") : null;
                if (state != null) {
                    if (state.has("status")) {
                        tc.put("status", state.get("status").asText());
                    }
                    if (state.has("input")) {
                        tc.set("input", state.get("input"));
                    }
                    if (state.has("output")) {
                        tc.set("output", state.get("output"));
                    }
                }
                if (part.has("time")) {
                    tc.set("time", part.get("time"));
                }
                // 按 partId 在 toolCalls 数组去重替换(同一 tool 的 pending→running→completed 共用 partId)
                ArrayNode toolCalls = partsNode.withArray("toolCalls");
                String partId = tc.has("partId") ? tc.get("partId").asText() : null;
                int idx = -1;
                if (partId != null) {
                    for (int i = 0; i < toolCalls.size(); i++) {
                        JsonNode existingTc = toolCalls.get(i);
                        if (existingTc.has("partId")
                                && partId.equals(existingTc.get("partId").asText())) {
                            idx = i;
                            break;
                        }
                    }
                }
                if (idx >= 0) {
                    toolCalls.set(idx, tc);
                } else {
                    toolCalls.add(tc);
                }
            }
            case "step-finish" -> {
                ObjectNode sf = objectMapper.createObjectNode();
                if (part.has("reason")) {
                    sf.put("reason", part.get("reason").asText());
                }
                partsNode.set("stepFinish", sf);
            }
            default -> {
                // 其他 part 类型不写入 parts(文本已在 setContent 处理)
            }
        }
    }

    /** 解析 parts JSON 字符串为 JsonNode 供 VO 透传;null/异常 → null(旧数据兼容) */
    private JsonNode parseParts(String partsJson) {
        if (partsJson == null || partsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(partsJson);
        } catch (Exception e) {
            log.warn("parse parts json for VO failed: {}", e.toString());
            return null;
        }
    }

    private String extractText(JsonNode parts) {
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : parts) {
            String type = p.has("type") ? p.get("type").asText() : "";
            if ("text".equals(type) && p.has("text")) {
                sb.append(p.get("text").asText());
            }
        }
        return sb.toString();
    }

    private String resolveStatus(JsonNode info, String role) {
        if ("user".equals(role)) return "completed";
        JsonNode time = info.get("time");
        if (time != null && time.has("completed") && !time.get("completed").isNull()) {
            return "completed";
        }
        return "streaming";
    }

    /**
     * 由 SSE 消费者在 question.asked / question.v2.asked 时调用,把待答复问题写入关联消息 parts。
     */
    public void upsertQuestionFromEvent(String opencodeSessionId, JsonNode properties) {
        if (properties == null) {
            return;
        }
        ChatSession session = chatSessionService.findByOpencodeSessionId(opencodeSessionId);
        if (session == null) {
            return;
        }
        String requestId = properties.has("id") ? properties.get("id").asText() : null;
        if (requestId == null) {
            return;
        }
        JsonNode tool = properties.has("tool") ? properties.get("tool") : null;
        String messageID = (tool != null && tool.has("messageID")) ? tool.get("messageID").asText() : null;

        ChatMessage msg = findTargetAssistantMessage(session.getId(), messageID);
        if (msg == null) {
            return;
        }

        ObjectNode partsNode = parsePartsObject(msg.getParts());
        ObjectNode pending = objectMapper.createObjectNode();
        pending.put("requestId", requestId);
        if (properties.has("questions")) {
            pending.set("questions", properties.get("questions"));
        }
        if (tool != null) {
            pending.set("tool", tool);
        }
        pending.put("version", properties.has("questions") && isV2Question(properties.get("questions")) ? "v2" : "v1");
        partsNode.set("pendingQuestion", pending);

        try {
            msg.setParts(objectMapper.writeValueAsString(partsNode));
            chatMessageMapper.updateById(msg);
        } catch (Exception e) {
            log.warn("serialize pendingQuestion failed (messageID={}): {}", messageID, e.toString());
        }
    }

    /**
     * 由 SSE 消费者在 permission.asked / permission.v2.asked 时调用,把待确认权限写入关联消息 parts。
     */
    public void upsertPermissionFromEvent(String opencodeSessionId, JsonNode properties) {
        if (properties == null) {
            return;
        }
        ChatSession session = chatSessionService.findByOpencodeSessionId(opencodeSessionId);
        if (session == null) {
            return;
        }
        String requestId = properties.has("id") ? properties.get("id").asText() : null;
        if (requestId == null) {
            return;
        }
        JsonNode tool = properties.has("tool") ? properties.get("tool") : null;
        String messageID = (tool != null && tool.has("messageID")) ? tool.get("messageID").asText() : null;

        ChatMessage msg = findTargetAssistantMessage(session.getId(), messageID);
        if (msg == null) {
            return;
        }

        ObjectNode partsNode = parsePartsObject(msg.getParts());
        ObjectNode pending = objectMapper.createObjectNode();
        pending.put("requestId", requestId);
        if (properties.has("action")) {
            pending.put("version", "v2");
            pending.set("action", properties.get("action"));
            if (properties.has("resources")) pending.set("resources", properties.get("resources"));
            if (properties.has("save")) pending.set("save", properties.get("save"));
            if (properties.has("metadata")) pending.set("metadata", properties.get("metadata"));
            if (properties.has("source")) pending.set("source", properties.get("source"));
        } else {
            pending.put("version", "v1");
            if (properties.has("permission")) pending.set("permission", properties.get("permission"));
            if (properties.has("patterns")) pending.set("patterns", properties.get("patterns"));
            if (properties.has("metadata")) pending.set("metadata", properties.get("metadata"));
            if (properties.has("always")) pending.set("always", properties.get("always"));
        }
        if (tool != null) {
            pending.set("tool", tool);
        }
        partsNode.set("pendingPermission", pending);

        try {
            msg.setParts(objectMapper.writeValueAsString(partsNode));
            chatMessageMapper.updateById(msg);
        } catch (Exception e) {
            log.warn("serialize pendingPermission failed (messageID={}): {}", messageID, e.toString());
        }
    }

    /** 清除指定会话中指定 requestId 的 pendingQuestion */
    public void clearPendingQuestion(Long sessionId, String requestId) {
        ChatMessage msg = findMessageWithPendingField(sessionId, "pendingQuestion", requestId);
        if (msg == null) {
            log.warn("clearPendingQuestion: no message found, sessionId={}, requestId={}", sessionId, requestId);
            return;
        }
        ObjectNode partsNode = parsePartsObject(msg.getParts());
        partsNode.remove("pendingQuestion");
        try {
            String newParts = objectMapper.writeValueAsString(partsNode);
            int rows = chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                    .eq(ChatMessage::getId, msg.getId())
                    .set(ChatMessage::getParts, newParts));
            log.info("clearPendingQuestion: sessionId={}, requestId={}, msgId={}, rows={}", sessionId, requestId, msg.getId(), rows);
        } catch (Exception e) {
            log.warn("clear pendingQuestion failed (msgId={}): {}", msg.getId(), e.toString());
        }
    }

    /** 清除指定会话中指定 permissionId 的 pendingPermission */
    public void clearPendingPermission(Long sessionId, String permissionId) {
        ChatMessage msg = findMessageWithPendingField(sessionId, "pendingPermission", permissionId);
        if (msg == null) {
            log.warn("clearPendingPermission: no message found, sessionId={}, permissionId={}", sessionId, permissionId);
            return;
        }
        ObjectNode partsNode = parsePartsObject(msg.getParts());
        partsNode.remove("pendingPermission");
        try {
            String newParts = objectMapper.writeValueAsString(partsNode);
            int rows = chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                    .eq(ChatMessage::getId, msg.getId())
                    .set(ChatMessage::getParts, newParts));
            log.info("clearPendingPermission: sessionId={}, permissionId={}, msgId={}, rows={}", sessionId, permissionId, msg.getId(), rows);
        } catch (Exception e) {
            log.warn("clear pendingPermission failed (msgId={}): {}", msg.getId(), e.toString());
        }
    }

    /**
     * 由 SSE 消费者在 question.replied / question.rejected / question.v2.replied / question.v2.rejected
     * 时调用,从关联消息 parts 中移除已处理的 pendingQuestion。
     * <p>
     * 与 {@link #upsertQuestionFromEvent} 对称:upsert 在 asked 时写入,本方法在 replied/rejected 时清除。
     * 若不清除,重新加载会话历史时会把已回答的问题再次恢复为"待回答"状态(重复弹框 bug)。
     */
    public void clearPendingQuestionFromEvent(String opencodeSessionId, JsonNode properties) {
        if (properties == null) {
            return;
        }
        ChatSession session = chatSessionService.findByOpencodeSessionId(opencodeSessionId);
        if (session == null) {
            return;
        }
        String requestId = properties.has("id") ? properties.get("id").asText() : null;
        JsonNode tool = properties.has("tool") ? properties.get("tool") : null;
        String messageID = (tool != null && tool.has("messageID")) ? tool.get("messageID").asText() : null;

        ChatMessage msg = null;
        if (requestId != null) {
            msg = findMessageWithPendingField(session.getId(), "pendingQuestion", requestId);
        }
        // 回退:按 tool.messageID 精确匹配(部分 replied 事件可能不带 id)
        if (msg == null && messageID != null) {
            msg = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getSessionId, session.getId())
                    .eq(ChatMessage::getOpencodeMessageId, messageID)
                    .last("limit 1"));
        }
        if (msg == null) {
            return;
        }
        ObjectNode partsNode = parsePartsObject(msg.getParts());
        if (!partsNode.has("pendingQuestion")) {
            return;
        }
        partsNode.remove("pendingQuestion");
        try {
            msg.setParts(objectMapper.writeValueAsString(partsNode));
            chatMessageMapper.updateById(msg);
        } catch (Exception e) {
            log.warn("clear pendingQuestion failed (messageID={}): {}", messageID, e.toString());
        }
    }

    /**
     * 由 SSE 消费者在 permission.replied / permission.v2.replied 时调用,
     * 从关联消息 parts 中移除已处理的 pendingPermission。
     * <p>
     * 与 {@link #upsertPermissionFromEvent} 对称。
     */
    public void clearPendingPermissionFromEvent(String opencodeSessionId, JsonNode properties) {
        if (properties == null) {
            return;
        }
        ChatSession session = chatSessionService.findByOpencodeSessionId(opencodeSessionId);
        if (session == null) {
            return;
        }
        String requestId = properties.has("id") ? properties.get("id").asText() : null;
        JsonNode tool = properties.has("tool") ? properties.get("tool") : null;
        String messageID = (tool != null && tool.has("messageID")) ? tool.get("messageID").asText() : null;

        ChatMessage msg = null;
        if (requestId != null) {
            msg = findMessageWithPendingField(session.getId(), "pendingPermission", requestId);
        }
        if (msg == null && messageID != null) {
            msg = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getSessionId, session.getId())
                    .eq(ChatMessage::getOpencodeMessageId, messageID)
                    .last("limit 1"));
        }
        if (msg == null) {
            return;
        }
        ObjectNode partsNode = parsePartsObject(msg.getParts());
        if (!partsNode.has("pendingPermission")) {
            return;
        }
        partsNode.remove("pendingPermission");
        try {
            msg.setParts(objectMapper.writeValueAsString(partsNode));
            chatMessageMapper.updateById(msg);
        } catch (Exception e) {
            log.warn("clear pendingPermission failed (messageID={}): {}", messageID, e.toString());
        }
    }

    /**
     * 找到关联的 assistant 消息:优先用 tool.messageID 精确匹配;否则取该会话最新的 assistant 消息(含 streaming)。
     */
    private ChatMessage findTargetAssistantMessage(Long sessionId, String opencodeMessageId) {
        if (opencodeMessageId != null) {
            ChatMessage byId = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getSessionId, sessionId)
                    .eq(ChatMessage::getOpencodeMessageId, opencodeMessageId)
                    .last("limit 1"));
            if (byId != null) return byId;
        }
        return chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, "assistant")
                .orderByDesc(ChatMessage::getId)
                .last("limit 1"));
    }

    /** 查找 parts 中含有指定字段且其 requestId 匹配的消息 */
    private ChatMessage findMessageWithPendingField(Long sessionId, String fieldName, String requestId) {
        List<ChatMessage> candidates = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, "assistant")
                .isNotNull(ChatMessage::getParts)
                .orderByDesc(ChatMessage::getId));
        for (ChatMessage m : candidates) {
            try {
                JsonNode parts = objectMapper.readTree(m.getParts());
                if (parts != null && parts.has(fieldName)) {
                    JsonNode field = parts.get(fieldName);
                    if (field.has("requestId") && requestId.equals(field.get("requestId").asText())) {
                        return m;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private boolean isV2Question(JsonNode questions) {
        if (questions == null || !questions.isArray() || questions.isEmpty()) return false;
        JsonNode first = questions.get(0);
        return first.has("header") || (first.has("options") && first.get("options").isArray());
    }

    private MessageVo toVo(ChatMessage m) {
        JsonNode parts = parseParts(m.getParts());
        return MessageVo.builder()
                .id(m.getId())
                .opencodeMessageId(m.getOpencodeMessageId())
                .role(m.getRole())
                .content(m.getContent())
                .parts(parts)
                .status(m.getStatus())
                .createdAt(m.getCreatedAt())
                .summarySha("summary".equals(m.getRole()) && parts != null ? parts.path("summarySha").asText(null) : null)
                .summaryParentSha("summary".equals(m.getRole()) && parts != null ? parts.path("summaryParentSha").asText(null) : null)
                .summaryFiles(extractSummaryFiles(m.getRole(), parts))
                .build();
    }

    /** 从 summary 消息的 parts 里解出变更文件清单(role != summary 时为空)。 */
    private List<MessageVo.SummaryFileVo> extractSummaryFiles(String role, JsonNode parts) {
        if (!"summary".equals(role) || parts == null) {
            return null;
        }
        JsonNode arr = parts.path("summaryFiles");
        if (!arr.isArray() || arr.isEmpty()) {
            return null;
        }
        List<MessageVo.SummaryFileVo> list = new java.util.ArrayList<>();
        arr.forEach(n -> list.add(MessageVo.SummaryFileVo.builder()
                .status(n.path("status").asText(""))
                .path(n.path("path").asText(""))
                .build()));
        return list;
    }
}

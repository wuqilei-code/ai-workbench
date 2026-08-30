package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.chat.MessageVo;
import com.aiworkbench.dto.chat.SendMessageRequest;
import com.aiworkbench.entity.ChatMessage;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.mapper.ChatMessageMapper;
import com.aiworkbench.service.agent.AgentStreamBridge;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天消息服务(deepagents 单轨)。
 * <p>
 * 持久化策略:用户消息发送即落库({@link #persistUserMessage});assistant 消息由
 * {@link AgentStreamBridge} 在流式过程中节流 upsert(文本 + 工具调用 + 状态),
 * 一轮一条记录,content=累计文本,parts={toolCalls:[...]}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionService chatSessionService;
    private final AgentStreamBridge agentStreamBridge;
    private final ObjectMapper objectMapper;

    /**
     * 找该会话在 beforeEpochMs(含)之前最近的一条用户消息,作为后台任务的"触发消息"。
     * DB 存北京时间墙上时间(MySQL SYSTEM=UTC+8,DATETIME 读回 LocalDateTime 不做偏移),
     * 需按 Asia/Shanghai 转成 epoch 毫秒再与 beforeEpochMs 比较。
     */
    public ChatMessage findTriggerUserMessage(Long sessionId, Long userId, long beforeEpochMs) {
        List<ChatMessage> list = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getUserId, userId)
                .eq(ChatMessage::getRole, "user")
                .orderByDesc(ChatMessage::getCreatedAt)
                .last("limit 50"));
        for (ChatMessage m : list) {
            if (m.getCreatedAt() != null
                    && m.getCreatedAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() <= beforeEpochMs) {
                return m;
            }
        }
        return null;
    }

    /** 历史从 MySQL 加载。游标分页见 {@link com.aiworkbench.controller.ChatMessageController#messages}。 */
    public List<MessageVo> listBySession(Long sessionId, Long userId, int limit,
                                         Long beforeId, Long afterId) {
        chatSessionService.getOwned(sessionId, userId); // 越权校验
        int capped = Math.max(1, Math.min(limit, 500));
        List<ChatMessage> list;
        if (beforeId != null) {
            // 加载更早:取 id < beforeId 的最近 capped 条,再反转为升序
            list = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getSessionId, sessionId)
                    .lt(ChatMessage::getId, beforeId)
                    .orderByDesc(ChatMessage::getId)
                    .last("limit " + capped));
            java.util.Collections.reverse(list);
        } else if (afterId != null) {
            // 增量追平:取 id > afterId 的最早 capped 条(升序)
            list = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getSessionId, sessionId)
                    .gt(ChatMessage::getId, afterId)
                    .orderByAsc(ChatMessage::getId)
                    .last("limit " + capped));
        } else {
            // 初载/默认:取最新 capped 条(id 降序取再反转,让前端直接看到会话尾部)
            list = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getSessionId, sessionId)
                    .orderByDesc(ChatMessage::getId)
                    .last("limit " + capped));
            java.util.Collections.reverse(list);
        }
        return list.stream().map(this::toVo).toList();
    }

    /** 全部消息(导出用,不受 listBySession 500 条上限约束) */
    public List<ChatMessage> allBySession(Long sessionId, Long userId) {
        chatSessionService.getOwned(sessionId, userId); // 越权校验
        return chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getId));
    }

    /** 清空会话消息(仅删本地 MySQL 记录,不删会话本身) */
    public int clearBySession(Long sessionId, Long userId) {
        chatSessionService.getOwned(sessionId, userId); // 越权校验
        return chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId));
    }

    /**
     * 发送用户消息:落库后交由 {@link AgentStreamBridge} 异步执行,
     * 流式回复经 SessionEventHub 以 SSE 直通前端。
     */
    public void sendUserMessage(Long sessionId, Long userId, SendMessageRequest req) {
        ChatSession s = chatSessionService.getOwned(sessionId, userId);
        persistUserMessage(s, req.getText());
        agentStreamBridge.sendMessage(s, userId, req.getText() != null ? req.getText() : "");
    }

    /** 终止当前生成 */
    public boolean abort(Long sessionId, Long userId) {
        ChatSession s = chatSessionService.getOwned(sessionId, userId);
        boolean ok = agentStreamBridge.abort(s.getAgentSessionId());
        // 用户中断输出:若有在途生成,插入"已中断"标记记录(前端渲染为分割线)。
        if (ok || countStreamingMessages(sessionId) > 0) {
            insertInterruptedMarker(sessionId, userId);
        }
        // 用户点击「停止」:桥的 abort 只撤销本地订阅,不会对该消息补发定稿事件;
        // 主动把残留 streaming 标记为 aborted,避免 DB/重载后永久转圈。
        if (ok) {
            int rows = markStreamingAborted(sessionId);
            if (rows > 0) {
                log.info("abort: marked {} streaming message(s) aborted, sessionId={}", rows, sessionId);
            }
        }
        return ok;
    }

    /** 会话当前正在流式生成的 assistant 消息数(判定 abort 时是否有在途生成)。 */
    public long countStreamingMessages(Long sessionId) {
        Long c = chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, "assistant")
                .eq(ChatMessage::getStatus, "streaming"));
        return c == null ? 0 : c;
    }

    /** 插入一条"已中断"标记消息:role=interrupted。前端把该记录渲染为"已中断"分割线。 */
    public void insertInterruptedMarker(Long sessionId, Long userId) {
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setUserId(userId);
        m.setRole("interrupted");
        m.setContent(null);
        m.setStatus("completed");
        chatMessageMapper.insert(m);
    }

    /**
     * deepagents 轨道:发送即落库用户消息(agent-service 无快照回流,不主动落库则刷新历史只剩 AI 消息)。
     */
    public void persistUserMessage(ChatSession session, String text) {
        ChatMessage m = new ChatMessage();
        m.setSessionId(session.getId());
        m.setUserId(session.getUserId());
        m.setAgentMessageId("da-user-" + UUID.randomUUID());
        m.setProviderId(session.getProviderId());
        m.setModelId(session.getModelId());
        m.setRole("user");
        m.setContent(text == null ? "" : text);
        m.setStatus("completed");
        m.setCompactionSummary(0);
        chatMessageMapper.insert(m);
    }

    /**
     * 由 {@link AgentStreamBridge} 调用:upsert 当前轮 assistant 消息
     * (content=累计文本,parts={toolCalls:[...]},status=streaming|completed|error|aborted)。
     *
     * @param agentSessionId 执行轨道会话 id("da-" 前缀)
     * @param agentMessageId 本轮 assistant 消息外部 id
     * @param toolCalls      工具调用节点(callId → {id,name,args,output,status}),保持插入序
     * @param elapsedSec     本轮生成耗时(秒,自流开始计),写入 parts 供前端刷新后展示
     */
    public void upsertAssistantTurn(String agentSessionId, String agentMessageId,
                                    String text, Map<String, ObjectNode> toolCalls,
                                    java.util.List<ObjectNode> timeline, String status,
                                    Long elapsedSec) {
        upsertAssistantTurn(agentSessionId, agentMessageId, text, toolCalls, timeline,
                status, elapsedSec, null, null);
    }

    /**
     * 同上,额外落库本轮结束时的上下文用量(deepagents 同口径 token 数与其压缩阈值),
     * 前端刷新后据此恢复进度圆环(否则只剩字符估算,与压缩判断不一致)。
     */
    public void upsertAssistantTurn(String agentSessionId, String agentMessageId,
                                    String text, Map<String, ObjectNode> toolCalls,
                                    java.util.List<ObjectNode> timeline, String status,
                                    Long elapsedSec, Integer contextTokens, Integer contextLimit) {
        ChatSession session = chatSessionService.findByAgentSessionId(agentSessionId);
        if (session == null) {
            log.debug("upsertAssistantTurn: unknown agent session {}", agentSessionId);
            return;
        }
        synchronized (partsLock(session.getId())) {
            ChatMessage existing = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getSessionId, session.getId())
                    .eq(ChatMessage::getAgentMessageId, agentMessageId)
                    .last("limit 1"));
            boolean isNew = existing == null;
            ChatMessage m = isNew ? new ChatMessage() : existing;
            if (isNew) {
                m.setSessionId(session.getId());
                m.setUserId(session.getUserId());
                m.setAgentMessageId(agentMessageId);
                m.setProviderId(session.getProviderId());
                m.setModelId(session.getModelId());
                m.setRole("assistant");
            }
            m.setContent(text == null ? "" : text);
            try {
                ObjectNode partsNode = objectMapper.createObjectNode();
                partsNode.putArray("toolCalls").addAll(toolCalls.values());
                // 时间线(文本段/工具调用交错序):有内容才写,旧数据无此键走前端回退逻辑
                if (timeline != null && !timeline.isEmpty()) {
                    partsNode.putArray("timeline").addAll(timeline);
                }
                // 本轮生成耗时(秒):前端刷新后据此展示"已完成 Xs"(内存态计时刷新即丢)
                if (elapsedSec != null && elapsedSec > 0) {
                    partsNode.put("elapsedSec", elapsedSec);
                }
                // 上下文用量(deepagents 同口径):刷新后恢复进度圆环
                if (contextTokens != null && contextTokens > 0) {
                    partsNode.put("contextTokens", contextTokens);
                }
                if (contextLimit != null && contextLimit > 0) {
                    partsNode.put("contextLimit", contextLimit);
                }
                m.setParts(objectMapper.writeValueAsString(partsNode));
                m.setToolCallCount(toolCalls.size());
            } catch (Exception e) {
                log.warn("serialize parts failed (agentMessageId={}): {}", agentMessageId, e.toString());
            }
            m.setStatus(status);
            if (m.getId() != null) {
                chatMessageMapper.updateById(m);
            } else {
                chatMessageMapper.insert(m);
            }
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<Long, Object> partsLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Object partsLock(Long sessionId) {
        return partsLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    /**
     * 由 {@link AgentStreamBridge} 在 HITL 中断时调用:把待确认请求写入当前 assistant 消息的
     * parts.pendingPermission(刷新后前端据此恢复确认卡片)。
     */
    public void upsertPendingPermission(String agentSessionId, String agentMessageId, String requestId,
                                        String toolName, String argsJson, String description,
                                        java.util.List<String> allowed) {
        ChatSession session = chatSessionService.findByAgentSessionId(agentSessionId);
        if (session == null) {
            return;
        }
        synchronized (partsLock(session.getId())) {
            ChatMessage m = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getSessionId, session.getId())
                    .eq(ChatMessage::getAgentMessageId, agentMessageId)
                    .last("limit 1"));
            if (m == null) {
                return;
            }
            try {
                ObjectNode partsNode = (ObjectNode) objectMapper.readTree(
                        m.getParts() == null || m.getParts().isBlank() ? "{}" : m.getParts());
                ObjectNode pending = objectMapper.createObjectNode();
                pending.put("requestId", requestId);
                ObjectNode tool = objectMapper.createObjectNode();
                tool.put("name", toolName);
                if (argsJson != null && !argsJson.isBlank()) {
                    try {
                        tool.set("args", objectMapper.readTree(argsJson));
                    } catch (Exception e) {
                        tool.put("args", argsJson);
                    }
                }
                tool.put("description", description == null ? "" : description);
                pending.set("tool", tool);
                ArrayNode a = pending.putArray("allowed");
                for (String d : allowed) a.add(d);
                partsNode.set("pendingPermission", pending);
                m.setParts(objectMapper.writeValueAsString(partsNode));
                chatMessageMapper.updateById(m);
            } catch (Exception e) {
                log.warn("upsertPendingPermission failed (msg={}): {}", agentMessageId, e.toString());
            }
        }
    }

    /** 清除指定 agent 会话中指定 requestId 的待确认请求(允许/拒绝/取消后)。 */
    public void clearPendingPermission(String agentSessionId, String requestId) {
        ChatSession session = chatSessionService.findByAgentSessionId(agentSessionId);
        if (session == null || requestId == null) {
            return;
        }
        Long sessionId = session.getId();
        List<ChatMessage> candidates = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, "assistant")
                .isNotNull(ChatMessage::getParts)
                .orderByDesc(ChatMessage::getId));
        for (ChatMessage m : candidates) {
            try {
                JsonNode parts = objectMapper.readTree(m.getParts());
                if (parts == null || !parts.isObject()) continue;
                JsonNode pp = parts.get("pendingPermission");
                if (pp != null && requestId.equals(pp.path("requestId").asText(null))) {
                    ObjectNode partsNode = (ObjectNode) parts;
                    partsNode.remove("pendingPermission");
                    m.setParts(objectMapper.writeValueAsString(partsNode));
                    chatMessageMapper.updateById(m);
                    log.info("clearPendingPermission: sessionId={} requestId={} msgId={}", sessionId, requestId, m.getId());
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    /** 用户处置人工确认:转发决策给桥,续跑被中断的生成。 */
    public boolean resumePermission(Long sessionId, Long userId, String requestId, String decision, String answer) {
        ChatSession s = chatSessionService.getOwned(sessionId, userId);
        return agentStreamBridge.resumePermission(s.getAgentSessionId(), requestId, decision, answer);
    }

    /** 前端提交回复发现已失效时,清除数据库中的 pendingPermission,防止刷新后重现。 */
    public void clearPendingPermissionBySession(Long sessionId, Long userId, String requestId) {
        ChatSession s = chatSessionService.getOwned(sessionId, userId);
        if (s != null) {
            clearPendingPermission(s.getAgentSessionId(), requestId);
        }
    }

    /**
     * 把会话中所有 status=streaming 的 assistant 消息标记为 aborted。
     * <p>
     * abort 成功后调用:桥不会对被终止的消息补发定稿事件,
     * 若不标记,DB 里会残留永久 streaming 的行,重载会话时 UI 状态异常。
     */
    public int markStreamingAborted(Long sessionId) {
        return chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, "assistant")
                .eq(ChatMessage::getStatus, "streaming")
                .set(ChatMessage::getStatus, "aborted"));
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
            sb.append("## ").append(roleLabel(m.getRole()));
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

    /** 导出会话为 Word(docx,POI 生成;中文字体名 SimHei,由打开端渲染)。 */
    public byte[] exportDocx(Long sessionId, Long userId, String title) {
        List<ChatMessage> msgs = allBySession(sessionId, userId);
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            XWPFParagraph titleP = doc.createParagraph();
            XWPFRun titleRun = titleP.createRun();
            titleRun.setText(title == null ? "会话导出" : title);
            setDocxFont(titleRun, 18, true);

            XWPFParagraph metaP = doc.createParagraph();
            XWPFRun metaRun = metaP.createRun();
            metaRun.setText("导出时间: " + LocalDateTime.now() + "    消息数: " + msgs.size());
            setDocxFont(metaRun, 10, false);

            for (ChatMessage m : msgs) {
                XWPFParagraph headP = doc.createParagraph();
                headP.setSpacingBefore(180);
                XWPFRun headRun = headP.createRun();
                String head = roleLabel(m.getRole());
                if (m.getCreatedAt() != null) {
                    head += "  ·  " + m.getCreatedAt();
                }
                headRun.setText(head);
                setDocxFont(headRun, 12, true);

                XWPFParagraph bodyP = doc.createParagraph();
                XWPFRun bodyRun = bodyP.createRun();
                String content = m.getContent() == null ? "" : m.getContent();
                boolean isPartsFallback = content.isBlank() && m.getParts() != null;
                String text = isPartsFallback ? m.getParts() : content;
                if (text.isEmpty()) {
                    bodyRun.setText("(无文本内容)");
                } else {
                    String[] lines = text.split("\n", -1);
                    bodyRun.setText(lines[0]);
                    for (int i = 1; i < lines.length; i++) {
                        bodyRun.addBreak();
                        bodyRun.setText(lines[i]);
                    }
                }
                setDocxFont(bodyRun, 11, false);
                if (isPartsFallback) {
                    bodyRun.setFontFamily("Consolas");
                }
            }
            doc.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "Word 导出失败: " + e.getMessage());
        }
    }

    /** 导出会话为 PDF(openhtmltopdf + 内置 simhei.ttf,中文正常渲染)。 */
    public byte[] exportPdf(Long sessionId, Long userId, String title) {
        List<ChatMessage> msgs = allBySession(sessionId, userId);
        String html = buildExportHtml(title, msgs);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            com.openhtmltopdf.pdfboxout.PdfRendererBuilder builder = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder();
            builder.useFont(() -> getClass().getResourceAsStream("/fonts/simhei.ttf"), "SimHei");
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "PDF 导出失败: " + e.getMessage());
        }
    }

    /** 会话内容渲染为 HTML(PDF 导出用;内嵌 SimHei 字体)。 */
    private String buildExportHtml(String title, List<ChatMessage> msgs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'/><style>")
                .append("@page{size:A4;margin:2cm}")
                .append("body{font-family:'SimHei',sans-serif;font-size:11px;color:#222;line-height:1.8}")
                .append("h1{font-size:18px;border-bottom:2px solid #ccc;padding-bottom:8px}")
                .append(".meta{color:#888;font-size:10px;margin-bottom:18px}")
                .append(".msg{margin:12px 0;padding:8px 10px;border-left:3px solid #999;background:#f6f6f6;page-break-inside:avoid}")
                .append(".msg.user{border-color:#4a7abd}")
                .append(".head{font-weight:bold;margin-bottom:5px}")
                .append(".content{white-space:pre-wrap;word-break:break-word}")
                .append(".parts{font-family:monospace;font-size:9px;color:#555;white-space:pre-wrap}")
                .append("</style></head><body>");
        sb.append("<h1>").append(escapeHtml(title == null ? "会话导出" : title)).append("</h1>");
        sb.append("<div class='meta'>导出时间: ").append(LocalDateTime.now())
                .append("    ·    消息数: ").append(msgs.size()).append("</div>");
        for (ChatMessage m : msgs) {
            String role = m.getRole() == null ? "" : m.getRole();
            sb.append("<div class='msg").append("user".equals(role) ? " user" : "").append("'>");
            sb.append("<div class='head'>").append(escapeHtml(roleLabel(role)));
            if (m.getCreatedAt() != null) {
                sb.append("  ·  ").append(escapeHtml(m.getCreatedAt().toString()));
            }
            sb.append("</div>");
            String content = m.getContent() == null ? "" : m.getContent();
            if (content.isBlank() && m.getParts() != null) {
                sb.append("<div class='parts'>").append(escapeHtml(m.getParts())).append("</div>");
            } else {
                sb.append("<div class='content'>").append(escapeHtml(content)).append("</div>");
            }
            sb.append("</div>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 设置 docx 段落的字体与字号(ascii/hAnsi;中文由打开端按默认中文字体回退渲染)。 */
    private static void setDocxFont(XWPFRun run, int size, boolean bold) {
        run.setFontFamily("SimHei");
        run.setFontSize(size);
        run.setBold(bold);
    }

    /** role 的中文展示名(导出 md/docx/pdf 共用)。 */
    private static String roleLabel(String role) {
        return switch (role == null ? "" : role) {
            case "user" -> "用户";
            case "assistant" -> "AI";
            case "tool" -> "工具";
            case "system" -> "系统";
            default -> role;
        };
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

    private MessageVo toVo(ChatMessage m) {
        return MessageVo.builder()
                .id(m.getId())
                .agentMessageId(m.getAgentMessageId())
                .role(m.getRole())
                .content(m.getContent())
                .parts(parseParts(m.getParts()))
                .compactionSummary(m.getCompactionSummary() != null && m.getCompactionSummary() == 1)
                .status(m.getStatus())
                .createdAt(m.getCreatedAt())
                .build();
    }
}

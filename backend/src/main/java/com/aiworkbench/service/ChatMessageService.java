package com.aiworkbench.service;

import com.aiworkbench.common.BusinessException;
import com.aiworkbench.common.ResultCode;
import com.aiworkbench.dto.chat.MessageVo;
import com.aiworkbench.dto.chat.SendMessageRequest;
import com.aiworkbench.entity.ChatMessage;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.mapper.ChatMessageMapper;
import com.aiworkbench.service.opencode.OpencodeClient;
import com.aiworkbench.service.opencode.OpencodeEventRouter;
import com.aiworkbench.service.opencode.dto.CreateSessionBody;
import com.aiworkbench.service.opencode.dto.OpencodeMessageDto;
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
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    private final OpencodeEventRouter eventRouter;
    private final ObjectMapper objectMapper;
    private final WorkspaceSnapshotService snapshotService;

    /**
     * 找该会话在 beforeEpochMs(含)之前最近的一条用户消息,作为后台任务的"触发消息"。
     * DB 存 UTC 墙上时间(MySQL 容器 SYSTEM=UTC,DATETIME 读回 LocalDateTime 不做偏移),与 epoch ms 直接按 UTC 对齐。
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
                    && m.getCreatedAt().toEpochSecond(ZoneOffset.UTC) * 1000L <= beforeEpochMs) {
                return m;
            }
        }
        return null;
    }

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
            case "summary" -> "AI 变更总结";
            default -> role;
        };
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

        // 压缩中禁止发送:压缩期间上下文正被改写,新消息会破坏压缩结果(前端已禁用,后端兜底)。
        if (isCompacting(sessionId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "会话正在压缩中,请稍候再发送");
        }

        // 基线快照:每轮对话开始前把工作区已存在的未提交变更(如用户手动删改文件)固化为
        // 独立 commit,使本轮结束时的变更总结只覆盖本会话真实产生的改动,避免把无关变更
        // 错误归因到当前会话(问题:会话 106 仅执行只读 ls,总结却出现"删除了模拟流水 Excel")。
        captureTurnBaseline(userId);

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
            // 冷启动占位:agent 实例尚未就绪时(空闲回收后/应用重启后首条消息),先经 SSE 通知前端
            // 显示「agent 启动中」占位,待 promptAsync 内部 getOrStart 阻塞至就绪后,真实事件流自然接管。
            if (!opencodeClient.isReady(userId)) {
                try {
                    eventRouter.dispatch(s.getOpencodeSessionId(), "session.starting",
                            "{\"status\":\"starting\"}");
                } catch (Exception dispatchErr) {
                    log.debug("dispatch session.starting failed: {}", dispatchErr.toString());
                }
            }
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
        boolean ok = opencodeClient.abort(userId, s.getOpencodeSessionId());
        // 用户点击「停止」:opencode 对正在生成的消息补发定稿事件,但卡死消息不会;
        // 主动把残留 streaming 标记为 aborted,避免 DB/重载后永久转圈。
        if (ok) {
            int rows = markStreamingAborted(sessionId);
            if (rows > 0) {
                log.info("abort: marked {} stuck streaming message(s) aborted, sessionId={}", rows, sessionId);
            }
        }
        return ok;
    }

    /** 主动触发会话压缩(项目侧检测到会话接近预算时调用)。
     *  auto=false:压缩完成后不自动继续(不注入 Continue),会话停在压缩总结处,由用户自行继续。
     *  压缩点会落库为一条 role=compaction 记录(status=compacting),前端直接渲染该记录
     *  为"正在压缩/已压缩"分割线,不再需要前端锚点推断;压缩完成时由事件消费者更新为 completed。 */
    public boolean summarize(Long sessionId, Long userId) {
        ChatSession s = chatSessionService.getOwned(sessionId, userId);
        insertCompactionMarker(s.getId(), s.getUserId());
        return opencodeClient.summarize(userId, s.getOpencodeSessionId(),
                s.getProviderId(), s.getModelId(), false);
    }

    /** 插入一条压缩点标记消息:role=compaction,status=compacting。
     *  前端把该记录渲染为"正在压缩…"占位;压缩完成时 {@link #completeCompaction} 更新为 completed。 */
    public void insertCompactionMarker(Long sessionId, Long userId) {
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setUserId(userId);
        m.setRole("compaction");
        m.setContent(null);
        m.setCompactionSummary(1);
        m.setStatus("compacting");
        chatMessageMapper.insert(m);
    }

    /** 压缩完成:把该会话最新一条压缩中标记更新为 completed。 */
    public void completeCompaction(Long sessionId) {
        if (sessionId == null) return;
        ChatMessage latest = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, "compaction")
                .eq(ChatMessage::getStatus, "compacting")
                .orderByDesc(ChatMessage::getId)
                .last("limit 1"));
        if (latest != null) {
            latest.setStatus("completed");
            chatMessageMapper.updateById(latest);
        }
    }

    /** 压缩完成(按 opencode session 定位 chat 会话):见 {@link #completeCompaction(Long)}。 */
    public void completeCompactionByOpencodeSession(String opencodeSessionId) {
        if (opencodeSessionId == null) return;
        ChatSession s = chatSessionService.findByOpencodeSessionId(opencodeSessionId);
        if (s != null) {
            completeCompaction(s.getId());
        }
    }

    /** 会话当前是否正在压缩(存在未完成的 compaction 标记记录)。 */
    public boolean isCompacting(Long sessionId) {
        if (sessionId == null) return false;
        return chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, "compaction")
                .eq(ChatMessage::getStatus, "compacting")) > 0;
    }

    /**
     * 每轮 AI 对话开始前的"基线快照":把工作区里对话开始前就已存在的未提交变更
     * (如用户手动删改文件、上一轮遗留的改动)提交为一个独立 commit。
     * <p>
     * 这样本轮结束时的快照 commit 相对其父(即此基线)的 diff 只包含本会话真实产生的改动,
     * 变更总结不会再错误地把用户手动操作/无关改动归因到当前会话。
     * <p>
     * 无变更时 git commit 静默失败(snapshot 返回 null),不产生空提交。
     */
    public void captureTurnBaseline(Long userId) {
        try {
            snapshotService.snapshot(userId, "AI 对话开始");
        } catch (Exception e) {
            log.debug("capture turn baseline failed (userId={}): {}", userId, e.toString());
        }
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
        // opencode message.updated 的 info 是完整 AssistantMessage(顶层带 tokens)
        JsonNode tokens = info.has("tokens") && info.get("tokens").isObject() ? info.get("tokens") : null;
        String tokensJson = tokens != null ? tokens.toString() : null;

        ChatMessage existing = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, session.getId())
                .eq(ChatMessage::getOpencodeMessageId, opencodeMessageId)
                .last("limit 1"));

        // 压缩产生的消息(前端不展示,仅标记占位):
        //  - opencode 压缩消息(type="compaction",schema Session.Message.Compaction)
        //  - compaction agent 的总结消息(assistant, mode=compaction / summary=true)
        //  - 压缩占位 user 消息(空内容)
        //  - auto 注入的 Continue user 消息(opencode 压缩后自动继续用)
        boolean compactionSummary;
        String msgType = info.path("type").asText(null);
        if ("compaction".equals(msgType)) {
            compactionSummary = true;
        } else if ("assistant".equals(role)) {
            compactionSummary = "compaction".equals(info.path("mode").asText(null))
                    || info.path("summary").asBoolean(false);
        } else {
            // user 消息:文本为空且 DB 也无内容 → 压缩占位;否则是真实消息(不隐藏)。
            // 注意 message.updated 可能只带 info 不带 parts,text 提取为空,须用 DB 已有 content 兜底,
            // 否则用户真实消息会被误判为压缩占位而隐藏。
            String trimmed = text != null ? text.trim() : "";
            boolean dbHasContent = existing != null && existing.getContent() != null
                    && !existing.getContent().isBlank();
            compactionSummary = trimmed.isEmpty() && !dbHasContent
                    || (trimmed.startsWith("Continue if you have next steps") && !dbHasContent);
        }
        int compactionFlag = compactionSummary ? 1 : 0;

        if (existing != null) {
            existing.setRole(role);
            // message.updated 不含 parts/文本,不要覆盖 message.part.updated 已写入的 content 与 parts
            if (text != null && !text.isEmpty()) {
                existing.setContent(text);
            }
            if (tokensJson != null) {
                existing.setTokens(tokensJson);
            }
            existing.setCompactionSummary(compactionFlag);
            existing.setStatus(status);
            chatMessageMapper.updateById(existing);
        } else {
            ChatMessage m = new ChatMessage();
            m.setSessionId(session.getId());
            m.setUserId(session.getUserId());
            m.setOpencodeMessageId(opencodeMessageId);
            m.setRole(role);
            m.setContent(text);
            m.setTokens(tokensJson);
            m.setCompactionSummary(compactionFlag);
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

    /**
     * 由 SSE 消费者对<b>节流后</b>的思维链文本落库(与 {@link #upsertStreamingText} 同理)。
     * <p>
     * 思维链(reasoning part)流式期间经 ctx 流式推送累计文本,若每条都重写 parts MEDIUMTEXT 列
     * 开销过大,故消费者在内存中合并、超间隔写一次;完成时由 message.updated 强制 flush 收口。
     *
     * @param opencodeSessionId 事件中的 opencode 会话 id
     * @param messageID         目标消息的 opencode 消息 id
     * @param text              最新思维链文本
     */
    public void upsertStreamingReasoning(String opencodeSessionId, String messageID, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ChatSession session = chatSessionService.findByOpencodeSessionId(opencodeSessionId);
        if (session == null) {
            return;
        }
        ChatMessage existing = findOrCreateMessage(session, messageID);
        ObjectNode partsNode = parsePartsObject(existing.getParts());
        ObjectNode r = objectMapper.createObjectNode();
        r.put("text", text);
        partsNode.set("reasoning", r);
        try {
            existing.setParts(objectMapper.writeValueAsString(partsNode));
        } catch (Exception e) {
            log.warn("serialize parts failed (messageID={}): {}", messageID, e.toString());
        }
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
        // 防御:user 消息一旦有真实内容就不是压缩占位(压缩占位始终为空);避免误标导致前端隐藏真实对话
        if ("user".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
            m.setCompactionSummary(0);
        }
        if (m.getId() != null) {
            chatMessageMapper.updateById(m);
        } else {
            chatMessageMapper.insert(m);
        }
    }

    /**
     * 会话一轮结束后从 opencode 拉最近消息,把 assistant 消息的真实 token 用量回填到 DB。
     * 进度环以该数据为准;只在 tokens 为空时写,避免反复覆盖。异步调用,失败静默。
     * 同时按 opencode 消息的 mode/summary/占位特征回填 compaction_summary 标记(前端隐藏压缩过程消息)。
     */
    public void backfillTokens(String opencodeSessionId) {
        try {
            ChatSession s = chatSessionService.findByOpencodeSessionId(opencodeSessionId);
            if (s == null) {
                return;
            }
            List<OpencodeMessageDto> msgs = opencodeClient.listMessages(s.getUserId(), opencodeSessionId, 30);
            for (OpencodeMessageDto m : msgs) {
                if (m.getId() == null) {
                    continue;
                }
                ChatMessage db = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, s.getId())
                        .eq(ChatMessage::getOpencodeMessageId, m.getId())
                        .last("limit 1"));
                if (db == null) {
                    continue;
                }
                boolean changed = false;
                if (m.getTokens() != null && db.getTokens() == null) {
                    String json = objectMapper.writeValueAsString(m.getTokens());
                    db.setTokens(json);
                    changed = true;
                }
                boolean isHidden = isCompactionMessage(m);
                if (isHidden && (db.getCompactionSummary() == null || db.getCompactionSummary() != 1)) {
                    db.setCompactionSummary(1);
                    changed = true;
                }
                if (changed) {
                    chatMessageMapper.updateById(db);
                }
            }
        } catch (Exception e) {
            log.debug("backfillTokens failed for session {}: {}", opencodeSessionId, e.toString());
        }
    }

    /** 判断 opencode 消息是否属压缩过程产物(前端隐藏):compaction agent 总结 / 空 user 占位 / Continue 注入。 */
    private boolean isCompactionMessage(OpencodeMessageDto m) {
        if (m.getMode() != null && "compaction".equals(m.getMode())) {
            return true;
        }
        if (Boolean.TRUE.equals(m.getSummary())) {
            return true;
        }
        String role = m.getRole();
        if (role == null) {
            return false;
        }
        String text = extractText(m.getParts());
        String trimmed = text != null ? text.trim() : "";
        if ("user".equals(role)) {
            return trimmed.isEmpty() || trimmed.startsWith("Continue if you have next steps");
        }
        return false;
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
                    // 优先完整输出 state.output(工具完成时定稿);
                    // 运行中 opencode 经 ctx.metadata 流式推送 state.metadata.output(过程输出,可能截断),
                    // 一并持久化,使工具执行过程内容也能实时进入前端折叠框,而非只显示最终结果。
                    if (state.has("output") && !state.get("output").isNull()) {
                        tc.set("output", state.get("output"));
                    } else if (state.has("metadata") && state.get("metadata").has("output")
                            && !state.get("metadata").get("output").isNull()) {
                        tc.set("output", state.get("metadata").get("output"));
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
            case "reasoning" -> {
                // 思维链 part:opencode 以 part.text 承载(streaming 期间为累计文本,完成时定稿)。
                // 通用支持:模型输出思维链则持久化;模型不输出该 part 则此分支不触发,无影响。
                ObjectNode r = objectMapper.createObjectNode();
                if (part.has("text")) {
                    r.put("text", part.get("text").asText());
                }
                partsNode.set("reasoning", r);
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

    private JsonNode parseTokens(String tokensJson) {
        if (tokensJson == null || tokensJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(tokensJson);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
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

    /**
     * 该会话最近的 assistant 消息是否存在<b>在途未完成</b>的 toolCall(status=running)。
     * <p>
     * 看门狗判定"工具卡死"用:与 hasUnresolvedPending 不同,这里不需要 pendingPermission——
     * 典型场景是 bash 工具因后台子进程持有管道而永不返回(如 Flask dev server 的 reloader 子进程),
     * agent loop 空转、无任何事件,消息永久 streaming,用户后续消息全部无响应。
     */
    public boolean hasRunningTool(Long sessionId) {
        List<ChatMessage> list = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, "assistant")
                .orderByDesc(ChatMessage::getId)
                .last("limit 5"));
        for (ChatMessage m : list) {
            if (m.getParts() == null || m.getParts().isBlank()) {
                continue;
            }
            try {
                JsonNode parts = objectMapper.readTree(m.getParts());
                JsonNode toolCalls = parts.has("toolCalls") ? parts.get("toolCalls") : null;
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        String status = tc.has("status") ? tc.get("status").asText("") : "";
                        if ("running".equals(status)) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {
                // 单条 parts 解析失败不影响判定
            }
        }
        return false;
    }

    /**
     * 把会话中所有 status=streaming 的 assistant 消息标记为 aborted。
     * <p>
     * abort 成功后调用:工具卡死/用户停止时 opencode 不会对该消息补发定稿事件,
     * 若不标记,DB 里会残留永久 streaming 的行,重载会话时 UI 状态异常。
     */
    public int markStreamingAborted(Long sessionId) {
        return chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, "assistant")
                .eq(ChatMessage::getStatus, "streaming")
                .set(ChatMessage::getStatus, "aborted"));
    }

    /** 该会话是否存在创建时间早于给定时刻、且仍为 streaming 的 assistant 消息。
     *  看门狗"绝对超时"兜底用:不依赖 opencode 事件心跳(事件可能在卡死时仍持续刷新),
     *  直接按 DB 里 streaming 消息的年龄判定——超过硬阈值仍未定稿即视为卡死。 */
    public boolean hasStreamingMessageOlderThan(Long sessionId, java.time.LocalDateTime threshold) {
        Long count = chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, "assistant")
                .eq(ChatMessage::getStatus, "streaming")
                .lt(ChatMessage::getCreatedAt, threshold));
        return count != null && count > 0;
    }

    /** 该会话当前是否存在未回答的 pendingPermission(parts 含 pendingPermission 的最新 assistant 消息)。看门狗判定卡死用。 */
    public boolean hasUnresolvedPending(Long sessionId) {
        List<ChatMessage> candidates = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getRole, "assistant")
                .isNotNull(ChatMessage::getParts)
                .like(ChatMessage::getParts, "pendingPermission")
                .orderByDesc(ChatMessage::getId)
                .last("limit 5"));
        for (ChatMessage m : candidates) {
            try {
                JsonNode parts = objectMapper.readTree(m.getParts());
                if (parts != null && parts.has("pendingPermission")) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /** 未回答且超时的 pending 请求(permission/question):看门狗超时自动拒绝用。
     *  返回 {sessionId, requestId, kind(permission/question)}。 */
    public java.util.List<StalePending> findStalePendingRequests(java.time.LocalDateTime threshold) {
        java.util.List<StalePending> out = new java.util.ArrayList<>();
        List<ChatMessage> candidates = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getRole, "assistant")
                .isNotNull(ChatMessage::getParts)
                .like(ChatMessage::getParts, "pending")
                .lt(ChatMessage::getCreatedAt, threshold)
                .orderByDesc(ChatMessage::getId)
                .last("limit 200"));
        for (ChatMessage m : candidates) {
            try {
                JsonNode parts = objectMapper.readTree(m.getParts());
                if (parts == null) continue;
                if (parts.has("pendingPermission") && parts.get("pendingPermission").has("requestId")) {
                    out.add(new StalePending(m.getSessionId(), m.getOpencodeMessageId(),
                            parts.get("pendingPermission").get("requestId").asText(), "permission"));
                }
                if (parts.has("pendingQuestion") && parts.get("pendingQuestion").has("requestId")) {
                    out.add(new StalePending(m.getSessionId(), m.getOpencodeMessageId(),
                            parts.get("pendingQuestion").get("requestId").asText(), "question"));
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    public record StalePending(Long sessionId, String opencodeMessageId, String requestId, String kind) {}

    private MessageVo toVo(ChatMessage m) {
        JsonNode parts = parseParts(m.getParts());
        JsonNode tokens = parseTokens(m.getTokens());
        return MessageVo.builder()
                .id(m.getId())
                .opencodeMessageId(m.getOpencodeMessageId())
                .role(m.getRole())
                .content(m.getContent())
                .parts(parts)
                .tokens(tokens)
                .compactionSummary(m.getCompactionSummary() != null && m.getCompactionSummary() == 1)
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

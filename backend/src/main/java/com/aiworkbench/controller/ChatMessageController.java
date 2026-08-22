package com.aiworkbench.controller;

import com.aiworkbench.common.R;
import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.common.audit.AuditLog;
import com.aiworkbench.dto.chat.MessageVo;
import com.aiworkbench.dto.chat.SendMessageRequest;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.service.ChatMessageService;
import com.aiworkbench.service.ChatSessionService;
import com.aiworkbench.service.agent.SessionEventHub;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chat/sessions/{id}")
@RequiredArgsConstructor
public class ChatMessageController {

    private final ChatMessageService chatMessageService;
    private final ChatSessionService chatSessionService;
    private final SessionEventHub hub;

    /**
     * 消息历史(从 MySQL 加载),游标分页:
     *  - 不传游标:取最新 limit 条(会话通常很长,进会话先看最近内容);
     *  - beforeId:取 id < beforeId 的最近 limit 条(向上翻页加载更早);
     *  - afterId:取 id > afterId 的最早 limit 条(增量追平/断线对账)。
     */
    @GetMapping("/messages")
    public R<List<MessageVo>> messages(@PathVariable Long id,
                                       @RequestParam(defaultValue = "200") int limit,
                                       @RequestParam(required = false) Long beforeId,
                                       @RequestParam(required = false) Long afterId) {
        return R.ok(chatMessageService.listBySession(id, SecurityUtil.currentUserId(),
                limit, beforeId, afterId));
    }

    /** 发送用户消息(立即返回,流式回复走 /events) */
    @PostMapping("/messages")
    @AuditLog(action = "发送消息", targetSpel = "#id")
    public R<Void> send(@PathVariable Long id, @Valid @RequestBody SendMessageRequest req) {
        chatMessageService.sendUserMessage(id, SecurityUtil.currentUserId(), req);
        return R.ok();
    }

    /** 清空会话消息(仅删本地 MySQL 记录,会话保留) */
    @DeleteMapping("/messages")
    @AuditLog(action = "清空会话", targetSpel = "#id")
    public R<Void> clear(@PathVariable Long id) {
        chatMessageService.clearBySession(id, SecurityUtil.currentUserId());
        return R.ok();
    }

    /** 导出会话内容(md 默认 / json / docx / pdf)。返回文件下载。 */
    @GetMapping(value = "/export", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> export(@PathVariable Long id,
                                         @RequestParam(defaultValue = "md") String format) {
        Long userId = SecurityUtil.currentUserId();
        ChatSession s = chatSessionService.getOwned(id, userId);
        String title = s.getTitle() == null ? "会话导出" : s.getTitle();
        String ext;
        String mediaType;
        byte[] data;
        switch (format.toLowerCase()) {
            case "json" -> {
                data = chatMessageService.exportJson(id, userId, title).getBytes(StandardCharsets.UTF_8);
                ext = "json";
                mediaType = MediaType.APPLICATION_JSON_VALUE;
            }
            case "docx" -> {
                data = chatMessageService.exportDocx(id, userId, title);
                ext = "docx";
                mediaType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            }
            case "pdf" -> {
                data = chatMessageService.exportPdf(id, userId, title);
                ext = "pdf";
                mediaType = MediaType.APPLICATION_PDF_VALUE;
            }
            default -> {
                data = chatMessageService.exportMarkdown(id, userId, title).getBytes(StandardCharsets.UTF_8);
                ext = "md";
                mediaType = "text/markdown";
            }
        }
        // RFC 5987 编码文件名,支持中文
        String encoded = URLEncoder.encode(title + "." + ext, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(mediaType))
                .contentLength(data.length)
                .body(data);
    }

    /** 终止当前生成 */
    @PostMapping("/abort")
    @AuditLog(action = "终止生成", targetSpel = "#id")
    public R<Boolean> abort(@PathVariable Long id) {
        return R.ok(chatMessageService.abort(id, SecurityUtil.currentUserId()));
    }

    /** 人工确认(HITL):允许/拒绝危险操作,续跑被中断的生成。 */
    @PostMapping("/permission/{requestId}/reply")
    @AuditLog(action = "处置权限确认", targetSpel = "#id")
    public R<Boolean> replyPermission(@PathVariable Long id,
                                      @PathVariable String requestId,
                                      @RequestBody Map<String, String> body) {
        String decision = body != null ? body.get("decision") : null;
        if (!"approve".equals(decision) && !"reject".equals(decision)) {
            throw new com.aiworkbench.common.BusinessException(
                    com.aiworkbench.common.ResultCode.BAD_REQUEST.getCode(), "decision 只能为 approve 或 reject");
        }
        return R.ok(chatMessageService.resumePermission(id, SecurityUtil.currentUserId(), requestId, decision));
    }

    /**
     * SSE 订阅该会话的实时事件(deepagents 原生事件经 SessionEventHub 直通)。
     * 越权校验:getOwned 不属于当前用户则抛 SESSION_NOT_FOUND(404)。
     */
    @GetMapping(value = "/events", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long id) {
        Long userId = SecurityUtil.currentUserId();
        ChatSession s = chatSessionService.getOwned(id, userId);
        String agentSessionId = s.getAgentSessionId();
        SseEmitter emitter = new SseEmitter(0L);
        hub.subscribe(agentSessionId, emitter);
        emitter.onCompletion(() -> hub.unsubscribe(agentSessionId, emitter));
        emitter.onTimeout(() -> {
            hub.unsubscribe(agentSessionId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> hub.unsubscribe(agentSessionId, emitter));
        try {
            emitter.send(SseEmitter.event().name("open")
                    .data("{\"agentSessionId\":\"" + agentSessionId + "\"}"));
        } catch (Exception ignored) {}
        return emitter;
    }
}
package com.aiworkbench.controller;

import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.common.audit.AuditLog;
import com.aiworkbench.dto.chat.CommandVo;
import com.aiworkbench.dto.chat.MessageVo;
import com.aiworkbench.dto.chat.RunCommandRequest;
import com.aiworkbench.dto.chat.SendMessageRequest;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.common.R;
import com.aiworkbench.service.ChatMessageService;
import com.aiworkbench.service.ChatSessionService;
import com.aiworkbench.service.opencode.OpencodeClient;
import com.aiworkbench.service.opencode.OpencodeEventRouter;
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
    private final OpencodeEventRouter router;
    private final OpencodeClient opencodeClient;

    /** 消息历史(从 MySQL 镜像加载) */
    @GetMapping("/messages")
    public R<List<MessageVo>> messages(@PathVariable Long id,
                                       @RequestParam(defaultValue = "200") int limit) {
        return R.ok(chatMessageService.listBySession(id, SecurityUtil.currentUserId(), limit));
    }

    /** 发送用户消息(立即返回,流式回复走 /events) */
    @PostMapping("/messages")
    @AuditLog(action = "发送消息", targetSpel = "#id")
    public R<Void> send(@PathVariable Long id, @Valid @RequestBody SendMessageRequest req) {
        chatMessageService.sendUserMessage(id, SecurityUtil.currentUserId(), req);
        return R.ok();
    }

    /** 清空会话消息(仅删本地 MySQL 镜像,会话保留) */
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

    /**
     * 该会话可用的 slash 命令列表(内置 + 用户 command/*.md,source=command)。
     * 越权校验同 messages:getOwned 不属于当前用户则抛 SESSION_NOT_FOUND。
     */
    @GetMapping("/commands")
    public R<List<CommandVo>> commands(@PathVariable Long id) {
        Long userId = SecurityUtil.currentUserId();
        chatSessionService.getOwned(id, userId);
        List<CommandVo> commands = opencodeClient.listCommands(userId).stream()
                .filter(c -> c.getSource() == null || "command".equals(c.getSource()))
                .map(c -> CommandVo.builder()
                        .name(c.getName())
                        .description(c.getDescription())
                        .agent(c.getAgent())
                        .model(c.getModel())
                        .source(c.getSource())
                        .subtask(c.getSubtask())
                        .hints(c.getHints())
                        .build())
                .toList();
        return R.ok(commands);
    }

    /**
     * 触发 slash 命令(fire-and-forget):用户消息与回复经 SSE 事件流镜像,与 prompt_async 同 UX。
     * 命令不存在/执行失败时 opencode 发布 session.error 事件,前端展示。
     */
    @PostMapping("/commands")
    @AuditLog(action = "执行命令", targetSpel = "#id")
    public R<Void> runCommand(@PathVariable Long id, @Valid @RequestBody RunCommandRequest req) {
        Long userId = SecurityUtil.currentUserId();
        ChatSession s = chatSessionService.getOwned(id, userId);
        opencodeClient.runCommand(userId, s.getOpencodeSessionId(), req.getCommand(), req.getArguments());
        return R.ok();
    }

    /** 终止当前生成 */
    @PostMapping("/abort")
    @AuditLog(action = "终止生成", targetSpel = "#id")
    public R<Boolean> abort(@PathVariable Long id) {
        return R.ok(chatMessageService.abort(id, SecurityUtil.currentUserId()));
    }

    /**
     * SSE 订阅该会话的实时事件(opencode /event 经路由转发)。
     * 越权校验:getOwned 不属于当前用户则抛 SESSION_NOT_FOUND(404)。
     */
    @GetMapping(value = "/events", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long id) {
        Long userId = SecurityUtil.currentUserId();
        ChatSession s = chatSessionService.getOwned(id, userId);
        SseEmitter emitter = new SseEmitter(0L);
        router.subscribe(s.getOpencodeSessionId(), emitter);
        emitter.onCompletion(() -> router.unsubscribe(s.getOpencodeSessionId(), emitter));
        emitter.onTimeout(() -> {
            router.unsubscribe(s.getOpencodeSessionId(), emitter);
            emitter.complete();
        });
        emitter.onError(e -> router.unsubscribe(s.getOpencodeSessionId(), emitter));
        try {
            emitter.send(SseEmitter.event().name("open")
                    .data("{\"sessionID\":\"" + s.getOpencodeSessionId() + "\"}"));
        } catch (Exception ignored) {}
        return emitter;
    }

    /** 回复问题 */
    @PostMapping("/question/{requestId}/reply")
    @AuditLog(action = "回复问题", targetSpel = "#id")
    public R<Void> replyQuestion(@PathVariable Long id,
                                 @PathVariable String requestId,
                                 @RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.currentUserId();
        ChatSession s = chatSessionService.getOwned(id, userId);
        @SuppressWarnings("unchecked")
        List<List<String>> answers = (List<List<String>>) body.get("answers");
        // opencode 调用失败不阻断本地清除:即便 opencode 返回 4xx/5xx(如 permission/question 已消费的 404),
        // 也要清掉 DB 里的 pending,否则刷新历史时确认框会重复出现。工具实际多已执行,只是 reply 接口报错。
        try {
            opencodeClient.replyQuestion(userId, s.getOpencodeSessionId(), requestId, answers);
        } catch (Exception e) {
            log.warn("reply question to opencode failed (still clear local pending): {}", e.toString());
        }
        chatMessageService.clearPendingQuestion(id, requestId);
        return R.ok();
    }

    /** 拒绝问题 */
    @PostMapping("/question/{requestId}/reject")
    @AuditLog(action = "拒绝问题", targetSpel = "#id")
    public R<Void> rejectQuestion(@PathVariable Long id,
                                  @PathVariable String requestId) {
        Long userId = SecurityUtil.currentUserId();
        ChatSession s = chatSessionService.getOwned(id, userId);
        try {
            opencodeClient.rejectQuestion(userId, s.getOpencodeSessionId(), requestId);
        } catch (Exception e) {
            log.warn("reject question to opencode failed (still clear local pending): {}", e.toString());
        }
        chatMessageService.clearPendingQuestion(id, requestId);
        return R.ok();
    }

    /** 回复权限 */
    @PostMapping("/permission/{permissionId}/reply")
    @AuditLog(action = "回复权限", targetSpel = "#id")
    public R<Void> replyPermission(@PathVariable Long id,
                                   @PathVariable String permissionId,
                                   @RequestBody Map<String, Object> body) {
        Long userId = SecurityUtil.currentUserId();
        ChatSession s = chatSessionService.getOwned(id, userId);
        String reply = (String) body.get("reply");
        try {
            opencodeClient.replyPermission(userId, s.getOpencodeSessionId(), permissionId, reply);
        } catch (Exception e) {
            log.warn("reply permission to opencode failed (still clear local pending): {}", e.toString());
        }
        chatMessageService.clearPendingPermission(id, permissionId);
        return R.ok();
    }
}

package com.aiworkbench.controller;

import com.aiworkbench.common.SecurityUtil;
import com.aiworkbench.common.audit.AuditLog;
import com.aiworkbench.dto.chat.MessageVo;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

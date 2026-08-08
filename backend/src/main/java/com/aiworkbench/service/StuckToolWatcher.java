package com.aiworkbench.service;

import com.aiworkbench.config.OpencodeProperties;
import com.aiworkbench.entity.ChatSession;
import com.aiworkbench.service.opencode.OpencodeClient;
import com.aiworkbench.service.opencode.process.OpencodeProcessManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具在途看门狗:兜底「生成中但长时间无事件」的卡死会话(典型:权限请求已发出但用户从未看到/未回答,
 * opencode 死等该权限,agent loop 卡住,前端展示权限弹窗消失)。
 * <p>
 * 判定:进程 streaming == true,且最近一次事件距现在超过阈值,且该会话 parts 中存在未回答的
 * pendingPermission → 视为卡死在权限等待上,自动调用 opencode abort 解除死锁,让 agent 结束本轮,
 * 用户重新发起即可。带 per-user 冷却(防止误杀后立刻再杀同一用户)。
 * <p>
 * 根因修复是前端按 requestId 清除 pendingPermission + chat.md 避免触发多权限命令;
 * 本组件只是最后一道安全网,不取代弹窗修复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StuckToolWatcher {

    private final OpencodeProperties props;
    private final OpencodeProcessManager processManager;
    private final ChatMessageService chatMessageService;
    private final ChatSessionService chatSessionService;
    private final OpencodeClient opencodeClient;

    /** userId → 上次 abort 时间戳(冷却,防止同一个卡死反复被杀) */
    private final Map<Long, Long> lastAbortAt = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${opencode.stuck-sweep-ms:5000}")
    public void sweep() {
        for (Long userId : processManager.activeUserIds()) {
            try {
                scan(userId);
            } catch (Exception e) {
                log.debug("stuck-tool scan failed for user {}: {}", userId, e.toString());
            }
        }
    }

    private void scan(Long userId) {
        if (!processManager.isStreaming(userId)) {
            return;
        }
        Instant lastEvent = processManager.lastEventAt(userId);
        // 尚无任何事件心跳(刚启动/异常),不干预
        if (lastEvent == null || lastEvent.equals(Instant.MIN)) {
            return;
        }
        long ageMs = System.currentTimeMillis() - lastEvent.toEpochMilli();
        String sessionId = processManager.lastEventSessionId(userId);
        boolean pending = false;
        if (sessionId != null) {
            ChatSession session = chatSessionService.findByOpencodeSessionId(sessionId);
            if (session != null) {
                pending = chatMessageService.hasUnresolvedPending(session.getId());
            }
        }
        if (!pending) {
            // 生成中但无事件、且无未回答权限:可能是合理的长工具执行,不自动干预,仅记录
            if (ageMs > props.getStuckToolTimeoutMs()) {
                log.warn("stuck-tool: user {} streaming with no event for {}ms (lastSession={}), no pending permission, leaving alone",
                        userId, ageMs, sessionId);
            }
            return;
        }
        if (ageMs < props.getStuckPermissionPromptMs()) {
            return;
        }
        if (ageMs < props.getStuckToolTimeoutMs()) {
            // 已进入「权限疑似未回答」窗口但未到强杀阈值,记录以便排查
            log.warn("stuck-tool: user {} session {} permission unanswered for {}ms (pending), will abort at {}ms",
                    userId, sessionId, ageMs, props.getStuckToolTimeoutMs());
            return;
        }
        abort(userId, sessionId, ageMs);
    }

    private void abort(Long userId, String sessionId, long ageMs) {
        long last = lastAbortAt.getOrDefault(userId, 0L);
        if (System.currentTimeMillis() - last < props.getStuckCooldownMs()) {
            log.warn("stuck-tool: user {} session {} still stuck ({}ms) but within cooldown, skip abort",
                    userId, sessionId, ageMs);
            return;
        }
        boolean ok;
        try {
            ok = opencodeClient.abort(userId, sessionId);
        } catch (Exception e) {
            log.warn("stuck-tool: abort failed for user {} session {}: {}", userId, sessionId, e.toString());
            return;
        }
        if (ok) {
            lastAbortAt.put(userId, System.currentTimeMillis());
            log.warn("stuck-tool: aborted user {} session {} (permission unanswered {}ms)", userId, sessionId, ageMs);
            // 记录一条日志事件即可;abort 后 opencode 会向会话输出终止消息,用户可在界面看到本轮被停止
        } else {
            log.warn("stuck-tool: abort returned false for user {} session {} (already idle/stopped?)", userId, sessionId);
        }
    }
}
